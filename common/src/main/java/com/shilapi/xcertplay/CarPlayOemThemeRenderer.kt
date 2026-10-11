package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import com.shilapi.xcertplay.airplay.CarPlayOemThemeRuntime
import com.shilapi.xcertplay.airplay.CarPlayOemScene
import kotlin.math.min

/** Shared Canvas renderer for the local cluster and the encoded web cluster. One owner per output. */
internal class CarPlayOemThemeRenderer(private val context: Context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var runtime: CarPlayOemThemeRuntime? = null
    private var logo: Bitmap? = null
    private var artworkLayout: String? = null
    private var artwork: List<Pair<OemArtwork, Bitmap>> = emptyList()
    private val dashboard = CarPlayClusterDashboardRenderer()
    private var colorCache: Pair<String, ClusterColors>? = null
    private val speed = ClusterSmoothedValue()
    private val rpm = ClusterSmoothedValue(ratePerSecond = 10.0)
    private val fuel = ClusterSmoothedValue(ratePerSecond = 2.0)
    private val coolant = ClusterSmoothedValue(ratePerSecond = 2.0)
    private var smoothed = ClusterReadings.from(emptyMap())
    private var lastFrameNanos = 0L

    fun bind(value: CarPlayOemThemeRuntime?) {
        logo?.recycle(); logo = null
        dashboard.fonts?.recycle(); dashboard.fonts = null
        artwork.forEach { it.second.recycle() }; artwork = emptyList(); artworkLayout = null
        runtime = value; colorCache = null
        // Read actual OEM artwork; never invent a manufacturer logo.
        value?.let { host ->
            dashboard.fonts = CarPlayOemRasterFonts(host::readFontResource,
                context.assets.open("carplay-ultra/font-metrics.bplist").use { it.readBytes() })
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
    }

    fun draw(canvas: Canvas, width: Int, height: Int): Boolean {
        val host = runtime ?: return false
        val state = host.scene
        loadArtwork(host, state.layoutId)
        // The accessory always owns its instruments (asset gauges are `rendersLocally`); they do not wait for
        // UI Sync. Before the phone confirms a layout, its initial DCA components define the video holes.
        val layout = runCatching { host.descriptor.layout(state.layoutId) }.getOrNull() ?: return false
        val scale = min(width.toFloat() / host.descriptor.clusterWidth, height.toFloat() / host.descriptor.clusterHeight)
        val saved = canvas.save()
        canvas.translate((width - host.descriptor.clusterWidth * scale) / 2,
            (height - host.descriptor.clusterHeight * scale) / 2)
        canvas.scale(scale, scale)
        val active = if (state.layoutConfirmed) state.activeComponents else state.activeComponents.ifEmpty {
            runCatching { host.descriptor.initialComponents(state.layoutId) }.getOrDefault(emptyMap())
        }
        val plan = OemScenePlan.build(layout, active, state.vehicleValues)
        // The phone owns the full-frame video background; keep it visible beneath local gauges.
        // Its DCA map is already rounded. Only punch out local content in that rounded window;
        // never paint its outer corners, which must keep the phone's original background color.
        val localLayer = canvas.saveLayer(0f, 0f, host.descriptor.clusterWidth.toFloat(), host.descriptor.clusterHeight.toFloat(), null)

        val style = ClusterDialStyle.of(layout["layoutStyleID"])
        val palette = state.paletteId ?: host.descriptor.palette(state.layoutId)
        val colors = colorsFor(host, state.layoutId, palette, style, state.appearance)
        dashboard.resolveColor = { id -> resolveColor(host, state, id) }
        val zones = ClusterZones.from(layout, plan)
        dashboard.drawBackground(canvas, style, zones)
        artwork.filterNot { it.first.persistent }.forEach { (item, bitmap) ->
            resetPaint()
            resolveColor(host, state, item.colorId)?.let { paint.colorFilter = PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN) }
            canvas.drawBitmap(bitmap, null, item.frame.rect(), paint)
        }
        if (!zones.assetDriven && style != ClusterDialStyle.MAPS_MEDIA) logo?.let { image ->
            val ratio = min(180f / image.width, 48f / image.height)
            val w = image.width * ratio; val h = image.height * ratio
            resetPaint(); paint.color = Color.WHITE
            canvas.drawBitmap(image, null, RectF((host.descriptor.clusterWidth - w) / 2, 660f,
                (host.descriptor.clusterWidth + w) / 2, 660f + h), paint)
        }
        val moving = smooth(ClusterReadings.from(state.vehicleValues))
        dashboard.draw(canvas, style, colors, zones.copy(localSlots = zones.localSlots.filterNot { it.persistent }), smoothed) {
            frame -> artwork.any { (item, _) -> frame.contains(item.frame) }
        }
        resetPaint()
        paint.xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        plan.remoteRegions.forEach { canvas.drawRoundRect(it.frame.rect(), it.cornerRadius, it.cornerRadius, paint) }
        OemPopover.from(layout, state.transitionData).forEach { popover ->
            val checkpoint = canvas.save()
            val t = popover.transform
            val matrix = android.graphics.Matrix().apply {
                setValues(floatArrayOf(t[0], t[2], t[4], t[1], t[3], t[5], 0f, 0f, 1f))
            }
            canvas.concat(matrix)
            paint.alpha = (popover.opacity * 255).toInt()
            canvas.drawRoundRect(popover.frame.rect(), popover.radius, popover.radius, paint)
            canvas.restoreToCount(checkpoint)
        }
        paint.xfermode = null
        canvas.restoreToCount(localLayer)
        // persistent 读数在资产中独立于可裁切布局层。
        artwork.filter { it.first.persistent }.forEach { (item, bitmap) ->
            resetPaint()
            resolveColor(host, state, item.colorId)?.let { paint.colorFilter = PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN) }
            canvas.drawBitmap(bitmap, null, item.frame.rect(), paint)
        }
        dashboard.drawPersistent(canvas, colors, zones, smoothed)
        canvas.restoreToCount(saved)
        return moving
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

    private fun colorsFor(host: CarPlayOemThemeRuntime, layoutId: String, palette: String, style: ClusterDialStyle, appearance: Int): ClusterColors {
        val key = "$layoutId/$palette/$appearance"
        colorCache?.takeIf { it.first == key }?.let { return it.second }
        val resolved = ClusterColors.resolve(style, appearance) { id ->
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
