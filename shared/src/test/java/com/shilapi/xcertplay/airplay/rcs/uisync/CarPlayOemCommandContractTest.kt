package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.BplistCodec
import org.junit.Assert.*
import org.junit.Test

/** Independent payload from the acquired LayoutController.lua, not Swift property names. */
class CarPlayOemCommandContractTest {
    @Test fun layoutTransitionUsesNumericInnerTypeAndData() {
        val body = BplistCodec.encode(mapOf("type" to 2L, "ssn" to 1L, "psn" to 2L, "asn" to 1L,
            "data" to BplistCodec.encode(mapOf("type" to 3L,
                "data" to mapOf("layoutID" to "6C45CC47-EC01-527A-B40D-1316AA9C936F", "activeComponents" to emptyMap<String, String>())))))
        val command = CarPlayUiSyncMessageFactory.decode(body).commandPayload()!!
        assertEquals(CarPlayUiSyncCommand.LAYOUT_CHANGE, command.command)
        assertEquals("6C45CC47-EC01-527A-B40D-1316AA9C936F", command.fields["layoutID"])
    }

    @Test fun paletteTypeIsHexadecimalAndInvalidTypesAreRejected() {
        assertEquals(CarPlayUiSyncCommand.PALETTE_CHANGE,
            CarPlayUiSyncCommandPayload.fromWireMap(mapOf("type" to 16L, "data" to emptyMap<String, Any?>())).command)
        listOf(mapOf("type" to 10L, "data" to emptyMap<String, Any?>()),
            mapOf("type" to "3", "data" to emptyMap<String, Any?>()),
            mapOf("type" to 3.0, "data" to emptyMap<String, Any?>()),
            mapOf("type" to 3L, "data" to emptyList<String>()),
            mapOf("type" to 3L, "command" to "layoutChange", "data" to emptyMap<String, Any?>()))
            .forEach { invalid -> assertThrows(IllegalArgumentException::class.java) {
                CarPlayUiSyncCommandPayload.fromWireMap(invalid)
            } }
    }
}
