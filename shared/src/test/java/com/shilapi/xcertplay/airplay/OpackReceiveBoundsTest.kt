package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.opack.OpackException
import org.junit.Assert.*
import org.junit.Test

class OpackReceiveBoundsTest {
    @Test fun largeNSDataUsesThe32BitLengthTagInsteadOfThe64BitTag() {
        val data = ByteArray(65_536) { (it % 251).toByte() }
        val encoded = OpackCodec.encode(data)
        assertEquals(0x93, encoded[0].toInt() and 0xff)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0), encoded.copyOfRange(1, 5))
        assertArrayEquals(data, OpackCodec.decode(encoded) as ByteArray)
        // Independent canonical OPACK NSData fixture, not produced by this encoder.
        assertArrayEquals(data, OpackCodec.decode(byteArrayOf(0x93.toByte(), 0, 0, 1, 0) + data) as ByteArray)
    }
    @Test fun deeplyNestedReceivePayloadFailsWithProtocolErrorBeforeStackExhaustion() {
        val bytes = ByteArray(81) { if (it == 80) 4 else 0xd1.toByte() }
        assertThrows(OpackException::class.java) { OpackCodec.decode(bytes) }
    }

    @Test fun ordinaryNestedVehicleAndThemeMetadataStillDecode() {
        val value = mapOf("a" to listOf(mapOf("b" to listOf(1L, 2L))))
        assertEquals(value, OpackCodec.decode(OpackCodec.encode(value)))
    }
}
