package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothHandoffTest {
    @Test
    fun reconnectsAreDisconnectedAgainOnlyWithinTheLimit() {
        val guard = HandoffReconnectGuard(maxRetries = 3, windowMillis = 60_000L)
        assertTrue(guard.allow(0))
        assertTrue(guard.allow(1_000))
        assertTrue(guard.allow(2_000))
        assertFalse(guard.allow(3_000))
        // The oldest attempt leaves the window, so one more is allowed.
        assertTrue(guard.allow(60_000))
        guard.reset()
        assertTrue(guard.allow(60_001))
    }

    @Test
    fun onlyRealBluetoothAddressesAreHeld() {
        assertTrue(BluetoothAdapterAddress.isValid("14:1A:97:73:B3:F6"))
        assertFalse(BluetoothAdapterAddress.isValid("00:00:00:00:00:00"))
        assertFalse(BluetoothAdapterAddress.isValid("not-an-address"))
    }
}
