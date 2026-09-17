package com.shilapi.xcertplay.airplay.rcs.opack

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpackCodecTest {
    @Test
    fun encodesKnownBaaIntermediateVector() {
        val intermediate = ByteArray(16) { it.toByte() }
        val expected = byteArrayOf(
            0xe1.toByte(),
            0x44,
            'b'.code.toByte(),
            'a'.code.toByte(),
            'I'.code.toByte(),
            'C'.code.toByte(),
            0x80.toByte(),
        ) + intermediate

        assertArrayEquals(expected, OpackCodec.encode(linkedMapOf("baIC" to intermediate)))
        @Suppress("UNCHECKED_CAST")
        val decoded = OpackCodec.decode(expected) as Map<Any?, Any?>
        assertArrayEquals(intermediate, decoded["baIC"] as ByteArray)
    }

    @Test
    fun roundTripsScalarsCollectionsAndReferences() {
        val encoded = OpackCodec.encode(
            linkedMapOf<String, Any?>(
                "null" to null,
                "true" to true,
                "small" to 12L,
                "negative" to -7L,
                "float" to 1.5f,
                "double" to -2.25,
                "string" to "CarPlay Ultra",
                "data" to byteArrayOf(1, 2, 3),
                "array" to listOf(4L, false, null),
                "dictionary" to linkedMapOf("nested" to 9L),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val decoded = OpackCodec.decode(encoded) as Map<Any?, Any?>
        assertEquals(null, decoded["null"])
        assertEquals(true, decoded["true"])
        assertEquals(12L, decoded["small"])
        assertEquals(-7L, decoded["negative"])
        assertEquals(1.5f, decoded["float"])
        assertEquals(-2.25, decoded["double"])
        assertEquals("CarPlay Ultra", decoded["string"])
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded["data"] as ByteArray)
        assertEquals(listOf(4L, false, null), decoded["array"])
        assertEquals(linkedMapOf<Any?, Any?>("nested" to 9L), decoded["dictionary"])
        assertEquals(listOf("a", "a"), OpackCodec.decode(byteArrayOf(0xd2.toByte(), 0x41, 0x61, 0xa0.toByte())))
    }

    @Test
    fun rejectsUnknownTagsExplicitly() {
        val failure = assertThrows(OpackException::class.java) {
            OpackCodec.decode(byteArrayOf(0x00))
        }

        assertEquals(true, failure.message!!.contains("Unknown OPACK tag 0x00"))
    }
}
