package com.shilapi.xcertplay

import android.content.Context
import android.widget.FrameLayout

/** A top pane whose bounds follow the requested alternate stream's aspect ratio. */
class CarPlayAltScreenView(context: Context, private val config: CarPlayAltScreenConfig) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            config.widthPixels
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val availableHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        val size = config.fitWithin(availableWidth, availableHeight)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(size.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size.height, MeasureSpec.EXACTLY),
        )
    }
}
