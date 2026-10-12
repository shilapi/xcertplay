package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Replays USB completion boundaries through the real UsbSession read and USBMUX frame parser. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE,
    shadows = [EvidenceUsbConnectionShadow::class, EvidenceUsbRequestShadow::class])
class UsbMuxIssue100RegressionTest {
    @Before fun resetReplay() { UsbEvidenceReplay.reset() }

    @Test fun fullHostHandshakeAndReaderAcceptBothCapturedPaddingReplies() {
        UsbEvidenceReplay.transfers.add(versionReplyWithPadding)
        UsbEvidenceReplay.transfers.add(capturedSynAckWithPadding)
        UsbEvidenceReplay.transfers.add(normalSynAck)
        val diagnostics = java.util.concurrent.CopyOnWriteArrayList<String>()
        val host = Iap2UsbMuxHost.open(pipe(), onDiagnostic = diagnostics::add)
        try {
            val deadline = System.nanoTime() + 2_000_000_000L
            var consumed = false
            while (!consumed && System.nanoTime() < deadline) {
                consumed = synchronized(ReflectionHelpers.getField<Any>(host, "stateLock")) {
                    UsbEvidenceReplay.completedReads == 3 && frameBuffer(host).bufferedBytes == 0
                }
                if (!consumed) Thread.sleep(1)
            }
            assertTrue("The real reader must consume all three USB completions", consumed)
            val failure = synchronized(ReflectionHelpers.getField<Any>(host, "stateLock")) {
                ReflectionHelpers.getField<IphoneUsbException?>(host, "failure")
            }
            assertEquals(null, failure)
            assertEquals(listOf(20, 17), UsbEvidenceReplay.writes.map { it.size })
            assertTrue(remainder(host).isEmpty())
            assertEquals(2, diagnostics.size)
            assertTrue(diagnostics[0].contains("previousProtocol=0 previousLength=20"))
            assertTrue(diagnostics[1].contains("previousProtocol=6 previousLength=36"))
        } finally { host.close() }
    }

    @Test fun capturedVersionAndSynAckPaddingDoNotDesynchronizeTheNextFrame() {
        val host = host()
        UsbEvidenceReplay.transfers.add(versionReplyWithPadding)
        assertEquals(20, frameLength(takeFrame(host)))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), remainder(host))

        UsbEvidenceReplay.transfers.add(capturedSynAckWithPadding)
        assertEquals(36, frameLength(takeFrame(host)))
        assertArrayEquals(hex("6d 43 6f 6e"), remainder(host))

        UsbEvidenceReplay.transfers.add(normalSynAck)
        assertArrayEquals(normalSynAck.copyOfRange(16, 36), framePayload(takeFrame(host)))
        assertTrue(remainder(host).isEmpty())
    }

    @Test fun ordinaryVersionAndTcpFramesArePreserved() {
        val host = host()
        UsbEvidenceReplay.transfers.add(versionReplyWithPadding.copyOf(20))
        assertEquals(20, frameLength(takeFrame(host)))
        UsbEvidenceReplay.transfers.add(normalSynAck)
        val frame = takeFrame(host)
        assertEquals(36, frameLength(frame))
        assertArrayEquals(normalSynAck.copyOfRange(16, 36), framePayload(frame))
        assertTrue(remainder(host).isEmpty())
    }

    @Test fun fragmentedAndCoalescedValidFramesRemainIntact() {
        val host = host()
        val second = normalSynAck.copyOf().also { it[15] = 2 }
        UsbEvidenceReplay.transfers.add(normalSynAck.copyOfRange(0, 8))
        UsbEvidenceReplay.transfers.add(normalSynAck.copyOfRange(8, 21))
        UsbEvidenceReplay.transfers.add(normalSynAck.copyOfRange(21, 36) + second)
        assertArrayEquals(normalSynAck.copyOfRange(16, 36), framePayload(takeFrame(host)))
        assertArrayEquals(second, remainder(host))
        assertArrayEquals(second.copyOfRange(16, 36), framePayload(takeFrame(host)))
        assertTrue(remainder(host).isEmpty())
        assertTrue(UsbEvidenceReplay.transfers.isEmpty())
        assertEquals(3, UsbEvidenceReplay.completedReads)
    }

    @Test fun fragmentedPaddedSynAckRetainsOnlyTheObservedExtraBytes() {
        val host = host()
        UsbEvidenceReplay.transfers.add(capturedSynAckWithPadding.copyOfRange(0, 13))
        UsbEvidenceReplay.transfers.add(capturedSynAckWithPadding.copyOfRange(13, 40))
        assertArrayEquals(normalSynAck.copyOfRange(16, 36), framePayload(takeFrame(host)))
        assertArrayEquals(hex("6d 43 6f 6e"), remainder(host))
        assertEquals(2, UsbEvidenceReplay.completedReads)
    }

    @Test fun paddingWithoutANextFrameTimesOutWithoutDiscardingOrSpinning() {
        val host = host()
        UsbEvidenceReplay.transfers.add(versionReplyWithPadding)
        takeFrame(host)
        assertEquals(null, takeFrame(host, 20))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), remainder(host))
        assertEquals(1, UsbEvidenceReplay.timedOutReads)
        UsbEvidenceReplay.transfers.add(normalSynAck)
        assertEquals(36, frameLength(takeFrame(host)))
        assertTrue(remainder(host).isEmpty())
    }

    @Test fun payloadBearingTrailersPreserveReportedReplyLengthsThroughUsbReads() {
        val host = host()
        val payloads = listOf(ByteArray(4) { it.toByte() },
            ByteArray(360) { (it % 251).toByte() }, ByteArray(918) { (it % 239).toByte() })
        val replies = payloads.map(::dataReply)
        UsbEvidenceReplay.transfers.add(versionReplyWithPadding)
        UsbEvidenceReplay.transfers.add(capturedSynAckWithPadding)
        // Reproduce the residual 40+4 -> 396+4 -> 954 byte boundaries from the follow-up.
        UsbEvidenceReplay.transfers.add(replies[0] + followUpTrailer + replies[1].copyOfRange(0, 12))
        UsbEvidenceReplay.transfers.add(replies[1].copyOfRange(12, replies[1].size) + followUpTrailer)
        UsbEvidenceReplay.transfers.add(replies[2].copyOfRange(0, 27))
        UsbEvidenceReplay.transfers.add(replies[2].copyOfRange(27, replies[2].size))
        assertEquals(20, frameLength(takeFrame(host)))
        assertEquals(36, frameLength(takeFrame(host)))
        replies.indices.forEach { index ->
            val frame = takeFrame(host)
            assertEquals(replies[index].size, frameLength(frame))
            assertArrayEquals(replies[index].copyOfRange(16, replies[index].size), framePayload(frame))
        }
        assertTrue(remainder(host).isEmpty())
        assertEquals(6, UsbEvidenceReplay.completedReads)
    }

    @Test fun fullHostReaderContinuesAfterPayloadReplyTrailers() {
        val replies = listOf(dataReply(ByteArray(4) { it.toByte() }),
            dataReply(ByteArray(360) { (it % 251).toByte() }), dataReply(ByteArray(918) { (it % 239).toByte() }))
        UsbEvidenceReplay.transfers.add(versionReplyWithPadding)
        UsbEvidenceReplay.transfers.add(capturedSynAckWithPadding)
        replies.indices.forEach { index ->
            UsbEvidenceReplay.transfers.add(replies[index] + if (index < replies.lastIndex) followUpTrailer else ByteArray(0))
        }
        val diagnostics = java.util.concurrent.CopyOnWriteArrayList<String>()
        val host = Iap2UsbMuxHost.open(pipe(), onDiagnostic = diagnostics::add)
        try {
            val deadline = System.nanoTime() + 2_000_000_000L
            var consumed = false
            while (!consumed && System.nanoTime() < deadline) {
                consumed = synchronized(ReflectionHelpers.getField<Any>(host, "stateLock")) {
                    UsbEvidenceReplay.completedReads == 5 && frameBuffer(host).bufferedBytes == 0
                }
                if (!consumed) Thread.sleep(1)
            }
            assertTrue("The real reader must consume the padded payload replies", consumed)
            val failure = synchronized(ReflectionHelpers.getField<Any>(host, "stateLock")) {
                ReflectionHelpers.getField<IphoneUsbException?>(host, "failure")
            }
            assertEquals(null, failure)
            assertEquals(4, diagnostics.size)
            assertTrue(diagnostics.any { it.contains("previousLength=40") })
            assertTrue(diagnostics.any { it.contains("previousLength=396") })
        } finally { host.close() }
    }

    private fun host(): Iap2UsbMuxHost {
        return Iap2UsbMuxHost::class.java.getDeclaredConstructor(
            Iap2UsbSession::class.java, Long::class.javaPrimitiveType,
            kotlin.jvm.functions.Function1::class.java).apply {
            isAccessible = true
        }.newInstance(pipe(), 1000L, { _: String -> })
    }

    private fun pipe(): Iap2UsbSession {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
            UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        return Iap2UsbSession(connection, endpoint(0x04), endpoint(0x85))
    }

    private fun takeFrame(host: Iap2UsbMuxHost, timeoutMillis: Long = 1000L): Any? {
        try {
            return Iap2UsbMuxHost::class.java.getDeclaredMethod("takeFrame",
                Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(host, timeoutMillis)
        } catch (error: InvocationTargetException) { throw error.targetException }
    }

    private fun remainder(host: Iap2UsbMuxHost): ByteArray =
        ReflectionHelpers.getField(frameBuffer(host), "bytes")
    private fun frameBuffer(host: Iap2UsbMuxHost): UsbMuxFrameBuffer =
        ReflectionHelpers.getField(host, "receiveFrames")
    private fun frameLength(frame: Any?): Int = ReflectionHelpers.getField(frame!!, "length")
    private fun framePayload(frame: Any?): ByteArray = ReflectionHelpers.getField(frame!!, "payload")

    private companion object {
        // Issue 100 attachment line 12: 24 received bytes, declared length 20.
        val versionReplyWithPadding = hex(
            "00 00 00 00 00 00 00 14 00 00 00 02 00 00 00 00 49 28 73 00 00 00 00 00")
        // Attachment lines 59-61: actual_length 40, declared length 36; no invented payloads.
        val capturedSynAckWithPadding = hex(
            "00 00 00 06 00 00 00 24 fa ce fa ce 00 00 00 01 " +
                "f2 7e 00 01 00 00 00 00 00 00 00 01 5f 12 02 00 7f 00 00 01 6d 43 6f 6e")
        val normalSynAck = capturedSynAckWithPadding.copyOf(36)
        val followUpTrailer = hex("00 00 01 68")
        // Follow-up payload contents are not supplied; preserve synthetic bytes with its
        // declared lengths and the real captured TCP header (including low data-offset bits).
        fun dataReply(payload: ByteArray): ByteArray = (normalSynAck + payload).also { frame ->
            val length = frame.size
            for (i in 0..3) frame[4 + i] = (length ushr (24 - i * 8)).toByte()
            frame[29] = 0x10
        }
        fun hex(value: String): ByteArray = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    }
}

/** Supplies exact USB completion chunks; framing remains entirely in production code. */
object UsbEvidenceReplay {
    val transfers = ArrayDeque<ByteArray>()
    val writes = mutableListOf<ByteArray>()
    var request: UsbRequest? = null
    var buffer: ByteBuffer? = null
    var cancelled = false
    var completedReads = 0
    var timedOutReads = 0
    fun reset() {
        transfers.clear(); writes.clear()
        request = null; buffer = null; cancelled = false; completedReads = 0; timedOutReads = 0
    }
}

@Implements(UsbDeviceConnection::class)
class EvidenceUsbConnectionShadow {
    @Implementation fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray,
        length: Int, timeoutMillis: Int): Int {
        UsbEvidenceReplay.writes.add(buffer.copyOf(length))
        return length
    }
    @Implementation fun requestWait(timeoutMillis: Long): UsbRequest {
        if (UsbEvidenceReplay.cancelled) return UsbEvidenceReplay.request!!
        val bytes = UsbEvidenceReplay.transfers.pollFirst() ?: run {
            Thread.sleep(timeoutMillis.coerceAtMost(50))
            UsbEvidenceReplay.timedOutReads++
            throw TimeoutException()
        }
        UsbEvidenceReplay.buffer!!.put(bytes)
        UsbEvidenceReplay.completedReads++
        return UsbEvidenceReplay.request!!
    }
    @Implementation fun close() = Unit
}

@Implements(UsbRequest::class)
class EvidenceUsbRequestShadow {
    @RealObject lateinit var request: UsbRequest
    @Implementation fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint) = true
    @Implementation fun queue(buffer: ByteBuffer): Boolean {
        UsbEvidenceReplay.request = request
        UsbEvidenceReplay.buffer = buffer
        UsbEvidenceReplay.cancelled = false
        return true
    }
    @Implementation fun cancel(): Boolean { UsbEvidenceReplay.cancelled = true; return true }
    @Implementation fun close() = Unit
}
