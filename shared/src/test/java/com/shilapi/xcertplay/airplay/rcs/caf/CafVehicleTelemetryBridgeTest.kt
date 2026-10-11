package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.CarPlayUltraVehicleProvisioning
import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test

class CafVehicleTelemetryBridgeTest {
    @Test fun referenceTreeUsesConfirmedUnitsAndInvalidStartup() {
        val plugin = CarPlayUltraVehicleProvisioning.create().pluginRegistrations.single().handler as CafVehicleStatePlugin
        val tree = CafConfigTree.decode(plugin.config)
        val chars = tree.accessories.flatMap { it.services }.flatMap { it.characteristics }.filter { it.iid.value >= 3000 }
        assertEquals(14, chars.size)
        assertTrue(chars.all { it.mutable == true && it.writable == false && it.supportsInvalid == true && it.initialValue == null && it.priority == 1L })
        assertEquals(1032L, chars.single { it.type.value == 0x30000046L }.units)
        assertEquals(981L, chars.single { it.type.value == 0x30000024L }.units)
        assertEquals(1205L, chars.single { it.type.value == 0x41000002L }.units)
        assertEquals(setOf(151L), chars.filter { it.type.value == 0x3000004AL }.map { it.units }.toSet())
        assertEquals(setOf(68L, 76L, 77L, 78L, 80L, 82L, 83L),
            chars.single { it.type.value == 0x41000011L }.validValues.map { it.toWireValue() }.toSet())
    }

    @Test fun telemetryIsReadableNotifiedDeduplicatedAndInvalidatedOnTheWire() {
        val provisioning = CarPlayUltraVehicleProvisioning.create()
        val plugin = provisioning.pluginRegistrations.single().handler as CafVehicleStatePlugin
        val bridge = CafVehicleTelemetryBridge(plugin)
        val registry = CafPluginRegistry.from(provisioning.pluginRegistrations)
        val k1 = ByteArray(32) { 1 }; val k2 = ByteArray(32) { 2 }
        RcsDataStream.open(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, k1, k2, InetAddress.getByName("127.0.0.1")).use { stream ->
            stream.listen(CafProtocolSession.handlerFactory(registry).create(stream))
            RcsChannel.connect(InetSocketAddress("127.0.0.1", stream.port), k2, k1).use { phone ->
                fun request(command: CafCommand, values: Any?, transaction: Long) {
                    phone.sendComm(CafRcsCodec.encode(CafEnvelope(0, CafMessage(command, transaction, values))).rcsBody)
                }
                fun receive() = CafRcsCodec.decode(requireNotNull(phone.receiveNext(3000)).body).message
                request(CafCommand.REGISTER_REQUEST, listOf(3000L, 3002L, 3013L), 1)
                assertEquals(CafCommand.REGISTER_RESPONSE, receive().command)
                val snapshot = mapOf("vehicleSpeed" to 61.234, "vehicleSpeedUnit" to 1032L, "engineRPM" to 1540.0, "prndl" to "D")
                bridge.publish(snapshot)
                val update = receive()
                assertEquals(CafCommand.UPDATE_NOTIFY, update.command)
                val values = update.values as Map<*, *>
                assertEquals(61.2, values[3000L] as Double, 0.000001)
                assertEquals(1540.0, values[3002L])
                assertEquals(68L, values[3013L])
                bridge.publish(snapshot)
                request(CafCommand.READ_REQUEST, listOf(3000L, 3002L, 3013L), 2)
                assertEquals(CafCommand.READ_RESPONSE, receive().command) // no duplicate notify ahead of read
                bridge.publish(mapOf("vehicleSpeed" to Double.NaN, "prndl" to "invalid"))
                val invalid = receive().values as Map<*, *>
                assertTrue(invalid.containsKey(3000L)); assertNull(invalid[3000L]); assertNull(invalid[3002L]); assertNull(invalid[3013L])
                request(CafCommand.CONFIG_REQUEST, null, 3)
                val config = receive().values as Map<*, *>
                assertNull(Ios27CafWireContract.characteristics(config).single { it["iid"] == 3000L }["initialValue"])
            }
        }
    }

    @Test fun climateWritesUpdateRuntimeAndRemainReadableInCaf() {
        val assets = java.io.File("src/main/assets/carplay-ultra")
        val root = java.nio.file.Files.createTempDirectory("caf-climate-").toFile()
        try {
            val runtime = com.shilapi.xcertplay.airplay.CarPlayOemAssetDeployment.prepare(root,
                { java.io.File(assets, it).inputStream() })
            val provisioning = CarPlayUltraVehicleProvisioning.create(themeRuntime = runtime)
            val registry = CafPluginRegistry.from(provisioning.pluginRegistrations)
            val k1 = ByteArray(32) { 1 }; val k2 = ByteArray(32) { 2 }
            RcsDataStream.open(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, k1, k2, InetAddress.getByName("127.0.0.1")).use { stream ->
                stream.listen(CafProtocolSession.handlerFactory(registry).create(stream))
                RcsChannel.connect(InetSocketAddress("127.0.0.1", stream.port), k2, k1).use { phone ->
                    fun request(command: CafCommand, values: Any?, transaction: Long) {
                        phone.sendComm(CafRcsCodec.encode(CafEnvelope(0, CafMessage(command, transaction, values))).rcsBody)
                    }
                    fun receive() = CafRcsCodec.decode(requireNotNull(phone.receiveNext(3000)).body).message
                    request(CafCommand.REGISTER_REQUEST, listOf(100L, 110L), 1); receive()
                    request(CafCommand.WRITE_REQUEST, mapOf(100L to 23.5, 110L to 4L), 2)
                    assertEquals(CafCommand.WRITE_RESPONSE, receive().command)
                    assertEquals(mapOf(100L to 23.5, 110L to 4L), receive().values)
                    assertEquals(23.5, runtime.scene.vehicleValues["climateTargetTemperature"])
                    assertEquals(4L, runtime.scene.vehicleValues["climateFanLevel"])
                    request(CafCommand.READ_REQUEST, listOf(100L, 110L), 3)
                    assertEquals(mapOf(100L to 23.5, 110L to 4L), receive().values)
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun customClimateOnlyTreeHasNoGuessedTelemetryIids() {
        val plugin = CafVehicleStatePlugin(5, CafConfigTreeFactory.tree(listOf(
            CafConfigTreeFactory.accessory(0x01000001, 1, emptyList()))))
        CafVehicleTelemetryBridge(plugin).publish(mapOf("vehicleSpeed" to 50.0, "vehicleSpeedUnit" to 1032L))
    }
}
