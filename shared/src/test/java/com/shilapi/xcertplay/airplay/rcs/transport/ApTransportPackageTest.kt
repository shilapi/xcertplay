package com.shilapi.xcertplay.airplay.rcs.transport

import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import org.junit.Assert.*
import org.junit.Test

class ApTransportPackageTest {
    @Test
    fun capturedUltra5RequestPreservesItsTransportHeader() {
        val packet = hex(ULTRA5_CONFIG_REQUEST)
        val decoded = ApTransportPackageCodec.decode(packet)
        assertArrayEquals(packet, decoded.encoded())
        assertArrayEquals(hex("6173796e"), packet.copyOfRange(4, 8))
        assertEquals(70, decoded.body.size)
    }

    @Test
    fun outboundCommUsesTheObservedAsynchronousHeader() {
        // Compare actual output to independent wire bytes, not the same codec's decoder.
        val body = byteArrayOf(1, 2, 3)
        val expected = hex("000000236173796e0000000000000000636f6d6d000000000000000000000000010203")
        assertArrayEquals(expected, RcsMessage.comm(body).encoded())
        assertArrayEquals(expected, ApTransportPackageCodec.comm(body).encoded())
    }

    @Test
    fun explicitHeaderTemplatePreservesItsTypeAndTransportMetadata() {
        val header = hex("0000002073796e630102030405060708636f6d6d1112131415161718ffffffce")
        val expected = hex("0000002373796e630102030405060708636f6d6d1112131415161718ffffffce010203")
        assertArrayEquals(expected,
            ApTransportPackageCodec.comm(byteArrayOf(1, 2, 3), header).encoded())
        assertArrayEquals(hex("0000002073796e630102030405060708636f6d6d1112131415161718ffffffce"), header)
    }

    @Test
    fun outboundSyncProbeOnlyChangesThePackageType() {
        val body = byteArrayOf(1, 2, 3)
        val expected = hex("0000002373796e630000000000000000636f6d6d000000000000000000000000010203")
        assertArrayEquals(expected, RcsMessage.sync(body).encoded())
        assertArrayEquals(expected, ApTransportPackageCodec.sync(body).encoded())
        assertEquals(ApTransportPackageCodec.PACKAGE_TYPE_SYNC,
            RcsMessage.sync(body).packageType)
    }

    @Test
    fun replyCorrelationMetadataIsOnlyExposedForReplyPackages() {
        val header = hex("0000002073796e630000000000000000636f6d6d1122334455667788ffffffce")
        val request = ApTransportPackageCodec.create(
            ApTransportPackageCodec.MESSAGE_TYPE_COMM, byteArrayOf(1, 2, 3), header)
        val reply = RcsMessage.fromPackage(ApTransportPackageCodec.reply(request, status = 7))
        assertEquals(ApTransportPackageCodec.PACKAGE_TYPE_REPLY, reply.packageType)
        assertEquals(0x1122334455667788L, reply.replyToken)
        assertEquals(7, reply.replyStatus)
        assertNull(RcsMessage.comm(byteArrayOf(1, 2, 3)).replyToken)
        assertNull(RcsMessage.sync(byteArrayOf(1, 2, 3)).replyStatus)
    }

    companion object {
        // ultra5.log line 755: complete decrypted APTransport packet from the phone.
        const val ULTRA5_CONFIG_REQUEST =
            "000000666173796e0000000000000000636f6d6d000000000000000000000000" +
                "e248706c7567696e4944084a706c7567696e44617461912e" +
                "e247636f6d6d616e644d636f6e66696752657175657374" +
                "4d7472616e73616374696f6e4944333b9c2c86bd9c0b3d"

        fun hex(value: String): ByteArray =
            value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
