package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandPayload
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CarPlayOemLocalThemeTest {
    private val assets = File("src/main/assets/carplay-ultra")
    private fun deployed(test: (CarPlayOemThemeRuntime) -> Unit) {
        val root = Files.createTempDirectory("oem-local-theme").toFile()
        try { test(CarPlayOemAssetDeployment.prepare(root, { File(assets, it).inputStream() })) }
        finally { root.deleteRecursively() }
    }

    private fun CarPlayOemThemeDescriptor.layoutId(style: String) =
        layouts.first { it["layoutStyleID"] == style }["id"] as String

    @Test fun palettesComeFromAssetLayoutStylesWithDefaultFirst() = deployed { runtime ->
        val d = runtime.descriptor
        assertEquals(listOf("custom"), d.palettes(d.layoutId("Cobranded")))
        assertEquals(listOf("vibrant"), d.palettes(d.layoutId("MapsMedia")))
        val digiRing = d.palettes(d.layoutId("DigiRing"))
        assertEquals("green", digiRing.first())
        assertEquals(setOf("blue", "green", "midnight", "orange", "red", "rose", "starlight", "yellow"), digiRing.toSet())
        assertTrue("multicolor" in d.palettes(d.layoutId("Modular")))
        assertEquals(35, d.themeChoices().size)
        assertEquals(d.themeChoices().size, d.themeChoices().distinct().size)
    }

    @Test fun localThemeIsRequestedFromPhoneAndLockedOnceUiSyncConnects() = deployed { runtime ->
        val digiRing = runtime.descriptor.layoutId("DigiRing")
        assertTrue(runtime.selectLocalTheme(digiRing, "orange"))
        assertEquals(digiRing, runtime.scene.layoutId)
        assertEquals("orange", runtime.scene.paletteId)

        val data = runtime.initialCommands().first().toWireMap()["data"] as Map<*, *>
        assertEquals(digiRing, data["layoutID"])
        assertEquals("orange", data["paletteID"])
        assertEquals(runtime.descriptor.initialComponents(digiRing), data["activeComponents"])

        assertFalse(runtime.selectLocalTheme(runtime.descriptor.initialLayout, "custom"))
        assertEquals(digiRing, runtime.scene.layoutId)
    }

    @Test fun unknownPaletteIsRejectedWithoutChangingScene() = deployed { runtime ->
        val before = runtime.scene
        assertThrows(IllegalArgumentException::class.java) {
            runtime.selectLocalTheme(runtime.descriptor.initialLayout, "no-such-palette")
        }
        assertEquals(before, runtime.scene)
    }

    /** ultra51 Center_Display: the phone confirms requestLayout with a bare endLayoutChange (no 0x03 first). */
    @Test fun bareEndLayoutChangeConfirmsRequestedLayout() = deployed { cluster ->
        val center = cluster.forDisplay("Center_Display")
        val layoutId = center.descriptor.initialLayout
        center.initialCommands()
        val replies = center.handle(com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandPayload(
            com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommand.END_LAYOUT_CHANGE,
            mapOf("paletteID" to "blue", "appearanceMode" to 1L, "fadeOutOldLayout" to true,
                "activeComponents" to emptyMap<String, String>(), "layoutID" to layoutId), assetWire = true))
        assertTrue(replies.isEmpty())
        assertFalse(center.scene.layoutConfirmed)
        center.onVideoFrame(158131106249L)
        assertTrue(center.scene.layoutConfirmed)
        assertFalse(center.scene.transitioning)
        assertEquals(layoutId, center.scene.layoutId)
        assertEquals("blue", center.scene.paletteId)
    }
    private fun CarPlayOemThemeRuntime.command(type: CarPlayUiSyncCommand, vararg fields: Pair<String, Any?>) =
        handle(CarPlayUiSyncCommandPayload(type, mapOf(*fields), assetWire = true))

    @Test fun layoutPaletteAndCompletionAppearanceApplyAtSenderFrame() = deployed { runtime ->
        runtime.initialCommands()
        val id = runtime.descriptor.layoutId("DigiRing")
        runtime.command(CarPlayUiSyncCommand.METADATA_TRANSFER, "isTransitioning" to true)
        runtime.command(CarPlayUiSyncCommand.LAYOUT_CHANGE, "layoutID" to id, "paletteID" to "orange")
        assertNotEquals(id, runtime.scene.layoutId)
        runtime.onVideoFrame(1_000_000)
        assertEquals("orange", runtime.scene.paletteId)
        runtime.command(CarPlayUiSyncCommand.END_LAYOUT_CHANGE, "layoutID" to id, "paletteID" to "blue",
            "appearanceMode" to 0L, "pts" to 1.1, "regions" to emptyMap<String, Any?>())
        runtime.onVideoFrame(1_066_667)
        assertFalse(runtime.scene.layoutConfirmed)
        runtime.onVideoFrame(1_100_000)
        assertEquals("blue", runtime.scene.paletteId)
        assertEquals(1, runtime.scene.appearance) // Cluster requires dark appearance.
        assertEquals(1.1, runtime.scene.transitionData["pts"])
        assertTrue(runtime.scene.layoutConfirmed)
        val finalData = runtime.scene.transitionData
        runtime.command(CarPlayUiSyncCommand.METADATA_TRANSFER, "isTransitioning" to false)
        runtime.onVideoFrame(1_133_333)
        assertEquals(finalData, runtime.scene.transitionData)
    }

    @Test fun centerPreferenceOverridesTargetsAndAutomaticRestoresLatestTarget() = deployed { cluster ->
        val center = cluster.forDisplay("Center_Display")
        center.command(CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE, "appearanceMode" to 0L)
        center.command(CarPlayUiSyncCommand.APPEARANCE_PREFERENCE_CHANGE, "preference" to 2L)
        center.command(CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE, "appearanceMode" to 0L)
        assertEquals(1, center.scene.appearance)
        center.command(CarPlayUiSyncCommand.APPEARANCE_PREFERENCE_CHANGE, "preference" to 0L)
        assertEquals(0, center.scene.appearance)
        center.command(CarPlayUiSyncCommand.APPEARANCE_PREFERENCE_CHANGE, "preference" to 1L)
        center.initialCommands()
        center.command(CarPlayUiSyncCommand.END_LAYOUT_CHANGE, "layoutID" to center.scene.layoutId,
            "paletteID" to "blue", "appearanceMode" to 1L, "activeComponents" to emptyMap<String, String>())
        center.onVideoFrame(1_000_000)
        assertEquals(0, center.scene.appearance)
        center.command(CarPlayUiSyncCommand.APPEARANCE_PREFERENCE_CHANGE, "preference" to 0L)
        assertEquals(1, center.scene.appearance)
    }

    @Test fun bareCompletionRequiresOutstandingRequestAndValidFields() = deployed { cluster ->
        val center = cluster.forDisplay("Center_Display")
        val fields = mapOf("layoutID" to center.scene.layoutId, "paletteID" to "blue",
            "appearanceMode" to 1L, "activeComponents" to emptyMap<String, String>())
        fun end(data: Map<String, Any?>) = center.handle(CarPlayUiSyncCommandPayload(
            CarPlayUiSyncCommand.END_LAYOUT_CHANGE, data, assetWire = true))
        assertThrows(IllegalArgumentException::class.java) { end(fields) }
        center.initialCommands()
        val before = center.scene
        for (bad in listOf(fields - "paletteID", fields + ("paletteID" to "unknown"),
            fields + ("appearanceMode" to "dark"), fields + ("activeComponents" to mapOf("bad" to "bad")))) {
            assertThrows(IllegalArgumentException::class.java) { end(bad) }
            assertEquals(before, center.scene)
        }
        end(fields + ("pts" to 2.0))
        center.onVideoFrame(1_000_000)
        assertFalse(center.scene.layoutConfirmed)
        center.onVideoFrame(2_000_000)
        assertTrue(center.scene.layoutConfirmed)
        assertThrows(IllegalArgumentException::class.java) { end(fields) }
        center.disconnect()
        assertThrows(IllegalArgumentException::class.java) { end(fields) }
    }

    @Test fun invalidLayoutPaletteAndAppearanceDoNotPoisonFrameQueue() = deployed { runtime ->
        val before = runtime.scene
        for (fields in listOf(mapOf("layoutID" to before.layoutId, "paletteID" to "unknown"),
            mapOf("layoutID" to before.layoutId, "appearanceMode" to 0x100000001L))) {
            assertThrows(IllegalArgumentException::class.java) { runtime.handle(CarPlayUiSyncCommandPayload(
                CarPlayUiSyncCommand.LAYOUT_CHANGE, fields, assetWire = true)) }
        }
        runtime.onVideoFrame(1_000_000)
        assertEquals(before, runtime.scene)
        runtime.command(CarPlayUiSyncCommand.METADATA_TRANSFER, "isTransitioning" to true)
        runtime.command(CarPlayUiSyncCommand.LAYOUT_CHANGE, "layoutID" to before.layoutId, "paletteID" to "blue")
        runtime.onVideoFrame(1_033_333)
        assertEquals("blue", runtime.scene.paletteId)
    }

    @Test fun vehicleSubscribersReplayMergeAndDisconnectWithoutReplacingUiListener() = deployed { runtime ->
        runtime.updateVehicleValues(mapOf("vehicleSpeed" to 42.0))
        val values = mutableListOf<Map<String, Any?>>()
        var uiUpdates = 0
        runtime.listener = { uiUpdates++ }
        val unsubscribe = runtime.addVehicleValuesListener(values::add)
        assertEquals(mapOf("vehicleSpeed" to 42.0), values.single())
        runtime.updateVehicleValues(mapOf("engineRPM" to 2000.0))
        assertEquals(mapOf("vehicleSpeed" to 42.0, "engineRPM" to 2000.0), values.last())
        runtime.disconnect()
        assertTrue(values.last().isEmpty())
        assertEquals(2, uiUpdates)
        unsubscribe()
        runtime.updateVehicleValues(mapOf("vehicleSpeed" to 5.0))
        assertEquals(3, values.size)
    }

}
