package com.shilapi.xcertplay.airplay.rcs.transport

import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RcsLifecycleTest {
    @Test fun peerEofReleasesListenerAndReportsLostChannel() {
        val ended = CountDownLatch(1)
        val cause = AtomicReference<Throwable?>()
        val stream = RcsDataStream.open(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
            ByteArray(32) { 1 }, ByteArray(32) { 2 })
        try {
            val port = stream.listen(object : RcsDataStreamHandler {
                override fun onStreamClosed(stream: RcsDataStream, failure: Throwable?) {
                    cause.set(failure); ended.countDown()
                }
            })
            Socket("127.0.0.1", port).use { assertTrue(stream.awaitPeerConnection(5_000)) }
            assertTrue(ended.await(5, TimeUnit.SECONDS))
            assertTrue("Peer EOF must be distinguishable from local teardown", cause.get() is EOFException)
            assertThrows(IOException::class.java) { Socket("127.0.0.1", port).close() }
        } finally { stream.close() }
    }

    @Test fun handlerFailurePreservesCauseInsteadOfReportingNormalTeardown() {
        val failure = IOException("invalid UI Sync payload")
        val ended = CountDownLatch(1)
        val cause = AtomicReference<Throwable?>()
        val stream = RcsDataStream.open(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
            ByteArray(32) { 1 }, ByteArray(32) { 2 })
        try {
            val port = stream.listen(object : RcsDataStreamHandler {
                override fun onMessage(stream: RcsDataStream, message: RcsMessage) { throw failure }
                override fun onStreamClosed(stream: RcsDataStream, failure: Throwable?) {
                    cause.set(failure); ended.countDown()
                }
            })
            RcsChannel.connect(InetSocketAddress("127.0.0.1", port),
                ByteArray(32) { 2 }, ByteArray(32) { 1 }).use { phone ->
                phone.sendComm(byteArrayOf(1))
                assertTrue(ended.await(5, TimeUnit.SECONDS))
                assertSame(failure, cause.get())
            }
        } finally { stream.close() }
    }
}
