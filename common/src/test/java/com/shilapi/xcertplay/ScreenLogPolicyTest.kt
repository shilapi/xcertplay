package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class ScreenLogPolicyTest {
    @Test fun screenHidesTraceAndProtocolBodyButKeepsProgress() {
        assertNull(ScreenLogPolicy.summary("TRACE wired MFi4 RX frameHex=00ff"))
        assertNull(ScreenLogPolicy.summary("TRACE MEDIA VIDEO payloadHex=00ff"))
        assertEquals("IAP2 RX message=0x4300", ScreenLogPolicy.summary("IAP2 RX message=0x4300\nframeHex=00ff\nfield=secret"))
        assertEquals("AirPlay session active", ScreenLogPolicy.summary("AirPlay session active"))
    }
}
