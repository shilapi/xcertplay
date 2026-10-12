package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One Android location sample converted into the fields carried by the NMEA pair. */
data class CarPlayLocationFix(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val altitudeMeters: Double? = null,
    val bearingDegrees: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val accuracyMeters: Double? = null,
    val timestampMillis: Long? = null,
) {
    init {
        require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0) {
            "latitudeDegrees must be between -90 and 90"
        }
        require(longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0) {
            "longitudeDegrees must be between -180 and 180"
        }
    }
}

/** Supplies location data only while the phone has subscribed to iAP2 LocationInformation. */
interface Iap2LocationProvider : AutoCloseable {
    /** The 0xFFFA parameter ids, i.e. the sentence types the iPhone asked for; called before [start]. */
    fun onRequested(components: Set<Int>) = Unit

    /** Starts location updates and returns whether at least one source was subscribed. */
    fun start(): Boolean

    fun stop()

    fun latestNmea(): String?

    override fun close() {
        stop()
    }
}

/** LIVI-compatible $GPGGA + $GPRMC encoding used by iAP2 0xFFFB. */
object NmeaLocationEncoder {
    fun encode(fix: CarPlayLocationFix): String {
        val timestamp = fix.timestampMillis
            ?.takeIf { it > 0 }
            ?.let(::ofEpochMilli)
            ?: ofEpochMilli(System.currentTimeMillis())
        val time = format("%02d%02d%02d.00", timestamp.hour, timestamp.minute, timestamp.second)
        val date = format(
            "%02d%02d%02d",
            timestamp.day,
            timestamp.month,
            timestamp.year % 100,
        )

        val latitude = degreesToNmea(fix.latitudeDegrees, latitude = true)
        val longitude = degreesToNmea(fix.longitudeDegrees, latitude = false)
        val hdop = fix.accuracyMeters
            ?.takeIf { it.isFinite() && it > 0 }
            ?.let { min(50.0, max(0.5, it / 5.0)) }
            ?: 1.0
        val altitude = fix.altitudeMeters
            ?.takeIf { it.isFinite() }
            ?.let { format("%.1f", it) }
            ?: "0.0"

        val ggaBody = "GPGGA,$time,${latitude.value},${latitude.hemisphere}," +
            "${longitude.value},${longitude.hemisphere},1,08,${format("%.1f", hdop)}," +
            "$altitude,M,0.0,M,,"
        val speedKnots = fix.speedMetersPerSecond
            ?.takeIf { it.isFinite() && it >= 0 }
            ?.let { format("%.2f", it * KNOTS_PER_METER_PER_SECOND) }
            ?: "0.00"
        val course = fix.bearingDegrees
            ?.takeIf { it.isFinite() }
            ?.let { format("%.2f", it) }
            ?: "0.00"
        val rmcBody = "GPRMC,$time,A,${latitude.value},${latitude.hemisphere}," +
            "${longitude.value},${longitude.hemisphere},$speedKnots,$course,$date,,"

        return "$${ggaBody}*${checksum(ggaBody)}\r\n$${rmcBody}*${checksum(rmcBody)}\r\n"
    }

    private fun degreesToNmea(value: Double, latitude: Boolean): NmeaCoordinate {
        val absolute = abs(value)
        var degrees = absolute.toInt()
        var minutes = (absolute - degrees) * MINUTES_PER_DEGREE
        if (minutes >= MINUTES_PER_DEGREE - MINUTE_ROUNDING_TOLERANCE) {
            degrees += 1
            minutes = 0.0
        }
        val degreeText = if (latitude) format("%02d", degrees) else format("%03d", degrees)
        val hemisphere = if (latitude) {
            if (value >= 0) "N" else "S"
        } else {
            if (value >= 0) "E" else "W"
        }
        return NmeaCoordinate("$degreeText${format("%07.4f", minutes)}", hemisphere)
    }

    private fun checksum(body: String): String {
        var value = 0
        for (character in body) value = value xor character.code
        return format("%02X", value)
    }

    private fun ofEpochMilli(millis: Long): Timestamp = Timestamp(millis)

    private fun format(format: String, vararg arguments: Any): String =
        String.format(Locale.US, format, *arguments)

    private data class NmeaCoordinate(val value: String, val hemisphere: String)

    private class Timestamp(millis: Long) {
        private val fields = java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneOffset.UTC)

        val hour: Int = fields.hour
        val minute: Int = fields.minute
        val second: Int = fields.second
        val day: Int = fields.dayOfMonth
        val month: Int = fields.monthValue
        val year: Int = fields.year
    }

    private const val KNOTS_PER_METER_PER_SECOND = 1.94384449
    private const val MINUTES_PER_DEGREE = 60.0
    private const val MINUTE_ROUNDING_TOLERANCE = 0.00005
}

/**
 * The iPhone's StartLocationInformation in one wireless session. The iPhone asks on the Bluetooth
 * iAP2 link and then closes that link, some iPhones after sending 0xFFFC there within milliseconds.
 * In testing it did not ask again on the Wi-Fi link, even while driving, so the Wi-Fi link carries
 * the request on.
 *
 * Both links share one location provider, so each link holds it while reporting and only the
 * last link to let go stops it. 0xFFFC on the Bluetooth link ends only that link; on the Wi-Fi
 * link it stops reporting outright.
 */
class Iap2LocationRequest {
    /** The 0xFFFA parameter ids while a request is running, otherwise null. */
    @Volatile var components: Set<Int>? = null
    private var generation = 0
    private var holders = 0

    @Synchronized
    internal fun hold(): Int {
        holders++
        return generation
    }

    /** Returns whether no link holds the provider any more. */
    @Synchronized
    internal fun release(token: Int): Boolean {
        if (token != generation) return false
        holders = (holders - 1).coerceAtLeast(0)
        return holders == 0
    }

    @Synchronized
    internal fun stopAll() {
        generation++
        holders = 0
    }
}

/**
 * Accessory side of iAP2 LocationInformation on one link: starts on 0xFFFA, sends the latest fix
 * on every [tick] (about once a second), stops on 0xFFFC. With [continueRequest], a request that
 * [request] recorded on the Bluetooth link starts this link too.
 */
class Iap2LocationReporter(
    private val provider: Iap2LocationProvider?,
    private val onProgress: (String) -> Unit,
    private val request: Iap2LocationRequest? = null,
    private val continueRequest: Boolean = false,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var active = false
    private var sentLogged = false
    private var continued = false
    private var lastAttemptNanos = 0L
    private var holdToken: Int? = null
    private var sentCount = 0L

    /** Handles 0xFFFA/0xFFFC; returns false for any other message. */
    fun handle(frame: Iap2Frame, send: (Iap2Frame) -> Unit): Boolean = when (frame.messageId) {
        Iap2LocationMessages.START_LOCATION_INFORMATION -> {
            val components = Iap2LocationMessages.requestedComponents(frame)
            onProgress("iap2 rx=0xfffa start-location-information components=$components")
            request?.components = components
            provider?.onRequested(components)
            start(send)
            true
        }
        Iap2LocationMessages.STOP_LOCATION_INFORMATION -> {
            onProgress("iap2 rx=0xfffc stop-location-information")
            if (request != null && !continueRequest) {
                // Bluetooth link of a wireless session: the iPhone stops here within milliseconds while
                // handing the session to Wi-Fi and does not ask again there, so only this link stops.
                onProgress("iap2 location: Bluetooth link stopped; the request stays for the Wi-Fi link")
                active = false
                sentLogged = false
                releaseProvider()
            } else {
                request?.components = null
                request?.stopAll()
                holdToken = null
                active = false
                sentLogged = false
                provider?.stop()
            }
            true
        }
        else -> false
    }

    /**
     * Sends the latest fix once a second while active; on the Wi-Fi link first takes over a Bluetooth
     * request once. The loop calls this after every incoming message too, so it must not send each time:
     * that sent a dozen fixes in 0.1 s at session start.
     */
    fun tick(send: (Iap2Frame) -> Unit) {
        // The Bluetooth reporter receives STOP_LOCATION_INFORMATION and clears the shared request.
        // Wi-Fi may already have taken over by then, so its active loop must observe that stop too.
        if (continueRequest && continued && request?.components == null) {
            active = false
            sentLogged = false
            releaseProvider()
            return
        }
        if (continueRequest && !active && !continued) {
            val components = request?.components
            if (components != null) {
                continued = true
                onProgress("iap2 location request continues from the Bluetooth link components=$components")
                provider?.onRequested(components)
                start(send)
                return
            }
        }
        if (active && sinceAttemptMillis() >= POLL_INTERVAL_MILLIS) sendLatest(send)
    }

    /** Wakes the loop when the next fix is due, or every second while a Bluetooth request may still arrive. */
    fun pollTimeout(remainingMillis: Long): Long = when {
        active -> min(remainingMillis, (POLL_INTERVAL_MILLIS - sinceAttemptMillis()).coerceAtLeast(1))
        continueRequest && !continued && provider != null -> min(remainingMillis, POLL_INTERVAL_MILLIS)
        else -> remainingMillis
    }

    /** Ends this link's reporting; the provider keeps running while another link still holds it. */
    fun close() {
        active = false
        releaseProvider()
    }

    private fun sinceAttemptMillis() = (nanoTime() - lastAttemptNanos) / 1_000_000

    private fun start(send: (Iap2Frame) -> Unit) {
        active = startProvider()
        sentLogged = false
        if (active && holdToken == null) holdToken = request?.hold() ?: 0
        if (active) sendLatest(send)
    }

    private fun releaseProvider() {
        val token = holdToken ?: return
        holdToken = null
        if (request?.release(token) != false) provider?.stop()
    }

    private fun startProvider(): Boolean {
        if (provider == null) return false
        return try {
            provider.start().also { started -> if (!started) onProgress("iap2 location provider did not start") }
        } catch (error: Exception) {
            onProgress("iap2 location provider start failed: ${error.message}")
            false
        }
    }

    private fun sendLatest(send: (Iap2Frame) -> Unit) {
        lastAttemptNanos = nanoTime()
        val sentence = provider?.latestNmea() ?: return
        send(Iap2LocationMessages.locationInformation(sentence))
        sentCount++
        if (!sentLogged) {
            sentLogged = true
            onProgress("iap2 tx=0xfffb location-information sentences=${sentenceTypes(sentence)}")
        } else if (sentCount % REPORT_EVERY_SENT == 0L) {
            onProgress("iap2 location reports sent=$sentCount sentences=${sentenceTypes(sentence)}")
        }
    }

    private fun sentenceTypes(nmea: String): String =
        nmea.lineSequence().map { it.trim().removePrefix("$").substringBefore(',') }
            .filter { it.isNotEmpty() }.distinct().joinToString("+")

    private companion object {
        const val POLL_INTERVAL_MILLIS = 1_000L
        /** About once every five minutes at one report a second. */
        const val REPORT_EVERY_SENT = 300L
    }
}

object Iap2LocationMessages {
    const val START_LOCATION_INFORMATION = 0xfffa
    const val LOCATION_INFORMATION = 0xfffb
    const val STOP_LOCATION_INFORMATION = 0xfffc

    /** 0xFFFA selector for `$PASCD`; the matching IdentificationInformation flag is 20. */
    const val VEHICLE_SPEED_DATA = 4

    /** The parameter ids of a 0xFFFA request (the sentence types asked for), or none if unreadable. */
    fun requestedComponents(frame: Iap2Frame): Set<Int> =
        runCatching { frame.body().asList().map { it.id }.toSortedSet() }.getOrDefault(emptySet())

    fun locationInformation(nmeaSentence: String): Iap2Frame {
        require(nmeaSentence.isNotEmpty()) { "NMEA sentence must not be empty" }
        return Iap2ControlMessages.locationInformation(nmeaSentence)
    }
}
