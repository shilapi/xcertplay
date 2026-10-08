package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class ScreenPresentationTimeTest {
    @Test fun senderNtpTimeRetainsSecondsAndFractionWithoutLocalClock() {
        val header = ByteArray(128)
        // Independent little-endian NTP fixture: 299521 seconds plus 0.5.
        byteArrayOf(0, 0, 0, 0x80.toByte(), 1, 0x92.toByte(), 4, 0).copyInto(header, 8)
        assertEquals(299521500000L, ScreenPresentationTime.microseconds(header))
        assertNull(ScreenPresentationTime.microseconds(ByteArray(128)))
    }
}
