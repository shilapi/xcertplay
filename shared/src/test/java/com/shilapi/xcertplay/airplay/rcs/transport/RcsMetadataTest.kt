package com.shilapi.xcertplay.airplay.rcs.transport

import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.net.Socket

class RcsMetadataTest {
    @Test fun transportReplyFollowsBusinessDeliveryAndPreservesCorrelation() {
        val entered = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val stream = RcsDataStream.open(RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            ByteArray(32) { 1 }, ByteArray(32) { 2 })
        try {
            val port = stream.listen(object : RcsDataStreamHandler {
                override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
                    entered.countDown()
                    check(completed.await(5000, TimeUnit.MILLISECONDS))
                }
            })
            RcsChannel.connect(InetSocketAddress("127.0.0.1", port),
                ByteArray(32) { 2 }, ByteArray(32) { 1 }).use { phone ->
                val request = ApTransportPackageTest.hex(
                    "0000002373796e630102030405060708636f6d6d111213141516171800000000010203")
                phone.send(RcsMessage.fromPackage(ApTransportPackageCodec.decode(request)))
                assertTrue(entered.await(5000, TimeUnit.MILLISECONDS))
                assertNull("Delivery is not complete until the handler returns", phone.receiveNext(100))
                completed.countDown()
                val reply = phone.receiveNext(5000) ?: error("Missing transport reply")
                val expected = ApTransportPackageTest.hex(
                    "0000002072706c790000000000000000636f6d6d111213141516171800000000")
                assertArrayEquals(expected, reply.encoded())
                assertTrue(reply.body.isEmpty())
            }
        } finally { completed.countDown(); stream.close() }
    }

    @Test fun encryptedChannelPreservesSynchronousRequestMetadata() {
        val request = ApTransportPackageTest.hex(
            "0000002373796e630102030405060708636f6d6d111213141516171800000000010203")
        val listener = RcsChannel.listen(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        try {
            Socket("127.0.0.1", listener.port).use { peer ->
                listener.accept(5000)!!.use { receiver ->
                    peer.getOutputStream().write(RcsFrameCodec.duplex(ByteArray(32) { 2 },
                        ByteArray(32) { 1 }).encrypt(request))
                    assertArrayEquals(request, receiver.receiveNext(5000)!!.encoded())
                }
            }
        } finally { listener.close() }
    }
}
