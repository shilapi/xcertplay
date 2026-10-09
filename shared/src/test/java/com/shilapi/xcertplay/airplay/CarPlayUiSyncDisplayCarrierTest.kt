package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCarrier
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMessageType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncProtocolVersion
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMessageFactory
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandPayload
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/** Phone's actual ClusterControl carrier from ultra16/17, outside the accessory Lua envelope. */
class CarPlayUiSyncDisplayCarrierTest {
    @Test fun structuredPayloadDropsOnlyFadeInAndKeepsRoutingAndCommandIndex() {
        val command = CarPlayUiSyncMessageFactory.command(1, 3, 1,
            CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.REQUEST_LAYOUT,
                mapOf("layoutID" to "layout", "fadeInNewLayout" to true,
                    "fadeOutOldLayout" to true, "activeComponents" to emptyMap<String, String>()), assetWire = true))
        val carrier = OpackCodec.decode(CarPlayUiSyncCarrier.encode(command, "Center_Display",
            commandPayloadKey = "payload", commandPayloadDictionary = true, omitFadeInNewLayout = true)) as Map<*, *>
        assertEquals("Center_Display", carrier["displayUUID"])
        assertTrue(carrier["data"] is ByteArray)
        val session = BplistCodec.decode(carrier["data"] as ByteArray) as Map<*, *>
        val payload = session["payload"] as Map<*, *>
        assertEquals(5L, payload["type"])
        val fields = payload["data"] as Map<*, *>
        assertFalse(fields.containsKey("fadeInNewLayout"))
        assertEquals(true, fields["fadeOutOldLayout"])
        assertEquals("layout", fields["layoutID"])
        assertEquals(command.payload!!["data"].let { it as Map<*, *> }.filterKeys { it != "fadeInNewLayout" }, fields)
    }

    @Test fun v3PayloadKeyExperimentChangesOnlyTheSessionCommandKey() {
        val command = CarPlayUiSyncMessageFactory.command(1, 3, 1,
            CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.REQUEST_LAYOUT,
                mapOf("layoutID" to "layout", "fadeInNewLayout" to true), assetWire = true))
        val baseline = OpackCodec.decode(CarPlayUiSyncCarrier.encode(command, "Center_Display")) as Map<*, *>
        val probe = OpackCodec.decode(CarPlayUiSyncCarrier.encode(command, "Center_Display",
            commandPayloadKey = "payload")) as Map<*, *>
        assertEquals(baseline.keys, probe.keys)
        assertEquals(baseline["displayUUID"], probe["displayUUID"])
        val before = BplistCodec.decode(baseline["data"] as ByteArray) as Map<*, *>
        val after = BplistCodec.decode(probe["data"] as ByteArray) as Map<*, *>
        assertFalse(after.containsKey("data"))
        assertTrue(after["payload"] is ByteArray)
        assertArrayEquals(before["data"] as ByteArray, after["payload"] as ByteArray)
        assertEquals(before.filterKeys { it != "data" }, after.filterKeys { it != "payload" })
        val reset = CarPlayUiSyncMessageFactory.reset(1, 1, 0, version = CarPlayUiSyncProtocolVersion.V3)
        assertArrayEquals(CarPlayUiSyncCarrier.encode(reset, "Center_Display"),
            CarPlayUiSyncCarrier.encode(reset, "Center_Display", commandPayloadKey = "payload"))
    }

    @Test fun deferredBootstrapUsesPhoneIdAndProbesBothDisplaysWithoutConfirmation() = exerciseBootstrapProbe("data")
    @Test fun deferredBootstrapProbesBothDisplaysUsingV3PayloadKey() = exerciseBootstrapProbe("payload")
    @Test fun bothDisplaysSendStructuredV3PayloadWithoutFadeIn() = exerciseBootstrapProbe("payload", true)

    private fun exerciseBootstrapProbe(commandPayloadKey: String, dictionary: Boolean = false) {
        val root = Files.createTempDirectory("ui-bootstrap-probe").toFile()
        val stream = RcsDataStream.open(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
            ByteArray(32) { 3 }, ByteArray(32) { 4 })
        try {
            val runtime = CarPlayOemAssetDeployment.prepare(root, {
                File("src/main/assets/carplay-ultra", it).inputStream()
            })
            val port = stream.listen()
            stream.attach(CarPlayUiSyncHandlerFactory("vehicle", themeRuntime = runtime,
                deferInitialResetUntilPeer = true, peerResetReadyTimeoutMillis = 1_000,
                commandPayloadKey = commandPayloadKey, commandPayloadDictionary = dictionary,
                omitFadeInNewLayout = dictionary).create(stream)!!)
            RcsChannel.connect(InetSocketAddress("127.0.0.1", port),
                ByteArray(32) { 4 }, ByteArray(32) { 3 }).use { phone ->
                assertNull("No id-less RESET before the phone identifies itself", phone.receiveNext(50))
                for (display in listOf("Cluster_Display", "Center_Display")) {
                    phone.sendComm(CarPlayUiSyncCarrier.encode(CarPlayUiSyncMessageFactory.reset(1, 1, 0,
                        version = CarPlayUiSyncProtocolVersion.V3).copy(remoteIdentifier = PAIRED_ID), display))
                    for ((type, psn) in listOf(CarPlayUiSyncMessageType.RESET to 1L,
                        CarPlayUiSyncMessageType.RESET_COMPLETE to 2L)) {
                        val received = CarPlayUiSyncCarrier.decode(phone.receiveNext(5_000)!!.body)
                        assertEquals(display, received.displayUuid)
                        assertEquals(type, received.message.type)
                        assertEquals(psn, received.message.packetSequenceNumber)
                        assertEquals(PAIRED_ID, received.message.remoteIdentifier)
                        assertFalse(received.message.toWireMap().containsKey("noAck"))
                    }
                }
                val commands = List(4) {
                    val body = phone.receiveNext(5_000)!!.body
                    val carrier = OpackCodec.decode(body) as Map<*, *>
                    val fields = (BplistCodec.decode(carrier["data"] as ByteArray) as Map<*, *>).toMutableMap()
                    assertTrue(if (dictionary) fields[commandPayloadKey] is Map<*, *>
                        else fields[commandPayloadKey] is ByteArray)
                    if (commandPayloadKey == "payload") {
                        assertFalse(fields.containsKey("data"))
                    }
                    CarPlayUiSyncCarrier.decode(body)
                }
                assertEquals(setOf("Cluster_Display", "Center_Display"), commands.map { it.displayUuid }.toSet())
                for (display in listOf("Cluster_Display", "Center_Display")) {
                    val (layout, allow) = commands.filter { it.displayUuid == display }
                    assertEquals(CarPlayUiSyncMessageType.COMMAND, layout.message.type)
                    assertEquals(CarPlayUiSyncCommand.REQUEST_LAYOUT, layout.message.commandPayload()!!.command)
                    assertEquals(3L, layout.message.packetSequenceNumber)
                    if (dictionary) assertFalse(layout.message.commandPayload()!!.fields.containsKey("fadeInNewLayout"))
                    assertEquals(CarPlayUiSyncCommand.ALLOW_TRANSITIONS, allow.message.commandPayload()!!.command)
                    assertEquals(mapOf("allow" to true), allow.message.commandPayload()!!.fields)
                    assertEquals(4L, allow.message.packetSequenceNumber)
                }
            }
        } finally { stream.close(); root.deleteRecursively() }
    }

    @Test fun centerAndClusterHandshakesAndLayoutsAreIndependentOnTheSameRcsStream() {
        val root = Files.createTempDirectory("ui-two-displays").toFile()
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
                fun receive(display: String) = CarPlayUiSyncCarrier.decode(phone.receiveNext(5_000)!!.body).also {
                    assertEquals(display, it.displayUuid)
                }.message
                fun send(display: String, complete: Boolean, ssn: Long, psn: Long, asn: Long) {
                    val message = if (complete)
                        CarPlayUiSyncMessageFactory.resetComplete(ssn, psn, asn,
                            version = CarPlayUiSyncProtocolVersion.V3)
                    else CarPlayUiSyncMessageFactory.reset(ssn, psn, asn,
                        version = CarPlayUiSyncProtocolVersion.V3)
                    phone.sendComm(CarPlayUiSyncCarrier.encode(message.copy(remoteIdentifier = PAIRED_ID), display))
                }
                assertEquals(CarPlayUiSyncMessageType.RESET, receive("Cluster_Display").type)
                send("Center_Display", false, 7, 1, 0)
                assertEquals(CarPlayUiSyncMessageType.RESET, receive("Center_Display").type)
                val answer = receive("Center_Display")
                assertEquals(CarPlayUiSyncMessageType.RESET_COMPLETE, answer.type)
                assertEquals(7L, answer.sessionSequenceNumber)
                send("Center_Display", true, 7, 2, 1)
                val centerLayout = receive("Center_Display").commandPayload()!!
                val centerAllow = receive("Center_Display").commandPayload()!!
                assertEquals(CarPlayUiSyncCommand.ALLOW_TRANSITIONS, centerAllow.command)
                assertEquals(mapOf("allow" to true), centerAllow.fields)
                val layouts = runtime.descriptor.center!!["layouts"] as List<*>
                val primary = layouts.map { it as Map<*, *> }.single { it["layoutType"] == "primary" }
                assertEquals(primary["id"], centerLayout.fields["layoutID"])
                assertFalse("Center readiness must not unlock the cluster overlay", runtime.scene.uiConnected)
                val center = runtime.forDisplay("Center_Display")
                assertTrue(center.scene.uiConnected)
                fun centerCommand(psn: Long, command: CarPlayUiSyncCommand, fields: Map<String, Any?>) {
                    phone.sendComm(CarPlayUiSyncCarrier.encode(CarPlayUiSyncMessageFactory.command(7, psn, 2,
                        CarPlayUiSyncCommandPayload(command, fields, assetWire = true)), "Center_Display"))
                    val ack = receive("Center_Display")
                    assertEquals(CarPlayUiSyncMessageType.COMMAND_ACK, ack.type)
                    assertEquals(psn, ack.acknowledgementSequenceNumber)
                }
                centerCommand(3, CarPlayUiSyncCommand.METADATA_TRANSFER, mapOf("isTransitioning" to true))
                centerCommand(4, CarPlayUiSyncCommand.LAYOUT_CHANGE, centerLayout.fields)
                centerCommand(5, CarPlayUiSyncCommand.END_LAYOUT_CHANGE,
                    mapOf("layoutID" to primary["id"]))
                runtime.onVideoFrame(1_000_000)
                assertFalse("Instrument frames must not commit a center layout", center.scene.layoutConfirmed)
                runtime.onDisplayVideoFrame("Center_Display", 1_000_000)
                assertTrue(center.scene.layoutConfirmed)
                send("Cluster_Display", false, 1, 1, 0)
                assertEquals(1L, receive("Cluster_Display").sessionSequenceNumber)
                send("Cluster_Display", true, 1, 2, 1)
                assertEquals(runtime.descriptor.initialLayout,
                    receive("Cluster_Display").commandPayload()!!.fields["layoutID"])
                assertEquals(CarPlayUiSyncCommand.ALLOW_TRANSITIONS,
                    receive("Cluster_Display").commandPayload()!!.command)
                assertTrue(runtime.scene.uiConnected)
                send("Center_Display", false, 8, 1, 0)
                assertEquals(8L, receive("Center_Display").sessionSequenceNumber)
                assertTrue("A center reset must not disconnect the cluster", runtime.scene.uiConnected)
            }
        } finally { stream.close(); root.deleteRecursively() }
    }

    @Test fun deployedV3PeerNegotiatesV3AndAccessoryKeepsPlistPayloads() = exerciseDisplaySession(3L)
    @Test fun deployedV2PeerUsesPlistPayloads() = exerciseDisplaySession(2L)
    @Test fun v2KeepsDataKeyWhenV3PayloadExperimentIsConfigured() = exerciseDisplaySession(2L, "payload")
    @Test fun deployedV1PeerOmitsPairedIdAndUsesPlistPayloads() = exerciseDisplaySession(1L)

    /** Accessory-initiated handshake from the asset's ClusterControlSession.lua over the real display carrier. */
    private fun exerciseDisplaySession(peerVersion: Long, commandPayloadKey: String = "data") {
        val root = Files.createTempDirectory("ui-display-carrier").toFile()
        val stream = RcsDataStream.open(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
            ByteArray(32) { 3 }, ByteArray(32) { 4 }, onTrace = {
                if (it.contains("UI Sync TX displayUUID=")) println(it)
            })
        val negotiated = minOf(peerVersion, 3L)
        try {
            val runtime = CarPlayOemAssetDeployment.prepare(root, {
                File("src/main/assets/carplay-ultra", it).inputStream()
            })
            val port = stream.listen()
            stream.attach(CarPlayUiSyncHandlerFactory("vehicle", themeRuntime = runtime,
                commandPayloadKey = commandPayloadKey, commandPayloadDictionary = commandPayloadKey == "payload",
                omitFadeInNewLayout = commandPayloadKey == "payload").create(stream)!!)
            RcsChannel.connect(InetSocketAddress("127.0.0.1", port),
                ByteArray(32) { 4 }, ByteArray(32) { 3 }).use { phone ->
                fun receive(): Map<*, *> {
                    val carrier = OpackCodec.decode(phone.receiveNext(5_000)!!.body) as Map<*, *>
                    assertEquals("Cluster_Display", carrier["displayUUID"])
                    val data = carrier["data"]
                    assertTrue("The asset session always sends plist NSData", data is ByteArray)
                    return BplistCodec.decode(data as ByteArray) as Map<*, *>
                }
                // The phone may use dictionary packets once it is v3; the accessory must still read them.
                fun send(fields: Map<String, Any?>, packet: Boolean = peerVersion == 3L) = phone.sendComm(OpackCodec.encode(mapOf(
                    "displayUUID" to "Cluster_Display", "data" to if (packet) fields else BplistCodec.encode(fields))))
                val reset = receive()
                assertEquals("Accessory initiates with RESET", 0L, reset["type"])
                assertEquals(1L, reset["ssn"]); assertEquals(1L, reset["psn"]); assertEquals(0L, reset["asn"])
                assertEquals("RESET advertises V3", 3L, reset["version"])
                assertNull(reset["id"])

                val wrongDisplay = OpackCodec.decode(PHONE_RESET) as Map<*, *>
                phone.sendComm(OpackCodec.encode(mapOf("displayUUID" to "Other_Display", "data" to wrongDisplay["data"])))
                val peerReset = CarPlayUiSyncCarrier.decode(PHONE_RESET).message.copy(
                    version = CarPlayUiSyncProtocolVersion.entries.first { it.wireValue == peerVersion },
                    remoteIdentifier = if (peerVersion >= 2L) PAIRED_ID else null)
                phone.sendComm(CarPlayUiSyncCarrier.encode(peerReset, "Cluster_Display"))
                val answer = receive()
                assertEquals("Accessory answers the phone's RESET with RESET_COMPLETE", 1L, answer["type"])
                assertEquals(negotiated, answer["version"])
                assertEquals(1L, answer["ssn"]); assertEquals(2L, answer["psn"]); assertEquals(1L, answer["asn"])
                assertNotEquals(true, answer["noAck"])
                assertEquals(if (negotiated >= 2L) PAIRED_ID else null, answer["id"])

                send(mapOf("type" to 1L, "ssn" to 1L, "psn" to 2L, "asn" to 1L,
                    "version" to negotiated, "id" to if (peerVersion >= 2L) PAIRED_ID else null).filterValues { it != null },
                    packet = false)
                val request = receive()
                assertEquals("The phone's RESET_COMPLETE is final; the layout request follows", 2L, request["type"])
                assertEquals(3L, request["psn"])
                assertEquals(2L, request["asn"])
                val layout = BplistCodec.decode(request["data"] as ByteArray) as Map<*, *>
                assertEquals(5L, layout["type"])
                assertEquals(runtime.descriptor.initialLayout, (layout["data"] as Map<*, *>)["layoutID"])
                if (commandPayloadKey == "payload") assertTrue((layout["data"] as Map<*, *>).containsKey("fadeInNewLayout"))
                assertTrue(runtime.scene.uiConnected)
                // Workspace.lua sends ALLOW_LAYOUT_CHANGE (0x11) right after the layout request.
                val allow = receive()
                assertEquals(2L, allow["type"])
                assertEquals(4L, allow["psn"])
                val allowCommand = BplistCodec.decode(allow["data"] as ByteArray) as Map<*, *>
                assertEquals(0x11L, allowCommand["type"])
                assertEquals(mapOf("allow" to true), allowCommand["data"])
                val layoutFields = layout["data"] as Map<*, *>
                fun command(psn: Long, type: Long, fields: Map<*, *>) = send(mapOf(
                    "type" to 2L, "ssn" to 1L, "psn" to psn, "asn" to 2L,
                    "data" to mapOf("type" to type, "data" to fields).let {
                        if (peerVersion == 3L) it else BplistCodec.encode(it)
                    }))
                command(3, 9, mapOf("isTransitioning" to true))
                assertEquals(3L, receive()["asn"])
                command(4, 3, layoutFields)
                assertEquals(4L, receive()["asn"])
                command(5, 6, mapOf("layoutID" to runtime.descriptor.initialLayout))
                assertEquals(5L, receive()["asn"])
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
        const val PAIRED_ID = "156377D7-3C1D-49F4-8F31-2CF79F8D7532"
        val PHONE_RESET = ("e24464617461918962706c6973743030d6010203040506070808090a095776657273696f6e" +
            "5361736e54747970655373736e5269645370736e1003100010015f102431353633373744372d334331442d343946342d" +
            "384633312d32434637394638443735333208151d21262a2d313335370000000000000101000000000000000b" +
            "0000000000000000000000000000005e4b646973706c6179555549444f436c75737465725f446973706c6179")
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
