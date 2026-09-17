package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val config = config(
            hevc = true,
            ultra = ultraConfig(
                vehicleStateProtocolInfo = vehicleStateProtocolInfo(),
                uiSyncInfo = mapOf("schemaVersion" to 1),
            ),
        )
        val requested = listOf(
            "uiContext",
            "hevc",
            "vehicleStateProtocol",
            "iAPChannel",
            "uiSync",
            "viewAreas",
            "altScreen",
            "unknownFeature",
        )

        val proposal = AirPlayFeatureNegotiation.propose(config, requested, eventPortAvailable = true)

        assertEquals(requested, proposal.requestedFeatures)
        assertEquals(
            listOf("viewAreas", "hevc", "iAPChannel", "altScreen", "vehicleStateProtocol", "uiSync"),
            proposal.supportedFeatures,
        )
        assertEquals(
            listOf("hevc", "vehicleStateProtocol", "iAPChannel", "uiSync", "viewAreas", "altScreen"),
            proposal.enabledFeatures,
        )
        assertFalse(proposal.enabledFeatures.contains("uiContext"))
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
    fun altScreenAndSidecarsRequireBothARequestAndTheirConfiguration() {
        val requested = listOf("altScreen", "vehicleStateProtocol", "uiSync")
        val noUltra = AirPlayFeatureNegotiation.propose(
            config = config(),
            requestedFeatures = requested,
            eventPortAvailable = true,
        )
        assertTrue(noUltra.enabledFeatures.isEmpty())

        val vehicleOnly = AirPlayFeatureNegotiation.propose(
            config = config(ultra = ultraConfig(vehicleStateProtocolInfo = vehicleStateProtocolInfo())),
            requestedFeatures = requested,
            eventPortAvailable = true,
        )
        assertEquals(listOf("altScreen", "vehicleStateProtocol"), vehicleOnly.enabledFeatures)

        val uiSyncOnly = AirPlayFeatureNegotiation.propose(
            config = config(ultra = ultraConfig(uiSyncInfo = mapOf("schemaVersion" to 1))),
            requestedFeatures = requested,
            eventPortAvailable = true,
        )
        assertEquals(listOf("altScreen", "uiSync"), uiSyncOnly.enabledFeatures)
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
        uiSyncInfo: Map<String, Any?>? = null,
    ): AirPlayUltraConfig = AirPlayUltraConfig(
        cluster = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        vehicleStateProtocolInfo = vehicleStateProtocolInfo,
        uiSyncInfo = uiSyncInfo,
    )

    private fun vehicleStateProtocolInfo(): AirPlayVehicleStateProtocolInfo =
        AirPlayVehicleStateProtocolInfo(
            pluginConfigs = mapOf(7L to mapOf("accessories" to emptyList<Any?>())),
            pluginMapping = mapOf(7L to "climate"),
        )
}
