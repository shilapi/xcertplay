package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayBonjourTest {
    private val config = AirPlayConfig(
        deviceName = "xcertplay",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        model = "LIVI",
    )
    private val identity = AirPlayIdentity(
        privateKey = ByteArray(32),
        publicKey = byteArrayOf(0x01, 0x23, 0xab.toByte()),
        pairingId = "pairing-1",
    )

    @Test
    fun airPlayTxtRecordsMatchLivi() {
        assertEquals(
            linkedMapOf(
                "deviceid" to "02:00:00:00:00:02",
                "features" to "0x44540380,0x61",
                "flags" to "0x4",
                "model" to "LIVI",
                "srcvers" to "366.0",
                "protovers" to "1.1",
                "pi" to "pairing-1",
                "pk" to "0123ab",
            ),
            CarPlayBonjourProtocol.airPlayTxtRecords(config, identity),
        )
    }

    @Test
    fun raopTxtRecordsMatchLivi() {
        assertEquals(
            linkedMapOf(
                "txtvers" to "1",
                "ch" to "2",
                "cn" to "0,1,2,3",
                "et" to "0,3,5",
                "sv" to "false",
                "da" to "true",
                "sr" to "44100",
                "ss" to "16",
                "pw" to "false",
                "vn" to "65537",
                "tp" to "UDP",
                "md" to "0,1,2",
                "vs" to "130.14",
                "am" to "LIVI",
                "pk" to "0123ab",
                "sf" to "0x4",
            ),
            CarPlayBonjourProtocol.raopTxtRecords(config, identity),
        )
    }

    @Test
    fun connectProbeRequestMatchesExactRequestLineAndHeaders() {
        assertEquals(
            "GET /ctrl-int/1/connect HTTP/1.1\r\n" +
                "Host: [fe80::1]:7000\r\n" +
                "User-Agent: AirPlay/366.0\r\n" +
                "AirPlay-Receiver-Device-ID: 2199023255554\r\n" +
                "Connection: close\r\n" +
                "\r\n",
            CarPlayBonjourProtocol.connectProbeRequest(
                host = "fe80::1%wlan0",
                port = 7000,
                sourceVersion = "366.0",
                deviceId = "02:00:00:00:00:02",
            ),
        )
    }

    @Test
    fun connectProbeRequestConvertsAlphabeticHexDeviceIdToDecimal() {
        assertEquals(
            "244837814094590",
            receiverDeviceIdHeader("de:ad:be:ef:ca:fe"),
        )
    }

    @Test
    fun connectProbeRequestConvertsMaximumDeviceIdAsUnsigned48BitValue() {
        assertEquals(
            "281474976710655",
            receiverDeviceIdHeader("ff:ff:ff:ff:ff:ff"),
        )
    }

    @Test
    fun connectProbeRequestAcceptsDeviceIdWithoutColons() {
        assertEquals("244837814094590", receiverDeviceIdHeader("deadbeefcafe"))
    }

    private fun receiverDeviceIdHeader(deviceId: String): String =
        CarPlayBonjourProtocol.connectProbeRequest(
            host = "192.168.1.1",
            port = 7000,
            sourceVersion = "366.0",
            deviceId = deviceId,
        ).split("\r\n")
            .single { it.startsWith("AirPlay-Receiver-Device-ID: ") }
            .substringAfter(": ")
}
