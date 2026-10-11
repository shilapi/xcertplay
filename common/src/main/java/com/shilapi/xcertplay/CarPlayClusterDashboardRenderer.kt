package com.shilapi.xcertplay

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Minimal Ultra cluster painter. Draws in the asset's 1920×720 coordinate space, inside the zone frames the
 * active layout declares; the layout style picks the dial shape and the palette supplies the colors.
 * It is an approximation of the asset look, not the CP/APL renderer.
 */
internal class CarPlayClusterDashboardRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digits = Typeface.create(Typeface.SANS_SERIF, 500, false)
    private val light = Typeface.create(Typeface.SANS_SERIF, 300, false)
    private val label = Typeface.create(Typeface.SANS_SERIF, 400, false)
    private val arcRect = RectF()
    var fonts: CarPlayOemRasterFonts? = null
    var resolveColor: (String) -> Int? = { null }

    data class Zones(val speed: OemFrame?, val rpm: OemFrame?, val centerSpeed: OemFrame?, val outsideTemperature: OemFrame?,
                     val localSlots: List<OemLocalSlot> = emptyList(), val assetDriven: Boolean = false)

    /** Dial faces; drawn before the asset's decorative artwork so that artwork stays visible on top. */
    fun drawBackground(canvas: Canvas, style: ClusterDialStyle, zones: Zones) {
        if (zones.assetDriven) return
        listOfNotNull(zones.speed, zones.rpm).forEach { f ->
            when (style) {
                ClusterDialStyle.MAPS_MEDIA -> Unit
                ClusterDialStyle.MODULAR -> panel(canvas, f)
                else -> backdrop(canvas, f.x + f.width / 2, f.y + f.height / 2, 285f * min(f.width, f.height) / 600f)
            }
        }
    }

    fun draw(canvas: Canvas, style: ClusterDialStyle, colors: ClusterColors, zones: Zones, r: ClusterReadings,
             hasArtwork: (OemFrame) -> Boolean) {
        if (zones.assetDriven || zones.localSlots.isNotEmpty()) {
            drawSlots(canvas, zones.localSlots, colors, r)
            if (r.simulated) text(canvas, "SIMULATED DATA", 960f,
                if (style == ClusterDialStyle.MAPS_MEDIA) 34f else 96f, 18f,
                ClusterColors.withAlpha(colors.detailText, 0x80), label, letterSpacing = 0.18f)
            return
        }
        when (style) {
            ClusterDialStyle.MODULAR -> {
                zones.speed?.let { modularSpeed(canvas, it, colors, r) }
                zones.rpm?.let { modularRpm(canvas, it, colors, r) }
            }
            ClusterDialStyle.MAPS_MEDIA -> mapsMedia(canvas, zones, colors, r)
            else -> {
                zones.speed?.let { radialSpeed(canvas, it, style, colors, r) }
                zones.rpm?.let { radialRpm(canvas, it, style, colors, r, hasArtwork(it)) }
            }
        }
        zones.outsideTemperature?.let { f ->
            text(canvas, r.outsideTemperature?.let { "${it.roundToInt()}°C" } ?: "—°C",
                f.x + f.width / 2, f.y + f.height * 0.74f, 26f, colors.detailText, label)
        }
        if (r.simulated) text(canvas, "SIMULATED DATA", 960f, if (style == ClusterDialStyle.MAPS_MEDIA) 34f else 96f,
            18f, ClusterColors.withAlpha(colors.detailText, 0x80), label, letterSpacing = 0.18f)
    }

    fun drawPersistent(canvas: Canvas, colors: ClusterColors, zones: Zones, r: ClusterReadings) {
        drawSlots(canvas, zones.localSlots.filter { it.persistent }, colors, r)
    }

    private fun drawSlots(canvas: Canvas, slots: List<OemLocalSlot>, c: ClusterColors, r: ClusterReadings) {
        slots.forEach { slot ->
            val f = slot.frame
            val saved = canvas.save()
            // 槽位是定位 frame，不是裁切框；线头与 centerOffset 可以延伸到 frame 外。
            drawAssetTicks(canvas, slot, c, r)
            ClusterSlotGauge.progress(slot, r)?.let { g ->
                val fill = g.colorId?.let(resolveColor) ?: if (g.background) c.track else
                    if (g.redline) c.redline else if (g.secondary) c.secondary else c.primary
                g.radial?.let { cfg ->
                    val angles = cfg["angleConfiguration"] as? Map<*, *> ?: return@let
                    val from = (angles["minimumAngle"] as? Number)?.toDouble() ?: return@let
                    val to = (angles["maximumAngle"] as? Number)?.toDouble() ?: return@let
                    val clockwise = angles["clockwise"] == true
                    val (start, sweep) = ClusterSlotGauge.angles(from, to, clockwise)
                    val width = (cfg["trackWidth"] as? Number)?.toFloat() ?: return@let
                    val inset = (cfg["trackInset"] as? Number)?.toFloat() ?: 0f
                    val radius = min(f.width, f.height) / 2 - inset - width / 2
                    if (radius > 0 && width > 0) {
                        paint.reset(); paint.isAntiAlias = true
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = width
                        paint.strokeCap = assetCap(cfg["trackLineCap"])
                        val cx = f.x + f.width / 2; val cy = f.y + f.height / 2
                        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
                        paint.color = fill
                        val end = if (g.redline || g.background) 1f else g.fraction
                        when {
                            g.gradient -> {
                                // 资产使用整圆遮罩和随进度旋转的 CONICAL 渐变，不能裁成实色进度弧。
                                val fixedStop = (0.25f + kotlin.math.abs(sweep) / 360f * g.fraction).coerceAtMost(0.999f)
                                val alpha = (android.graphics.Color.alpha(fill) *
                                    (0.2f + 0.8f * min(1f, g.fraction / 0.5f))).roundToInt()
                                val bright = (fill and 0x00ffffff) or (alpha shl 24)
                                val clear = fill and 0x00ffffff
                                val shader = SweepGradient(cx, cy, intArrayOf(bright, bright, clear, clear, clear),
                                    floatArrayOf(0f, 0.0001f, fixedStop, 0.9999f, 1f))
                                val transform = Matrix()
                                // 渐变从当前进度端向已走过的圆周方向淡出。
                                transform.setScale(1f, if (clockwise) -1f else 1f, cx, cy)
                                transform.postRotate(start + sweep * g.fraction, cx, cy)
                                shader.setLocalMatrix(transform)
                                paint.shader = shader
                                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                                canvas.drawCircle(cx, cy, radius, paint)
                                paint.shader = null
                                paint.xfermode = null
                            }
                            g.platter && g.colorId != null -> {
                                paint.style = Paint.Style.FILL
                                val platterInset = (cfg["platterInset"] as? Number)?.toFloat() ?: 0f
                                canvas.drawCircle(cx, cy, min(f.width, f.height) / 2 - platterInset, paint)
                            }
                            g.platter -> Unit
                            g.fullCircle -> canvas.drawCircle(cx, cy, radius, paint)
                            end > g.startFraction -> canvas.drawArc(arcRect, start + sweep * g.startFraction,
                                sweep * (end - g.startFraction), false, paint)
                        }
                    }
                }
                g.linear?.let { cfg ->
                    val left = (cfg["leftInset"] as? Number)?.toFloat() ?: 0f
                    val right = (cfg["rightInset"] as? Number)?.toFloat() ?: 0f
                    val width = (cfg["trackWidth"] as? Number)?.toFloat() ?: min(14f, f.height)
                    val w = f.width - left - right
                    val offset = (cfg["centerOffset"] as? Number)?.toFloat() ?: 0f
                    val end = if (g.redline || g.background) 1f else g.fraction
                    if (w > 0 && width > 0 && end > g.startFraction) {
                        paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE
                        paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND; paint.color = fill
                        val rtl = cfg["isRTL"] == true
                        fun x(p: Float) = f.x + left + w * if (rtl) 1f - p else p
                        val y = f.y + f.height / 2 + offset
                        canvas.drawLine(x(g.startFraction), y, x(end), y, paint)
                    }
                }
            }
            ClusterSlotContent.text(slot, r)?.let { content ->
                drawAssetText(canvas, content, if (content.large) c.largeText else c.detailText)
            }
            canvas.restoreToCount(saved)
        }
    }

    /** cap=0 的 ROUND 对应由 DigiRing 两端 atan((width/2)/radius) 的精确补偿支持；
     * 其余值保持平端，BUTT/SQUARE 的宿主枚举排列尚未直接查证。 */
    private fun assetCap(raw: Any?): Paint.Cap = if ((raw as? Number)?.toInt() == 0) Paint.Cap.ROUND else Paint.Cap.BUTT

    private fun drawAssetText(canvas: Canvas, content: ClusterSlotText, fallbackColor: Int) {
        val config = content.configuration
        val font = fonts?.get((config["font"] as? Map<*, *>)?.get("name") as? String ?: "")
        val unitFont = fonts?.get((config["unitFont"] as? Map<*, *>)?.get("name") as? String ?: "") ?: font
        val f = content.frame
        val unit = content.unit
        val value = if (unit.isNotEmpty()) content.value.removeSuffix(unit).trimEnd() else content.value
        val tracking = (config["valueTracking"] as? Number)?.toFloat() ?: 0f
        val spacing = if (unit.isNotEmpty() && value.isNotEmpty()) (config["labelSpacing"] as? Number)?.toFloat() ?: 0f else 0f
        val offset = (config["valueYOffset"] as? Number)?.toFloat() ?: 0f
        val colorIds = config["colorIDs"] as? Map<*, *>
        fun color(key: String) = ((colorIds?.get(key) as? Map<*, *>)?.get("0") as? String)?.let(resolveColor) ?: fallbackColor
        if (font != null && unitFont != null) {
            val valueWidth = font.width(value, tracking) + spacing
            val unitWidth = unitFont.width(unit)
            val x = when ((config["alignment"] as? Number)?.toInt()) {
                1 -> 0f
                2 -> (f.width - valueWidth - unitWidth).coerceAtLeast(0f)
                4 -> (f.width / 2 - valueWidth / 2).coerceAtLeast(0f)
                else -> ((f.width - valueWidth - unitWidth) / 2).coerceAtLeast(0f)
            }
            val baselineMode = (config["offsetType"] as? Number)?.toInt() == 2
            val y = if (baselineMode) f.height - font.baselineOffset - offset else (f.height - font.height) / 2 + offset
            font.draw(canvas, value, f.x + x, f.y + y, color("valueColorIDs"), tracking)
            val unitY = if (baselineMode) f.height - unitFont.height - offset else (f.height - font.height) / 2
            unitFont.draw(canvas, unit, f.x + x + valueWidth, f.y + unitY, color("unitColorIDs"))
            return
        }
        paint.reset(); paint.isAntiAlias = true; paint.typeface = if (content.large) digits else label
        paint.textSize = content.pointSize
        val measured = paint.measureText(content.value)
        val x = when ((config["alignment"] as? Number)?.toInt()) {
            1 -> f.x
            2 -> f.x + (f.width - measured).coerceAtLeast(0f)
            else -> f.x + ((f.width - measured) / 2).coerceAtLeast(0f)
        }
        val metrics = paint.fontMetrics
        val y = if ((config["offsetType"] as? Number)?.toInt() == 2) f.y + offset else
            f.y + (f.height - (metrics.descent - metrics.ascent)) / 2 - metrics.ascent + offset
        paint.color = color("valueColorIDs"); paint.textAlign = Paint.Align.LEFT
        canvas.drawText(content.value, x, y, paint)
    }

    private fun drawAssetTicks(canvas: Canvas, slot: OemLocalSlot, c: ClusterColors, r: ClusterReadings) {
        if (!ClusterSlotContent.visible(slot, r.values)) return
        val types = (slot.style["progressBar"] as? Map<*, *>)?.get("type") as? Map<*, *> ?: return
        val kind = if (types.containsKey("tickmarks")) "tickmarks" else if (types.containsKey("tickLabels")) "tickLabels" else return
        val body = types[kind] as? Map<*, *> ?: return
        val config = body["configuration"] as? Map<*, *> ?: return
        val shape = ((config["style"] as? Map<*, *>)?.get("circular") as? Map<*, *>)?.get("configuration") as? Map<*, *> ?: return
        val from = (shape["minimumAngle"] as? Number)?.toDouble() ?: return
        val to = (shape["maximumAngle"] as? Number)?.toDouble() ?: return
        val (start, sweep) = ClusterSlotGauge.angles(from, to, shape["clockwise"] == true)
        val source = body["dataSource"] as? Map<*, *> ?: return
        val rpm = source.containsKey("tachometer")
        val (max, labelCount) = ClusterDialGeometry.scale(if (rpm) r.rpmMax else r.speedMax, rpm)
        if (labelCount < 2) return
        val major = max / (labelCount - 1)
        val labels = config["tickLabelsConfiguration"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val f = slot.frame; val cx = f.x + f.width / 2; val cy = f.y + f.height / 2
        val radius = min(f.width, f.height) / 2
        fun n(map: Map<*, *>, key: String, default: Float) = (map[key] as? Number)?.toFloat() ?: default
        if (kind == "tickLabels") {
            val inset = n(labels, "inset", 60f)
            val size = n(labels["labelsFont"] as? Map<*, *> ?: emptyMap<Any, Any>(), "pointSize", 30f)
            val divisor = n(labels, "labelDivider", if (rpm) 1000f else 1f)
            val count = labelCount - 1
            for (i in 0..count) {
                if (i == 0 && labels["hideFirstLabel"] == true) continue
                val v = i * major; val a = Math.toRadians((start + sweep * (v / max)).toDouble())
                val shown = v / divisor
                val value = if (shown % 1.0 == 0.0) shown.toInt().toString() else "%.1f".format(Locale.ROOT, shown)
                val x = cx + (radius - inset) * cos(a).toFloat()
                val y = cy + (radius - inset) * sin(a).toFloat()
                val color = if (rpm && v >= r.redlineRpm) c.redline else c.ticks
                val font = fonts?.get((labels["labelsFont"] as? Map<*, *>)?.get("name") as? String ?: "")
                if (font != null) font.draw(canvas, value, x - font.width(value) / 2, y - font.height / 2, color)
                else text(canvas, value, x, y + size * 0.35f, size, color, light)
            }
        } else {
            val configs = config["tickTypeConfigurations"] as? Map<*, *> ?: return
            val primary = ((configs["primary"] as? Map<*, *>)?.get("ticksCount") as? Number)?.toInt() ?: 0
            val secondary = ((configs["secondary"] as? Map<*, *>)?.get("ticksCount") as? Number)?.toInt() ?: 0
            if (primary <= 0) return
            configs.forEach { (kind, raw) ->
                val tick = raw as? Map<*, *> ?: return@forEach
                val subdivisions = primary * when (kind) {
                    "primary" -> 1
                    "secondary" -> 1 + secondary
                    else -> (1 + secondary) * (1 + n(tick, "ticksCount", 0f).toInt())
                }
                val step = major / subdivisions
                val count = ((labelCount - 1) * subdivisions).coerceAtMost(2000)
                paint.reset(); paint.isAntiAlias = true; paint.strokeWidth = n(tick, "tickWidth", 2f)
                paint.strokeCap = assetCap(tick["lineCap"])
                val outer = radius - n(tick, "tickInset", 20f); val inner = outer - n(tick, "tickLength", 10f)
                for (i in 0..count) {
                    if (i == 0 && tick["hideFirstTick"] == true) continue
                    if (kind == "secondary" && i % (1 + secondary) == 0) continue
                    if (kind == "tertiary" && i % (1 + n(tick, "ticksCount", 0f).toInt()) == 0) continue
                    val v = i * step; val a = Math.toRadians((start + sweep * (v / max)).toDouble())
                    paint.color = if (rpm && v >= r.redlineRpm) c.redline else c.ticks
                    canvas.drawLine(cx + inner * cos(a).toFloat(), cy + inner * sin(a).toFloat(),
                        cx + outer * cos(a).toFloat(), cy + outer * sin(a).toFloat(), paint)
                }
            }
        }
    }

    // ---- radial styles (Cobranded / DigiRing / DigiBand / DigiBandWithTicks) ----

    private fun radialSpeed(canvas: Canvas, f: OemFrame, style: ClusterDialStyle, c: ClusterColors, r: ClusterReadings) {
        val s = min(f.width, f.height) / 600f
        val cx = f.x + f.width / 2; val cy = f.y + f.height / 2
        if (style == ClusterDialStyle.COBRANDED) arc(canvas, cx, cy, 297f * s, 6f * s, c.decorative, 0f, 1f, Paint.Cap.BUTT)
        dial(canvas, cx, cy, s, style, c, ClusterDialGeometry.fraction(r.speed, r.speedMax), r.speedMax, null, divisor = 1.0)
        // Slot positions follow the asset Speedometer component (600×600 coordinates).
        text(canvas, r.speed?.let { it.roundToInt().toString() } ?: "—", cx, f.y + 345f * s, 150f * s, c.largeText, digits)
        text(canvas, r.speedUnit, cx, f.y + 402f * s, 30f * s, c.detailText, label)
        fuel(canvas, f.x + 205f * s, f.y + 504f * s, 190f * s, s, c, r)
    }

    private fun radialRpm(canvas: Canvas, f: OemFrame, style: ClusterDialStyle, c: ClusterColors, r: ClusterReadings,
                          artworkLabelsUnit: Boolean) {
        val s = min(f.width, f.height) / 600f
        val cx = f.x + f.width / 2; val cy = f.y + f.height / 2
        val redline = ClusterDialGeometry.fraction(r.redlineRpm, r.rpmMax)
        dial(canvas, cx, cy, s, style, c, ClusterDialGeometry.fraction(r.rpm, r.rpmMax), r.rpmMax, redline, divisor = 1000.0)
        val reverse = r.prndl == "R"
        text(canvas, r.gearLabel, cx, f.y + 345f * s, 150f * s, if (reverse) c.lowLevel else c.largeText, digits)
        text(canvas, r.driveMode ?: "", cx, f.y + 402f * s, 28f * s, c.detailText, label, letterSpacing = 0.12f)
        if (!artworkLabelsUnit) text(canvas, "×1000 r/min", cx, f.y + 190f * s, 22f * s, c.detailText, label)
        coolant(canvas, f.x + 205f * s, f.y + 505f * s, 190f * s, s, c, r)
    }

    private fun dial(canvas: Canvas, cx: Float, cy: Float, s: Float, style: ClusterDialStyle, c: ClusterColors,
                     value: Float, max: Double, redline: Float?, divisor: Double) {
        val major = ClusterDialGeometry.majorStep(max)
        when (style) {
            ClusterDialStyle.COBRANDED -> {
                val ring = 262f * s
                arc(canvas, cx, cy, ring, 14f * s, c.track, 0f, 1f, Paint.Cap.ROUND)
                redline?.let { arc(canvas, cx, cy, ring, 14f * s, ClusterColors.withAlpha(c.redline, 0x99), it, 1f, Paint.Cap.BUTT) }
                glowArc(canvas, cx, cy, ring, 14f * s, c.primary, value)
                ticks(canvas, cx, cy, ring - 18f * s, 16f * s, 3f * s, max, major / 2, c.ticks, redline, c.redline)
                labels(canvas, cx, cy, ring - 64f * s, 28f * s, max, major, divisor, c.ticks)
                tip(canvas, cx, cy, ring, value, 10f * s)
            }
            ClusterDialStyle.DIGI_RING -> {
                val ring = 256f * s
                arc(canvas, cx, cy, ring, 30f * s, c.track, 0f, 1f, Paint.Cap.ROUND)
                glowArc(canvas, cx, cy, ring, 30f * s, c.primary, value)
                redline?.let { arc(canvas, cx, cy, ring + 24f * s, 5f * s, c.redline, it, 1f, Paint.Cap.ROUND) }
            }
            ClusterDialStyle.DIGI_BAND, ClusterDialStyle.DIGI_BAND_TICKS -> {
                val ring = 246f * s; val band = 52f * s
                arc(canvas, cx, cy, ring, band, c.track, 0f, 1f, Paint.Cap.BUTT)
                redline?.let { arc(canvas, cx, cy, ring, band, ClusterColors.withAlpha(c.redline, 0x66), it, 1f, Paint.Cap.BUTT) }
                arc(canvas, cx, cy, ring, band, c.primary, 0f, value, Paint.Cap.BUTT)
                if (value >= (redline ?: 2f)) arc(canvas, cx, cy, ring, band, c.redline, redline!!, value, Paint.Cap.BUTT)
                if (style == ClusterDialStyle.DIGI_BAND_TICKS) {
                    ticks(canvas, cx, cy, ring + band / 2, band, 2f * s, max, major / 2, ClusterColors.withAlpha(0xFF000000.toInt(), 0x80), null, 0)
                    labels(canvas, cx, cy, ring - band / 2 - 30f * s, 26f * s, max, major, divisor, c.ticks)
                }
            }
            else -> Unit
        }
    }

    // ---- Modular: digital panels with horizontal bars ----

    private fun modularSpeed(canvas: Canvas, f: OemFrame, c: ClusterColors, r: ClusterReadings) {
        bar(canvas, f.x + 60, f.y + 70, f.width - 120, 14f, c.track, c.primary, ClusterDialGeometry.fraction(r.speed, r.speedMax), null, 0)
        text(canvas, r.speed?.let { it.roundToInt().toString() } ?: "—", f.x + f.width / 2, f.y + f.height * 0.56f, 190f, c.largeText, digits)
        text(canvas, r.speedUnit, f.x + f.width / 2, f.y + f.height * 0.56f + 52, 30f, c.detailText, label)
        fuel(canvas, f.x + 120, f.y + f.height - 100, f.width - 240, 1.4f, c, r)
    }

    private fun modularRpm(canvas: Canvas, f: OemFrame, c: ClusterColors, r: ClusterReadings) {
        val redline = ClusterDialGeometry.fraction(r.redlineRpm, r.rpmMax)
        bar(canvas, f.x + 60, f.y + 70, f.width - 120, 14f, c.track, c.secondary, ClusterDialGeometry.fraction(r.rpm, r.rpmMax), redline, c.redline)
        text(canvas, r.rpm?.let { "%,d".format(Locale.ROOT, (it / 50).roundToInt() * 50) + " rpm" } ?: "— rpm",
            f.x + f.width / 2, f.y + 128, 26f, c.detailText, label)
        text(canvas, r.gearLabel, f.x + f.width / 2, f.y + f.height * 0.56f, 190f,
            if (r.prndl == "R") c.lowLevel else c.largeText, digits)
        text(canvas, r.driveMode ?: "", f.x + f.width / 2, f.y + f.height * 0.56f + 52, 28f, c.detailText, label, letterSpacing = 0.12f)
        coolant(canvas, f.x + 120, f.y + f.height - 100, f.width - 240, 1.4f, c, r)
    }

    // ---- MapsMedia: full-screen map, compact readouts along the bottom ----

    private fun mapsMedia(canvas: Canvas, z: Zones, c: ClusterColors, r: ClusterReadings) {
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(0f, 520f, 0f, 720f, 0x00000000, 0xD0000000.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 520f, 1920f, 720f, paint)
        paint.shader = null
        val center = z.centerSpeed ?: z.speed
        val cx = center?.let { it.x + it.width / 2 } ?: 960f
        text(canvas, r.speed?.let { it.roundToInt().toString() } ?: "—", cx, 664f, 110f, c.largeText, digits)
        text(canvas, r.speedUnit, cx, 700f, 24f, c.detailText, label)
        z.speed?.let { f -> fuel(canvas, f.x + 60, 640f, 260f, 1.2f, c, r) }
        z.rpm?.let { f ->
            text(canvas, r.gearLabel, f.x + f.width - 120, 668f, 72f, if (r.prndl == "R") c.lowLevel else c.largeText, digits)
            bar(canvas, f.x + 60, 650f, 300f, 10f, c.track, c.primary, ClusterDialGeometry.fraction(r.rpm, r.rpmMax),
                ClusterDialGeometry.fraction(r.redlineRpm, r.rpmMax), c.redline)
        }
    }

    // ---- secondary readouts ----

    private fun fuel(canvas: Canvas, x: Float, y: Float, w: Float, s: Float, c: ClusterColors, r: ClusterReadings) {
        val level = r.fuelPercent
        val low = level != null && level <= r.fuelLowPercent
        val fill = if (low) c.lowLevel else c.largeText
        bar(canvas, x + 22 * s, y + 8 * s, w - 44 * s, 6 * s, ClusterColors.withAlpha(c.detailText, 0x40), fill,
            ((level ?: 0.0) / 100).toFloat(), null, 0)
        text(canvas, "E", x + 6 * s, y + 15 * s, 16 * s, c.detailText, label)
        text(canvas, "F", x + w - 6 * s, y + 15 * s, 16 * s, c.detailText, label)
        val range = r.rangeKm?.let { "%d km".format(Locale.ROOT, it.roundToInt()) } ?: "— km"
        text(canvas, range, x + w / 2, y + 40 * s, 20 * s, if (low) c.lowLevel else c.detailText, label)
    }

    private fun coolant(canvas: Canvas, x: Float, y: Float, w: Float, s: Float, c: ClusterColors, r: ClusterReadings) {
        val t = r.coolant
        val fraction = if (t == null) 0f else ((t - r.coolantMin) / (r.coolantMax - r.coolantMin)).coerceIn(0.0, 1.0).toFloat()
        val fill = when {
            t == null -> c.detailText
            t >= 115 -> c.critical
            t < 60 -> c.cold
            else -> c.largeText
        }
        bar(canvas, x + 22 * s, y + 8 * s, w - 44 * s, 6 * s, ClusterColors.withAlpha(c.detailText, 0x40), fill, fraction, null, 0)
        text(canvas, "C", x + 6 * s, y + 15 * s, 16 * s, c.detailText, label)
        text(canvas, "H", x + w - 6 * s, y + 15 * s, 16 * s, c.detailText, label)
        text(canvas, t?.let { "${it.roundToInt()}°C" } ?: "—°C", x + w / 2, y + 40 * s, 20 * s, c.detailText, label)
    }

    // ---- primitives ----

    private fun backdrop(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.reset(); paint.isAntiAlias = true
        paint.shader = RadialGradient(cx, cy, radius, intArrayOf(0xFF111516.toInt(), 0xFF07090A.toInt(), 0xFF000000.toInt()),
            floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
    }

    private fun panel(canvas: Canvas, f: OemFrame) {
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(0f, f.y, 0f, f.y + f.height, 0xFF0E1214.toInt(), 0xFF050607.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(f.x, f.y, f.x + f.width, f.y + f.height, 36f, 36f, paint)
        paint.shader = null
    }

    private fun arc(canvas: Canvas, cx: Float, cy: Float, radius: Float, width: Float, color: Int,
                    from: Float, to: Float, cap: Paint.Cap) {
        if (to <= from) return
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.strokeCap = cap; paint.color = color
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arcRect, ClusterDialGeometry.START_DEGREES + ClusterDialGeometry.SWEEP_DEGREES * from,
            ClusterDialGeometry.SWEEP_DEGREES * (to - from), false, paint)
    }

    /** Soft halo under the progress arc; a widened translucent stroke avoids software-layer blur. */
    private fun glowArc(canvas: Canvas, cx: Float, cy: Float, radius: Float, width: Float, color: Int, value: Float) {
        if (value <= 0.002f) return
        arc(canvas, cx, cy, radius, width * 2.4f, ClusterColors.withAlpha(color, 0x22), 0f, value, Paint.Cap.ROUND)
        arc(canvas, cx, cy, radius, width * 1.6f, ClusterColors.withAlpha(color, 0x33), 0f, value, Paint.Cap.ROUND)
        arc(canvas, cx, cy, radius, width, color, 0f, value, Paint.Cap.ROUND)
    }

    private fun tip(canvas: Canvas, cx: Float, cy: Float, radius: Float, value: Float, size: Float) {
        val a = Math.toRadians((ClusterDialGeometry.START_DEGREES + ClusterDialGeometry.SWEEP_DEGREES * value).toDouble())
        paint.reset(); paint.isAntiAlias = true; paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx + radius * cos(a).toFloat(), cy + radius * sin(a).toFloat(), size, paint)
    }

    private fun ticks(canvas: Canvas, cx: Float, cy: Float, outer: Float, length: Float, width: Float, max: Double,
                      step: Double, color: Int, redline: Float?, redColor: Int) {
        paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE; paint.strokeWidth = width
        val count = (max / step).roundToInt()
        for (i in 0..count) {
            val f = (i * step / max).toFloat().coerceAtMost(1f)
            val major = i % 2 == 0
            paint.color = if (redline != null && f >= redline) redColor else color
            val a = Math.toRadians((ClusterDialGeometry.START_DEGREES + ClusterDialGeometry.SWEEP_DEGREES * f).toDouble())
            val inner = outer - if (major) length else length * 0.55f
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            canvas.drawLine(cx + inner * ca, cy + inner * sa, cx + outer * ca, cy + outer * sa, paint)
        }
    }

    private fun labels(canvas: Canvas, cx: Float, cy: Float, radius: Float, size: Float, max: Double, step: Double,
                       divisor: Double, color: Int) {
        val count = (max / step).roundToInt()
        for (i in 0..count) {
            val v = i * step
            val f = (v / max).toFloat().coerceAtMost(1f)
            val a = Math.toRadians((ClusterDialGeometry.START_DEGREES + ClusterDialGeometry.SWEEP_DEGREES * f).toDouble())
            val shown = v / divisor
            val textValue = if (shown % 1.0 == 0.0) shown.toInt().toString() else "%.1f".format(Locale.ROOT, shown)
            text(canvas, textValue, cx + radius * cos(a).toFloat(), cy + radius * sin(a).toFloat() + size * 0.35f,
                size, color, light)
        }
    }

    private fun bar(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, track: Int, fill: Int, value: Float,
                    redline: Float?, redColor: Int) {
        paint.reset(); paint.isAntiAlias = true
        paint.color = track
        canvas.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, paint)
        redline?.let {
            paint.color = ClusterColors.withAlpha(redColor, 0x66)
            canvas.drawRoundRect(x + w * it, y, x + w, y + h, h / 2, h / 2, paint)
        }
        if (value > 0f) {
            paint.color = if (redline != null && value >= redline) redColor else fill
            canvas.drawRoundRect(x, y, x + w * value.coerceIn(0f, 1f), y + h, h / 2, h / 2, paint)
        }
    }

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, face: Typeface,
                     letterSpacing: Float = 0f) {
        if (value.isEmpty()) return
        paint.reset(); paint.isAntiAlias = true
        paint.typeface = face; paint.textSize = size; paint.color = color; paint.textAlign = Paint.Align.CENTER
        paint.letterSpacing = letterSpacing
        paint.fontFeatureSettings = "tnum" // tabular digits keep changing numbers from jittering
        canvas.drawText(value, x, y, paint)
    }
}
