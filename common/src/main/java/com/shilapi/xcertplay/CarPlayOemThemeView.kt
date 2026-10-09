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

/** Local instrument cluster in OEM coordinates; remote DCA video remains on the TextureView beneath it.
 * Style follows the active layout's `layoutStyleID`, colors follow the active palette. This is the minimal
 * dashboard (see agent_docs/2026-10-09_ultra-minimal-cluster-dashboard.zh-CN.md), not CP/APL parity:
 * no verified telltales, filters or asset animations.
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
    private val dashboard = CarPlayClusterDashboardRenderer()
    private var colorCache: Pair<String, ClusterColors>? = null
    private val speed = ClusterSmoothedValue()
    private val rpm = ClusterSmoothedValue(ratePerSecond = 10.0)
    private val fuel = ClusterSmoothedValue(ratePerSecond = 2.0)
    private val coolant = ClusterSmoothedValue(ratePerSecond = 2.0)
    private var smoothed = ClusterReadings.from(emptyMap())
    private var lastFrameNanos = 0L

    fun bind(value: CarPlayOemThemeRuntime?) {
        if (runtime?.listener === ownedListener) runtime?.listener = null
        bindGeneration++
        val generation = bindGeneration
        logo?.recycle(); logo = null
        artwork.forEach { it.second.recycle() }; artwork = emptyList(); artworkLayout = null
        runtime = value; scene = value?.scene; colorCache = null
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
        // The accessory always owns its instruments (asset gauges are `rendersLocally`); they do not wait for
        // UI Sync. Before the phone confirms a layout, its initial DCA components define the video holes.
        val layout = runCatching { host.descriptor.layout(state.layoutId) }.getOrNull() ?: return
        val scale = min(width.toFloat() / host.descriptor.clusterWidth, height.toFloat() / host.descriptor.clusterHeight)
        val saved = canvas.save()
        canvas.translate((width - host.descriptor.clusterWidth * scale) / 2,
            (height - host.descriptor.clusterHeight * scale) / 2)
        canvas.scale(scale, scale)
        val active = state.activeComponents.ifEmpty { runCatching { host.descriptor.initialComponents(state.layoutId) }.getOrDefault(emptyMap()) }
        val plan = OemScenePlan.build(layout, active, state.vehicleValues)
        // Phone video is already in full display coordinates. Keep remote zones transparent;
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

        val style = ClusterDialStyle.of(layout["layoutStyleID"])
        val palette = state.paletteId ?: host.descriptor.palette(state.layoutId)
        val colors = colorsFor(host, state.layoutId, palette, style)
        val zones = ClusterZones.from(layout, plan)
        dashboard.drawBackground(canvas, style, zones)
        artwork.forEach { (item, bitmap) ->
            resetPaint()
            resolveColor(host, state, item.colorId)?.let { paint.colorFilter = PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN) }
            canvas.drawBitmap(bitmap, null, item.frame.rect(), paint)
        }
        if (style != ClusterDialStyle.MAPS_MEDIA) logo?.let { image ->
            val ratio = min(180f / image.width, 48f / image.height)
            val w = image.width * ratio; val h = image.height * ratio
            resetPaint(); paint.color = Color.WHITE
            canvas.drawBitmap(image, null, RectF((host.descriptor.clusterWidth - w) / 2, 660f,
                (host.descriptor.clusterWidth + w) / 2, 660f + h), paint)
        }
        val moving = smooth(ClusterReadings.from(state.vehicleValues))
        dashboard.draw(canvas, style, colors, zones, smoothed) { frame -> artwork.any { (item, _) -> frame.contains(item.frame) } }
        canvas.restoreToCount(saved)
        if (moving) postInvalidateOnAnimation()
    }

    /** Glides speed/rpm/fuel/coolant toward the latest sample; returns true while another frame is needed. */
    private fun smooth(target: ClusterReadings): Boolean {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.0 else (now - lastFrameNanos) / 1e9
        lastFrameNanos = now
        val moving = listOf(speed.step(target.speed, dt), rpm.step(target.rpm, dt),
            fuel.step(target.fuelPercent, dt), coolant.step(target.coolant, dt)).any { it }
        if (!moving) lastFrameNanos = 0L
        smoothed = target.copy(speed = speed.value, rpm = rpm.value, fuelPercent = fuel.value, coolant = coolant.value)
        return moving
    }

    private fun colorsFor(host: CarPlayOemThemeRuntime, layoutId: String, palette: String, style: ClusterDialStyle): ClusterColors {
        val key = "$layoutId/$palette"
        colorCache?.takeIf { it.first == key }?.let { return it.second }
        val resolved = ClusterColors.resolve(style) { id ->
            runCatching { host.descriptor.colorDefinition(layoutId, palette, id) }.getOrNull()
        }
        colorCache = key to resolved
        return resolved
    }

    private fun OemFrame.contains(other: OemFrame) = other.x >= x && other.y >= y &&
        other.x + other.width <= x + width && other.y + other.height <= y + height

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
