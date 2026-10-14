package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.io.Closeable

/**
 * Keeps the Wi-Fi radio out of power save while a CarPlay session runs. Without a lock the radio
 * may sleep between packets, which shows up as the periodic audio gaps and ~0.5 s arrival stalls a
 * Wi-Fi Direct link otherwise shows. It is not reference counted: acquire/release are paired by the
 * session lifecycle, and a duplicated acquire must not outlive the release.
 */
internal class WirelessPerformanceLock(
    context: Context,
    private val diagnostic: (String) -> Unit = {},
) : Closeable {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var lock: WifiManager.WifiLock? = null
    private var lowLatencyLock: WifiManager.WifiLock? = null

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF still keeps power save off in the background.
    @Synchronized
    fun acquire() {
        if (lock != null) return
        acquireLowLatency()
        val manager = wifi ?: run {
            report("Wi-Fi lock unavailable: no WifiManager on this head unit")
            return
        }
        var failure: Throwable? = null
        lock = runCatching {
            manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure {
            failure = it
            Log.w(TAG, "High-performance Wi-Fi lock unavailable", it)
        }.getOrNull()
        report(
            if (lock != null) {
                "Wi-Fi high-performance lock held for this session"
            } else {
                val error = failure
                "Wi-Fi high-performance lock refused: " +
                    (error?.let { "${it.javaClass.simpleName} ${it.message?.take(120) ?: ""}".trim() }
                        ?: "createWifiLock returned null")
            },
        )
    }

    /**
     * Android 10's low-latency mode also asks the Wi-Fi driver for low latency, but only applies
     * while the app is in the foreground with the screen on, so the high-performance lock stays
     * held for the background.
     */
    private fun acquireLowLatency() {
        if (lowLatencyLock != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = wifi ?: return
        lowLatencyLock = runCatching {
            manager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "$TAG-low-latency").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure {
            report("Wi-Fi low-latency lock refused: ${it.javaClass.simpleName} ${it.message?.take(120) ?: ""}".trim())
        }.getOrNull()
        if (lowLatencyLock != null) report("Wi-Fi low-latency lock held for this session")
    }

    @Synchronized
    fun release() {
        lowLatencyLock?.let { current ->
            lowLatencyLock = null
            runCatching { if (current.isHeld) current.release() }
        }
        val current = lock ?: return
        lock = null
        runCatching { if (current.isHeld) current.release() }
            .onFailure { Log.w(TAG, "Could not release the Wi-Fi lock", it) }
        report("Wi-Fi high-performance lock released")
    }

    override fun close() = release()

    private fun report(message: String) {
        Log.i(TAG, message)
        runCatching { diagnostic(message) }
    }

    private companion object {
        const val TAG = "xcertplay-WifiLock"
    }
}
