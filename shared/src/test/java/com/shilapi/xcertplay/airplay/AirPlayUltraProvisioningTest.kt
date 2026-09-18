package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginHandler
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistration
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistrationProvider
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistry
import com.shilapi.xcertplay.airplay.rcs.caf.CarAccessoryMessages
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMessageFactory
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMessageType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncProtocolVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayUltraProvisioningTest {
    @Test
    fun fallbackProvisioningEnablesMandatoryVehicleAndUiSyncChannels() {
        val provisioning = CarPlayUltraFallbackProvisioning.create()
        val runtime = provisioning.createRuntime()
        val config = config(provisioning.createConfig(cluster, runtime))

        assertTrue(runtime.supportsRcsClientType(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA))
        assertTrue(runtime.supportsRcsClientType(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2))
        assertTrue(runtime.supportsRcsClientType(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL))
        assertTrue(config.ultra!!.readyFeatures.contains(AirPlayFeature.ALT_SCREEN))
        assertTrue(config.ultra!!.readyFeatures.contains(AirPlayFeature.UI_CONTEXT))
        assertTrue(config.ultra!!.readyFeatures.contains(AirPlayFeature.VEHICLE_STATE_PROTOCOL))
        assertTrue(config.ultra!!.readyFeatures.contains(AirPlayFeature.UI_SYNC))

        val proposal = AirPlayFeatureNegotiation.propose(
            config = config,
            requestedFeatures = listOf("uiContext", "altScreen", "vehicleStateProtocol", "uiSync"),
            eventPortAvailable = true,
        )
        assertEquals(
            listOf("uiContext", "altScreen", "vehicleStateProtocol", "uiSync"),
            proposal.enabledFeatures,
        )
        val info = AirPlayInfoPlist.build(config)
        val encoded = BplistCodec.decode(BplistCodec.encode(info)) as Map<*, *>
        assertTrue(encoded.containsKey("vehicleStateProtocolInfo"))
        assertEquals(emptyMap<String, Any?>(), encoded["uiSyncInfo"])
    }

    @Test
    fun fallbackConfigResponseIsAStructurallyValidEmptyCafTree() {
        val reader = CarAccessoryMessages.reader(
            fallbackConfigResponse(
                pluginId = CarPlayUltraFallbackProvisioning.PLUGIN_ID,
                transactionId = 1,
            ).rcsBody,
        )
        @Suppress("UNCHECKED_CAST")
        val values = reader.values() as Map<String, Any?>

        assertEquals(CarPlayUltraFallbackProvisioning.PLUGIN_ID, reader.pluginId)
        assertEquals(emptyList<Any?>(), values["accessories"])
    }

    @Test
    fun fallbackUiSyncMocksUseDocumentedEnvelopeFields() {
        val reset = CarPlayUltraFallbackProvisioning.createMockUiSyncReset()
        val command = CarPlayUltraFallbackProvisioning.createMockUiSyncCommand()

        assertEquals(CarPlayUiSyncMessageType.RESET, reset.type)
        assertEquals(CarPlayUiSyncProtocolVersion.V3, reset.version)
        assertEquals(0L, reset.sessionSequenceNumber)
        assertEquals(0L, reset.packetSequenceNumber)
        assertEquals(0L, reset.acknowledgementSequenceNumber)

        assertEquals(CarPlayUiSyncMessageType.COMMAND, command.type)
        assertEquals(1L, command.sessionSequenceNumber)
        assertEquals(1L, command.packetSequenceNumber)
        assertEquals(0L, command.acknowledgementSequenceNumber)
        assertEquals("targetAppearanceChange", command.commandPayload()!!.command.wireName)
        assertEquals("dark", command.commandPayload()!!.fields["appearanceMode"])
        assertEquals(command, CarPlayUiSyncMessageFactory.decode(command.toBplist()))
    }

    @Test
    fun noOemRegistrationsKeepVehicleStateDisabled() {
        val provisioning = AirPlayUltraProvisioning(
            runtimeFeatures = setOf(AirPlayFeature.ALT_SCREEN),
        )
        val runtime = provisioning.createRuntime()
        val config = config(provisioning.createConfig(cluster, runtime))

        assertEquals(setOf(AirPlayFeature.ALT_SCREEN), config.ultra?.readyFeatures)
        assertFalse(runtime.supportsRcsClientType(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA))
    }

    @Test
    fun firmwareRegistrationAndSidecarEnableVehicleStateOnBothCafChannels() {
        val handler = object : CafPluginHandler {}
        val registration = CafPluginRegistration(
            pluginId = 7,
            protocolVersion = CafPluginRegistry.CAF_PROTOCOL_VERSION_1_0,
            clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
            handler = handler,
            pluginName = "climate",
            pluginConfig = mapOf("oemField" to "preserved"),
        )
        val provisioning = AirPlayUltraProvisioning(
            pluginRegistrations = listOf(registration),
            pluginMapping = mapOf("climate" to 7),
            runtimeFeatures = setOf(AirPlayFeature.ALT_SCREEN),
        )
        val runtime = provisioning.createRuntime()
        val config = config(provisioning.createConfig(cluster, runtime))

        assertTrue(runtime.supportsRcsClientType(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA))
        assertTrue(runtime.supportsRcsClientType(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2))
        assertTrue(config.ultra!!.readyFeatures.contains(AirPlayFeature.VEHICLE_STATE_PROTOCOL))

        val proposal = AirPlayFeatureNegotiation.propose(
            config = config,
            requestedFeatures = listOf("altScreen", "vehicleStateProtocol"),
            eventPortAvailable = true,
        )
        assertEquals(
            listOf("altScreen", "vehicleStateProtocol"),
            proposal.enabledFeatures,
        )
        val info = AirPlayInfoPlist.build(config)
        val vehicle = info["vehicleStateProtocolInfo"] as Map<*, *>
        assertEquals(1, vehicle["pluginCount"])
        assertEquals(
            mapOf("pluginID" to 7L, "oemField" to "preserved"),
            (vehicle["pluginConfigs"] as List<*>).single(),
        )
        assertEquals(mapOf("climate" to 7L), vehicle["pluginMapping"])
    }

    @Test
    fun uiSyncCanBeEnabledOnlyWithAnExplicitClusterHandler() {
        val clusterFactory = RcsDataStreamHandlerFactory { null }
        val provisioning = AirPlayUltraProvisioning(
            uiSyncInfo = AirPlayUiSyncInfo(),
            rcsFactories = mapOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL to clusterFactory),
        )
        val runtime = provisioning.createRuntime()
        val config = config(provisioning.createConfig(cluster, runtime))

        assertTrue(config.ultra!!.readyFeatures.contains(AirPlayFeature.UI_SYNC))
        val proposal = AirPlayFeatureNegotiation.propose(
            config = config,
            requestedFeatures = listOf("uiSync"),
            eventPortAvailable = true,
        )
        assertEquals(listOf("uiSync"), proposal.enabledFeatures)
        assertEquals(emptyMap<String, Any?>(), AirPlayInfoPlist.build(config)["uiSyncInfo"])
    }

    @Test
    fun registrationConfigCannotContradictPluginId() {
        val registration = CafPluginRegistration(
            pluginId = 7,
            protocolVersion = CafPluginRegistry.CAF_PROTOCOL_VERSION_1_0,
            clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
            handler = object : CafPluginHandler {},
            pluginConfig = mapOf("pluginID" to 8L),
        )

        assertThrows(IllegalArgumentException::class.java) {
            AirPlayVehicleStateProtocolInfo.from(listOf(registration))
        }
    }

    @Test
    fun registrationNameBuildsNameToIdMappingWithoutGuessing() {
        val info = AirPlayVehicleStateProtocolInfo.from(
            registrations = listOf(
                CafPluginRegistration(
                    pluginId = 11,
                    protocolVersion = CafPluginRegistry.CAF_PROTOCOL_VERSION_1_0,
                    clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
                    handler = object : CafPluginHandler {},
                    pluginName = "seat",
                ),
            ),
        )

        assertEquals(mapOf("seat" to 11L), info!!.pluginMapping)
    }

    @Test
    fun pluginMappingMustUseNamesAsKeysAndKnownNumericIdsAsValues() {
        val info = AirPlayVehicleStateProtocolInfo(
            pluginConfigs = listOf(
                AirPlayVehicleStateProtocolPlugin(pluginId = 7L),
            ),
            pluginMapping = mapOf("climate" to 8L),
        )
        val failure = assertThrows(AirPlayConfigurationException::class.java) {
            AirPlayFeatureNegotiation.capabilities(
                config = config(
                    AirPlayUltraConfig(
                        cluster = cluster,
                        vehicleStateProtocolInfo = info,
                        readyFeatures = setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
                        runtime = AirPlayUltraRuntime.builder()
                            .sidecar(
                                AirPlayFeature.VEHICLE_STATE_PROTOCOL,
                                info.toInfoResponseMap(),
                            )
                            .rcs(
                                AirPlayFeatureCatalog.requiredRcsClientTypes(
                                    AirPlayFeature.VEHICLE_STATE_PROTOCOL,
                                ),
                                RcsDataStreamHandlerFactory { null },
                            )
                            .build(),
                    ),
                ),
                eventPortAvailable = true,
            )
        }

        assertTrue(failure.message!!.contains("unknown pluginID"))
    }

    @Test
    fun pluginConfigEntriesRequirePluginId() {
        val failure = assertThrows(AirPlayConfigurationException::class.java) {
            AirPlayVehicleStateProtocolPlugin.fromWireMap(
                mapOf("pluginName" to "climate"),
            )
        }
        assertTrue(failure.message!!.contains("pluginID"))
    }

    @Test
    fun typedVehiclePluginPreservesOemFieldsAndRejectsContradictoryIds() {
        val plugin = AirPlayVehicleStateProtocolPlugin.of(
            pluginId = 7L,
            "pluginConfig" to mapOf("accessories" to emptyList<Any?>()),
        )

        assertEquals(
            mapOf(
                "pluginID" to 7L,
                "pluginConfig" to mapOf("accessories" to emptyList<Any?>()),
            ),
            plugin.toWireMap(),
        )
        assertThrows(AirPlayConfigurationException::class.java) {
            AirPlayVehicleStateProtocolPlugin(
                pluginId = 7L,
                fields = mapOf("pluginID" to 8L),
            )
        }
    }

    @Test
    fun registryLoadsFromProvider() {
        val registration = CafPluginRegistration(
            pluginId = 9,
            protocolVersion = CafPluginRegistry.CAF_PROTOCOL_VERSION_1_0,
            clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
            handler = object : CafPluginHandler {},
        )
        val registry = CafPluginRegistry.from(
            CafPluginRegistrationProvider { listOf(registration) },
        )

        assertEquals(registration, registry.requireRegistration(9))
        assertTrue(registry.supports(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA))
    }

    private fun config(ultra: AirPlayUltraConfig): AirPlayConfig = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        ultra = ultra,
    )

    private companion object {
        val cluster = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)
    }
}
