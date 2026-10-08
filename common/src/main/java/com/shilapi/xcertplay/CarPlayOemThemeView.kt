package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.view.View
import com.shilapi.xcertplay.airplay.CarPlayOemThemeRuntime
import com.shilapi.xcertplay.airplay.CarPlayOemScene
import kotlin.math.min

/** Local gauge overlay in OEM coordinates. Remote video remains on the TextureView beneath it.
 * This intentionally does not claim CP/APL animation, font, verified-telltale or filter parity.
 */
class CarPlayOemThemeView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var runtime: CarPlayOemThemeRuntime? = null
    private var scene: CarPlayOemScene? = null
    private var logo: Bitmap? = null
    private var ownedListener: ((CarPlayOemScene) -> Unit)? = null
    private var artworkLayout: String? = null
    private var artwork: List<Pair<OemArtwork, Bitmap>> = emptyList()
    private var bindGeneration = 0L

    fun bind(value: CarPlayOemThemeRuntime?) {
        if (runtime?.listener === ownedListener) runtime?.listener = null
        bindGeneration++
        val generation = bindGeneration
        logo?.recycle(); logo = null
        artwork.forEach { it.second.recycle() }; artwork = emptyList(); artworkLayout = null
        runtime = value; scene = value?.scene
        ownedListener = value?.let { host -> { state: CarPlayOemScene -> post {
            if (runtime === host && bindGeneration == generation) {
                scene = state
                loadArtwork(host, state.layoutId)
                invalidate()
            }
        }; Unit } }
        value?.listener = ownedListener
        // Read actual OEM artwork; never invent a manufacturer logo.
        value?.let { host ->
            runCatching {
                val item = host.descriptor.cluster["manufacturerLogo"] as Map<*, *>
                val type = item["type"] as Map<*, *>
                val image = type["image"] as Map<*, *>
                val id = (image["_0"] as Map<*, *>)["identifier"] as String
                val data = host.readResource(id)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 4_000_000)
                logo = BitmapFactory.decodeByteArray(data, 0, data.size)
            }
            loadArtwork(host, host.scene.layoutId)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val host = runtime ?: return
        val state = scene ?: return
        if (!state.uiConnected || !state.layoutConfirmed) return
        val scale = min(width.toFloat() / host.descriptor.clusterWidth, height.toFloat() / host.descriptor.clusterHeight)
        val saved = canvas.save()
        canvas.translate((width - host.descriptor.clusterWidth * scale) / 2,
            (height - host.descriptor.clusterHeight * scale) / 2)
        canvas.scale(scale, scale)
        val plan = OemScenePlan.build(host.descriptor.layout(state.layoutId), state.activeComponents, state.vehicleValues)
        // Phone video is already in full display coordinates. Keep confirmed remote zones transparent;
        // mask its other pixels so phone content cannot bleed through locally rendered instruments.
        val mask = Path().apply {
            addRect(0f, 0f, host.descriptor.clusterWidth.toFloat(), host.descriptor.clusterHeight.toFloat(), Path.Direction.CW)
            plan.remoteFrames.forEach { frame ->
                val remote = Path().apply { addRect(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height, Path.Direction.CW) }
                op(remote, Path.Op.DIFFERENCE)
            }
        }
        resetPaint()
        paint.color = Color.BLACK
        canvas.drawPath(mask, paint)
        artwork.forEach { (item, bitmap) ->
            resetPaint()
            resolveColor(host, state, item.colorId)?.let { paint.colorFilter = PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN) }
            canvas.drawBitmap(bitmap, null, item.frame.rect(), paint)
        }
        logo?.let { image ->
            val ratio = min(180f / image.width, 48f / image.height)
            val w = image.width * ratio; val h = image.height * ratio
            resetPaint(); paint.color = Color.WHITE
            canvas.drawBitmap(image, null, RectF((host.descriptor.clusterWidth - w) / 2, 660f,
                (host.descriptor.clusterWidth + w) / 2, 660f + h), paint)
        }
        plan.gauges.forEach { gauge ->
            val frame = gauge.frame
            gauge(canvas, frame.x, frame.y, frame.width, frame.height, gauge.reading, gauge.unit,
                resolveColor(host, state, "radial.detailText") ?: Color.WHITE)
        }
        canvas.restoreToCount(saved)
    }

    private fun gauge(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, reading: Double?, unit: String, detailColor: Int) {
        resetPaint()
        // Diagnostic number overlay; full asset-native CP gauge/PRNDL semantics are not implemented.
        paint.color = Color.rgb(50, 64, 59); paint.style = Paint.Style.STROKE; paint.strokeWidth = 6f
        canvas.drawArc(RectF(x + 28, y + 28, x + w - 28, y + h - 28), 135f, 270f, false, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(w, h) * .2f
        canvas.drawText(reading?.let { "%.0f".format(java.util.Locale.ROOT, it) } ?: "—", x + w / 2, y + h * .56f, paint)
        paint.textSize = min(w, h) * .045f
        paint.color = detailColor
        canvas.drawText(if (reading == null) "Unavailable" else unit, x + w / 2, y + h * .66f, paint)
    }

    private fun OemFrame.rect() = RectF(x, y, x + width, y + height)

    private fun resetPaint() { paint.reset(); paint.isAntiAlias = true; paint.isFilterBitmap = true }

    private fun loadArtwork(host: CarPlayOemThemeRuntime, layoutId: String) {
        if (artworkLayout == layoutId) return
        artwork.forEach { it.second.recycle() }; artwork = emptyList(); artworkLayout = layoutId
        var pixels = 0L
        artwork = OemScenePlan.artwork(host.descriptor.layout(layoutId)).mapNotNull { item ->
            runCatching {
                val data = host.readResource(item.identifier)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                val count = bounds.outWidth.toLong() * bounds.outHeight
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && count in 1..4_000_000 && pixels + count <= 8_000_000)
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return@runCatching null
                pixels += count
                item to bitmap
            }.getOrNull()
        }
    }

    private fun resolveColor(host: CarPlayOemThemeRuntime, state: CarPlayOemScene, id: String?): Int? {
        if (id == null) return null
        val palette = state.paletteId ?: host.descriptor.palette(state.layoutId)
        val definition = host.descriptor.colorDefinition(state.layoutId, palette, id) ?: return null
        val single = (definition["single"] as? Map<*, *>)?.get("value") as? Map<*, *>
        val dynamic = (definition["dynamic"] as? Map<*, *>)?.get("value") as? Map<*, *>
        val color = single ?: dynamic?.get(if (state.appearance == 1) "darkMode" else "lightMode") as? Map<*, *> ?: return null
        val stops = color["stops"] as? Map<*, *> ?: return null
        // Complex gradients require CP's native renderer. Do not turn one arbitrary stop into a solid.
        if (stops.size != 1) return null
        val rgba = stops.values.singleOrNull() as? String ?: return null
        if (!rgba.matches(Regex("#[0-9a-fA-F]{8}"))) return null
        return runCatching { Color.parseColor("#${rgba.substring(7, 9)}${rgba.substring(1, 7)}") }.getOrNull()
    }
}
