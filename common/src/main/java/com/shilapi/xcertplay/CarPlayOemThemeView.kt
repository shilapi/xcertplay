package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.shilapi.xcertplay.airplay.CarPlayOemScene
import com.shilapi.xcertplay.airplay.CarPlayOemThemeRuntime

/** Local overlay and web encoding share the same OEM renderer, including gauges and video holes. */
class CarPlayOemThemeView(context: Context) : View(context) {
    private val renderer = CarPlayOemThemeRenderer(context)
    private var runtime: CarPlayOemThemeRuntime? = null
    private var ownedListener: ((CarPlayOemScene) -> Unit)? = null

    fun bind(value: CarPlayOemThemeRuntime?) {
        if (runtime?.listener === ownedListener) runtime?.listener = null
        runtime = value
        renderer.bind(value)
        ownedListener = value?.let { host -> { _: CarPlayOemScene ->
            post { if (runtime === host) invalidate() }; Unit
        } }
        value?.listener = ownedListener
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (renderer.draw(canvas, width, height)) postInvalidateOnAnimation()
    }
}
