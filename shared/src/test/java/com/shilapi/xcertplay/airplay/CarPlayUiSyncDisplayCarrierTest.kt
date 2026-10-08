package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCarrier
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMessageType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncProtocolVersion
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/** Phone's actual ClusterControl carrier from ultra16/17, outside the accessory Lua envelope. */
class CarPlayUiSyncDisplayCarrierTest {
    @Test fun deployedResetAndRepliesUseTheDisplayOpackCarrier() {
        val root = Files.createTempDirectory("ui-display-carrier").toFile()
        val stream = RcsDataStream.open(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
            ByteArray(32) { 3 }, ByteArray(32) { 4 })
        try {
            val runtime = CarPlayOemAssetDeployment.prepare(root, {
                File("src/main/assets/carplay-ultra", it).inputStream()
            })
            val port = stream.listen()
            stream.attach(CarPlayUiSyncHandlerFactory("vehicle", themeRuntime = runtime).create(stream)!!)
            RcsChannel.connect(InetSocketAddress("127.0.0.1", port),
                ByteArray(32) { 4 }, ByteArray(32) { 3 }).use { phone ->
                fun receive(): Map<*, *> {
                    val carrier = OpackCodec.decode(phone.receiveNext(5_000)!!.body) as Map<*, *>
                    assertEquals("Cluster_Display", carrier["displayUUID"])
                    return BplistCodec.decode(carrier["data"] as ByteArray) as Map<*, *>
                }
                fun send(fields: Map<String, Any?>) = phone.sendComm(OpackCodec.encode(mapOf(
                    "displayUUID" to "Cluster_Display", "data" to BplistCodec.encode(fields))))
                // TCP acceptance does not imply the phone's display session is open yet.
                assertNull("OEM must wait for the phone RESET before its first TX", phone.receiveNext(100))
                val wrongDisplay = OpackCodec.decode(PHONE_RESET) as Map<*, *>
                phone.sendComm(OpackCodec.encode(mapOf("displayUUID" to "Other_Display", "data" to wrongDisplay["data"])))
                assertNull("A different display must not open the OEM session", phone.receiveNext(100))
                phone.sendComm(PHONE_RESET)
                val reset = receive()
                assertEquals(0L, reset["type"])
                assertEquals(2L, reset["version"])
                assertEquals("156377D7-3C1D-49F4-8F31-2CF79F8D7532", reset["id"])
                assertFalse(runtime.scene.uiConnected)
                // Phone processes our payload RESET with its own vehicle ID, negotiates v2,
                // enters state 2, then sends its own vehicle ID in resetComplete.
                send(mapOf("type" to 1L, "ssn" to 1L, "psn" to 1L, "asn" to 1L,
                    "version" to 2L, "id" to "156377D7-3C1D-49F4-8F31-2CF79F8D7532"))
                val complete = receive()
                assertEquals(1L, complete["type"])
                assertEquals(2L, complete["version"])
                assertEquals(2L, complete["psn"])
                assertEquals(1L, complete["asn"])
                assertEquals(true, complete["noAck"])
                assertEquals("156377D7-3C1D-49F4-8F31-2CF79F8D7532", complete["id"])
                val request = receive()
                assertEquals(2L, request["type"])
                assertEquals(3L, request["psn"])
                assertEquals(1L, request["asn"])
                val layout = BplistCodec.decode(request["data"] as ByteArray) as Map<*, *>
                assertEquals(5L, layout["type"])
                assertEquals(runtime.descriptor.initialLayout, (layout["data"] as Map<*, *>)["layoutID"])
                assertTrue(runtime.scene.uiConnected)
                val layoutFields = layout["data"] as Map<*, *>
                fun command(psn: Long, type: Long, fields: Map<*, *>) = send(mapOf(
                    "type" to 2L, "ssn" to 1L, "psn" to psn, "asn" to 3L,
                    "data" to BplistCodec.encode(mapOf("type" to type, "data" to fields))))
                command(2, 9, mapOf("isTransitioning" to true))
                assertEquals(2L, receive()["asn"])
                command(3, 3, layoutFields)
                assertEquals(3L, receive()["asn"])
                command(4, 6, mapOf("layoutID" to runtime.descriptor.initialLayout))
                assertEquals(4L, receive()["asn"])
                runtime.onVideoFrame(1_000_000)
                assertTrue("The instrument overlay requires a confirmed layout", runtime.scene.layoutConfirmed)
            }
        } finally { stream.close(); root.deleteRecursively() }
    }

    @Test fun recordedPhoneCarrierSeparatesDisplayRouteFromSessionVersionAndId() {
        val received = CarPlayUiSyncCarrier.decode(PHONE_RESET)
        assertEquals("Cluster_Display", received.displayUuid)
        assertEquals(CarPlayUiSyncMessageType.RESET, received.message.type)
        assertEquals(CarPlayUiSyncProtocolVersion.V3, received.message.version)
        assertEquals(1L, received.message.sessionSequenceNumber)
        assertEquals(1L, received.message.packetSequenceNumber)
        assertEquals(0L, received.message.acknowledgementSequenceNumber)
        assertNotEquals(received.displayUuid, received.message.remoteIdentifier)
        val bare = CarPlayUiSyncCarrier.decode(received.message.toBplist())
        assertNull(bare.displayUuid)
        assertEquals(received.message, bare.message)
    }

    @Test fun malformedDisplayCarrierIsRejectedBeforeSessionParsing() {
        listOf(
            mapOf("data" to PHONE_RESET),
            mapOf("displayUUID" to 1, "data" to PHONE_RESET),
            mapOf("displayUUID" to "", "data" to PHONE_RESET),
            mapOf("displayUUID" to "Cluster_Display", "data" to "plist"),
            mapOf("displayUUID" to "Cluster_Display", "data" to byteArrayOf(0)),
        ).forEach { carrier -> assertThrows(IllegalArgumentException::class.java) {
            CarPlayUiSyncCarrier.decode(OpackCodec.encode(carrier))
        } }
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayUiSyncCarrier.decode(PHONE_RESET + byteArrayOf(0))
        }
    }

    private companion object {
        val PHONE_RESET = ("e24464617461918962706c6973743030d6010203040506070808090a095776657273696f6e" +
            "5361736e54747970655373736e5269645370736e1003100010015f102431353633373744372d334331442d343946342d" +
            "384633312d32434637394638443735333208151d21262a2d313335370000000000000101000000000000000b" +
            "0000000000000000000000000000005e4b646973706c6179555549444f436c75737465725f446973706c6179")
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
