package com.shilapi.xcertplay.network

import android.net.wifi.WifiManager.LocalOnlyHotspotCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOnlyHotspotRetryTest {
    @Test
    fun aRadioStillReleasingIsAskedAgainWithGrowingDelays() {
        for (retries in 0 until LocalOnlyHotspotRetry.MAX_RETRIES) {
            assertTrue(LocalOnlyHotspotRetry.shouldRetry(LocalOnlyHotspotCallback.ERROR_GENERIC, retries))
            assertTrue(LocalOnlyHotspotRetry.shouldRetry(LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE, retries))
        }
        assertFalse(
            LocalOnlyHotspotRetry.shouldRetry(LocalOnlyHotspotCallback.ERROR_GENERIC, LocalOnlyHotspotRetry.MAX_RETRIES),
        )
        assertEquals(listOf(500L, 1000L, 1500L), (1..3).map(LocalOnlyHotspotRetry::delayMillis))
    }

    @Test
    fun failuresThatWaitingCannotFixAreNotRetried() {
        assertFalse(LocalOnlyHotspotRetry.shouldRetry(LocalOnlyHotspotCallback.ERROR_NO_CHANNEL, 0))
        assertFalse(LocalOnlyHotspotRetry.shouldRetry(LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED, 0))
    }
}
