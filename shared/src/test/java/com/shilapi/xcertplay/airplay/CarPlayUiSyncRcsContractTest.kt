package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncHandlerFactory
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

/** Independent numeric envelope and nested plist inspected after encrypted RCS transport. */
class CarPlayUiSyncRcsContractTest {
    @Test fun encryptedClusterControlUsesAccessoryEnvelopeAndCurrentAck() {
        val stream = RcsDataStream.open(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
            ByteArray(32) { 3 }, ByteArray(32) { 4 })
        try {
            val port = stream.listen()
            val factory = CarPlayUiSyncHandlerFactory("vehicle")
            stream.attach(factory.create(stream)!!)
            RcsChannel.connect(InetSocketAddress("127.0.0.1", port),
                ByteArray(32) { 4 }, ByteArray(32) { 3 }).use { phone ->
                fun receive(): Map<*, *> = BplistCodec.decode(phone.receiveNext(5_000)!!.body) as Map<*, *>
                val reset = receive()
                assertEquals(0L, reset["type"])
                assertEquals(3L, reset["version"])
                assertEquals(1L, reset["ssn"])
                assertFalse(reset.containsKey("vehicleID"))
                phone.sendComm(BplistCodec.encode(mapOf("type" to 1L, "ssn" to 1L, "psn" to 1L,
                    "asn" to 1L, "version" to 2L, "id" to "paired-phone")))
                val complete = receive()
                assertEquals(1L, complete["type"])
                assertEquals("paired-phone", complete["id"])
                repeat(2) {
                    val command = receive()
                    assertEquals(2L, command["type"])
                    assertTrue(command["data"] is ByteArray)
                    assertFalse(command.containsKey("payload"))
                    assertTrue((BplistCodec.decode(command["data"] as ByteArray) as Map<*, *>)["command"] is String)
                }
                phone.sendComm(BplistCodec.encode(mapOf("type" to 2L, "ssn" to 1L, "psn" to 2L,
                    "asn" to 4L, "data" to BplistCodec.encode(mapOf("command" to "requestFocus", "focusToken" to "focus")))))
                val reply = receive()
                assertEquals(2L, reply["type"])
                assertEquals(2L, reply["asn"])
                assertEquals(mapOf("command" to "giveFocus", "focusToken" to "focus"),
                    BplistCodec.decode(reply["data"] as ByteArray))
                val ack = receive()
                assertEquals(3L, ack["type"])
                assertEquals(2L, ack["asn"])
            }
        } finally { stream.close() }
    }
}
