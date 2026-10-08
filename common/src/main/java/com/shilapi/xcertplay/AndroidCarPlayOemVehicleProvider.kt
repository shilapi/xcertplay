package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Optional real Automotive VHAL input. No Android Car service, permission or property means unavailable. */
internal class AndroidCarPlayOemVehicleProvider(
    private val context: Context,
    private val publish: (Map<String, Any?>) -> Unit,
    private val trace: (String) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadScheduledExecutor { job -> Thread(job, "Ultra-VHAL").apply { isDaemon = true } }
    private var car: Any? = null
    private var manager: Any? = null
    private var initialized = false
    private var lastAvailability: Set<String>? = null
    private val failedProperties = mutableSetOf<String>()

    fun start() {
        worker.scheduleWithFixedDelay({
            if (closed.get()) return@scheduleWithFixedDelay
            if (!initialized) {
                initialized = true
                if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) runCatching {
                    // android.car is an optional system shared library, never downloaded or impersonated.
                    val type = Class.forName("android.car.Car")
                    car = type.getMethod("createCar", Context::class.java).invoke(null, context)
                    manager = type.getMethod("getCarManager", String::class.java).invoke(car, "property")
                    trace("OEM vehicle provider source=Android-VHAL connected=${manager != null}")
                }.onFailure { trace("OEM vehicle provider unavailable: ${it.cause?.message ?: it.message}") }
                else trace("OEM vehicle provider unavailable: Android Automotive Car service is absent")
            }
            val values = CarPlayOemVehicleValues.snapshot(read("PERF_VEHICLE_SPEED"), read("ENGINE_RPM"), SystemClock.elapsedRealtimeNanos())
            val available = values.filterValues { it != null }.keys
            if (available != lastAvailability) {
                lastAvailability = available
                trace("OEM vehicle provider source=Android-VHAL available=${available.sorted()}")
            }
            // close() clears values immediately; this lock prevents a racing sample restoring them afterward.
            synchronized(closed) { if (!closed.get()) publish(values) }
        }, 0, 250, TimeUnit.MILLISECONDS)
    }

    private fun read(name: String): OemVehicleReading? = runCatching {
        val target = manager ?: return null
        val id = Class.forName("android.car.VehiclePropertyIds").getField(name).getInt(null)
        val value = target.javaClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(target, id, 0) ?: return null
        val type = value.javaClass
        val status = type.getMethod("getStatus").invoke(value) as Int
        OemVehicleReading(type.getMethod("getValue").invoke(value) as? Number,
            type.getMethod("getTimestamp").invoke(value) as Long,
            status == type.getField("STATUS_AVAILABLE").getInt(null))
    }.onFailure {
        if (failedProperties.add(name)) {
            val reason = it.cause ?: it
            trace("OEM vehicle property unavailable property=$name reason=${reason.javaClass.simpleName}: ${reason.message}")
        }
    }.getOrNull()

    override fun close() {
        synchronized(closed) {
            if (!closed.compareAndSet(false, true)) return
            publish(CarPlayOemVehicleValues.snapshot(null, null, SystemClock.elapsedRealtimeNanos()))
        }
        worker.execute { car?.let { runCatching { it.javaClass.getMethod("disconnect").invoke(it) } }; car = null; manager = null }
        worker.shutdown()
    }
}
