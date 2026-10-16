package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Owns one Android LocalOnlyHotspot reservation and reports its live configuration.
 *
 * [start] must run on a worker thread because it blocks until the system callback arrives and
 * the AP interface is usable. The reservation and multicast lock stay owned by this instance
 * until [close].
 */
class LocalOnlyHotspotManager(
    context: Context,
    private val diagnostic: (String) -> Unit = {},
) : WirelessHotspotManager {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val stateLock = Object()

    private var startAttempt: StartAttempt? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var callbackThread: HandlerThread? = null
    private var stopped = false
    private var closed = false

    /**
     * Starts a LocalOnlyHotspot and waits up to [timeoutMillis] for the live configuration and AP
     * interface. The returned credentials are not retained by this manager.
     */
    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LocalOnlyHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val attempt = StartAttempt()
        synchronized(stateLock) {
            check(!closed) { "LocalOnlyHotspotManager is closed" }
            check(startAttempt == null && reservation == null) {
                "A LocalOnlyHotspot is already starting or active"
            }
            startAttempt = attempt
        }

        val thread = HandlerThread("xcertplay-local-only-hotspot").apply { start() }
        attempt.thread = thread
        var acquiredMulticastLock: WifiManager.MulticastLock? = null
        val deadlineNanos = deadlineAfter(timeoutMillis)
        val preStartInterfaces = networkInterfaceNames()

        try {
            var retries = 0
            var startedReservation: WifiManager.LocalOnlyHotspotReservation? = null
            while (startedReservation == null) {
                ensureStartActive(attempt)
                wifiManager.startLocalOnlyHotspot(
                    createCallback(attempt),
                    Handler(thread.looper),
                )
                try {
                    startedReservation = awaitStart(attempt, deadlineNanos, timeoutMillis)
                } catch (rejected: LocalOnlyHotspotRejected) {
                    if (!LocalOnlyHotspotRetry.shouldRetry(rejected.reason, retries)) throw rejected
                    // The radio is still releasing a previous hotspot or Wi-Fi Direct group.
                    retries++
                    val delayMillis = LocalOnlyHotspotRetry.delayMillis(retries)
                    report("LocalOnlyHotspot ${failureReason(rejected.reason)}; retry $retries in ${delayMillis}ms")
                    synchronized(stateLock) {
                        attempt.failure = null
                        // wait(0) would block forever, so an exhausted deadline fails instead.
                        val waitNanos = minOf(delayMillis * NANOS_PER_MILLISECOND, deadlineNanos - System.nanoTime())
                        if (waitNanos <= 0) throw rejected
                        waitNanos(waitNanos)
                        ensureStartActiveLocked(attempt)
                    }
                }
            }
            val activeReservation = startedReservation
            acquiredMulticastLock = acquireMulticastLock(attempt)
            val configuration = readConfiguration(activeReservation)
            val apInterface = awaitApInterface(
                bssid = configuration.bssidBytes,
                preStartInterfaces = preStartInterfaces,
                attempt = attempt,
                deadlineNanos = deadlineNanos,
            )

            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                reservation = activeReservation
                multicastLock = acquiredMulticastLock
                callbackThread = thread
                startAttempt = null
                stopped = false
                acquiredMulticastLock = null
            }

            return WirelessHotspotInfo(
                ssid = configuration.ssid,
                passphrase = configuration.passphrase,
                security = configuration.security,
                channel = configuration.channel,
                frequencyMHz = null,
                bssid = apInterface?.bssid ?: configuration.bssid,
                interfaceName = apInterface?.name,
                hostAddress = apInterface?.hostAddress,
                bandLabel = configuration.bandLabel,
                backend = WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT,
            )
        } catch (failure: Exception) {
            cleanupFailedStart(attempt, acquiredMulticastLock)
            throw failure
        }
    }

    override fun close() {
        val activeReservation: WifiManager.LocalOnlyHotspotReservation?
        val activeMulticastLock: WifiManager.MulticastLock?
        val activeCallbackThread: HandlerThread?
        synchronized(stateLock) {
            if (closed) return
            closed = true
            startAttempt?.stopped = true
            stateLock.notifyAll()
            activeReservation = reservation
            activeMulticastLock = multicastLock
            activeCallbackThread = callbackThread
            reservation = null
            multicastLock = null
            callbackThread = null
        }

        releaseMulticastLock(activeMulticastLock)
        activeReservation?.close()
        activeCallbackThread?.quitSafely()
    }

    private fun createCallback(attempt: StartAttempt): WifiManager.LocalOnlyHotspotCallback =
        object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(
                reservation: WifiManager.LocalOnlyHotspotReservation,
            ) {
                val closeReservation = synchronized(stateLock) {
                    if (
                        closed ||
                        startAttempt !== attempt ||
                        attempt.stopped ||
                        attempt.reservation != null
                    ) {
                        true
                    } else {
                        attempt.reservation = reservation
                        stateLock.notifyAll()
                        false
                    }
                }
                if (closeReservation) reservation.close()
            }

            override fun onFailed(reason: Int) {
                synchronized(stateLock) {
                    if (startAttempt === attempt && attempt.failure == null) {
                        attempt.failure = LocalOnlyHotspotRejected(
                            reason,
                            "LocalOnlyHotspot failed: ${failureReason(reason)}",
                        )
                        stateLock.notifyAll()
                    }
                }
            }

            override fun onStopped() {
                var lockToRelease: WifiManager.MulticastLock? = null
                synchronized(stateLock) {
                    if (startAttempt === attempt) attempt.stopped = true
                    if (reservation === attempt.reservation) {
                        stopped = true
                        lockToRelease = multicastLock
                        multicastLock = null
                    }
                    stateLock.notifyAll()
                }
                releaseMulticastLock(lockToRelease)
            }
        }

    private fun awaitStart(
        attempt: StartAttempt,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ): WifiManager.LocalOnlyHotspotReservation = synchronized(stateLock) {
        while (true) {
            if (closed) throw IOException("LocalOnlyHotspot manager closed while starting")
            attempt.failure?.let { throw it }
            if (attempt.stopped) {
                throw IOException("LocalOnlyHotspot stopped before startup completed")
            }
            attempt.reservation?.let { return@synchronized it }

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for LocalOnlyHotspot",
                )
            }
            waitNanos(remainingNanos)
        }
        error("unreachable")
    }

    private fun acquireMulticastLock(attempt: StartAttempt): WifiManager.MulticastLock {
        ensureStartActive(attempt)
        val lock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG)
        lock.setReferenceCounted(false)
        try {
            lock.acquire()
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
            }
            return lock
        } catch (failure: Exception) {
            releaseMulticastLock(lock)
            throw failure
        }
    }

    private fun readConfiguration(
        reservation: WifiManager.LocalOnlyHotspotReservation,
    ): HotspotConfiguration {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            readSoftApConfiguration(reservation.softApConfiguration)
        } else {
            @Suppress("DEPRECATION")
            val configuration = reservation.wifiConfiguration
                ?: throw IOException("LocalOnlyHotspot did not provide a Wi-Fi configuration")
            readWifiConfiguration(configuration)
        }
    }

    @Suppress("DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readSoftApConfiguration(configuration: SoftApConfiguration): HotspotConfiguration {
        val ssid = validateSsid(configuration.ssid)
        val security = mapSoftApSecurity(configuration.securityType)
        val passphrase = validatePassphrase(security, configuration.passphrase)
        val channel = if (Build.VERSION.SDK_INT >= 36) {
            readConfiguredChannel(configuration)
        } else {
            readLegacySoftApChannel(configuration)
        }
        val bssid = configuration.bssid

        return HotspotConfiguration(
            ssid = ssid,
            passphrase = passphrase,
            security = security,
            channel = channel.first,
            bssid = bssid?.toString(),
            bssidBytes = bssid?.toByteArray(),
            bandLabel = channel.second,
        )
    }

    @Suppress("DEPRECATION")
    private fun readWifiConfiguration(configuration: WifiConfiguration): HotspotConfiguration {
        val ssid = validateSsid(configuration.SSID)
        val security = mapWifiConfigurationSecurity(configuration)
        val passphrase = validatePassphrase(security, unquote(configuration.preSharedKey))
        val bssid = configuration.BSSID?.let {
            try {
                MacAddress.fromString(it)
            } catch (failure: IllegalArgumentException) {
                throw IOException("LocalOnlyHotspot reported an invalid BSSID: $it", failure)
            }
        }
        val channel = readWifiConfigurationChannel(configuration)

        return HotspotConfiguration(
            ssid = ssid,
            passphrase = passphrase,
            security = security,
            channel = channel,
            bssid = bssid?.toString(),
            bssidBytes = bssid?.toByteArray(),
            bandLabel = readWifiConfigurationBandLabel(configuration, channel),
        )
    }

    @RequiresApi(36)
    private fun readConfiguredChannel(configuration: SoftApConfiguration): Pair<Int, String> {
        val channels = configuration.channels
        if (channels.size() != 1) {
            throw IOException("Expected one LocalOnlyHotspot channel, got ${channels.size()}")
        }
        val band = channels.keyAt(0)
        val channel = requireChannel(
            channels.valueAt(0),
            "SoftApConfiguration.channels",
            allowAuto = true,
        )
        return channel to softApBandLabel(band)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readLegacySoftApChannel(configuration: SoftApConfiguration): Pair<Int, String> {
        val channel = try {
            val getter = SoftApConfiguration::class.java.getMethod("getChannel")
            val value = getter.invoke(configuration) as? Number
                ?: throw IOException("SoftApConfiguration.getChannel returned no channel")
            requireChannel(
                value.toInt(),
                "SoftApConfiguration.getChannel",
                allowAuto = true,
            )
        } catch (failure: ReflectiveOperationException) {
            throw IOException(
                "Android ${Build.VERSION.RELEASE} does not expose SoftApConfiguration.getChannel",
                failure,
            )
        }
        return channel to readLegacySoftApBandLabel(configuration, channel)
    }

    private fun readWifiConfigurationChannel(configuration: WifiConfiguration): Int {
        val channel = try {
            WifiConfiguration::class.java.getField("apChannel").getInt(configuration)
        } catch (failure: ReflectiveOperationException) {
            throw IOException(
                "Android ${Build.VERSION.RELEASE} does not expose WifiConfiguration.apChannel",
                failure,
            )
        }
        return requireChannel(channel, "WifiConfiguration.apChannel", allowAuto = true)
    }

    private fun readWifiConfigurationBandLabel(
        configuration: WifiConfiguration,
        channel: Int,
    ): String {
        val band = try {
            (WifiConfiguration::class.java.getField("apBand").get(configuration) as? Number)
                ?.toInt()
        } catch (_: ReflectiveOperationException) {
            null
        }
        return band?.let(::softApBandLabel) ?: legacyBandLabel(channel)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readLegacySoftApBandLabel(
        configuration: SoftApConfiguration,
        channel: Int,
    ): String {
        val band = try {
            (SoftApConfiguration::class.java.getMethod("getBand").invoke(configuration) as? Number)
                ?.toInt()
        } catch (_: ReflectiveOperationException) {
            null
        }
        return band?.let(::softApBandLabel) ?: legacyBandLabel(channel)
    }

    private fun awaitApInterface(
        bssid: ByteArray?,
        preStartInterfaces: Set<String>,
        attempt: StartAttempt,
        deadlineNanos: Long,
    ): ApInterface? {
        var matchedInterfaceName: String? = null
        while (true) {
            ensureStartActive(attempt)
            val networkInterface = findInterface(bssid, preStartInterfaces)
            if (networkInterface != null) {
                matchedInterfaceName = networkInterface.name
                networkInterface.hotspotAddress()?.let { hostAddress ->
                    val interfaceBssid = networkInterface.hardwareAddress?.toMacAddressString()
                        ?: (hostAddress as? Inet6Address)?.toEui64MacAddress()
                    if (bssid == null && interfaceBssid == null) {
                        return@let
                    }
                    return ApInterface(
                        name = networkInterface.name,
                        hostAddress = hostAddress,
                        bssid = interfaceBssid,
                    )
                }
            }

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                return matchedInterfaceName?.let {
                    ApInterface(it, null, null)
                }
            }
            try {
                TimeUnit.NANOSECONDS.sleep(minOf(remainingNanos, INTERFACE_POLL_NANOS))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for the LocalOnlyHotspot interface", interrupted)
            }
        }
    }

    private fun findInterface(
        bssid: ByteArray?,
        preStartInterfaces: Set<String>,
    ): NetworkInterface? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val candidates = Collections.list(interfaces).filter { networkInterface ->
            try {
                networkInterface.isUp && !networkInterface.isLoopback
            } catch (_: SocketException) {
                false
            }
        }
        if (bssid != null) {
            return candidates.firstOrNull {
                try {
                    it.hardwareAddress?.contentEquals(bssid) == true
                } catch (_: SocketException) {
                    false
                }
            }
        }
        candidates.firstOrNull { it.name !in preStartInterfaces && it.hotspotAddress() != null }
            ?.let { return it }

        val primaryInterface = connectivityManager?.activeNetwork
            ?.let { connectivityManager.getLinkProperties(it)?.interfaceName }
        val nonPrimary = candidates.filter { it.name != primaryInterface }
        return nonPrimary.firstOrNull { it.hasPrivate192Address() }
            ?: nonPrimary.firstOrNull { it.hasSiteLocalAddress() }
            ?: nonPrimary.firstOrNull { it.hasLinkLocalAddress() }
            ?: nonPrimary.firstOrNull { it.hotspotAddress() != null }
    }

    private fun networkInterfaceNames(): Set<String> =
        NetworkInterface.getNetworkInterfaces()
            ?.let {
                Collections.list(it)
                    .filter { networkInterface ->
                        try {
                            networkInterface.isUp
                        } catch (_: SocketException) {
                            false
                        }
                    }
                    .mapTo(linkedSetOf()) { n -> n.name }
            }
            .orEmpty()

    private fun NetworkInterface.hasPrivate192Address(): Boolean =
        Collections.list(inetAddresses).any {
            it is Inet4Address && it.address.size == 4 &&
                (it.address[0].toInt() and 0xff) == 192 &&
                (it.address[1].toInt() and 0xff) == 168
        }

    private fun NetworkInterface.hasSiteLocalAddress(): Boolean =
        Collections.list(inetAddresses).any { it is Inet4Address && it.isSiteLocalAddress }

    private fun NetworkInterface.hasLinkLocalAddress(): Boolean =
        Collections.list(inetAddresses).any { it is Inet6Address && it.isLinkLocalAddress }

    private fun NetworkInterface.hotspotAddress(): InetAddress? {
        var ipv4: InetAddress? = null
        for (address in Collections.list(inetAddresses)) {
            if (address is Inet6Address && address.isLinkLocalAddress) {
                if (address.scopeId == index) return address
                try {
                    return Inet6Address.getByAddress(null, address.address, this)
                } catch (_: UnknownHostException) {
                    continue
                }
            }
            if (address is Inet4Address && !address.isLoopbackAddress && ipv4 == null) {
                ipv4 = address
            }
        }
        return ipv4
    }

    private fun ensureStartActive(attempt: StartAttempt) {
        synchronized(stateLock) {
            ensureStartActiveLocked(attempt)
        }
    }

    private fun ensureStartActiveLocked(attempt: StartAttempt) {
        if (closed) throw IOException("LocalOnlyHotspot manager closed while starting")
        if (startAttempt !== attempt) throw IOException("LocalOnlyHotspot startup was cancelled")
        if (attempt.stopped) throw IOException("LocalOnlyHotspot stopped while starting")
    }

    private fun cleanupFailedStart(
        attempt: StartAttempt,
        multicastLock: WifiManager.MulticastLock?,
    ) {
        val failedReservation: WifiManager.LocalOnlyHotspotReservation?
        val failedThread: HandlerThread?
        synchronized(stateLock) {
            if (startAttempt === attempt) startAttempt = null
            attempt.stopped = true
            stateLock.notifyAll()
            failedReservation = attempt.reservation
            failedThread = attempt.thread
        }
        releaseMulticastLock(multicastLock)
        failedReservation?.close()
        failedThread?.quitSafely()
    }

    private fun waitNanos(nanos: Long) {
        val millis = nanos / NANOS_PER_MILLISECOND
        val remainder = (nanos % NANOS_PER_MILLISECOND).toInt()
        try {
            stateLock.wait(millis, remainder)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for LocalOnlyHotspot", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun validateSsid(value: String?): String {
        val ssid = unquote(value)
        if (ssid.isNullOrEmpty() || ssid == WifiManager.UNKNOWN_SSID) {
            throw IOException("LocalOnlyHotspot did not report a usable SSID")
        }
        if ('\u0000' in ssid) throw IOException("LocalOnlyHotspot SSID contains U+0000")
        return ssid
    }

    private fun validatePassphrase(
        security: Iap2WirelessSecurity,
        value: String?,
    ): String {
        val passphrase = value.orEmpty()
        if (security != Iap2WirelessSecurity.NONE && passphrase.isEmpty()) {
            throw IOException("LocalOnlyHotspot did not report a passphrase for secured Wi-Fi")
        }
        if ('\u0000' in passphrase) {
            throw IOException("LocalOnlyHotspot passphrase contains U+0000")
        }
        return passphrase
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun mapSoftApSecurity(securityType: Int): Iap2WirelessSecurity = when (securityType) {
        SoftApConfiguration.SECURITY_TYPE_OPEN -> Iap2WirelessSecurity.NONE
        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION ->
            Iap2WirelessSecurity.WPA3_TRANSITION
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
        SoftApConfiguration.SECURITY_TYPE_WPA3_OWE,
        SoftApConfiguration.SECURITY_TYPE_WPA3_OWE_TRANSITION,
        -> throw IOException("Unsupported LocalOnlyHotspot security type: OWE")
        else -> throw IOException(
            "Unsupported LocalOnlyHotspot security type: $securityType",
        )
    }

    private fun mapWifiConfigurationSecurity(
        configuration: WifiConfiguration,
    ): Iap2WirelessSecurity {
        val keyManagement = configuration.allowedKeyManagement
            ?: throw IOException("LocalOnlyHotspot did not report its key management")
        val open = keyManagement.get(WifiConfiguration.KeyMgmt.NONE)
        val wpa2 = keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
        val sae = keyManagement.get(WifiConfiguration.KeyMgmt.SAE)
        val owe = keyManagement.get(WifiConfiguration.KeyMgmt.OWE)

        return when {
            owe -> throw IOException("Unsupported LocalOnlyHotspot security type: OWE")
            open && !wpa2 && !sae -> Iap2WirelessSecurity.NONE
            wpa2 && sae -> Iap2WirelessSecurity.WPA3_TRANSITION
            wpa2 -> Iap2WirelessSecurity.WPA_WPA2
            sae -> Iap2WirelessSecurity.WPA3_ONLY
            else -> throw IOException(
                "Unsupported LocalOnlyHotspot key management: $keyManagement",
            )
        }
    }

    private fun requireChannel(channel: Int, source: String): Int {
        return requireChannel(channel, source, allowAuto = false)
    }

    private fun requireChannel(channel: Int, source: String, allowAuto: Boolean): Int {
        if (channel !in 0..0xff || (!allowAuto && channel == 0)) {
            throw IOException("$source returned invalid channel: $channel")
        }
        return channel
    }

    private fun ByteArray.toMacAddressString(): String =
        joinToString(":") { "%02x".format(it.toInt() and 0xff) }

    private fun Inet6Address.toEui64MacAddress(): String? {
        val bytes = address
        if (!isLinkLocalAddress || bytes.size != 16 || bytes[11] != 0xff.toByte() ||
            bytes[12] != 0xfe.toByte()
        ) {
            return null
        }
        return byteArrayOf(
            (bytes[8].toInt() xor 0x02).toByte(),
            bytes[9],
            bytes[10],
            bytes[13],
            bytes[14],
            bytes[15],
        ).toMacAddressString()
    }

    private fun softApBandLabel(band: Int): String = when (band) {
        SoftApConfiguration.BAND_2GHZ -> "2.4 GHz"
        SoftApConfiguration.BAND_5GHZ -> "5 GHz"
        SoftApConfiguration.BAND_6GHZ -> "6 GHz"
        SoftApConfiguration.BAND_60GHZ -> "60 GHz"
        else -> "Unknown band ($band)"
    }

    private fun legacyBandLabel(channel: Int): String = when (channel) {
        in 1..14 -> "2.4 GHz"
        in 32..177 -> "5 GHz"
        else -> "Unknown band"
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private fun failureReason(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "no channel available"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "generic error"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "incompatible Wi-Fi mode"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "tethering disallowed"
        else -> "reason $reason"
    }

    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        if (lock == null) return
        try {
            if (lock.isHeld) lock.release()
        } catch (_: RuntimeException) {
            // close() is best-effort; the hotspot reservation remains the authoritative owner.
        }
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        runCatching { diagnostic(message) }
    }

    private class LocalOnlyHotspotRejected(val reason: Int, message: String) : IOException(message)

    private class StartAttempt {
        var thread: HandlerThread? = null
        var reservation: WifiManager.LocalOnlyHotspotReservation? = null
        var failure: IOException? = null
        var stopped = false
    }

    private class HotspotConfiguration(
        val ssid: String,
        val passphrase: String,
        val security: Iap2WirelessSecurity,
        val channel: Int,
        val bssid: String?,
        val bssidBytes: ByteArray?,
        val bandLabel: String,
    )

    private class ApInterface(
        val name: String,
        val hostAddress: InetAddress?,
        val bssid: String?,
    )

    private companion object {
        const val TAG = "xcertplay-local-only-hotspot"
        const val MULTICAST_LOCK_TAG = "xcertplay-local-only-hotspot-mdns"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val INTERFACE_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(100)
    }
}

/** Which LocalOnlyHotspot failures are worth asking for again, and how long to wait first. */
internal object LocalOnlyHotspotRetry {
    const val MAX_RETRIES = 3

    /**
     * Generic and incompatible-mode failures are what a radio still releasing a previous hotspot or
     * Wi-Fi Direct group reports; no channel or disallowed tethering does not change by waiting.
     */
    fun shouldRetry(reason: Int, retries: Int): Boolean =
        retries < MAX_RETRIES && (
            reason == WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC ||
                reason == WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE
            )

    /** 0.5 s, 1 s, 1.5 s, the same steps Wi-Fi Direct uses. */
    fun delayMillis(retry: Int): Long = P2pCreateRetry.busyDelayMillis(retry)
}
