package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.CarPlayUltraVehicleProvisioning
import org.junit.Assert.*
import org.junit.Test

/** iOS 27 (24A437) firmware contracts, checked without the receiver's config decoder. */
class CafFirmwareContractTest {
    private fun referenceConfig() = (CarPlayUltraVehicleProvisioning.create()
        .pluginRegistrations.single().handler as CafVehicleStatePlugin).config

    @Test fun referenceIdentifiersUseFirmwareWireKey() {
        Ios27CafWireContract.assertIdentifiers(referenceConfig())
        val control = CafConfigTreeFactory.control(123, 200)
        assertEquals(200L, control["iid"])
        assertFalse(control.containsKey("instanceID"))
    }

    @Test fun readOnlyNumericCharacteristicsAlsoHaveAllConstraints() {
        Ios27CafWireContract.assertNumericConstraints(referenceConfig())
    }

    @Test fun referenceClimateServicesDeclareMatchingVehicleLayoutKeys() {
        Ios27CafWireContract.assertReferenceClimateLayoutKeys(referenceConfig())
    }

    @Test fun changingVehicleStateIsEligibleForFastStartupRegistration() {
        val characteristics = Ios27CafWireContract.characteristics(referenceConfig())
        // Use type IDs here so an incorrect identifier key cannot hide the mutable failure.
        assertEquals(setOf(0x31000017L, 0x31000019L, 0x31000012L),
            characteristics.filter { it["mutable"] == true || !it.containsKey("initialValue") }
                .map { (it["type"] as Number).toLong() }.toSet())
        assertEquals(false, characteristics.single { it["type"] == 0x46000005L }["mutable"])
    }

    @Test fun decoderRejectsWrongIdentifierKeyAtEveryTreeLevel() {
        for (level in listOf("accessory", "service", "characteristic", "control")) {
            val characteristic = numericCharacteristic()
            val control = linkedMapOf<String, Any?>("type" to 123L, "iid" to 200L,
                "sender" to "device", "hasResponse" to true)
            val service = linkedMapOf<String, Any?>("type" to 0x11000002L, "iid" to 10L,
                "characteristics" to listOf(characteristic), "controls" to listOf(control))
            val accessory = linkedMapOf<String, Any?>("type" to 0x01000001L, "iid" to 1L,
                "version" to "1.0", "services" to listOf(service))
            val node = when (level) {
                "accessory" -> accessory
                "service" -> service
                "characteristic" -> characteristic
                else -> control
            }
            node["instanceID"] = node.remove("iid")
            val error = assertThrows(CafProtocolException::class.java) {
                CafConfigTree.decode(mapOf("accessories" to listOf(accessory)))
            }
            assertTrue("$level: ${error.message}", error.message.orEmpty().contains("iid"))
        }
    }

    @Test fun decoderRequiresEveryConstraintForEveryNumericFormat() {
        for (format in 1L..9L) {
            for (key in listOf("minimumValue", "maximumValue", "stepValue")) {
                val characteristic = numericCharacteristic().apply { put("format", format); remove(key) }
                val error = assertThrows(CafProtocolException::class.java) {
                    CafConfigTree.decode(treeWith(characteristic))
                }
                assertTrue("format=$format missing=$key: ${error.message}",
                    error.message.orEmpty().contains(key))
            }
        }
    }

    @Test fun invalidOemProfileIsRejectedBeforePublishing() {
        val invalid = mapOf("accessories" to listOf(mapOf("type" to 0x01000001L,
            "instanceID" to 1L, "version" to "1.0")))
        assertThrows(CafProtocolException::class.java) {
            CarPlayUltraVehicleProvisioning.create(profile = mapOf("config" to invalid))
        }
    }

    private fun numericCharacteristic() = linkedMapOf<String, Any?>(
        "type" to 0x31000019L, "iid" to 101L, "format" to 9L,
        "initialValue" to 0L, "writable" to false,
        "minimumValue" to 0L, "maximumValue" to 1L, "stepValue" to 1L)

    private fun treeWith(characteristic: Map<String, Any?>) = mapOf("accessories" to listOf(mapOf(
        "type" to 0x01000001L, "iid" to 1L, "version" to "1.0", "services" to listOf(mapOf(
            "type" to 0x11000002L, "iid" to 10L, "characteristics" to listOf(characteristic))))))
}

/** Deliberately inspects the raw dictionary; never calls CafConfigTree.decode or the factory. */
internal object Ios27CafWireContract {
    private fun children(node: Map<*, *>, key: String) =
        (node[key] as? List<*>)?.map { it as Map<*, *> }.orEmpty()

    fun characteristics(tree: Map<*, *>) = children(tree, "accessories")
        .flatMap { children(it, "services") }.flatMap { children(it, "characteristics") }

    fun assertReferenceClimateLayoutKeys(tree: Map<*, *>) {
        val climate = children(tree, "accessories").single { it["type"] == 0x01000001L }
        // 24A437 Climate.app requires accessoryIsTracked(climate). Services without this
        // STRING characteristic are skipped by CAFPositionManager's tracking cache.
        // seat_front_left is a supported AM370 / 44C9FA31EC-985 asset region.
        for (type in listOf(0x11000002L, 0x11000007L)) {
            val service = children(climate, "services").single { it["type"] == type }
            val keys = children(service, "characteristics").filter { it["type"] == 0x36000065L }
            assertEquals("Climate service ${type.toString(16)} needs VehicleLayoutKey", 1, keys.size)
            val key = keys.single()
            assertEquals(10L, key["format"])
            assertEquals("seat_front_left", key["initialValue"])
            assertEquals(false, key["writable"])
            assertEquals(false, key["mutable"])
        }
        val ids = characteristics(tree).map { it["iid"] }
        assertEquals("Characteristic IIDs must remain unique", ids.size, ids.toSet().size)
    }

    fun assertIdentifiers(tree: Map<*, *>) {
        val accessories = children(tree, "accessories")
        assertFalse(accessories.isEmpty())
        val services = accessories.flatMap { children(it, "services") }
        val nodes = accessories + services + characteristics(tree) + services.flatMap { children(it, "controls") }
        for (node in nodes) {
            assertTrue("Missing numeric iid: $node", node["iid"] is Number)
            assertFalse("Wrong wire key: $node", node.containsKey("instanceID"))
        }
    }

    fun assertNumericConstraints(tree: Map<*, *>) {
        for (node in characteristics(tree)) {
            if ((node["format"] as Number).toLong() in 1L..9L) {
                for (key in listOf("minimumValue", "maximumValue", "stepValue")) {
                    assertTrue("Numeric characteristic missing $key: $node", node[key] is Number)
                }
            }
        }
    }
}
