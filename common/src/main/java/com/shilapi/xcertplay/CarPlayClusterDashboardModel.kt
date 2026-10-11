package com.shilapi.xcertplay

/**
 * Pure model for the minimal Ultra cluster: which gauge style the asset layout asks for, which palette colors
 * it resolves to, and which readings are present. No Android types so it stays JVM-testable.
 */
internal enum class ClusterDialStyle(val colorPrefix: String) {
    COBRANDED("radial"), DIGI_RING("radial"), DIGI_BAND("radial"), DIGI_BAND_TICKS("radial"),
    MODULAR("modular"), MAPS_MEDIA("mapsMedia");

    companion object {
        /** Asset `layoutStyleID`; unknown or NoStyle falls back to the most conventional tick dial. */
        fun of(layoutStyleId: Any?): ClusterDialStyle = when (layoutStyleId) {
            "Cobranded" -> COBRANDED
            "DigiRing" -> DIGI_RING
            "DigiBand" -> DIGI_BAND
            "DigiBandWithTicks" -> DIGI_BAND_TICKS
            "Modular" -> MODULAR
            "MapsMedia" -> MAPS_MEDIA
            else -> DIGI_BAND_TICKS
        }
    }
}

/** ARGB colors used by the renderer, resolved from the active palette with neutral fallbacks. */
internal data class ClusterColors(
    val primary: Int, val track: Int, val secondary: Int, val redline: Int, val largeText: Int,
    val detailText: Int, val lowLevel: Int, val critical: Int, val cold: Int, val decorative: Int,
    val ticks: Int, val activeTicks: Int,
) {
    companion object {
        /**
         * [lookup] returns the asset color definition (palette entry with layout-style override applied).
         * Only the dark-mode value is used: the cluster display declares `requiresDarkAppearance`.
         */
        fun resolve(style: ClusterDialStyle, appearance: Int = 1, lookup: (String) -> Map<*, *>?): ClusterColors {
            val p = style.colorPrefix
            fun c(vararg ids: String, fallback: Int): Int =
                ids.firstNotNullOfOrNull { id -> lookup(id)?.let { argb(it, appearance) } } ?: fallback
            val primary = if (style == ClusterDialStyle.COBRANDED)
                c("partner.cobranded.innerRingActive", "radial.primaryActive", fallback = WHITE)
            else c("$p.primaryActive", "$p.leftTopActive", fallback = WHITE)
            val track = if (style == ClusterDialStyle.COBRANDED)
                c("partner.cobranded.innerRingInactive", "radial.primaryInactive", fallback = withAlpha(primary, 0x4c))
            else c("$p.primaryInactive", "$p.leftTopInactive", fallback = withAlpha(primary, 0x4c))
            return ClusterColors(
                primary = primary, track = track,
                secondary = c("$p.secondaryActive", "$p.rightTopActive", fallback = primary),
                redline = c("$p.redlineProgress", "$p.redline", "radial.primaryRedlineTicks", fallback = 0xFFFF544B.toInt()),
                largeText = c("$p.largeText", "radial.largeText", fallback = 0xE5FFFFFF.toInt()),
                detailText = c("$p.detailText", "$p.leftText", "radial.detailText", fallback = 0xB2FFFFFF.toInt()),
                lowLevel = c("$p.lowLevelActive", fallback = 0xFFFF9F0A.toInt()),
                critical = c("$p.criticalActive", fallback = 0xFFFF544B.toInt()),
                cold = c("$p.coldActive", fallback = WHITE),
                decorative = c("partner.cobranded.decorativeRing", fallback = 0xFF6D7C7A.toInt()),
                ticks = c("$p.primaryInactiveTicks", "radial.inactiveTickLabel", fallback = 0xB2FFFFFF.toInt()),
                activeTicks = c("$p.primaryActiveTicks", fallback = 0xFF000000.toInt()),
            )
        }

        /** `single` or `dynamic.darkMode`, then the first gradient stop (gradients are approximated as solid). */
        fun argb(definition: Map<*, *>, appearance: Int = 1): Int? {
            val single = (definition["single"] as? Map<*, *>)?.get("value") as? Map<*, *>
            val dynamic = (definition["dynamic"] as? Map<*, *>)?.get("value") as? Map<*, *>
            val color = single ?: (dynamic?.get(if (appearance == 1) "darkMode" else "lightMode")) as? Map<*, *> ?: return null
            val stops = color["stops"] as? Map<*, *> ?: return null
            val first = stops.entries.minByOrNull { (it.key as? String)?.toDoubleOrNull() ?: Double.MAX_VALUE }
            return parseRgba(first?.value as? String ?: return null)
        }

        /** Asset colors are `#rrggbbaa`. */
        fun parseRgba(value: String): Int? {
            if (!value.matches(Regex("#[0-9a-fA-F]{8}"))) return null
            val rgb = value.substring(1, 7).toLong(16)
            val alpha = value.substring(7, 9).toLong(16)
            return ((alpha shl 24) or rgb).toInt()
        }

        fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
        private const val WHITE = 0xFFFFFFFF.toInt()
    }
}

/** Readings parsed from `CarPlayOemScene.vehicleValues`; a missing value stays null and is drawn as "—". */
internal data class ClusterReadings(
    val speed: Double?, val speedMax: Double, val speedUnit: String,
    val rpm: Double?, val rpmMax: Double, val redlineRpm: Double,
    val prndl: String?, val gear: Int?,
    val fuelPercent: Double?, val fuelLowPercent: Double, val rangeKm: Double?, val odometerKm: Double?,
    val coolant: Double?, val coolantMin: Double, val coolantMax: Double,
    val outsideTemperature: Double?, val driveMode: String?, val simulated: Boolean,
    val values: Map<String, Any?> = emptyMap(),
) {
    /** Text shown in the gear slot: "D3" style for drive with a known gear, otherwise the PRNDL letter. */
    val gearLabel: String get() = when {
        prndl == null -> "—"
        prndl == "D" && gear != null && gear > 0 -> "D$gear"
        else -> prndl
    }

    companion object {
        fun from(values: Map<String, Any?>): ClusterReadings {
            fun num(key: String) = telemetryNumber(values[key], values[when (key) {
                "vehicleSpeed" -> "vehicleSpeedUnit"
                "remainingDistance", "odometer" -> "lengthUnit"
                else -> ""
            }])
            val transmission = values["transmission"] as? Map<*, *>
            val mode = (transmission?.get("mode") as? Number)?.toInt()?.toChar()?.toString()
            val rpmMax = num("engineRPMMax")?.takeIf { it > 0 } ?: 8000.0
            return ClusterReadings(
                speed = num("vehicleSpeed")?.takeIf { it >= 0 },
                speedMax = num("vehicleSpeedMax")?.takeIf { it > 0 } ?: 260.0,
                speedUnit = OemScenePlan.speedUnit(values["vehicleSpeedUnit"]),
                rpm = num("engineRPM")?.takeIf { it >= 0 },
                rpmMax = rpmMax,
                redlineRpm = num("engineMarkerRedlineRPM")?.takeIf { it in 0.0..rpmMax } ?: rpmMax * 0.8,
                prndl = (mode ?: values["prndl"] as? String)?.takeIf { it in setOf("P", "R", "N", "D", "S", "M", "L") },
                gear = telemetryNumber(transmission?.get("position") ?: values["transmission"])?.takeIf { it % 1.0 == 0.0 && it in 0.0..Int.MAX_VALUE.toDouble() }?.toInt(),
                fuelPercent = num("fuelLevel")?.coerceIn(0.0, 100.0),
                fuelLowPercent = num("fuelLevelMarkerLow") ?: 15.0,
                rangeKm = num("remainingDistance")?.takeIf { it >= 0 },
                odometerKm = num("odometer")?.takeIf { it >= 0 },
                coolant = num("engineTemperature"),
                coolantMin = num("engineTemperatureMin") ?: 40.0,
                coolantMax = num("engineTemperatureMax") ?: 130.0,
                outsideTemperature = num("outsideTemperature"),
                driveMode = values["driveMode"] as? String,
                simulated = values["dataSource"] == "mock",
                values = values,
            )
        }
    }
}

/** Gauge geometry shared by every radial style; angles follow the asset's 135° → 405° (clockwise) sweep. */
internal object ClusterDialGeometry {
    const val START_DEGREES = 135f
    const val SWEEP_DEGREES = 270f

    /** Ticks.getMaxValueWithLabelsCount 的候选量程；与进度条使用同一上限。 */
    fun scale(max: Double, rpm: Boolean): Pair<Double, Int> {
        val intervals = if (rpm) doubleArrayOf(1000.0, 2000.0, 4000.0, 5000.0, 10000.0)
            else doubleArrayOf(10.0, 20.0, 30.0, 40.0, 50.0, 100.0, 200.0)
        val limit = if (rpm) 11 else 12
        var count = limit
        var step = intervals.last()
        intervals.forEach { candidate ->
            val n = kotlin.math.ceil(max / candidate).toInt().coerceIn(4, limit)
            if (candidate * n >= max && candidate * n < step * count) { count = n; step = candidate }
        }
        return if (step * count >= max) step * count to count + 1 else max to 0
    }

    fun fraction(value: Double?, max: Double): Float =
        if (value == null || max <= 0) 0f else (value / max).coerceIn(0.0, 1.0).toFloat()

    /** Label step that gives 6–14 major ticks for the gauge maximum (e.g. 260 km/h → 20, 8000 rpm → 1000). */
    fun majorStep(max: Double): Double {
        val candidates = doubleArrayOf(10.0, 20.0, 25.0, 40.0, 50.0, 100.0, 500.0, 1000.0, 2000.0)
        return candidates.firstOrNull { max / it <= 14 } ?: (max / 10)
    }
}

/** Exponential smoothing so needles/bars glide between 10 Hz samples instead of stepping. */
internal class ClusterSmoothedValue(private val ratePerSecond: Double = 7.0) {
    var value: Double? = null
        private set

    /** Returns true while still moving toward [target]. A null target snaps to unavailable. */
    fun step(target: Double?, dtSeconds: Double): Boolean {
        val current = value
        if (target == null || current == null) { value = target; return false }
        val next = current + (target - current) * (1 - kotlin.math.exp(-ratePerSecond * dtSeconds.coerceIn(0.0, 0.25)))
        val settled = kotlin.math.abs(target - next) < 0.01
        value = if (settled) target else next
        return !settled
    }
}

/** Where the dashboard draws, taken from the active asset layout rather than fixed coordinates. */
internal object ClusterZones {
    fun from(layout: Map<String, Any?>, plan: OemScenePlan): CarPlayClusterDashboardRenderer.Zones {
        val speeds = plan.gauges.filter { it.source == "vehicleSpeed" }.map { it.frame }
        val outside = (layout["zones"] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.firstOrNull { zone ->
            (zone["components"] as? List<*>)?.any { (it as? Map<*, *>)?.get("name") == "OutsideTemperature" } == true
        }?.let { OemScenePlan.frame(it["frame"]) }
        return CarPlayClusterDashboardRenderer.Zones(
            speed = speeds.firstOrNull(),
            rpm = plan.gauges.firstOrNull { it.source == "engineRPM" }?.frame,
            centerSpeed = speeds.getOrNull(1),
            outsideTemperature = outside,
            localSlots = plan.localSlots,
            assetDriven = plan.localSlots.isNotEmpty(),
        )
    }
}

/** Supported asset text types. Unknown types and unknown conditional rules stay absent. */
internal fun telemetryNumber(raw: Any?, unit: Any? = null): Double? {
    val key = if (unit is Number) unit.toLong().toString() else unit?.toString()
    val value = if (raw is Map<*, *>) raw[key] ?: raw[unit] else raw
    return (value as? Number)?.toDouble()?.takeIf { it.isFinite() }
}
internal data class ClusterSlotText(val frame: OemFrame, val value: String, val pointSize: Float, val large: Boolean,
    val configuration: Map<*, *> = emptyMap<Any, Any>(), val unit: String = "")
internal object ClusterSlotContent {
    fun visible(slot: OemLocalSlot, values: Map<String, Any?>): Boolean = slot.rules.all { raw ->
        val rule = raw as? Map<*, *> ?: return@all false
        rule.all { (kind, body) ->
            val config = (body as? Map<*, *>)?.get("rule") as? Map<*, *> ?: return@all false
            when (kind) {
                // 本地静态仪表处于 DRIVE；资产初始 OFF 的开场动画尚未复现。
                "layoutMode" -> ((config["level"] as? Number)?.toInt() ?: 400).let { level ->
                    level < 400 || level == 400 && ((config["opacity"] as? Number)?.toDouble() ?: 1.0) > 0
                }
                "tickmarksSpeedUnit" -> when ((config["rule"] as? Number)?.toInt()) {
                    1 -> values[config["showSecondarySpeedIdentifier"]] != true
                    2 -> values[config["showSecondarySpeedIdentifier"]] == true &&
                        OemScenePlan.speedUnit(values[config["vehicleSpeedUnitIdentifier"]]) == "mph"
                    3 -> values[config["showSecondarySpeedIdentifier"]] == true &&
                        OemScenePlan.speedUnit(values[config["vehicleSpeedUnitIdentifier"]]) == "km/h"
                    else -> false
                }
                "secondarySpeed" -> (values[config["dataIdentifier"]] == true) == (config["enabled"] == true)
                "invalidData" -> {
                    val key = config["dataIdentifier"]
                    val unit = when (key) {
                        "vehicleSpeed" -> values["vehicleSpeedUnit"]
                        "remainingDistance" -> values["lengthUnit"]
                        else -> null
                    }
                    telemetryNumber(values[key], unit) != null
                }
                "remainingRange" -> when ((config["rule"] as? Number)?.toInt()) {
                    1 -> values[config["dataIdentifier"]] != true
                    2 -> values[config["dataIdentifier"]] == true
                    else -> false
                }
                "temperatureLabel" -> {
                    val t = telemetryNumber(values[config["temperatureDataIdentifier"]])
                    val min = telemetryNumber(values[config["temperatureMinDataIdentifier"]])
                    val max = telemetryNumber(values[config["temperatureMaxDataIdentifier"]])
                    if (t == null || min == null || max == null) true else when ((config["rule"] as? Number)?.toInt()) {
                        1 -> t in min..max
                        2 -> t !in min..max
                        else -> false
                    }
                }
                "transmissionMode" -> when ((config["rule"] as? Number)?.toInt()) {
                    1 -> ClusterReadings.from(values).gear?.let { it > 0 } != false
                    2 -> ClusterReadings.from(values).gear?.let { it <= 0 } == true
                    else -> false
                }
                else -> false
            }
        }
    }

    fun text(slot: OemLocalSlot, r: ClusterReadings): ClusterSlotText? {
        val values = r.values
        if (!visible(slot, values)) return null
        val text = slot.style["textElement"] as? Map<*, *> ?: return null
        val types = (text["configuration"] as? Map<*, *>)?.get("dataType") as? Map<*, *> ?: return null
        val kind = types.keys.singleOrNull() as? String ?: return null
        val body = types[kind] as? Map<*, *> ?: return null
        val config = body["configuration"] as? Map<*, *> ?: body
        val range = config["remainingRangeTextConfiguration"] as? Map<*, *>
        val ids = config["dataIdentifiers"] as? Map<*, *> ?: config
        val label = (range ?: config)["labelConfiguration"] as? Map<*, *> ?: config
        fun number(key: String, rawUnit: Any?): Double? = telemetryNumber(values[key], rawUnit)
        fun unit(raw: Any?): String = (label["unitLabels"] as? Map<*, *>)?.get(
            when (raw) {
                is Number -> raw.toLong().toString()
                "km" -> "151"
                "mi" -> "164"
                "C" -> "1082"
                "F" -> "1081"
                else -> raw?.toString()
            }) as? String ?: ""
        fun reading(key: String, rawUnit: Any?): String {
            val raw = number(key, rawUnit)
            val n = if (key == "engineTemperature" || key == "outsideTemperature") when ((rawUnit as? Number)?.toInt()) {
                1081 -> raw?.let { it * 1.8 + 32 }
                1083 -> raw?.let { it + 273.15 }
                else -> raw
            } else raw
            val divider = (label["divider"] as? Number)?.toDouble()?.takeIf { it > 0 && it.isFinite() } ?: 1.0
            val precision = (label["precision"] as? Number)?.toInt()?.coerceIn(0, 6) ?: 0
            val value = n?.let { java.lang.String.format(java.util.Locale.ROOT, "%.$precision" + "f", it / divider) } ?: "—"
            return value + unit(rawUnit).let { if (it.startsWith("°") || it.isEmpty()) it else " $it" }
        }
        val value = when (kind) {
            "speed" -> r.speed?.let { kotlin.math.round(it).toInt().toString() } ?: "—"
            "speedUnit" -> unit(values[config["unitDataIdentifier"]]).ifEmpty { "—" }
            "secondarySpeedWithUnit" -> {
                if (values[config["showSecondarySpeedDataIdentifier"]] != true) return null
                val speed = r.speed ?: return ClusterSlotText(slot.frame, "—", 36f, false)
                when (OemScenePlan.speedUnit(values[config["unitDataIdentifier"]])) {
                    "km/h" -> "${kotlin.math.round((values[config["dataIdentifier"]] as? Map<*, *>)?.let { telemetryNumber(it, 1033) } ?: speed / 1.609344).toInt()} mph"
                    "mph" -> "${kotlin.math.round((values[config["dataIdentifier"]] as? Map<*, *>)?.let { telemetryNumber(it, 1032) } ?: speed * 1.609344).toInt()} km/h"
                    else -> "—"
                }
            }
            "remainingChargeFuelRange" -> {
                if (values["remainingRangeHidden"] == true) return null
                reading(range?.get("remainingRangeDataIdentifier") as? String ?: return null,
                    values[range["unitDataIdentifier"]])
            }
            "temperature", "outsideTemperature" -> reading((ids["dataIdentifier"] ?: ids["temperatureDataIdentifier"]) as? String ?: return null,
                values[ids["temperatureUnitDataIdentifier"] ?: ids["unitDataIdentifier"]])
            "gearPosition" -> if (config["gaugeVersion"] == "numericalOnly") {
                r.gear?.let { if (it > 0) it.toString() else "" } ?: "—"
            } else r.gearLabel
            else -> return null
        }
        val large = kind == "speed" || kind == "gearPosition"
        val size = ((label["font"] as? Map<*, *>)?.get("pointSize") as? Number)?.toFloat()
            ?.takeIf { it.isFinite() && it > 0 } ?: if (large) 150f else 30f
        val suffix = when (kind) {
            "temperature", "outsideTemperature" -> unit(values[ids["temperatureUnitDataIdentifier"] ?: ids["unitDataIdentifier"]])
            "remainingChargeFuelRange" -> unit(values[range?.get("unitDataIdentifier")])
            "secondarySpeedWithUnit" -> value.substringAfterLast(' ', "")
            else -> ""
        }
        return ClusterSlotText(slot.frame, value, size, large, label, suffix)
    }
}

internal data class ClusterSlotProgress(val frame: OemFrame, val fraction: Float, val radial: Map<*, *>?,
                                        val linear: Map<*, *>?, val secondary: Boolean, val startFraction: Float = 0f, val redline: Boolean = false,
                                        val background: Boolean = false, val colorId: String? = null, val platter: Boolean = false,
                                        val fullCircle: Boolean = false, val gradient: Boolean = false)
internal object ClusterSlotGauge {
    /** Android drawArc (start, sweep) in y-down degrees. DigiBarRadialLayout.getRotationAngle places the
     * indicator at `min + delta·p·(clockwise ? 1 : -1)` with `y = +sin`, i.e. the same y-down convention;
     * its `Path.arcTo(-min, -max)` is the y-up path form of that arc. Negating here mirrors the gauge vertically. */
    fun angles(minRadians: Double, maxRadians: Double, clockwise: Boolean): Pair<Float, Float> {
        val start = Math.toDegrees(minRadians).toFloat()
        val delta = ((Math.toDegrees(maxRadians - minRadians).toFloat() % 360f) + 360f) % 360f
        return start to if (clockwise) delta else -(360f - delta)
    }

    fun progress(slot: OemLocalSlot, r: ClusterReadings): ClusterSlotProgress? {
        if (!ClusterSlotContent.visible(slot, r.values)) return null
        val bar = slot.style["progressBar"] as? Map<*, *> ?: return null
        val types = bar["type"] as? Map<*, *> ?: return null
        val redline = types.containsKey("digiBarRedline")
        val background = types.containsKey("digiBarBackground")
        val gradient = types.containsKey("digiBandGradientProgress")
        val body = (types["digiBarProgress"] ?: types["digiBarRedline"] ?: types["digiBarBackground"]
            ?: types["digiBandGradientProgress"]) as? Map<*, *> ?: return null
        val source = body["dataSource"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val kind = source.keys.singleOrNull() as? String ?: if (background) "static" else return null
        val fraction = when (kind) {
            "static" -> 1f
            "speedometer" -> ClusterDialGeometry.fraction(r.speed, ClusterDialGeometry.scale(r.speedMax, false).first)
            "tachometer" -> ClusterDialGeometry.fraction(r.rpm, ClusterDialGeometry.scale(r.rpmMax, true).first)
            "chargeFuel" -> ClusterDialGeometry.fraction(r.fuelPercent, 100.0)
            "temperature" -> if (r.coolant == null || r.coolantMax <= r.coolantMin) 0f else
                ((r.coolant - r.coolantMin) / (r.coolantMax - r.coolantMin)).coerceIn(0.0, 1.0).toFloat()
            else -> return null
        }
        val config = body["configuration"] as? Map<*, *> ?: return null
        val redlineConfig = config["redlineConfiguration"] as? Map<*, *>
        val style = (if (redline) redlineConfig?.get("style") else config["style"]) as? Map<*, *> ?: return null
        fun configuration(key: String) = if (gradient && key == "radial") style else
            (style[key] as? Map<*, *>)?.get("configuration") as? Map<*, *>
        val colorIds = if (background || gradient) config["colorIDs"] else if (redline) redlineConfig?.get("colorIDs") else
            (config["colorsConfiguration"] as? Map<*, *>)?.get("activeColorIDs")
        return ClusterSlotProgress(slot.frame, fraction, configuration("radial"), configuration("linear"),
            kind == "chargeFuel" || kind == "temperature",
            if (redline) ClusterDialGeometry.fraction(r.redlineRpm, ClusterDialGeometry.scale(r.rpmMax, true).first) else 0f,
            redline, background, (colorIds as? Map<*, *>)?.get("0") as? String
                ?: (config["blurConfiguration"] as? Map<*, *>)?.get("colorID") as? String,
            background && ((style["radial"] as? Map<*, *>)?.get("type") as? Number)?.toInt() == 2,
            background && ((style["radial"] as? Map<*, *>)?.get("type") as? Number)?.toInt() == 1, gradient)
    }
}
