package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RcsBackpressureProbeTest {
    @Test fun boundedProbeRepeatsTheSameFrameAndReportsActualBytes() {
        val message = RcsMessage.comm(ByteArray(512))
        val target = 1_048_577
        val logs = mutableListOf<String>()
        var count = 0
        RcsBackpressureProbe.run(message, target, {
            assertSame(message, it); count++
        }, { fail("Unexpected timeout") }, logs::add)
        val bytes = count * message.encoded().size
        assertTrue(bytes >= target)
        assertTrue(bytes - message.encoded().size < target)
        assertTrue(logs.last().contains("complete writtenPlainBytes=$bytes"))
    }

    @Test fun stalledWriteTimesOutOnceAndDoesNotReportSuccess() {
        val released = CountDownLatch(1)
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        var timeoutCount = 0
        try {
            RcsBackpressureProbe.run(RcsMessage.comm(ByteArray(512)), 1_048_577,
                { assertTrue(released.await(2, TimeUnit.SECONDS)) },
                { timeoutCount++; released.countDown() }, logs::add, timeoutMillis = 50)
            fail("A timed out write must fail the probe")
        } catch (_: IOException) { }
        assertEquals(1, timeoutCount)
        assertTrue(logs.any { it.contains("probe timeout") })
        assertFalse(logs.any { it.contains("probe complete") })
    }
}
