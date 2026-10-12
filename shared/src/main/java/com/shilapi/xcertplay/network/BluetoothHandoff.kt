package com.shilapi.xcertplay.network

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Which of the iPhone's classic Bluetooth links wireless CarPlay turns off; both off by default. */
object BluetoothHandoffSettings {
    private const val PREFS = "xcertplay_bluetooth_handoff"
    private const val KEY_CALLS = "disconnect_calls"
    private const val KEY_AUDIO = "disconnect_audio"

    /** Hands-free (HFP): calls go through CarPlay and the stock phone shows no popup. */
    fun callsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CALLS, false)

    fun setCallsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CALLS, enabled).apply()
        BluetoothHandoff.shared(context).settingsChanged()
    }

    /** Bluetooth audio (A2DP): music goes through CarPlay instead of Bluetooth. */
    fun audioEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUDIO, false)

    fun setAudioEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIO, enabled).apply()
        BluetoothHandoff.shared(context).settingsChanged()
    }

    internal fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Does what CarPlay's disableBluetooth asks: while wireless CarPlay runs, the head unit's
 * hands-free (HFP client) and/or Bluetooth audio (A2DP sink) links to that iPhone are turned off,
 * so calls and media go through CarPlay.
 *
 * Each link's connection priority is saved and set to off, which head-unit Bluetooth stacks
 * check before reconnecting (a BYD DiLink stack was seen reconnecting otherwise), and the link is
 * disconnected. One instance serves the whole process, so a reconnect (settings saved, reconnect
 * button, brief drop) keeps the links off: [releaseLater] restores them only if no session holds
 * them again within [RELEASE_DELAY_MS]. The saved priorities persist: if the process is killed
 * before that release (some head units kill apps when the car is switched off), the next start
 * takes the links over and restores them unless CarPlay connects within [STARTUP_GRACE_MS].
 *
 * The head unit's HFP client (profile 16) and A2DP sink (profile 11) are hidden API and are called
 * reflectively. On Android 10 and earlier they need only BLUETOOTH_ADMIN; on later versions the
 * calls may require privileged permissions and fail, which is logged and leaves the links
 * connected. All state lives on the main thread.
 */
class BluetoothHandoff private constructor(context: Context) {
    private enum class Link(val profile: Int, val label: String, val stateChanged: String) {
        CALLS(16, "HFP", "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"),
        AUDIO(11, "A2DP", "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"),
    }

    private val app = context.applicationContext
    private val prefs = BluetoothHandoffSettings.prefs(app)
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = runCatching {
        app.getSystemService(BluetoothManager::class.java)?.adapter
    }.getOrNull()
    private val proxies = mutableMapOf<Link, BluetoothProfile>()
    private val requested = mutableSetOf<Link>()
    private val pending = mutableMapOf<Link, MutableList<(BluetoothProfile) -> Unit>>()
    private val held = mutableSetOf<Link>()
    private var target: String? = null
    /** The session ended and [delayedRelease] is waiting: a setting may restore, never newly hold. */
    private var releasePending = false
    private var receiverRegistered = false
    private val guards = Link.values().associateWith { HandoffReconnectGuard() }
    private var releaseAt = 0L
    private var releaseReason = ""
    private val delayedRelease = Runnable { releaseNow(releaseReason) }

    /** Where progress is written; the controller of the current connection sets it. */
    @Volatile
    var diagnostic: (String) -> Unit = {}

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val link = Link.values().firstOrNull { it.stateChanged == intent.action } ?: return
            if (link !in held) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val address = target ?: return
            if (!device.address.equals(address, ignoreCase = true)) return
            if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_CONNECTED) return
            if (guards.getValue(link).allow(SystemClock.elapsedRealtime())) {
                report("${link.label} to $address reconnected during CarPlay; disconnecting again")
                withProxy(link) { disconnect(link, it, address) }
            } else {
                report("${link.label} to $address keeps reconnecting; leaving it connected")
            }
        }
    }

    init {
        handler.post {
            if (target == null) takeOverLeftover()
        }
    }

    /**
     * Links a killed process left off stay off a while longer: restoring at once would reconnect
     * the iPhone only for the CarPlay session about to start to drop it again.
     */
    private fun takeOverLeftover() {
        val address = prefs.getString(KEY_HELD_ADDRESS, null) ?: return
        val links = Link.values().filter { prefs.contains(priorityKey(it)) }
        if (links.isEmpty()) {
            prefs.edit().remove(KEY_HELD_ADDRESS).apply()
            return
        }
        target = address
        held += links
        links.forEach { guards.getValue(it).reset() }
        registerReceiver()
        scheduleRelease(STARTUP_GRACE_MS, "no CarPlay session within ${STARTUP_GRACE_MS / 1000}s of startup")
        report("Bluetooth handoff: ${links.joinToString("+") { it.label }} to $address left off by an earlier run; " +
            "restoring in ${STARTUP_GRACE_MS / 1000}s unless CarPlay connects")
    }

    /**
     * The app is exiting, or the head unit powered on without CarPlay: restore the links now
     * instead of after the delay, and wait up to [timeoutMillis] for it. Call from a background
     * thread.
     */
    fun releaseNowBlocking(timeoutMillis: Long, reason: String = "app exit"): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Bluetooth restore must not block the main thread" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        val releaseApplied = CountDownLatch(1)
        handler.post {
            try {
                handler.removeCallbacks(delayedRelease)
                if (target != null) {
                    releaseNow(reason)
                } else {
                    prefs.getString(KEY_HELD_ADDRESS, null)?.let { address ->
                        for (link in Link.values()) restoreLink(link, address)
                    }
                }
            } finally {
                releaseApplied.countDown()
            }
        }
        return try {
            if (!releaseApplied.await(timeoutMillis, TimeUnit.MILLISECONDS)) return false
            var restored = Link.values().none { prefs.contains(priorityKey(it)) }
            while (!restored) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                Thread.sleep(minOf(50L, remaining))
                restored = Link.values().none { prefs.contains(priorityKey(it)) }
            }
            restored
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Turns off the iPhone's HFP when [calls] and A2DP when [audio] until released; a link no
     * longer wanted is restored. Cancels a pending delayed release.
     */
    fun hold(address: String, calls: Boolean, audio: Boolean) {
        handler.post {
            handler.removeCallbacks(delayedRelease)
            val normalized = address.trim().uppercase()
            if (!BluetoothAdapterAddress.isValid(normalized)) {
                report("Bluetooth handoff skipped: invalid iPhone Bluetooth address $address")
                return@post
            }
            if (target != null && target != normalized) releaseNow("another iPhone took over")
            target = normalized
            releasePending = false
            registerReceiver()
            applyLinks(normalized, wanted(calls, audio), allowNewHolds = true)
        }
    }

    /**
     * A Bluetooth setting changed: it applies at once, as the Wi-Fi search setting does. A link
     * switched off is restored now; a link switched on is turned off while a session runs. After
     * the session ended only restoring applies, so the pending delayed release still happens.
     */
    fun settingsChanged() {
        handler.post {
            val address = target ?: return@post
            val wanted = wanted(BluetoothHandoffSettings.callsEnabled(app), BluetoothHandoffSettings.audioEnabled(app))
            report("Bluetooth handoff settings changed: calls=${Link.CALLS in wanted} audio=${Link.AUDIO in wanted}")
            applyLinks(address, wanted, allowNewHolds = !releasePending)
        }
    }

    private fun wanted(calls: Boolean, audio: Boolean): Set<Link> = buildSet {
        if (calls) add(Link.CALLS)
        if (audio) add(Link.AUDIO)
    }

    private fun applyLinks(address: String, wanted: Set<Link>, allowNewHolds: Boolean) {
        for (link in Link.values()) {
            when {
                link in wanted && link in held -> report("${link.label} to $address still off for CarPlay")
                link in wanted && allowNewHolds -> {
                    held += link
                    guards.getValue(link).reset()
                    withProxy(link) { profile -> if (link in held) turnOff(link, profile, address) }
                }
                link in wanted -> report("${link.label} to $address stays connected until the next CarPlay session")
                link in held -> {
                    held -= link
                    restoreLink(link, address)
                }
                else -> report("${link.label} to $address left connected (setting off)")
            }
        }
    }

    /**
     * Restores the links after [RELEASE_DELAY_MS] unless a session holds them again first. A longer
     * wait already pending (after startup) is kept.
     */
    fun releaseLater() {
        handler.post {
            if (target == null) return@post
            if (releasePending && releaseAt >= SystemClock.uptimeMillis() + RELEASE_DELAY_MS) return@post
            scheduleRelease(RELEASE_DELAY_MS, "no CarPlay session for ${RELEASE_DELAY_MS / 1000}s")
            report("Bluetooth handoff: restoring in ${RELEASE_DELAY_MS / 1000}s unless CarPlay reconnects")
        }
    }

    private fun scheduleRelease(delayMillis: Long, reason: String) {
        handler.removeCallbacks(delayedRelease)
        releaseAt = SystemClock.uptimeMillis() + delayMillis
        releaseReason = reason
        handler.postAtTime(delayedRelease, releaseAt)
        releasePending = true
    }

    private fun releaseNow(reason: String) {
        val address = target ?: return
        handler.removeCallbacks(delayedRelease)
        report("Bluetooth handoff: restoring links ($reason)")
        target = null
        releasePending = false
        held.clear()
        unregisterReceiver()
        for (link in Link.values()) restoreLink(link, address)
    }

    private fun turnOff(link: Link, profile: BluetoothProfile, address: String) {
        val device = remoteDevice(address) ?: return
        val savedKey = priorityKey(link)
        if (!prefs.contains(savedKey)) {
            // Off is what this class sets; reading it means an earlier run never restored, so it is
            // not the user's choice and must not be saved as the original.
            val read = call(profile, "getPriority", device) as? Int
            val original = read?.takeIf { it > PRIORITY_OFF } ?: PRIORITY_ON
            prefs.edit().putString(KEY_HELD_ADDRESS, address).putInt(savedKey, original).commit()
        }
        val off = describe(call(profile, "setPriority", device, PRIORITY_OFF))
        val state = runCatching { profile.getConnectionState(device) }.getOrDefault(-1)
        val disconnected = if (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_CONNECTING) {
            describe(call(profile, "disconnect", device))
        } else {
            "not connected"
        }
        report("${link.label} to $address off for CarPlay: priority off $off, disconnect $disconnected")
    }

    private fun disconnect(link: Link, profile: BluetoothProfile, address: String) {
        val device = remoteDevice(address) ?: return
        report("${link.label} to $address disconnect: ${describe(call(profile, "disconnect", device))}")
    }

    private fun restoreLink(link: Link, address: String) {
        val key = priorityKey(link)
        if (!prefs.contains(key)) return
        val original = prefs.getInt(key, PRIORITY_ON).takeIf { it > PRIORITY_OFF } ?: PRIORITY_ON
        withProxy(link) { profile ->
            if (link in held || !prefs.contains(key) || prefs.getString(KEY_HELD_ADDRESS, null) != address) {
                return@withProxy
            }
            val device = remoteDevice(address) ?: return@withProxy
            val priority = call(profile, "setPriority", device, original)
            val readBack = call(profile, "getPriority", device)
            if (priority != true || readBack != original) {
                report("${link.label} to $address restore pending: priority $original ${describe(priority)}, " +
                    "read back ${describe(readBack)}")
                return@withProxy
            }
            val state = runCatching { profile.getConnectionState(device) }.getOrDefault(-1)
            // The restriction is gone once the priority reads back; reconnecting is best effort; the
            // head unit or the phone reconnects on its own (some A2DP sinks reject connects they did
            // not start), so a rejected connect no longer keeps the restore pending.
            val reconnect = if (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_CONNECTING) {
                true
            } else {
                call(profile, "connect", device)
            }
            val editor = prefs.edit().remove(key)
            if (Link.values().none { other -> other != link && prefs.contains(priorityKey(other)) }) {
                editor.remove(KEY_HELD_ADDRESS)
            }
            val saved = editor.commit()
            report("${link.label} to $address restored: priority $original ok, " +
                "reconnect ${describe(reconnect)}, connection state=$state, saved=$saved")
        }
    }

    /** Calls a hidden profile method by reflection; returns its result or the failure. */
    private fun call(profile: BluetoothProfile, method: String, device: BluetoothDevice, vararg extra: Int): Any? =
        try {
            val types = arrayOf<Class<*>>(BluetoothDevice::class.java) + extra.map { Int::class.javaPrimitiveType!! }
            profile.javaClass.getMethod(method, *types).invoke(profile, device, *extra.toTypedArray())
        } catch (error: Throwable) {
            (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        }

    private fun describe(result: Any?): String = when (result) {
        true -> "ok"
        false -> "rejected"
        is Throwable -> "failed ${result.javaClass.simpleName}: ${result.message.orEmpty().take(120)}"
        else -> result.toString()
    }

    private fun withProxy(link: Link, action: (BluetoothProfile) -> Unit) {
        proxies[link]?.let {
            action(it)
            return
        }
        pending.getOrPut(link) { mutableListOf() }.add(action)
        if (!requested.add(link)) return
        val bluetooth = adapter
        if (bluetooth == null) {
            report("Bluetooth handoff unavailable: no Bluetooth adapter")
            return
        }
        val ok = runCatching {
            bluetooth.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profileId: Int, service: BluetoothProfile) {
                    handler.post {
                        proxies[link] = service
                        pending.remove(link)?.forEach { it(service) }
                    }
                }

                override fun onServiceDisconnected(profileId: Int) {
                    handler.post {
                        proxies.remove(link)
                        requested.remove(link)
                    }
                }
            }, link.profile)
        }.getOrDefault(false)
        if (!ok) {
            requested.remove(link)
            pending.remove(link)
            report("Bluetooth handoff: the head unit has no ${link.label} profile")
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply { Link.values().forEach { addAction(it.stateChanged) } }
        receiverRegistered = runCatching {
            @Suppress("DEPRECATION")
            app.registerReceiver(receiver, filter, null, handler)
        }.onFailure { report("Bluetooth reconnect watch unavailable: ${it.javaClass.simpleName}") }.isSuccess
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { app.unregisterReceiver(receiver) }
    }

    private fun remoteDevice(address: String): BluetoothDevice? =
        runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    private fun priorityKey(link: Link) = "$PRIORITY_KEY_PREFIX${link.name.lowercase()}"

    private fun report(message: String) {
        runCatching { diagnostic(message) }
    }

    companion object {
        /** Long enough for a settings save or reconnect to bring the session back. */
        const val RELEASE_DELAY_MS = 15_000L
        /** Long enough for wireless CarPlay to connect after the head unit powers on. */
        const val STARTUP_GRACE_MS = 60_000L
        private const val PRIORITY_OFF = 0
        private const val PRIORITY_ON = 100
        private const val KEY_HELD_ADDRESS = "held_address"
        private const val PRIORITY_KEY_PREFIX = "saved_priority_"

        @Volatile
        private var instance: BluetoothHandoff? = null

        fun shared(context: Context): BluetoothHandoff =
            instance ?: synchronized(this) {
                instance ?: BluetoothHandoff(context).also { instance = it }
            }

        /** Whether an earlier run left an iPhone link off; reads only the saved state. */
        fun hasLeftover(context: Context): Boolean {
            val prefs = BluetoothHandoffSettings.prefs(context)
            return prefs.all.keys.any { it.startsWith(PRIORITY_KEY_PREFIX) }
        }
    }
}

/** Disconnects at most [maxRetries] reconnects per [windowMillis], so a fight with the head unit stops. */
internal class HandoffReconnectGuard(
    private val maxRetries: Int = 3,
    private val windowMillis: Long = 60_000L,
) {
    private val attempts = ArrayDeque<Long>()

    fun allow(nowMillis: Long): Boolean {
        while (attempts.isNotEmpty() && nowMillis - attempts.first() >= windowMillis) attempts.removeFirst()
        if (attempts.size >= maxRetries) return false
        attempts.addLast(nowMillis)
        return true
    }

    fun reset() = attempts.clear()
}

internal object BluetoothAdapterAddress {
    private val MAC = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")

    fun isValid(address: String): Boolean = MAC.matches(address) && address != "00:00:00:00:00:00"
}
