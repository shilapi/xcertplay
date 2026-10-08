package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree
import org.junit.Assert.*
import org.junit.Test

/** Protocol boundaries from agent_docs, independent of the receiver's own round-trip fixtures. */
class UltraWireContractTest {
    @Test fun everyDisplayDeclaresViewAreaIdentityAndAdjacency() {
        val config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "950.7.1",
            AirPlayDisplayConfig(1280, 720))
        val display = (AirPlayInfoPlist.build(config)["displays"] as List<*>).first() as Map<*, *>
        assertTrue(display.containsKey("adjacentViewAreas"))
        assertEquals(0, ((display["viewAreas"] as List<*>).first() as Map<*, *>)["viewAreaIndex"])
    }

    @Test fun firmwareIidIsAccepted() {
        val tree = CafConfigTree.decode(mapOf("accessories" to listOf(mapOf(
            "type" to 0x01000001L, "iid" to 1L, "version" to "1.0"))))
        assertEquals(1L, tree.accessories.single().iid.value)
    }

    @Test fun signedStatusAndInvalidValueRemainPlistIntegersAndNull() {
        val decoded = BplistCodec.decode(BplistCodec.encode(mapOf("error" to -50L, "value" to null))) as Map<*, *>
        assertEquals(-50L, decoded["error"])
        assertTrue(decoded.containsKey("value"))
        assertNull(decoded["value"])
    }
}
