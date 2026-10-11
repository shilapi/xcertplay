package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

/** /info validation contract from AirPlaySender 24A437, including ultra2.log's proposal. */
class AirPlayNegotiatedInfoTest {
    @Test fun acceptedAltScreenCannotOmitItsDisplay() {
        val config = config().let { it.copy(ultra = it.ultra!!.copy(declareClusterDisplay = false)) }
        assertThrows(AirPlayConfigurationException::class.java) { info(config, null) }
    }

    @Test fun pixelAreaLimitUsesNegotiatedHevcAndIncludesTheBoundary() {
        val config = config()
        val enabled = AirPlayFeatureNegotiation.capabilities(config, true).supportedFeatureSet
        fun withMain(height: Int) = config.copy(main = AirPlayDisplayConfig(4096, height))
        assertNotNull(info(withMain(2176), enabled)) // 0x880000, accepted with HEVC.
        assertThrows(AirPlayConfigurationException::class.java) { info(withMain(2177), enabled) }
        assertNotNull(info(withMain(2304), enabled - AirPlayFeature.HEVC)) // 0x900000.
        assertThrows(AirPlayConfigurationException::class.java) {
            info(withMain(2305), enabled - AirPlayFeature.HEVC)
        }
        val oversizedCluster = config.copy(ultra = config.ultra!!.copy(cluster = AirPlayDisplayConfig(4096, 2177)))
        assertThrows(AirPlayConfigurationException::class.java) { info(oversizedCluster, enabled) }
    }

    @Test fun dynamicDisplayUpdateUsesNegotiatedHevcBeforeSending() {
        val output = mutableListOf<Map<String, Any?>>()
        val control = CarPlayDisplayControlSession(config(), { output.add(it); true }, {})
        control.negotiated(setOf(AirPlayFeature.VIEW_AREAS, AirPlayFeature.HEVC))
        assertThrows(AirPlayConfigurationException::class.java) {
            control.updateDisplay(AirPlayInfoPlist.MAIN_UUID, AirPlayDisplayConfig(4096, 2177))
        }
        assertTrue(output.isEmpty())
        control.negotiated(setOf(AirPlayFeature.VIEW_AREAS))
        control.updateDisplay(AirPlayInfoPlist.MAIN_UUID, AirPlayDisplayConfig(4096, 2304))
        assertEquals("updateDisplayInfo", output.single()["type"])
    }

    private fun config(): AirPlayConfig {
        val provisioning = CarPlayUltraVehicleProvisioning.create()
        return AirPlayConfig(
            "vehicle", "02:11:22:33:44:55", "02:11:22:33:44:56", "980.77.1",
            AirPlayDisplayConfig(1080, 2160, widthPhysicalMm = 200, heightPhysicalMm = 400),
            hevc = true,
            ultra = provisioning.createConfig(AirPlayDisplayConfig(1080, 720), provisioning.createRuntime()),
        )
    }

    private fun info(config: AirPlayConfig, enabled: Set<AirPlayFeature>?): Map<*, *> =
        BplistCodec.decode(BplistCodec.encode(AirPlayInfoPlist.build(config, enabled))) as Map<*, *>

    // carEndpoint_validateInfoResponseKeyPresentForFeature @0x25168745c:
    // negotiated=true requires presence; negotiated=false requires absence.
    private fun invalidSidecars(info: Map<*, *>, enabled: Set<AirPlayFeature>): List<String> =
        AirPlayFeature.entries.mapNotNull { feature ->
            feature.infoResponseKey?.takeIf { info.containsKey(it) != (feature in enabled) }
        }

    @Test fun ultra2ProposalDoesNotLeakUnnegotiatedUiSyncInfo() {
        val config = config()
        val requested = listOf("uiContext", "viewAreas", "cornerMasks", "focusTransfer",
            "h.264Level5.1", "mainBuffered", "altScreen", "enhancedSiri", "hevc",
            "vehicleStateProtocol", "sessionManagement", "videoPlayback", "logTransfer", "iAPChannel")
        val proposal = AirPlayFeatureNegotiation.propose(config, requested, true)
        assertEquals(setOf(AirPlayFeature.UI_CONTEXT, AirPlayFeature.VIEW_AREAS,
            AirPlayFeature.ALT_SCREEN, AirPlayFeature.HEVC, AirPlayFeature.VEHICLE_STATE_PROTOCOL,
            AirPlayFeature.IAP_CHANNEL), proposal.enabledFeatureSet)
        assertTrue(proposal.capabilities.supports(AirPlayFeature.UI_SYNC))
        val response = info(config, proposal.enabledFeatureSet)
        assertTrue("Unnegotiated or missing sidecars: ${invalidSidecars(response, proposal.enabledFeatureSet)}",
            invalidSidecars(response, proposal.enabledFeatureSet).isEmpty())
        assertEquals(listOf(110L, 111L), (response["displays"] as List<*>).map { (it as Map<*, *>)["type"] })
        assertTrue(response["uiContextLastOnDisplayURLs"] is List<*>)
        assertTrue(response["uiContextNowOnDisplayURLs"] is List<*>)
    }

    @Test fun emptyNegotiationIsDistinctFromInfoBeforeSetup() {
        val config = config()
        val beforeSetup = info(config, null)
        assertTrue(beforeSetup.containsKey("uiSyncInfo"))
        assertEquals(2, (beforeSetup["displays"] as List<*>).size)

        val afterSetup = info(config, emptySet())
        assertTrue(invalidSidecars(afterSetup, emptySet()).isEmpty())
        assertEquals(listOf(110L), (afterSetup["displays"] as List<*>).map { (it as Map<*, *>)["type"] })
        assertFalse(afterSetup.containsKey("uiContextNowOnDisplayURLs"))
        assertFalse(afterSetup.containsKey("uiContextLastOnDisplayURLs"))
        assertNotNull(afterSetup["hidDevices"])
        assertNotNull(afterSetup["audioFormats"])
    }

    @Test fun acceptedUiSyncStillCarriesItsSidecar() {
        val config = config()
        val enabled = AirPlayFeatureNegotiation.capabilities(config, true).supportedFeatureSet
        val response = info(config, enabled)
        assertTrue(response.containsKey("uiSyncInfo"))
        assertTrue(invalidSidecars(response, enabled).isEmpty())
    }
}
