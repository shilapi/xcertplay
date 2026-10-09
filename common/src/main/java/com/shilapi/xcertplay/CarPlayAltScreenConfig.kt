package com.shilapi.xcertplay

import kotlin.math.roundToInt

/** The alternate stream request and its host pane share this resolution. */
data class CarPlayAltScreenConfig(val widthPixels: Int, val heightPixels: Int) {
    init {
        require(widthPixels > 0 && heightPixels > 0) { "Alternate screen dimensions must be positive" }
    }

    data class Size(val width: Int, val height: Int)

    /** Fits the entire instrument image without stretching or cropping. */
    fun fitWithin(availableWidth: Int, availableHeight: Int): Size {
        require(availableWidth >= 0 && availableHeight >= 0)
        val scale = minOf(availableWidth.toDouble() / widthPixels, availableHeight.toDouble() / heightPixels)
        return Size(
            (widthPixels * scale).roundToInt().coerceAtMost(availableWidth),
            (heightPixels * scale).roundToInt().coerceAtMost(availableHeight),
        )
    }

    companion object {
        // Code configuration entry: change these together to request another instrument resolution.
        val DEFAULT = CarPlayAltScreenConfig(widthPixels = 1920, heightPixels = 720)

        // Ultra main stream is pinned to the OEM asset's Center_Display size (package.json displays[].size)
        // instead of the host pane, so the phone's centerConsole layout matches the declared panel.
        val ULTRA_MAIN = CarPlayAltScreenConfig(widthPixels = 1920, heightPixels = 720)
    }
}
