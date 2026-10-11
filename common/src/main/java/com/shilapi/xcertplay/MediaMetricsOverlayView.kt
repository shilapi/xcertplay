package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.media.MediaMetricsMonitor
import java.util.Locale
import kotlin.math.ceil

/** Draws the current media metrics snapshot without owning any media-side state. */
@SuppressLint("ViewConstructor")
class MediaMetricsOverlayView(
    context: Context,
    private val monitor: MediaMetricsMonitor,
    private val fpsAxisMax: () -> Float = { AirPlayDisplaySettings.DEFAULT_FPS.toFloat() },
) : View(context) {
    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint().apply {
        // Matches the debug log overlay so the two panels read as one family.
        color = Color.argb(150, 0, 0, 0)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 190, 200, 205)
        strokeWidth = density
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 218, 222)
        textSize = sp(10f)
        typeface = Typeface.MONOSPACE
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(12f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val videoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = VIDEO_COLOR
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val fpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FPS_COLOR
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val videoPath = Path()
    private val fpsPath = Path()
    private val panelRect = RectF()
    private val chartRect = RectF()
    private var snapshot = monitor.snapshot()
    private val refresh = object : Runnable {
        override fun run() {
            if (!isShown) return
            snapshot = monitor.snapshot()
            invalidate()
            postDelayed(this, MediaMetricsMonitor.DEFAULT_UPDATE_INTERVAL_MILLIS)
        }
    }

    init {
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startRefreshing()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        if (visibility == VISIBLE) startRefreshing() else removeCallbacks(refresh)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        panelRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRect(panelRect, backgroundPaint)

        val current = snapshot
        val padding = dp(8f)
        val readoutBaseline = padding - valuePaint.ascent()
        canvas.drawText(
            String.format(
                Locale.US,
                "Video %.1f ms   FPS %.1f",
                current.videoLatencyMs,
                current.fps,
            ),
            padding,
            readoutBaseline,
            valuePaint,
        )
        val outputLatency = current.audioOutputLatencyMs?.let {
            String.format(Locale.US, "%.1f ms", it)
        } ?: "--"
        val audioBaseline = readoutBaseline + dp(16f)
        canvas.drawText(
            String.format(Locale.US, "Audio buffer %.1f ms   output %s", current.audioLatencyMs, outputLatency),
            padding,
            audioBaseline,
            valuePaint,
        )

        // The plot shares the panel corners and starts at the bottom edge of the audio readout.
        chartRect.set(
            0f,
            audioBaseline + valuePaint.descent(),
            width.toFloat(),
            height.toFloat(),
        )
        if (chartRect.width() <= 0f || chartRect.height() <= 0f) return

        val samples = current.samples
        val videoMax = niceMaximum(maxOf(MIN_VIDEO_MAX_MS, samples.maxOfOrNull { it.videoLatencyMs } ?: 0f))
        val fpsMax = resolvedFpsAxisMax()
        drawGrid(canvas, chartRect)
        drawSeries(
            canvas,
            chartRect,
            samples,
            videoMax,
            fpsMax,
            current.timestampNs,
            current.chartWindowNs,
        )
        // Axis values are drawn after the series so the traces never wash them out.
        drawAxisValues(canvas, chartRect, videoMax, fpsMax)
        drawLegend(canvas, chartRect, videoMax, fpsMax)
    }

    private fun drawGrid(canvas: Canvas, chart: RectF) {
        for (index in 0..GRID_ROWS) {
            val y = chart.bottom - chart.height() * index / GRID_ROWS
            canvas.drawLine(chart.left, y, chart.right, y, gridPaint)
        }
        for (index in 0..GRID_COLUMNS) {
            val x = chart.left + chart.width() * index / GRID_COLUMNS
            canvas.drawLine(x, chart.top, x, chart.bottom, gridPaint)
        }
    }

    /**
     * Draws the millisecond scale on the left and the frame-rate scale on the right, both inside
     * the plot so the chart can stay flush with the panel corners.
     */
    private fun drawAxisValues(canvas: Canvas, chart: RectF, videoMax: Float, fpsMax: Float) {
        val horizontalInset = dp(4f)
        val verticalInset = dp(3f)
        // The bottom row is the zero baseline and stays unlabelled.
        for (index in 1..GRID_ROWS) {
            val ratio = index.toFloat() / GRID_ROWS
            val y = chart.bottom - chart.height() * ratio
            val baseline = y - labelPaint.ascent() + verticalInset
            canvas.drawText(formatAxis(videoMax * ratio), chart.left + horizontalInset, baseline, labelPaint)
            val fpsLabel = formatAxis(fpsMax * ratio)
            canvas.drawText(
                fpsLabel,
                chart.right - horizontalInset - labelPaint.measureText(fpsLabel),
                baseline,
                labelPaint,
            )
        }
    }

    private fun drawLegend(
        canvas: Canvas,
        chart: RectF,
        videoMax: Float,
        fpsMax: Float,
    ) {
        val axisInset = dp(4f)
        val legendGap = dp(8f)
        val lineWidth = dp(12f)
        val textGap = dp(3f)
        val seriesGap = dp(10f)
        val videoLabelWidth = labelPaint.measureText("Video ms")
        val fpsLabelWidth = labelPaint.measureText("FPS")
        val legendWidth = lineWidth + textGap + videoLabelWidth + seriesGap +
            lineWidth + textGap + fpsLabelWidth
        val availableLeft = chart.left + axisInset +
            labelPaint.measureText(formatAxis(videoMax)) + legendGap
        val availableRight = chart.right - axisInset -
            labelPaint.measureText(formatAxis(fpsMax)) - legendGap
        val fitsTopRow = availableRight - availableLeft >= legendWidth
        var x = if (fitsTopRow) {
            availableLeft + (availableRight - availableLeft - legendWidth) / 2f
        } else {
            chart.left + (chart.width() - legendWidth) / 2f
        }
        val y = chart.top + dp(11f) + if (fitsTopRow) 0f else labelPaint.fontSpacing
        canvas.drawLine(x, y, x + lineWidth, y, videoPaint)
        x += lineWidth + textGap
        canvas.drawText("Video ms", x, y + dp(3f), labelPaint)
        x += videoLabelWidth + seriesGap
        canvas.drawLine(x, y, x + lineWidth, y, fpsPaint)
        x += lineWidth + textGap
        canvas.drawText("FPS", x, y + dp(3f), labelPaint)
    }

    private fun drawSeries(
        canvas: Canvas,
        chart: RectF,
        samples: List<MediaMetricsMonitor.Sample>,
        videoMax: Float,
        fpsMax: Float,
        nowNs: Long,
        chartWindowNs: Long,
    ) {
        if (samples.isEmpty()) return
        val windowStartNs = nowNs - chartWindowNs
        val lineInset = maxOf(videoPaint.strokeWidth, fpsPaint.strokeWidth) / 2f
        val lineTop = chart.top + lineInset
        val lineHeight = (chart.height() - lineInset * 2f).coerceAtLeast(0f)
        videoPath.rewind()
        fpsPath.rewind()
        samples.forEachIndexed { index, sample ->
            val xRatio = ((sample.timestampNs - windowStartNs).toDouble() /
                chartWindowNs).toFloat().coerceIn(0f, 1f)
            val x = chart.left + chart.width() * xRatio
            val videoY = lineTop + lineHeight *
                (1f - (sample.videoLatencyMs / videoMax).coerceIn(0f, 1f))
            val fpsY = lineTop + lineHeight * (1f - (sample.fps / fpsMax).coerceIn(0f, 1f))
            if (index == 0) {
                videoPath.moveTo(x, videoY)
                fpsPath.moveTo(x, fpsY)
            } else {
                videoPath.lineTo(x, videoY)
                fpsPath.lineTo(x, fpsY)
            }
        }
        canvas.drawPath(videoPath, videoPaint)
        canvas.drawPath(fpsPath, fpsPaint)
    }

    private fun startRefreshing() {
        removeCallbacks(refresh)
        if (isShown) post(refresh)
    }

    private fun niceMaximum(value: Float): Float = (ceil(value / 10f) * 10f).coerceAtLeast(10f)

    /**
     * The frame-rate scale is fixed to the configured target so the trace height is comparable
     * between sessions and screens.
     */
    private fun resolvedFpsAxisMax(): Float = fpsAxisMax()
        .takeIf { it.isFinite() && it > 0f }
        ?.coerceIn(AirPlayDisplaySettings.MIN_FPS.toFloat(), AirPlayDisplaySettings.MAX_FPS.toFloat())
        ?: AirPlayDisplaySettings.DEFAULT_FPS.toFloat()

    private fun formatAxis(value: Float): String = String.format(Locale.US, "%.0f", value)

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    private companion object {
        const val GRID_ROWS = 3
        const val GRID_COLUMNS = 3
        const val MIN_VIDEO_MAX_MS = 50f
        val VIDEO_COLOR = Color.rgb(83, 214, 140)
        val FPS_COLOR = Color.rgb(255, 178, 71)
    }
}
