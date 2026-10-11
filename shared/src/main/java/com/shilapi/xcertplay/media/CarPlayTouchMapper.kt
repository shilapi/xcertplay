package com.shilapi.xcertplay.media

import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayContact

/** Converts Android MotionEvents into normalized CarPlay touch contacts. */
object CarPlayTouchMapper {
    private const val MAX_CONTACTS = 2

    /** A stream size different from the view is letterboxed (fit-center), matching the video transform. */
    fun contacts(
        event: MotionEvent,
        viewWidth: Int,
        viewHeight: Int,
        streamWidth: Int = viewWidth,
        streamHeight: Int = viewHeight,
    ): List<AirPlayContact> {
        val width = viewWidth.coerceAtLeast(1)
        val height = viewHeight.coerceAtLeast(1)
        val action = event.actionMasked
        val liftedIndex = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val allUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val count = minOf(MAX_CONTACTS, event.pointerCount)
        val rect = contentRect(width, height, streamWidth.coerceAtLeast(1), streamHeight.coerceAtLeast(1))
        if (action == MotionEvent.ACTION_DOWN &&
            (event.x < rect[0] || event.x > rect[0] + rect[2] || event.y < rect[1] || event.y > rect[1] + rect[3])
        ) return emptyList()
        val contacts = ArrayList<AirPlayContact>(count)
        for (index in 0 until count) {
            contacts.add(
                AirPlayContact(
                    id = index,
                    x = ((event.getX(index) - rect[0]) / rect[2]).coerceIn(0.0, 1.0),
                    y = ((event.getY(index) - rect[1]) / rect[3]).coerceIn(0.0, 1.0),
                    down = !allUp && index != liftedIndex,
                ),
            )
        }
        return contacts
    }

    /** [left, top, width, height] of the fit-center stream inside the view. */
    fun contentRect(viewWidth: Int, viewHeight: Int, streamWidth: Int, streamHeight: Int): DoubleArray {
        val width = viewWidth.coerceAtLeast(1).toDouble()
        val height = viewHeight.coerceAtLeast(1).toDouble()
        val scale = minOf(width / streamWidth.coerceAtLeast(1), height / streamHeight.coerceAtLeast(1))
        val contentWidth = (streamWidth.coerceAtLeast(1) * scale).coerceIn(1.0, width)
        val contentHeight = (streamHeight.coerceAtLeast(1) * scale).coerceIn(1.0, height)
        return doubleArrayOf((width - contentWidth) / 2, (height - contentHeight) / 2, contentWidth, contentHeight)
    }
}
