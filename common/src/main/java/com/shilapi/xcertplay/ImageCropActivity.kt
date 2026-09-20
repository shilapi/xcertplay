package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Loads one local image and writes a user-positioned 1:1 PNG into the AirPlay icon slot. */
class ImageCropActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var cropView: SquareCropView
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }

        cropView = SquareCropView(this)
        statusView = TextView(this).apply {
            // The hint floats over the user's photo, which can be any colour, so it gets its own
            // dark pill instead of dark text drawn straight onto the image.
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(GlassPalette.HINT_SCRIM)
            }
            text = "正在载入图片"
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        controls.addView(
            Button(this).apply {
                text = "取消"
                isAllCaps = false
                textSize = 17f
                setTextColor(GlassPalette.PRIMARY)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(18).toFloat()
                    setColor(GlassPalette.BUTTON_NEUTRAL)
                }
                stateListAnimator = null
                minHeight = dp(52)
                setOnClickListener {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(
            Button(this).apply {
                text = "保存 1:1"
                isAllCaps = false
                textSize = 17f
                setTextColor(GlassPalette.BUTTON_TEXT)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(18).toFloat()
                    setColor(GlassPalette.ACCENT)
                }
                stateListAnimator = null
                minHeight = dp(52)
                setOnClickListener { saveCrop() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )

        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(GlassPalette.BACKGROUND_TOP, GlassPalette.BACKGROUND_BOTTOM),
            )
            addView(
                cropView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                statusView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply { topMargin = dp(12) },
            )
            addView(
                controls,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ),
            )
        }
        setContentView(root)

        executor.execute {
            val bitmap = try {
                decodeBitmap(uri)
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                if (bitmap == null) {
                    statusView.text = "无法解码图片"
                } else {
                    cropView.setBitmap(bitmap)
                    statusView.text = "拖动移动，双指缩放"
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        cropView.bitmap?.recycle()
        super.onDestroy()
    }

    private fun saveCrop() {
        val cropped = cropView.cropToSquare() ?: run {
            statusView.text = "图片尚未就绪"
            return
        }
        val encoded = ByteArrayOutputStream().use { output ->
            if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                cropped.recycle()
                statusView.text = "无法编码图片"
                return
            }
            cropped.recycle()
            output.toByteArray()
        }
        try {
            AirPlayPersistence.saveCustomAirPlayIcon(applicationContext, encoded)
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_CROP_COMPLETE, true),
            )
            finish()
        } catch (_: Exception) {
            statusView.text = "无法保存图片"
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DECODE_EDGE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class SquareCropView(context: android.content.Context) : View(context) {
        private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val dimPaint = Paint().apply { color = GlassPalette.CROP_MASK }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1f * resources.displayMetrics.density
        }
        private val matrix = Matrix()
        private val viewport = RectF()
        private val mappedBitmap = RectF()
        private var minimumScale = 1f
        private var maximumScale = 1f
        private var lastX = 0f
        private var lastY = 0f
        private var lastDistance = 0f

        var bitmap: Bitmap? = null
            private set

        fun setBitmap(value: Bitmap) {
            bitmap?.recycle()
            bitmap = value
            if (width > 0 && height > 0) configure()
            invalidate()
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            configure()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val source = bitmap ?: return
            canvas.drawColor(GlassPalette.BACKGROUND_TOP)
            canvas.drawRect(0f, 0f, width.toFloat(), viewport.top, dimPaint)
            canvas.drawRect(0f, viewport.bottom, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(0f, viewport.top, viewport.left, viewport.bottom, dimPaint)
            canvas.drawRect(viewport.right, viewport.top, width.toFloat(), viewport.bottom, dimPaint)

            val save = canvas.save()
            canvas.clipRect(viewport)
            canvas.drawBitmap(source, matrix, imagePaint)
            canvas.restoreToCount(save)

            canvas.drawRect(viewport, borderPaint)
            val thirdWidth = viewport.width() / 3f
            val thirdHeight = viewport.height() / 3f
            canvas.drawLine(viewport.left + thirdWidth, viewport.top, viewport.left + thirdWidth, viewport.bottom, gridPaint)
            canvas.drawLine(viewport.right - thirdWidth, viewport.top, viewport.right - thirdWidth, viewport.bottom, gridPaint)
            canvas.drawLine(viewport.left, viewport.top + thirdHeight, viewport.right, viewport.top + thirdHeight, gridPaint)
            canvas.drawLine(viewport.left, viewport.bottom - thirdHeight, viewport.right, viewport.bottom - thirdHeight, gridPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (bitmap == null) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    return true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    lastDistance = pointerDistance(event)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        val distance = pointerDistance(event)
                        if (lastDistance > 0f) {
                            val factor = distance / lastDistance
                            matrix.postScale(
                                factor,
                                factor,
                                (event.getX(0) + event.getX(1)) / 2f,
                                (event.getY(0) + event.getY(1)) / 2f,
                            )
                        }
                        lastDistance = distance
                    } else {
                        matrix.postTranslate(event.x - lastX, event.y - lastY)
                    }
                    lastX = event.x
                    lastY = event.y
                    constrainMatrix()
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    lastDistance = 0f
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    lastDistance = 0f
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

        fun cropToSquare(): Bitmap? {
            val source = bitmap ?: return null
            if (viewport.width() <= 0f || viewport.height() <= 0f) return null
            val inverse = Matrix()
            if (!matrix.invert(inverse)) return null
            val sourceRect = RectF()
            inverse.mapRect(sourceRect, viewport)
            val left = floor(sourceRect.left).toInt().coerceIn(0, source.width - 1)
            val top = floor(sourceRect.top).toInt().coerceIn(0, source.height - 1)
            val right = ceil(sourceRect.right).toInt().coerceIn(left + 1, source.width)
            val bottom = ceil(sourceRect.bottom).toInt().coerceIn(top + 1, source.height)
            val square = min(right - left, bottom - top)
            val centerX = (left + right) / 2
            val centerY = (top + bottom) / 2
            val cropLeft = (centerX - square / 2).coerceIn(0, source.width - square)
            val cropTop = (centerY - square / 2).coerceIn(0, source.height - square)
            val cropped = Bitmap.createBitmap(source, cropLeft, cropTop, square, square)
            val output = Bitmap.createBitmap(
                OUTPUT_ICON_PIXELS,
                OUTPUT_ICON_PIXELS,
                Bitmap.Config.ARGB_8888,
            )
            Canvas(output).drawBitmap(
                cropped,
                null,
                RectF(0f, 0f, OUTPUT_ICON_PIXELS.toFloat(), OUTPUT_ICON_PIXELS.toFloat()),
                imagePaint,
            )
            if (cropped !== source) cropped.recycle()
            return output
        }

        private fun configure() {
            val source = bitmap ?: return
            if (width <= 0 || height <= 0) return
            val side = min(width, height).toFloat() - 2f * CROP_PADDING_DP * resources.displayMetrics.density
            if (side <= 0f) return
            viewport.set(
                (width - side) / 2f,
                (height - side) / 2f,
                (width + side) / 2f,
                (height + side) / 2f,
            )
            minimumScale = max(side / source.width, side / source.height)
            maximumScale = minimumScale * MAX_ZOOM
            matrix.reset()
            matrix.postScale(minimumScale, minimumScale)
            matrix.postTranslate(
                viewport.centerX() - source.width * minimumScale / 2f,
                viewport.centerY() - source.height * minimumScale / 2f,
            )
            constrainMatrix()
            invalidate()
        }

        private fun constrainMatrix() {
            val source = bitmap ?: return
            val values = FloatArray(9)
            matrix.getValues(values)
            val scale = values[Matrix.MSCALE_X]
            if (scale < minimumScale) {
                matrix.postScale(minimumScale / scale, minimumScale / scale, viewport.centerX(), viewport.centerY())
            } else if (scale > maximumScale) {
                matrix.postScale(maximumScale / scale, maximumScale / scale, viewport.centerX(), viewport.centerY())
            }
            mappedBitmap.set(0f, 0f, source.width.toFloat(), source.height.toFloat())
            matrix.mapRect(mappedBitmap)
            var dx = 0f
            var dy = 0f
            if (mappedBitmap.left > viewport.left) dx = viewport.left - mappedBitmap.left
            if (mappedBitmap.right < viewport.right) dx = viewport.right - mappedBitmap.right
            if (mappedBitmap.top > viewport.top) dy = viewport.top - mappedBitmap.top
            if (mappedBitmap.bottom < viewport.bottom) dy = viewport.bottom - mappedBitmap.bottom
            if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
        }

        private fun pointerDistance(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        private companion object {
            const val CROP_PADDING_DP = 24f
            const val MAX_ZOOM = 8f
        }
    }

    companion object {
        const val EXTRA_CROP_COMPLETE = "crop_complete"
        private const val MAX_DECODE_EDGE = 2048
        private const val OUTPUT_ICON_PIXELS = 1024
    }
}
