package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Simulated ICE vehicle for the minimal Ultra cluster. Every value is plausible but synthetic and the
 * snapshot carries `dataSource=mock` so the cluster labels it. Keys follow the asset data identifiers.
 *
 * The speed profile is a deterministic function of the loop time; distance, fuel and coolant are integrated.
 */
internal class CarPlayClusterMockVehicle(startOdometerKm: Double = 12_480.0) {
    private var elapsed = 0.0
    private var odometerKm = startOdometerKm
    private var fuelLiters = TANK_LITERS * 0.68
    private var coolant = 46.0

    val elapsedSeconds: Double get() = elapsed

    fun advance(dtSeconds: Double): Map<String, Any?> {
        val dt = dtSeconds.coerceIn(0.0, 1.0)
        elapsed += dt
        val loop = elapsed % LOOP_SECONDS
        val phase = phase(loop)
        val speed = phase.speed
        val gear = if (phase.prndl == "D") gearFor(speed) else 0
        val warm = ((coolant - 46.0) / (90.0 - 46.0)).coerceIn(0.0, 1.0)
        val idle = 760.0 + 360.0 * (1 - warm) + 18 * sin(elapsed * 2.1)
        val rpm = when (phase.prndl) {
            "D" -> max(idle, speed * RPM_PER_KMH[gear - 1] * (1 + 0.12 * phase.load))
            "R" -> idle + speed * 95
            else -> idle
        }.coerceAtMost(REDLINE_RPM)

        val km = speed * dt / 3600.0
        odometerKm += km
        // ~7.2 L/100 km while moving plus ~0.8 L/h idle.
        fuelLiters -= km * 0.072 + dt / 3600.0 * 0.8
        if (fuelLiters < TANK_LITERS * 0.08) fuelLiters = TANK_LITERS * 0.68 // demo refuel
        // Coolant warms toward a thermostat-held 90 °C.
        coolant += (90.0 - coolant) * (1 - kotlin.math.exp(-dt / 45.0))
        val fuelPercent = fuelLiters / TANK_LITERS * 100

        return linkedMapOf(
            "dataSource" to "mock",
            "vehicleSpeed" to speed, "vehicleSpeedUnit" to 1032L, "vehicleSpeedMax" to SPEED_MAX,
            "engineRPM" to rpm, "engineRPMMax" to RPM_MAX, "engineMarkerRedlineRPM" to REDLINE_RPM,
            "prndl" to phase.prndl, "transmission" to gear.toLong(),
            "fuelLevel" to fuelPercent, "fuelLevelMarkerLow" to 15.0,
            "remainingDistance" to fuelLiters / 0.072, "lengthUnit" to "km", "odometer" to odometerKm,
            "engineTemperature" to coolant + 0.6 * sin(elapsed / 7.0) * warm,
            "engineTemperatureMin" to 40.0, "engineTemperatureMax" to 130.0, "temperatureUnit" to "C",
            "outsideTemperature" to 22.5, "driveMode" to phase.driveMode,
        )
    }

    private data class Phase(val speed: Double, val prndl: String, val load: Double, val driveMode: String = "COMFORT")

    private fun phase(t: Double): Phase {
        fun ramp(from: Double, to: Double, start: Double, end: Double): Double {
            val x = ((t - start) / (end - start)).coerceIn(0.0, 1.0)
            return from + (to - from) * (0.5 - 0.5 * cos(PI * x))
        }
        return when {
            t < 6 -> Phase(0.0, "P", 0.0)
            t < 7 -> Phase(0.0, "N", 0.0)
            t < 8 -> Phase(0.0, "D", 0.0)
            t < 30 -> Phase(ramp(0.0, 60.0, 8.0, 30.0), "D", 1.0)
            t < 50 -> Phase(60 + 1.8 * sin((t - 30) / 3), "D", 0.2)
            t < 70 -> Phase(ramp(60.0, 112.0, 50.0, 70.0), "D", 1.0, "SPORT")
            t < 110 -> Phase(110 + 2.5 * sin((t - 70) / 5), "D", 0.3)
            t < 125 -> Phase(ramp(110.0, 40.0, 110.0, 125.0), "D", 0.0)
            t < 140 -> Phase(40 + 1.2 * sin(t - 125), "D", 0.2)
            t < 152 -> Phase(ramp(40.0, 0.0, 140.0, 152.0), "D", 0.0)
            t < 160 -> Phase(0.0, "D", 0.0)
            t < 161 -> Phase(0.0, "N", 0.0)
            t < 170 -> Phase(abs(5.5 * sin(PI * (t - 161) / 9)), "R", 0.4)
            else -> Phase(0.0, "P", 0.0)
        }
    }

    companion object {
        const val LOOP_SECONDS = 180.0
        const val TANK_LITERS = 55.0
        const val SPEED_MAX = 260.0
        const val RPM_MAX = 8000.0
        const val REDLINE_RPM = 6500.0
        /** Overall rpm per km/h of a 6-speed automatic. */
        private val RPM_PER_KMH = doubleArrayOf(118.0, 70.0, 48.0, 36.0, 29.0, 24.0)
        private val UPSHIFT_KMH = doubleArrayOf(18.0, 32.0, 48.0, 62.0, 76.0)

        fun gearFor(speedKmh: Double): Int = 1 + UPSHIFT_KMH.count { speedKmh >= it }
    }
}

/** Publishes [CarPlayClusterMockVehicle] snapshots on the main thread; same lifecycle shape as the VHAL provider. */
internal class CarPlayClusterMockVehicleFeed(
    private val publish: (Map<String, Any?>) -> Unit,
    private val trace: (String) -> Unit,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val vehicle = CarPlayClusterMockVehicle()
    private var last = 0L
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtimeNanos()
            publish(vehicle.advance(if (last == 0L) 0.0 else min(1.0, (now - last) / 1e9)))
            last = now
            handler.postDelayed(this, PERIOD_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        trace("OEM vehicle provider source=mock period=${PERIOD_MS}ms (simulated speed/gear/rpm/fuel/coolant)")
        handler.post(tick)
    }

    override fun close() {
        running = false
        handler.removeCallbacks(tick)
        last = 0L
    }

    private companion object { const val PERIOD_MS = 100L }
}
