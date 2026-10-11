package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.AirPlayVehicleStateProtocolInfo
import com.shilapi.xcertplay.airplay.CarPlayUltraVehicleProvisioning
import com.shilapi.xcertplay.airplay.toInfoResponseMap
import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageTest
import com.shilapi.xcertplay.airplay.rcs.transport.RcsFrameCodec
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CafSessionWireTest {
    @Test
    fun activeFirstRequestUsesOpackAndLaterResponsesFollowExplicitPeerFraming() {
        val registry = CafPluginRegistry.from(CarPlayUltraVehicleProvisioning.create().pluginRegistrations)
        val readKey = ByteArray(32) { 1 }
        val writeKey = ByteArray(32) { 2 }
        RcsDataStream.open(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, readKey, writeKey,
            InetAddress.getByName("127.0.0.1")).use { stream ->
            val session = CafProtocolSession(stream.clientType, stream, registry,
                ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1)
            stream.listen(session)
            RcsChannel.connect(InetSocketAddress("127.0.0.1", stream.port), writeKey, readKey).use { phone ->
                val pending = session.requestConfig(0)
                val first = receiveRawOpack(phone)
                assertEquals("configRequest", first["command"])
                assertEquals(pending.transactionId, first["transactionID"])

                // An explicitly wrapped peer remains compatible after the default changes.
                val wrapped = CafRcsCodec.encode(
                    CafEnvelope(0, CafMessage(CafCommand.CONFIG_REQUEST, 42)),
                    CafPayloadEncoding.BINARY_PLIST, CafPayloadFraming.COMMAND_PARAMS_DATA,
                )
                phone.sendComm(wrapped.rcsBody)
                val response = CafRcsCodec.decode(requireNotNull(phone.receiveNext(3000)).body)
                assertEquals(CafPayloadEncoding.BINARY_PLIST, response.encoding)
                assertEquals(CafPayloadFraming.COMMAND_PARAMS_DATA, response.framing)
                assertEquals(CafCommand.CONFIG_RESPONSE, response.message.command)
                assertEquals(42L, response.message.transactionId)
            }
        }
    }

    @Test
    fun ultra5ConfigReplyHasAnAsynchronousHeaderOnTheEncryptedWire() {
        for (clientType in CafPluginRegistry.DEFAULT_CLIENT_TYPES) {
            val registry = CafPluginRegistry.from(CarPlayUltraVehicleProvisioning.create().pluginRegistrations)
            val phoneWriteKey = ByteArray(32) { 1 }
            val phoneReadKey = ByteArray(32) { 2 }
            RcsDataStream.open(clientType, phoneWriteKey, phoneReadKey,
                InetAddress.getByName("127.0.0.1")).use { stream ->
                stream.listen(CafProtocolSession.handlerFactory(registry).create(stream))
                Socket().use { phone ->
                    phone.connect(InetSocketAddress("127.0.0.1", stream.port), 3000)
                    phone.soTimeout = 3000
                    val frames = RcsFrameCodec.duplex(phoneReadKey, phoneWriteKey)
                    phone.getOutputStream().apply {
                        write(frames.encrypt(ApTransportPackageTest.hex(
                            ApTransportPackageTest.ULTRA5_CONFIG_REQUEST)))
                        flush()
                    }
                    // Read the actual encrypted response without RcsChannel/ApTransportPackageCodec,
                    // whose shared encoder/decoder previously hid the missing packageType.
                    var ciphertext = ByteArray(0)
                    var plaintext = ByteArray(0)
                    val buffer = ByteArray(4096)
                    val deadline = System.nanoTime() + 3_000_000_000L
                    while (plaintext.size < 32 || plaintext.size < ByteBuffer.wrap(plaintext).int) {
                        check(System.nanoTime() < deadline) { "No complete CAF response before timeout" }
                        val count = phone.getInputStream().read(buffer)
                        check(count > 0) { "RCS closed before a complete CAF response" }
                        ciphertext += buffer.copyOf(count)
                        val decrypted = frames.decrypt(ciphertext)
                        ciphertext = decrypted.rest
                        plaintext += decrypted.data
                    }
                    assertArrayEquals(ApTransportPackageTest.hex("6173796e"), plaintext.copyOfRange(4, 8))
                    // `cmnd`, not `comm`: this is accessory-initiated traffic and the phone's
                    // receive gate only accepts `cmnd` on upload (ultra41, received messages 0 -> 9).
                    assertArrayEquals(ApTransportPackageTest.hex("636d6e64"), plaintext.copyOfRange(16, 20))
                    assertArrayEquals(ByteArray(8), plaintext.copyOfRange(8, 16))
                    assertArrayEquals(ByteArray(12), plaintext.copyOfRange(20, 32))
                    val size = ByteBuffer.wrap(plaintext).int
                    val envelope = OpackCodec.decode(plaintext.copyOfRange(32, size)) as Map<*, *>
                    assertEquals(0L, envelope["pluginID"])
                    val reply = OpackCodec.decode(envelope["pluginData"] as ByteArray) as Map<*, *>
                    assertEquals("configResponse", reply["command"])
                    assertEquals(4398781798871243835L, reply["transactionID"])
                    val config = reply["values"] as Map<*, *>
                    Ios27CafWireContract.assertIdentifiers(config)
                    Ios27CafWireContract.assertNumericConstraints(config)
                    Ios27CafWireContract.assertReferenceClimateLayoutKeys(config)
                }
            }
        }
    }

    @Test
    fun ultra3RequestReceivesConfigAndSubscriptionRepliesOnBothVehicleChannels() {
        for (clientType in CafPluginRegistry.DEFAULT_CLIENT_TYPES) {
            val provisioning = CarPlayUltraVehicleProvisioning.create()
            val registry = CafPluginRegistry.from(provisioning.pluginRegistrations)
            val info = AirPlayVehicleStateProtocolInfo.fromRegistry(registry)!!.toInfoResponseMap()
            assertEquals(listOf(mapOf("pluginID" to 0L)), info["pluginConfigs"])
            assertEquals(mapOf("Communication Plug-in" to 0L), info["pluginMapping"])
            val phoneWriteKey = ByteArray(32) { 1 }
            val phoneReadKey = ByteArray(32) { 2 }
            RcsDataStream.open(clientType, phoneWriteKey, phoneReadKey,
                InetAddress.getByName("127.0.0.1")).use { stream ->
                val factory = CafProtocolSession.handlerFactory(registry)
                stream.listen(factory.create(stream))
                RcsChannel.connect(InetSocketAddress("127.0.0.1", stream.port),
                    phoneReadKey, phoneWriteKey).use { phone ->
                    phone.sendComm(CafWireFormatTest.ULTRA3_CONFIG_REQUEST)
                    val config = receiveRawOpack(phone)
                    assertEquals("configResponse", config["command"])
                    assertEquals(5800410672642552193L, config["transactionID"])
                    val tree = config["values"] as Map<*, *>
                    Ios27CafWireContract.assertIdentifiers(tree)
                    Ios27CafWireContract.assertNumericConstraints(tree)
                    Ios27CafWireContract.assertReferenceClimateLayoutKeys(tree)

                    // Model the firmware's mutable/initialValue gate with matching ASC types.
                    // Actual phone consumers and its ASC registration tree still require a device test.
                    val eligible = Ios27CafWireContract.characteristics(tree)
                        .filter { it["mutable"] == true || !it.containsKey("initialValue") }
                        .map { (it["iid"] as Number).toLong() }
                    assertEquals(listOf(100L, 101L, 110L), eligible.filter { it < 3000 })
                    assertEquals((3000L..3013L).toSet(), eligible.filter { it >= 3000 }.toSet())

                    // Follow-up packets use the observed raw OPACK schema, independently of CafRcsCodec.
                    phone.sendComm(rawRequest("registerRequest", 10, eligible))
                    val registered = receiveRawOpack(phone)
                    assertEquals("registerResponse", registered["command"])
                    assertEquals(10L, registered["transactionID"])
                    assertEquals(emptyMap<Any?, Any?>(), registered["errors"])

                    phone.sendComm(rawRequest("readRequest", 11, listOf(100L, 999L)))
                    val read = receiveRawOpack(phone)
                    assertEquals("readResponse", read["command"])
                    assertEquals(11L, read["transactionID"])
                    assertEquals(22.0, (read["values"] as Map<*, *>)[100L])
                    assertEquals(CafVehicleStatePlugin.NOT_FOUND, (read["errors"] as Map<*, *>)[999L])

                    phone.sendComm(rawRequest("writeRequest", 12, mapOf(100L to 22.5)))
                    val written = receiveRawOpack(phone)
                    assertEquals("writeResponse", written["command"])
                    assertEquals(12L, written["transactionID"])
                    assertEquals(emptyMap<Any?, Any?>(), written["errors"])
                    val update = receiveRawOpack(phone)
                    assertEquals("updateNotify", update["command"])
                    assertEquals(mapOf(100L to 22.5), update["values"])

                    val provider = provisioning.pluginRegistrations.single().handler as CafVehicleStatePlugin
                    provider.update(mapOf(101L to 23.1))
                    val telemetry = receiveRawOpack(phone)
                    assertEquals("updateNotify", telemetry["command"])
                    assertEquals(mapOf(101L to 23.1), telemetry["values"])

                    phone.sendComm(rawRequest("configRequest", 13, null))
                    val refreshed = receiveRawOpack(phone)
                    assertEquals("configResponse", refreshed["command"])
                    assertEquals(13L, refreshed["transactionID"])
                    val refreshedTree = refreshed["values"] as Map<*, *>
                    Ios27CafWireContract.assertIdentifiers(refreshedTree)
                    val snapshots = Ios27CafWireContract.characteristics(refreshedTree).associateBy { it["iid"] }
                    assertEquals(22.5, snapshots.getValue(100L)["initialValue"])
                    assertEquals(23.1, snapshots.getValue(101L)["initialValue"])
                }
            }
        }
    }

    @Test
    fun explicitProfilePluginIdIsPreservedWithoutZeroAlias() {
        val provisioning = CarPlayUltraVehicleProvisioning.create(profile = mapOf("pluginID" to 7L))
        val registry = CafPluginRegistry.from(provisioning.pluginRegistrations)
        assertEquals(7L, registry.allRegistrations().single().pluginId)
        assertThrows(CafProtocolException::class.java) { registry.requireRegistration(0) }
    }

    private fun rawRequest(command: String, transactionId: Long, values: Any?): ByteArray =
        OpackCodec.encode(mapOf("pluginID" to 0L, "pluginData" to OpackCodec.encode(mapOf(
            "command" to command, "transactionID" to transactionId, "values" to values,
        ))))

    private fun receiveRawOpack(phone: RcsChannel): Map<*, *> {
        val reply = requireNotNull(phone.receiveNext(3000)) { "No CAF response before timeout" }
        val outer = OpackCodec.decode(reply.body) as Map<*, *>
        assertEquals(0L, outer["pluginID"])
        assertFalse(outer.containsKey("params"))
        return OpackCodec.decode(outer["pluginData"] as ByteArray) as Map<*, *>
    }
}
