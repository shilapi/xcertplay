package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** CarKit 24A437 uses physical DDP displays, rather than logical display features, for Ultra. */
class CarPlayPhysicalDisplayContractTest {
    private fun deployed(test: (AirPlayConfig) -> Unit) {
        val root = Files.createTempDirectory("physical-display-contract").toFile()
        try {
            val theme = CarPlayOemAssetDeployment.prepare(root, openResource = {
                File("src/main/assets/carplay-ultra", it).inputStream()
            })
            val provisioning = CarPlayUltraVehicleProvisioning.create(themeRuntime = theme)
            test(AirPlayConfig("vehicle", "02:11:22:33:44:55", "02:11:22:33:44:56", "980.77.1",
                AirPlayDisplayConfig(1080, 1440, 100, 133),
                ultra = provisioning.createConfig(AirPlayDisplayConfig(1920, 720, 311, 116),
                    provisioning.createRuntime())))
        } finally { root.deleteRecursively() }
    }

    private fun info(config: AirPlayConfig, features: Set<AirPlayFeature>? = null): Map<*, *> =
        BplistCodec.decode(BplistCodec.encode(AirPlayInfoPlist.build(config, features))) as Map<*, *>

    @Test fun deployedInfoCreatesDdpDisplayAndBindsBothLogicalStreams() = deployed { config ->
        val response = info(config)
        val panels = response["displayPanels"] as? List<*>
        assertNotNull("Missing physical display declaration keeps CAF ultraEnabled false", panels)
        val displays = (response["displays"] as List<*>).map { it as Map<*, *> }
        val physical = panels!!.map { it as Map<*, *> }
        assertEquals(2, physical.size)
        val streamIds = physical.flatMap { panel ->
            assertTrue(panel["uid"] is String)
            listOf("widthPixels", "heightPixels", "widthPhysical", "heightPhysical").forEach {
                assertTrue("Physical geometry $it must be numeric", panel[it] is Number)
            }
            (panel["initialVideoStreams"] as List<*>).map { stream ->
                val fields = stream as Map<*, *>
                listOf("originXPixels", "originYPixels", "zIndex").forEach {
                    assertTrue("CARStreamInfo rejects absent $it", fields[it] is Number)
                }
                fields["uuid"]
            }
        }
        assertEquals(displays.map { it["uuid"] }, streamIds)
        assertEquals(streamIds.size, streamIds.distinct().size)
        val cluster = physical.single { (it["properties"] as List<*>).contains("Instrument Cluster") }
        val plugin = (cluster["displayPlugins"] as List<*>).single() as Map<*, *>
        assertEquals("Cluster_Display", plugin["uid"])
        val managed = (plugin["managedVideoStreams"] as List<*>).map { (it as Map<*, *>)["uuid"] }
        assertEquals(listOf(AirPlayInfoPlist.ALT_UUID), managed)
        assertEquals(managed, (cluster["initialVideoStreams"] as List<*>).map { (it as Map<*, *>)["uuid"] })
        assertEquals(1920L, cluster["widthPixels"])
        assertEquals(720L, cluster["heightPixels"])
        assertEquals(985L, config.ultra!!.clusterAsset!!.assetVersion)
        // Asset package.json names the centerConsole panel Center_Display; there are no 110/111 ids in the asset.
        val main = physical.single { (it["properties"] as List<*>).contains("CarPlay") }
        val mainPlugin = (main["displayPlugins"] as List<*>).single() as Map<*, *>
        assertEquals("Center_Display", mainPlugin["uid"])
        assertEquals(listOf(AirPlayInfoPlist.MAIN_UUID),
            (mainPlugin["managedVideoStreams"] as List<*>).map { (it as Map<*, *>)["uuid"] })
        assertEquals(AirPlayInfoPlist.MAIN_UUID, main["uid"])
    }

    @Test fun partialNegotiationsCannotCreateAnUnboundDdpDisplay() = deployed { config ->
        val supported = AirPlayFeatureNegotiation.capabilities(config, true).supportedFeatureSet
        assertNotNull(info(config, supported)["displayPanels"])
        listOf(emptySet(), supported - AirPlayFeature.ALT_SCREEN,
            supported - AirPlayFeature.UI_SYNC).forEach { accepted ->
            val response = info(config, accepted)
            assertFalse(response.containsKey("displayPanels"))
            assertEquals(if (AirPlayFeature.ALT_SCREEN in accepted) 2 else 1,
                (response["displays"] as List<*>).size)
        }
        assertFalse(info(config.copy(ultra = null)).containsKey("displayPanels"))
    }

    @Test fun referenceVehicleWithoutLoadedOemDisplayDoesNotInventAPlugin() = deployed { config ->
        val provisioning = CarPlayUltraVehicleProvisioning.create()
        val reference = config.copy(ultra = provisioning.createConfig(config.ultra!!.cluster,
            provisioning.createRuntime()))
        assertFalse(info(reference).containsKey("displayPanels"))
        assertTrue(info(reference).containsKey("vehicleStateProtocolInfo"))
    }
}
