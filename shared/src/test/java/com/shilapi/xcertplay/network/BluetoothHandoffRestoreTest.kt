package com.shilapi.xcertplay.network

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Looper
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BluetoothHandoffRestoreTest {
    private lateinit var app: Application
    private lateinit var handoff: BluetoothHandoff
    private lateinit var calls: TestProfile
    private lateinit var audio: TestProfile

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        BluetoothHandoffSettings.prefs(app).edit().clear().commit()
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        calls = TestProfile()
        audio = TestProfile()
        shadowOf(adapter).setProfileProxy(16, calls)
        shadowOf(adapter).setProfileProxy(11, audio)
        handoff = ReflectionHelpers.callConstructor(
            BluetoothHandoff::class.java, ClassParameter.from(Context::class.java, app),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun queuedHoldIsReleasedBeforeExitReportsSuccess() {
        handoff.hold(ADDRESS, calls = true, audio = false)
        assertTrue(restore(checkWaitingForMain = true))
        assertEquals(100, calls.priority)
        assertFalse(prefs().contains("held_address"))
    }

    @Test
    fun rejectedPriorityRestoreRemainsPending() {
        hold()
        calls.rejectRestore = true
        assertFalse(restore())
        assertEquals(0, calls.priority)
        assertTrue(prefs().contains("saved_priority_calls"))
        assertEquals(ADDRESS, prefs().getString("held_address", null))
    }

    @Test
    fun acceptedSetterWithAnUnchangedPriorityRemainsPending() {
        hold()
        calls.ignoreRestore = true
        assertFalse(restore())
        assertTrue(prefs().contains("saved_priority_calls"))
        assertEquals(0, calls.connects)
    }

    @Test
    fun rejectedReconnectStillCountsAsRestoredOncePriorityReadsBack() {
        // BYD's A2DP sink rejects connects it did not start; the restriction is already gone once
        // the priority reads back, so exiting must not wait for a reconnect that never comes.
        hold()
        calls.acceptConnect = false
        assertTrue(restore())
        assertEquals(100, calls.priority)
        assertEquals(1, calls.connects)
        assertFalse(prefs().contains("saved_priority_calls"))
    }

    @Test
    fun alreadyConnectedPhoneDoesNotNeedAnotherConnectRequest() {
        hold()
        calls.state = BluetoothProfile.STATE_CONNECTED
        calls.acceptConnect = false
        assertTrue(restore())
        assertEquals(0, calls.connects)
    }

    @Test
    fun switchingASettingOffDuringASessionRestoresThatLinkAtOnce() {
        handoff.hold(ADDRESS, calls = true, audio = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, calls.priority)
        assertEquals(0, audio.priority)

        setting(calls = false, audio = true)

        assertEquals(100, calls.priority)
        assertEquals(1, calls.connects)
        assertFalse(prefs().contains("saved_priority_calls"))
        assertEquals("Music stays off", 0, audio.priority)
        assertEquals(ADDRESS, prefs().getString("held_address", null))
    }

    @Test
    fun switchingASettingOnDuringASessionTurnsThatLinkOff() {
        handoff.hold(ADDRESS, calls = false, audio = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(100, calls.priority)

        setting(calls = true, audio = false)

        assertEquals(0, calls.priority)
        assertTrue(prefs().contains("saved_priority_calls"))
    }

    @Test
    fun afterTheSessionEndedASettingOnlyRestoresAndTheDelayedReleaseStillRuns() {
        handoff.hold(ADDRESS, calls = true, audio = false)
        shadowOf(Looper.getMainLooper()).idle()
        handoff.releaseLater()
        shadowOf(Looper.getMainLooper()).idle()

        setting(calls = false, audio = true)
        assertEquals("Switched off: restored now, not after 15 s", 100, calls.priority)
        assertEquals("No new hold once the session ended", 100, audio.priority)

        setting(calls = true, audio = true)
        assertEquals(100, calls.priority)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BluetoothHandoff.RELEASE_DELAY_MS))
        assertFalse(prefs().contains("held_address"))
    }

    @Test
    fun aSettingChangeWithoutASessionChangesNothing() {
        setting(calls = false, audio = false)
        assertEquals(100, calls.priority)
        assertEquals(0, calls.connects)
    }

    @Test
    fun linksLeftOffByAKilledRunWaitForCarPlayBeforeTheyAreRestored() {
        leaveCallsOff()
        newHandoff()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Still off right after startup", 0, calls.priority)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BluetoothHandoff.STARTUP_GRACE_MS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(100, calls.priority)
        assertEquals(1, calls.connects)
        assertFalse(prefs().contains("held_address"))
    }

    @Test
    fun carPlayConnectingDuringTheStartupGraceKeepsTheLeftoverLinkOff() {
        leaveCallsOff()
        val restarted = newHandoff()
        restarted.hold(ADDRESS, calls = true, audio = false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2 * BluetoothHandoff.STARTUP_GRACE_MS))
        assertEquals(0, calls.priority)
        assertEquals(0, calls.connects)
        assertTrue(prefs().contains("saved_priority_calls"))
    }

    @Test
    fun releaseLaterDoesNotShortenTheStartupGrace() {
        leaveCallsOff()
        val restarted = newHandoff()
        shadowOf(Looper.getMainLooper()).idle()
        restarted.releaseLater()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BluetoothHandoff.RELEASE_DELAY_MS + 1_000))
        assertEquals(0, calls.priority)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BluetoothHandoff.STARTUP_GRACE_MS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(100, calls.priority)
    }

    @Test
    fun powerOnWithoutAutoStartRestoresTheLeftoverAtOnce() {
        leaveCallsOff()
        handoff = newHandoff()
        assertTrue(restore())
        assertEquals(100, calls.priority)
        assertFalse(prefs().contains("held_address"))
    }

    @Test
    fun aLeftoverIsFoundFromTheSavedStateAlone() {
        assertFalse(BluetoothHandoff.hasLeftover(app))
        leaveCallsOff()
        assertTrue(BluetoothHandoff.hasLeftover(app))
    }

    /** What a run killed by the head unit's power-off leaves: the priority off and its original saved. */
    private fun leaveCallsOff() {
        prefs().edit().putString("held_address", ADDRESS).putInt("saved_priority_calls", 100).commit()
        calls.priority = 0
    }

    private fun newHandoff(): BluetoothHandoff = ReflectionHelpers.callConstructor(
        BluetoothHandoff::class.java, ClassParameter.from(Context::class.java, app),
    )

    private fun setting(calls: Boolean, audio: Boolean) {
        prefs().edit().putBoolean("disconnect_calls", calls).putBoolean("disconnect_audio", audio).commit()
        handoff.settingsChanged()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun hold() {
        handoff.hold(ADDRESS, calls = true, audio = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, calls.priority)
    }

    private fun prefs() = BluetoothHandoffSettings.prefs(app)

    private fun restore(checkWaitingForMain: Boolean = false): Boolean {
        val result = AtomicReference<Boolean?>()
        val failure = AtomicReference<Throwable?>()
        val started = CountDownLatch(1)
        val worker = thread(isDaemon = true) {
            started.countDown()
            try {
                result.set(handoff.releaseNowBlocking(500))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        started.await()
        if (checkWaitingForMain) {
            worker.join(30)
            assertTrue("Exit must wait for its main-thread release request", worker.isAlive)
            assertNull(result.get())
        }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (worker.isAlive && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(2)
        }
        if (worker.isAlive) worker.interrupt()
        worker.join(500)
        failure.get()?.let { throw AssertionError(it) }
        return requireNotNull(result.get()) { "Bluetooth restore did not finish" }
    }

    /** Public methods stand in for BYD's hidden Bluetooth profile API. */
    class TestProfile : BluetoothProfile {
        var priority = 100
        var state = BluetoothProfile.STATE_DISCONNECTED
        var connects = 0
        var rejectRestore = false
        var ignoreRestore = false
        var acceptConnect = true

        fun getPriority(device: BluetoothDevice): Int = priority
        fun setPriority(device: BluetoothDevice, value: Int): Boolean {
            if (value > 0 && rejectRestore) return false
            if (value == 0 || !ignoreRestore) priority = value
            return true
        }
        fun connect(device: BluetoothDevice): Boolean { connects++; return acceptConnect }
        fun disconnect(device: BluetoothDevice): Boolean { state = BluetoothProfile.STATE_DISCONNECTED; return true }
        override fun getConnectionState(device: BluetoothDevice): Int = state
        override fun getConnectedDevices(): List<BluetoothDevice> = emptyList()
        override fun getDevicesMatchingConnectionStates(states: IntArray): List<BluetoothDevice> = emptyList()
    }

    private companion object {
        const val ADDRESS = "14:1A:97:73:B3:F6"
    }
}
