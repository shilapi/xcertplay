package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.CarPlayOemThemeRuntime

internal object CarPlayClusterThemePreference {
    private const val PREFS = "xcertplay_cluster_theme"

    /** Also remember themes chosen through the phone, after its frame confirms the final layout. */
    fun observe(context: Context, runtime: CarPlayOemThemeRuntime): () -> Unit {
        val application = context.applicationContext
        val choices = runtime.descriptor.themeChoices().toSet()
        var lastSaved: Pair<String, String>? = null
        return runtime.addSceneListener { scene ->
            if (scene.layoutConfirmed && !scene.transitioning) {
                val choice = scene.layoutId to (scene.paletteId ?: runtime.descriptor.palette(scene.layoutId))
                if (choice in choices && choice != lastSaved) {
                    save(application, runtime, choice.first, choice.second)
                    lastSaved = choice
                }
            } else lastSaved = null
        }
    }

    /** Persist only an accepted user choice, never a rejected change while the phone owns UI Sync. */
    fun select(context: Context, runtime: CarPlayOemThemeRuntime, layoutId: String, paletteId: String): Boolean {
        if (layoutId to paletteId !in runtime.descriptor.themeChoices()) return false
        if (!runtime.selectLocalTheme(layoutId, paletteId)) return false
        save(context, runtime, layoutId, paletteId)
        return true
    }

    private fun save(context: Context, runtime: CarPlayOemThemeRuntime, layoutId: String, paletteId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("asset_id", runtime.descriptor.assetId)
            .putString("layout_id", layoutId)
            .putString("palette_id", paletteId)
            .apply()
    }

    /** Restore before binding views or opening UI Sync; obsolete asset choices leave the default intact. */
    fun restore(context: Context, runtime: CarPlayOemThemeRuntime): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("asset_id", null) != runtime.descriptor.assetId) return false
        val layout = prefs.getString("layout_id", null) ?: return false
        val palette = prefs.getString("palette_id", null) ?: return false
        if (layout to palette !in runtime.descriptor.themeChoices()) return false
        return runtime.selectLocalTheme(layout, palette)
    }
}
