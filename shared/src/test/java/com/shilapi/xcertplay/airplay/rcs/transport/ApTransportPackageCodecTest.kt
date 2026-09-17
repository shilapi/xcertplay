package com.shilapi.xcertplay.airplay.rcs.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ApTransportPackageCodecTest {
    @Test
    fun decodesFragmentedAndConcatenatedPackages() {
        val first = ApTransportPackageCodec.comm(byteArrayOf(1, 2, 3))
        val second = ApTransportPackageCodec.comm(byteArrayOf(4, 5))
        val encoded = first.encoded() + second.encoded()

        val fragmented = ApTransportPackageCodec.decodeAvailable(encoded.copyOfRange(0, 20))
        assertTrue(fragmented.packages.isEmpty())
        assertEquals(20, fragmented.remainder.size)

        val complete = ApTransportPackageCodec.decodeAvailable(encoded)
        assertEquals(2, complete.packages.size)
        assertEquals(ApTransportPackageCodec.MESSAGE_TYPE_COMM, complete.packages[0].messageType)
        assertArrayEquals(byteArrayOf(1, 2, 3), complete.packages[0].body)
        assertArrayEquals(byteArrayOf(4, 5), complete.packages[1].body)
        assertEquals(0, complete.remainder.size)
    }

    @Test
    fun rejectsDeclaredSizeSmallerThanHeader() {
        val header = ByteArray(ApTransportPackageCodec.HEADER_SIZE)
        header[3] = 31

        val failure = assertThrows(ApTransportProtocolException::class.java) {
            ApTransportPackageCodec.decodeAvailable(header)
        }

        assertTrue(failure.message!!.contains("smaller than header"))
    }

    @Test
    fun preservesUninterpretedHeaderMetadata() {
        val template = ByteArray(ApTransportPackageCodec.HEADER_SIZE) { it.toByte() }
        val packageValue = ApTransportPackageCodec.comm(byteArrayOf(9), template)
        val encoded = packageValue.encoded()

        assertEquals(33, encoded.size)
        assertEquals(4, encoded[4].toInt())
        assertEquals(ApTransportPackageCodec.MESSAGE_TYPE_COMM, packageValue.messageType)
        assertArrayEquals(byteArrayOf(9), ApTransportPackageCodec.decode(encoded).body)
    }
}
