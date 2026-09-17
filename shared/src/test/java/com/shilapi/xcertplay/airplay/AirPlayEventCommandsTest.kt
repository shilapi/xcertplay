package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayEventCommandsTest {
    @Test
    fun iapSendMessageWrapsRawIap2InBinaryPlistCommandShape() {
        val rawIap2 = byteArrayOf(0x40, 0x40, 0x01, 0x02, 0x03)

        val command = AirPlayEventCommands.iapSendMessage(rawIap2)
        @Suppress("UNCHECKED_CAST")
        val decoded = BplistCodec.decode(BplistCodec.encode(command)) as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val params = decoded["params"] as Map<*, *>

        assertEquals("iAPSendMessage", decoded["type"])
        assertArrayEquals(rawIap2, params["data"] as ByteArray)
    }
}
