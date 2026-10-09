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
        fun resolve(style: ClusterDialStyle, lookup: (String) -> Map<*, *>?): ClusterColors {
            val p = style.colorPrefix
            fun c(vararg ids: String, fallback: Int): Int =
                ids.firstNotNullOfOrNull { id -> lookup(id)?.let(::argb) } ?: fallback
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
        fun argb(definition: Map<*, *>): Int? {
            val single = (definition["single"] as? Map<*, *>)?.get("value") as? Map<*, *>
            val dynamic = (definition["dynamic"] as? Map<*, *>)?.get("value") as? Map<*, *>
            val color = single ?: (dynamic?.get("darkMode") ?: dynamic?.get("lightMode")) as? Map<*, *> ?: return null
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
) {
    /** Text shown in the gear slot: "D3" style for drive with a known gear, otherwise the PRNDL letter. */
    val gearLabel: String get() = when {
        prndl == null -> "—"
        prndl == "D" && gear != null && gear > 0 -> "D$gear"
        else -> prndl
    }

    companion object {
        fun from(values: Map<String, Any?>): ClusterReadings {
            fun num(key: String) = (values[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }
            val rpmMax = num("engineRPMMax")?.takeIf { it > 0 } ?: 8000.0
            return ClusterReadings(
                speed = num("vehicleSpeed")?.takeIf { it >= 0 },
                speedMax = num("vehicleSpeedMax")?.takeIf { it > 0 } ?: 260.0,
                speedUnit = OemScenePlan.speedUnit(values["vehicleSpeedUnit"]).ifEmpty { "km/h" },
                rpm = num("engineRPM")?.takeIf { it >= 0 },
                rpmMax = rpmMax,
                redlineRpm = num("engineMarkerRedlineRPM")?.takeIf { it in 0.0..rpmMax } ?: rpmMax * 0.8,
                prndl = (values["prndl"] as? String)?.takeIf { it in setOf("P", "R", "N", "D", "S", "M", "L") },
                gear = (values["transmission"] as? Number)?.toInt(),
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
            )
        }
    }
}

/** Gauge geometry shared by every radial style; angles follow the asset's 135° → 405° (clockwise) sweep. */
internal object ClusterDialGeometry {
    const val START_DEGREES = 135f
    const val SWEEP_DEGREES = 270f

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
        )
    }
}
