package com.shilapi.xcertplay.airplay.rcs

import com.shilapi.xcertplay.airplay.rcs.transport.RcsFrameCodec
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RcsChannelTest {
    @Test
    fun encryptedFrameUsesLengthHeaderAndDirectionalCounters() {
        val serverReadKey = ByteArray(32) { 1 }
        val serverWriteKey = ByteArray(32) { 2 }
        val sender = RcsFrameCodec.duplex(serverWriteKey, serverReadKey)
        val receiver = RcsFrameCodec.duplex(serverReadKey, serverWriteKey)
        val plaintext = byteArrayOf(1, 2, 3, 4)

        val first = sender.encrypt(plaintext)
        assertEquals(plaintext.size + 18, first.size)
        assertEquals(plaintext.size, (first[0].toInt() and 0xff) or ((first[1].toInt() and 0xff) shl 8))

        val decoded = receiver.decrypt(first)
        assertArrayEquals(plaintext, decoded.data)
        assertEquals(0, decoded.rest.size)

        val second = sender.encrypt(byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), receiver.decrypt(second).data)
    }

    @Test
    fun listenerAndConnectedChannelExchangeSemanticMessages() {
        val serverReadKey = ByteArray(32) { 3 }
        val serverWriteKey = ByteArray(32) { 4 }
        val listener = RcsChannel.listen(
            readKey = serverReadKey,
            writeKey = serverWriteKey,
            bindAddress = InetAddress.getByName("127.0.0.1"),
        )
        val client = RcsChannel.connect(
            remote = InetSocketAddress(InetAddress.getByName("127.0.0.1"), listener.port),
            readKey = serverWriteKey,
            writeKey = serverReadKey,
        )
        val server = listener.accept(2_000)
        assertNotNull(server)

        try {
            client.sendComm(byteArrayOf(7, 8, 9))
            val received = server!!.receiveNext(2_000)
            assertNotNull(received)
            assertArrayEquals(byteArrayOf(7, 8, 9), received!!.body)

            server.sendComm(byteArrayOf(10, 11))
            assertArrayEquals(byteArrayOf(10, 11), client.receiveNext(2_000)!!.body)
        } finally {
            server?.close()
            client.close()
            listener.close()
        }
    }
}
