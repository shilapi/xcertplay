package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.CarPlayOemAssetDeployment
import com.shilapi.xcertplay.airplay.CarPlayOemThemeRuntime
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandPayload
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CarPlayClusterThemePreferenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun runtime(): CarPlayOemThemeRuntime = CarPlayOemAssetDeployment.prepare(
        File(context.cacheDir, "theme-preference"),
        { File("../shared/src/main/assets/carplay-ultra", it).inputStream() })

    private fun ring(runtime: CarPlayOemThemeRuntime) =
        runtime.descriptor.layouts.first { it["layoutStyleID"] == "DigiRing" }["id"] as String

    @Test fun phoneConfirmedThemeAndPaletteSurviveRestartWithoutSavingIntermediateLayout() {
        val first = runtime()
        val layout = ring(first)
        CarPlayClusterThemePreference.select(context, first, first.scene.layoutId, "custom")
        CarPlayClusterThemePreference.observe(context, first)
        var uiUpdates = 0
        first.listener = { uiUpdates++ }
        first.initialCommands()
        fun command(type: CarPlayUiSyncCommand, fields: Map<String, Any?>) =
            first.handle(CarPlayUiSyncCommandPayload(type, fields, assetWire = true))
        command(CarPlayUiSyncCommand.METADATA_TRANSFER, mapOf("isTransitioning" to true))
        val fields = mapOf("layoutID" to layout, "paletteID" to "orange", "appearanceMode" to 1L,
            "activeComponents" to first.descriptor.initialComponents(layout))
        command(CarPlayUiSyncCommand.LAYOUT_CHANGE, fields)
        first.onVideoFrame(1_000_000)
        val intermediateRestart = runtime()
        CarPlayClusterThemePreference.restore(context, intermediateRestart)
        assertEquals(first.descriptor.initialLayout, intermediateRestart.scene.layoutId)
        command(CarPlayUiSyncCommand.END_LAYOUT_CHANGE, fields)
        first.onVideoFrame(1_033_333)
        val restarted = runtime()
        assertTrue(CarPlayClusterThemePreference.restore(context, restarted))
        assertEquals(layout, restarted.scene.layoutId)
        assertEquals("orange", restarted.scene.paletteId)
        command(CarPlayUiSyncCommand.PALETTE_CHANGE, mapOf("layoutIdentifier" to layout, "paletteIdentifier" to "blue"))
        first.disconnect()
        // A local choice between connections must not prevent saving the phone's previous theme again.
        CarPlayClusterThemePreference.select(context, first, first.descriptor.initialLayout, "custom")
        first.initialCommands()
        command(CarPlayUiSyncCommand.METADATA_TRANSFER, mapOf("isTransitioning" to true))
        val reconnected = fields + ("paletteID" to "blue")
        command(CarPlayUiSyncCommand.LAYOUT_CHANGE, reconnected)
        command(CarPlayUiSyncCommand.END_LAYOUT_CHANGE, reconnected)
        first.onVideoFrame(1_066_666)
        val paletteRestart = runtime()
        CarPlayClusterThemePreference.restore(context, paletteRestart)
        assertEquals("blue", paletteRestart.scene.paletteId)
        val request = paletteRestart.initialCommands().first().toWireMap()["data"] as Map<*, *>
        assertEquals(layout, request["layoutID"])
        assertEquals("blue", request["paletteID"])
        assertTrue("Persistence must not replace the View listener", uiUpdates > 0)
    }

    @Test fun restartRestoresLayoutAndPaletteInInitialPhoneRequest() {
        val first = runtime()
        val layout = ring(first)
        assertTrue(CarPlayClusterThemePreference.select(context, first, layout, "orange"))
        val restarted = runtime()
        assertTrue(CarPlayClusterThemePreference.restore(context, restarted))
        assertEquals(layout, restarted.scene.layoutId)
        assertEquals("orange", restarted.scene.paletteId)
        val request = restarted.initialCommands().first().toWireMap()["data"] as Map<*, *>
        assertEquals(layout, request["layoutID"])
        assertEquals("orange", request["paletteID"])
    }

    @Test fun rejectedSelectionDoesNotOverwriteRememberedChoice() {
        val first = runtime()
        val layout = ring(first)
        assertTrue(CarPlayClusterThemePreference.select(context, first, layout, "orange"))
        first.initialCommands()
        assertFalse(CarPlayClusterThemePreference.select(context, first, layout, "blue"))
        val restarted = runtime()
        assertTrue(CarPlayClusterThemePreference.restore(context, restarted))
        assertEquals("orange", restarted.scene.paletteId)
    }

    @Test fun missingOrStalePreferencePreservesDeploymentDefault() {
        val restarted = runtime()
        val before = restarted.scene
        assertFalse(CarPlayClusterThemePreference.restore(context, restarted))
        val prefs = context.getSharedPreferences("xcertplay_cluster_theme", Context.MODE_PRIVATE)
        for ((asset, layout, palette) in listOf(
            Triple("different-asset", ring(restarted), "orange"),
            Triple(restarted.descriptor.assetId, "removed-layout", "orange"),
            Triple(restarted.descriptor.assetId, ring(restarted), "removed-palette"),
            Triple(restarted.descriptor.assetId, ring(restarted), "custom") // Exists, but not offered for DigiRing.
        )) {
            prefs.edit().putString("asset_id", asset).putString("layout_id", layout)
                .putString("palette_id", palette).commit()
            assertFalse(CarPlayClusterThemePreference.restore(context, restarted))
            assertEquals(before, restarted.scene)
        }
    }
}
