package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pCreateRetryTest {
    @Test
    fun busyIsRetriedWithGrowingDelays() {
        for (retries in 0 until P2pCreateRetry.MAX_BUSY_RETRIES) {
            assertTrue(P2pCreateRetry.shouldRetry(WifiP2pManager.BUSY, retries))
        }
        assertEquals(listOf(500L, 1000L, 1500L), (1..3).map(P2pCreateRetry::busyDelayMillis))
    }

    @Test
    fun busyStopsAfterTheLastRetry() {
        assertFalse(P2pCreateRetry.shouldRetry(WifiP2pManager.BUSY, P2pCreateRetry.MAX_BUSY_RETRIES))
    }

    @Test
    fun otherRejectionsAreNotRetried() {
        assertFalse(P2pCreateRetry.shouldRetry(WifiP2pManager.ERROR, 0))
        assertFalse(P2pCreateRetry.shouldRetry(WifiP2pManager.P2P_UNSUPPORTED, 0))
    }
}
