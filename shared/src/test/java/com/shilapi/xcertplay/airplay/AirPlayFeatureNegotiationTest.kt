package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayFeatureNegotiationTest {
    @Test
    fun requestedFeaturesAreStringsDeduplicatedAndOrdered() {
        val requested = AirPlayFeatureNegotiation.requestedFeatures(
            listOf(
                "hevc",
                "unknownFeature",
                "hevc",
                "",
                17,
                " viewAreas ",
            ),
        )

        assertEquals(listOf("hevc", "unknownFeature", "viewAreas"), requested)
    }

    @Test
    fun proposalReturnsOnlyTheIntersectionOfRequestedAndSupportedFeatures() {
        val runtime = runtime(
            sidecars = sidecars(),
            runtimeFeatures = setOf(
                AirPlayFeature.UI_CONTEXT,
                AirPlayFeature.CORNER_MASKS,
                AirPlayFeature.FOCUS_TRANSFER,
                AirPlayFeature.H264_LEVEL_5_1,
                AirPlayFeature.ENHANCED_SIRI,
                AirPlayFeature.MAIN_BUFFERED,
                AirPlayFeature.VIDEO_PLAYBACK,
                AirPlayFeature.SESSION_MANAGEMENT,
            ),
            rcsClientTypes = setOf(
                RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
                RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
                RcsClientTypes.CAR_PLAY_UPDATE_DATA,
                RcsClientTypes.CAR_PLAY_LOGGING_DATA,
            ),
        )
        val config = config(
            hevc = true,
            ultra = ultraConfig(
                readyFeatures = AirPlayFeatureCatalog.ALL.toSet(),
                runtime = runtime,
            ),
        )
        val requested = AirPlayFeatureCatalog.ALL.map(AirPlayFeature::wireName) +
            "unknownFeature"

        val proposal = AirPlayFeatureNegotiation.propose(config, requested, eventPortAvailable = true)

        assertEquals(requested, proposal.requestedFeatures)
        assertEquals(
            AirPlayFeatureCatalog.ALL.toSet(),
            proposal.capabilities.supportedFeatureSet,
        )
        assertEquals(
            AirPlayFeatureCatalog.ALL.toSet(),
            proposal.enabledFeatureSet,
        )
        assertEquals(requested.dropLast(1), proposal.enabledFeatures)
        assertFalse(proposal.enabledFeatures.contains("unknownFeature"))
    }

    @Test
    fun ultraFeaturesRemainDisabledWithoutUltraConfiguration() {
        val config = config(hevc = true, ultra = null)
        val requested = listOf(
            "viewAreas",
            "hevc",
            "iAPChannel",
            "altScreen",
            "vehicleStateProtocol",
            "uiSync",
        )

        val proposal = AirPlayFeatureNegotiation.propose(config, requested, eventPortAvailable = true)

        assertEquals(listOf("viewAreas", "hevc", "iAPChannel"), proposal.enabledFeatures)
        assertNull(proposal.capabilities.ultraDisplay)
    }

    @Test
    fun iapChannelRequiresAnAvailableEventPort() {
        val config = config()
        val requested = listOf("iAPChannel", "viewAreas")

        assertEquals(
            listOf("viewAreas"),
            AirPlayFeatureNegotiation.propose(config, requested, eventPortAvailable = false).enabledFeatures,
        )
        assertEquals(
            listOf("iAPChannel", "viewAreas"),
            AirPlayFeatureNegotiation.propose(config, requested, eventPortAvailable = true).enabledFeatures,
        )
    }

    @Test
    fun sidecarPresenceDoesNotEnableAFeatureWithoutReadyOptIn() {
        val runtime = runtime(
            sidecars = sidecars(),
            rcsClientTypes = setOf(
                RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
                RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
            ),
        )
        val config = config(
            ultra = ultraConfig(
                runtime = runtime,
            ),
        )

        val proposal = AirPlayFeatureNegotiation.propose(
            config = config,
            requestedFeatures = listOf("altScreen", "vehicleStateProtocol", "uiSync"),
            eventPortAvailable = true,
        )

        assertTrue(proposal.enabledFeatures.isEmpty())
    }

    @Test
    fun readyFeatureWithoutRuntimeHandlerFailsLocally() {
        val failure = assertThrows(AirPlayConfigurationException::class.java) {
            AirPlayFeatureNegotiation.capabilities(
                config = config(
                    ultra = ultraConfig(
                        readyFeatures = setOf(AirPlayFeature.UI_CONTEXT),
                    ),
                ),
                eventPortAvailable = true,
            )
        }

        assertTrue(failure.message!!.contains("runtime handler"))
    }

    @Test
    fun vehicleStateProtocolRequiresBothRcsHandlers() {
        val failure = assertThrows(AirPlayConfigurationException::class.java) {
            AirPlayFeatureNegotiation.capabilities(
                config = config(
                    ultra = ultraConfig(
                        readyFeatures = setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
                        runtime = runtime(
                            sidecars = mapOf(
                                AirPlayFeature.VEHICLE_STATE_PROTOCOL to
                                    vehicleStateProtocolInfo().asInfoResponse(),
                            ),
                            rcsClientTypes = setOf(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
                        ),
                    ),
                ),
                eventPortAvailable = true,
            )
        }

        assertTrue(failure.message!!.contains("CarPlayProtocolData2"))
    }

    @Test
    fun readySidecarBackedFeatureWithoutInfoIsRejectedDuringLocalConstruction() {
        val failure = assertThrows(AirPlayConfigurationException::class.java) {
            AirPlayFeatureNegotiation.capabilities(
                config = config(
                    ultra = ultraConfig(
                        readyFeatures = setOf(AirPlayFeature.MAIN_BUFFERED),
                        runtime = runtime(
                            runtimeFeatures = setOf(AirPlayFeature.MAIN_BUFFERED),
                        ),
                    ),
                ),
                eventPortAvailable = true,
            )
        }

        assertTrue(failure.message!!.contains("mainBufferedInfo"))
    }

    @Test
    fun vehicleStateProtocolRequiresANonEmptyPluginArray() {
        val failure = assertThrows(AirPlayConfigurationException::class.java) {
            AirPlayFeatureNegotiation.capabilities(
                config = config(
                    ultra = ultraConfig(
                        vehicleStateProtocolInfo = AirPlayVehicleStateProtocolInfo(
                            pluginConfigs = emptyList(),
                        ),
                        readyFeatures = setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
                        runtime = runtime(
                            rcsClientTypes = setOf(
                                RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                                RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
                            ),
                        ),
                    ),
                ),
                eventPortAvailable = true,
            )
        }

        assertTrue(failure.message!!.contains("pluginConfigs"))
    }

    private fun config(
        hevc: Boolean = false,
        ultra: AirPlayUltraConfig? = null,
    ): AirPlayConfig = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        hevc = hevc,
        ultra = ultra,
    )

    private fun ultraConfig(
        vehicleStateProtocolInfo: AirPlayVehicleStateProtocolInfo? = null,
        uiSyncInfo: AirPlayUiSyncInfo? = null,
        fileTransferInfo: Map<String, Any?>? = null,
        logTransferInfo: Map<String, Any?>? = null,
        mainBufferedInfo: Map<String, Any?>? = null,
        videoPlaybackInfo: Map<String, Any?>? = null,
        sessionManagementInfo: Map<String, Any?>? = null,
        readyFeatures: Set<AirPlayFeature> = emptySet(),
        runtime: AirPlayUltraRuntime? = null,
    ): AirPlayUltraConfig = AirPlayUltraConfig(
        cluster = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        vehicleStateProtocolInfo = vehicleStateProtocolInfo,
        uiSyncInfo = uiSyncInfo,
        fileTransferInfo = fileTransferInfo,
        logTransferInfo = logTransferInfo,
        mainBufferedInfo = mainBufferedInfo,
        videoPlaybackInfo = videoPlaybackInfo,
        sessionManagementInfo = sessionManagementInfo,
        readyFeatures = readyFeatures,
        runtime = runtime,
    )

    private fun sidecars(): Map<AirPlayFeature, Any?> = mapOf(
        AirPlayFeature.VEHICLE_STATE_PROTOCOL to vehicleStateProtocolInfo().asInfoResponse(),
        AirPlayFeature.UI_SYNC to AirPlayUiSyncInfo(),
        AirPlayFeature.FILE_TRANSFER to mapOf("schemaVersion" to 1),
        AirPlayFeature.LOG_TRANSFER to mapOf("schemaVersion" to 1),
        AirPlayFeature.MAIN_BUFFERED to mapOf("schemaVersion" to 1),
        AirPlayFeature.VIDEO_PLAYBACK to mapOf("schemaVersion" to 1),
        AirPlayFeature.SESSION_MANAGEMENT to mapOf("schemaVersion" to 1),
    )

    private fun runtime(
        sidecars: Map<AirPlayFeature, Any?> = emptyMap(),
        runtimeFeatures: Set<AirPlayFeature> = emptySet(),
        rcsClientTypes: Set<RcsClientType> = emptySet(),
    ): AirPlayUltraRuntime {
        val builder = AirPlayUltraRuntime.builder()
        sidecars.forEach { (feature, value) -> builder.sidecar(feature, value) }
        runtimeFeatures.forEach { feature -> builder.runtimeFeature(feature) }
        if (rcsClientTypes.isNotEmpty()) {
            builder.rcs(rcsClientTypes, RcsDataStreamHandlerFactory { null })
        }
        return builder.build()
    }

    private fun vehicleStateProtocolInfo(): AirPlayVehicleStateProtocolInfo =
        AirPlayVehicleStateProtocolInfo(
            pluginConfigs = listOf(
                mapOf(
                    "pluginID" to 7L,
                    "accessories" to emptyList<Any?>(),
                ),
            ),
            pluginMapping = mapOf("climate" to 7L),
        )
}

private fun AirPlayVehicleStateProtocolInfo.asInfoResponse(): Map<String, Any?> = linkedMapOf(
    "protocolVersion" to protocolVersion,
    "pluginCount" to pluginConfigs.size,
    "pluginConfigs" to pluginConfigs,
    "pluginMapping" to pluginMapping,
)
