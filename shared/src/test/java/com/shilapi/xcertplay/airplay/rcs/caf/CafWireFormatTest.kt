package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import org.junit.Assert.*
import org.junit.Test

class CafWireFormatTest {
    @Test
    fun ultra3RawOpackConfigRequestIsDecoded() {
        val envelope = CafRcsCodec.decode(ULTRA3_CONFIG_REQUEST)

        assertEquals(0L, envelope.pluginId)
        assertEquals(CafCommand.CONFIG_REQUEST, envelope.message.command)
        assertEquals(5800410672642552193L, envelope.message.transactionId)
        assertEquals(CafPayloadEncoding.LEGACY_OPACK, envelope.encoding)
        assertEquals(CafPayloadFraming.DIRECT, envelope.framing)
    }

    @Test
    fun rawBinaryPlistEnvelopeIsDecoded() {
        val body = BplistCodec.encode(mapOf(
            "pluginID" to 7L,
            "pluginData" to BplistCodec.encode(mapOf(
                "command" to "configRequest",
                "transactionID" to 42L,
            )),
        ))

        val envelope = CafRcsCodec.decode(body)
        assertEquals(7L, envelope.pluginId)
        assertEquals(42L, envelope.message.transactionId)
        assertEquals(CafPayloadEncoding.BINARY_PLIST, envelope.encoding)
        assertEquals(CafPayloadFraming.DIRECT, envelope.framing)
    }

    @Test
    fun reencodingObservedRequestPreservesRawOpackEnvelope() {
        val envelope = CafRcsCodec.decode(ULTRA3_CONFIG_REQUEST)
        val encoded = CafRcsCodec.encode(envelope).rcsBody
        val outer = OpackCodec.decode(encoded) as Map<*, *>
        val inner = OpackCodec.decode(outer["pluginData"] as ByteArray) as Map<*, *>

        assertEquals(0L, outer["pluginID"])
        assertFalse(outer.containsKey("params"))
        assertEquals("configRequest", inner["command"])
        assertEquals(5800410672642552193L, inner["transactionID"])
    }

    @Test
    fun commandWrappersRemainCompatibleWithBothBlobEncodings() {
        for (opack in listOf(false, true)) {
            fun blob(value: Any?): ByteArray = if (opack) OpackCodec.encode(value) else BplistCodec.encode(value)
            val outer = blob(mapOf("pluginID" to 7L, "pluginData" to blob(mapOf(
                "command" to "configRequest", "transactionID" to 42L,
            ))))
            val body = BplistCodec.encode(mapOf("params" to mapOf("data" to outer)))
            val envelope = CafRcsCodec.decode(body)
            assertEquals(CafPayloadFraming.COMMAND_PARAMS_DATA, envelope.framing)
            assertEquals(7L, envelope.pluginId)
            assertEquals(42L, envelope.message.transactionId)

            val encodedWrapper = BplistCodec.decode(CafRcsCodec.encode(envelope).rcsBody) as Map<*, *>
            val params = encodedWrapper["params"] as Map<*, *>
            assertArrayEquals(outer, params["data"] as ByteArray)
        }
    }

    @Test
    fun malformedRawBodiesAndConflictingFramingsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CafRcsCodec.decode(ULTRA3_CONFIG_REQUEST.copyOf(ULTRA3_CONFIG_REQUEST.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CafRcsCodec.decode(ULTRA3_CONFIG_REQUEST + byteArrayOf(0))
        }
        assertThrows(CafProtocolException::class.java) {
            CafRcsCodec.decode(OpackCodec.encode(mapOf("pluginID" to 0L, "pluginData" to "not data")))
        }
        assertThrows(CafProtocolException::class.java) {
            CafRcsCodec.decode(OpackCodec.encode(mapOf(
                "pluginID" to -1L, "pluginData" to byteArrayOf(),
            )))
        }
        assertThrows(CafProtocolException::class.java) {
            CafRcsCodec.decode(BplistCodec.encode(mapOf(
                "params" to mapOf("data" to ULTRA3_CONFIG_REQUEST), "pluginID" to 1L,
            )))
        }
    }

    companion object {
        // ultra3.log line 1407: original decrypted RCS comm body, not produced by our encoder.
        val ULTRA3_CONFIG_REQUEST: ByteArray = (
            "e248706c7567696e4944084a706c7567696e44617461912e" +
                "e247636f6d6d616e644d636f6e66696752657175657374" +
                "4d7472616e73616374696f6e49443381713e5ec6327f50"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
