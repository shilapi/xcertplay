package com.shilapi.xcertplay

/** VHAL readings retain availability and the vehicle timestamp; neither stale nor missing data is zero. */
internal data class OemVehicleReading(val value: Number?, val timestampNanos: Long, val available: Boolean)

internal object CarPlayOemVehicleValues {
    const val MAX_AGE_NANOS = 2_000_000_000L
    fun snapshot(speedMetersPerSecond: OemVehicleReading?, rpm: OemVehicleReading?, nowNanos: Long): Map<String, Any?> {
        fun usable(reading: OemVehicleReading?): Double? {
            if (reading == null || !reading.available || reading.timestampNanos <= 0 || reading.timestampNanos > nowNanos ||
                nowNanos - reading.timestampNanos > MAX_AGE_NANOS) return null
            return reading.value?.toDouble()?.takeIf { it.isFinite() }
        }
        // VHAL speed is signed m/s (reverse is negative); OEM speedometer shows magnitude in km/h.
        val speed = usable(speedMetersPerSecond)?.let { kotlin.math.abs(it) * 3.6 }?.takeIf { it.isFinite() }
        val engine = usable(rpm)?.takeIf { it >= 0 }
        return mapOf("vehicleSpeed" to speed, "vehicleSpeedUnit" to if (speed != null) 1032L else null,
            "engineRPM" to engine)
    }
}
