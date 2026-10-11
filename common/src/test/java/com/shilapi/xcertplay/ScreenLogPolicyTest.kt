package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class ScreenLogPolicyTest {
    @Test fun screenKeepsProgressWithoutTraceOrProtocolBodies() {
        assertNull(ScreenLogPolicy.summary("TRACE MEDIA VIDEO wireHex=00ff"))
        assertEquals("IAP2 RX [wired] 0x4300 Availability frame=12B",
            ScreenLogPolicy.summary("IAP2 RX [wired] 0x4300 Availability frame=12B\n  raw-body=00ff\n  details=..."))
        assertEquals("airplay SETUP stream type=110",
            ScreenLogPolicy.summary("airplay SETUP stream type=110 payload={type=110, key=[B@123}"))
        assertEquals("AirPlay session active", ScreenLogPolicy.summary("AirPlay session active"))
        assertTrue(ScreenLogPolicy.summary("long " + "x".repeat(2000))!!.length <= 483)
    }
}
