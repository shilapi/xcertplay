package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.SafeAreaRect

/** Full-screen editor for the two horizontal and two vertical safe-area boundaries. */
class SafeAreaEditorView(context: Context) : View(context) {
    private enum class Edge {
        LEFT,
        TOP,
        RIGHT,
        BOTTOM,
    }

    private val density = resources.displayMetrics.density
    private val touchRadius = 40f * density

    /** How far the feather shadow reaches into the frosted band. */
    private val feather = 28f * density

    /** Frosted veil over the pixels that the crop discards. */
    private val frostPaint = Paint().apply { color = GlassPalette.SCRIM_FROST }

    /** Barely-there white inside the rectangle, so the kept pane reads as polished glass. */
    private val panePaint = Paint().apply { color = GlassPalette.PANE_WASH }

    /** Shader-driven; the colour is supplied per band by [drawFeather]. */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * White channel underneath the boundary line.
     *
     * The line itself is the brand accent, which is only about 3.5:1 against a light pane and can
     * drop to roughly 1:1 over a bright patch of live video -- exactly the case this editor exists
     * for. A translucent white stroke under it gives the line a background of known luminance, so
     * it stays readable whatever the phone is projecting. This replaces an earlier accent-coloured
     * glow, which shared a hue with the line and therefore lowered its edge contrast.
     */
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GlassPalette.ACCENT_HALO
        strokeWidth = 13f * density
        style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GlassPalette.ACCENT
        strokeWidth = 3f * density
        style = Paint.Style.STROKE
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GlassPalette.LABEL_BG
    }
    private val labelRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GlassPalette.HAIRLINE
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GlassPalette.PRIMARY
        textSize = 15f * density * resources.configuration.fontScale
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private var rect: SafeAreaRect? = null
    private var sourceRect: SafeAreaRect? = null
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var activeEdge: Edge? = null

    init {
        isClickable = true
    }

    fun setRect(value: SafeAreaRect, sourceWidthPixels: Int, sourceHeightPixels: Int) {
        require(sourceWidthPixels > 0 && sourceHeightPixels > 0) {
            "Source dimensions must be positive"
        }
        sourceWidth = sourceWidthPixels
        sourceHeight = sourceHeightPixels
        sourceRect = value
        if (width > 0 && height > 0) {
            rect = AirPlaySafeArea.scaleRect(
                value,
                sourceWidth,
                sourceHeight,
                width,
                height,
            )
            invalidate()
        }
    }

    fun currentRectForSource(): SafeAreaRect? {
        sourceRect?.let { return it }
        val current = rect ?: return null
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0) return null
        return AirPlaySafeArea.scaleRect(current, width, height, sourceWidth, sourceHeight)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        val source = sourceRect ?: AirPlaySafeArea.default(width, height)
        rect = if (sourceWidth > 0 && sourceHeight > 0) {
            AirPlaySafeArea.scaleRect(source, sourceWidth, sourceHeight, width, height)
        } else {
            source.clampTo(width, height)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val safe = rect ?: return
        val width = width.toFloat()
        val height = height.toFloat()
        val left = safe.left.toFloat()
        val top = safe.top.toFloat()
        val right = safe.right.toFloat()
        val bottom = safe.bottom.toFloat()

        // 1. Frost over everything the crop throws away.
        canvas.drawRect(0f, 0f, width, top, frostPaint)
        canvas.drawRect(0f, bottom, width, height, frostPaint)
        canvas.drawRect(0f, top, left, bottom, frostPaint)
        canvas.drawRect(right, top, width, bottom, frostPaint)

        // 2. Feather the frost edge: darkest on the boundary, clearing outwards, so the rectangle
        //    reads as a raised pane rather than a hard cut-out.
        drawFeather(canvas, left, top, right, bottom, width, height)

        // 3. The kept pane itself.
        canvas.drawRect(left, top, right, bottom, panePaint)

        // 4. Boundary lines run the full width/height so the grab target is visible from the edge.
        drawBoundary(canvas, left, 0f, left, height)
        drawBoundary(canvas, right, 0f, right, height)
        drawBoundary(canvas, 0f, top, width, top)
        drawBoundary(canvas, 0f, bottom, width, bottom)

        drawLabel(canvas, "x=${safe.left}", left + 10f * density, top + 22f * density)
        drawLabel(canvas, "x=${safe.right}", right + 10f * density, bottom - 10f * density)
        drawLabel(canvas, "y=${safe.top}", left + 10f * density, top - 10f * density)
        drawLabel(canvas, "y=${safe.bottom}", right - 96f * density, bottom + 22f * density)
    }

    /** Soft inner shadow spilling out of the rectangle into the frosted band. */
    private fun drawFeather(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        width: Float,
        height: Float,
    ) {
        fun shader(fromX: Float, fromY: Float, toX: Float, toY: Float): Shader =
            LinearGradient(
                fromX,
                fromY,
                toX,
                toY,
                GlassPalette.SCRIM_SHADOW_CLEAR,
                GlassPalette.SCRIM_SHADOW,
                Shader.TileMode.CLAMP,
            )

        // The bands are clipped to the space actually available, so a rectangle pushed against a
        // screen edge simply gets a shorter feather instead of bleeding past the view.
        val topSpan = minOf(feather, top)
        if (topSpan > 0f) {
            shadowPaint.shader = shader(0f, top - topSpan, 0f, top)
            canvas.drawRect(0f, top - topSpan, width, top, shadowPaint)
        }
        val bottomSpan = minOf(feather, height - bottom)
        if (bottomSpan > 0f) {
            shadowPaint.shader = shader(0f, bottom + bottomSpan, 0f, bottom)
            canvas.drawRect(0f, bottom, width, bottom + bottomSpan, shadowPaint)
        }
        val leftSpan = minOf(feather, left)
        if (leftSpan > 0f) {
            shadowPaint.shader = shader(left - leftSpan, 0f, left, 0f)
            canvas.drawRect(left - leftSpan, top, left, bottom, shadowPaint)
        }
        val rightSpan = minOf(feather, width - right)
        if (rightSpan > 0f) {
            shadowPaint.shader = shader(right + rightSpan, 0f, right, 0f)
            canvas.drawRect(right, top, right + rightSpan, bottom, shadowPaint)
        }
        shadowPaint.shader = null
    }

    private fun drawBoundary(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        canvas.drawLine(x0, y0, x1, y1, haloPaint)
        canvas.drawLine(x0, y0, x1, y1, linePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val safe = rect ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeEdge = nearestEdge(event.x, event.y, safe)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val edge = activeEdge ?: return true
                val moved = moveEdge(safe, edge, event.x, event.y)
                rect = moved
                if (sourceWidth > 0 && sourceHeight > 0 && width > 0 && height > 0) {
                    sourceRect = AirPlaySafeArea.scaleRect(
                        moved,
                        width,
                        height,
                        sourceWidth,
                        sourceHeight,
                    )
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                activeEdge = null
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun nearestEdge(x: Float, y: Float, safe: SafeAreaRect): Edge? {
        val candidates = listOf(
            Edge.LEFT to kotlin.math.abs(x - safe.left),
            Edge.RIGHT to kotlin.math.abs(x - safe.right),
            Edge.TOP to kotlin.math.abs(y - safe.top),
            Edge.BOTTOM to kotlin.math.abs(y - safe.bottom),
        ).filter { it.second <= touchRadius }
        return candidates.minByOrNull { it.second }?.first
    }

    private fun moveEdge(safe: SafeAreaRect, edge: Edge, x: Float, y: Float): SafeAreaRect {
        val pointX = x.toInt().coerceIn(0, width)
        val pointY = y.toInt().coerceIn(0, height)
        return when (edge) {
            Edge.LEFT -> safe.copy(left = pointX.coerceIn(0, safe.right - 1))
            Edge.TOP -> safe.copy(top = pointY.coerceIn(0, safe.bottom - 1))
            Edge.RIGHT -> safe.copy(right = pointX.coerceIn(safe.left + 1, width))
            Edge.BOTTOM -> safe.copy(bottom = pointY.coerceIn(safe.top + 1, height))
        }
    }

    /**
     * Coordinate readout on a white pill.
     *
     * The text used to be drawn straight onto the mask, which was fine while the mask was a flat
     * grey but is no longer guaranteed once live video sits behind the pane. [x]/[y] stay the text
     * baseline anchors they always were, so the call sites keep their original placement.
     */
    private fun drawLabel(canvas: Canvas, value: String, x: Float, y: Float) {
        val padX = 8f * density
        val padY = 5f * density
        val metrics = textPaint.fontMetrics
        val pillWidth = textPaint.measureText(value) + padX * 2f
        val pillHeight = (metrics.descent - metrics.ascent) + padY * 2f

        val minEdge = 2f * density
        val pillLeft = (x - padX).coerceIn(
            minEdge,
            (width - pillWidth - minEdge).coerceAtLeast(minEdge),
        )
        val pillTop = (y + metrics.ascent - padY).coerceIn(
            minEdge,
            (height - pillHeight - minEdge).coerceAtLeast(minEdge),
        )
        val pill = RectF(pillLeft, pillTop, pillLeft + pillWidth, pillTop + pillHeight)
        val radius = pillHeight / 2f
        canvas.drawRoundRect(pill, radius, radius, labelBgPaint)
        canvas.drawRoundRect(pill, radius, radius, labelRimPaint)
        canvas.drawText(value, pillLeft + padX, pillTop + padY - metrics.ascent, textPaint)
    }
}
