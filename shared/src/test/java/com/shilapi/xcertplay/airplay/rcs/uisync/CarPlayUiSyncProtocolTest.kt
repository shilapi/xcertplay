package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsPayloadStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayUiSyncProtocolTest {
    @Test
    fun messageTypesProtocolVersionsAndStatesMatchFirmwareNames() {
        assertEquals(
            listOf("reset", "resetComplete", "command", "commandAck"),
            CarPlayUiSyncMessageType.entries.map(CarPlayUiSyncMessageType::wireName),
        )
        assertEquals(
            listOf("v1", "v2", "v3"),
            CarPlayUiSyncProtocolVersion.entries.map(CarPlayUiSyncProtocolVersion::wireName),
        )
        assertEquals(
            listOf(
                "initialized",
                "awaitingResetSelfInitiated",
                "awaitingResetCompleteRemoteInitiated",
                "ready",
            ),
            CarPlayUiSyncSessionState.entries.map(CarPlayUiSyncSessionState::wireName),
        )
    }

    @Test
    fun resetRoundTripsThroughBinaryPlist() {
        val reset = CarPlayUiSyncMessageFactory.reset(
            sessionSequenceNumber = 4,
            packetSequenceNumber = 5,
            acknowledgementSequenceNumber = 3,
            version = CarPlayUiSyncProtocolVersion.V3,
        )

        val decoded = CarPlayUiSyncMessageFactory.decode(reset.toBplist())

        assertEquals(reset, decoded)
        assertEquals(CarPlayUiSyncMessageType.RESET, decoded.type)
        assertEquals(CarPlayUiSyncProtocolVersion.V3, decoded.version)
        assertFalse(decoded.toWireMap().containsKey("payload"))
        assertFalse(decoded.toWireMap().containsKey("vehicleID"))
    }

    @Test
    fun clusterControlCarrierIsClassifiedAsUiSyncPlist() {
        assertEquals(
            RcsPayloadStyle.UI_SYNC_PLIST,
            RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.payloadStyle,
        )
    }

    @Test
    fun commandRoundTripsDocumentedPayloadFields() {
        val command = CarPlayUiSyncMessageFactory.command(
            sessionSequenceNumber = 6,
            packetSequenceNumber = 7,
            acknowledgementSequenceNumber = 2,
            command = CarPlayUiSyncCommand.DRIVE_MODE_DYNAMIC_ASSIGNMENT_CHANGE,
            fields = mapOf(
                "driveModeToLayoutIdAssignments" to mapOf(
                    "sport" to "layout-sport",
                    "normal" to "layout-normal",
                ),
            ),
            vehicleId = "vehicle-1",
        )

        val decoded = CarPlayUiSyncMessageFactory.decode(command.toBplist())
        val payload = decoded.commandPayload()!!

        assertEquals(CarPlayUiSyncCommand.DRIVE_MODE_DYNAMIC_ASSIGNMENT_CHANGE, payload.command)
        assertEquals(
            mapOf(
                "sport" to "layout-sport",
                "normal" to "layout-normal",
            ),
            payload.fields["driveModeToLayoutIdAssignments"],
        )
        assertEquals("vehicle-1", decoded.vehicleId)
    }

    @Test
    fun commandFactoryRejectsMissingFirmwareRequiredFields() {
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCommandPayload(
                command = CarPlayUiSyncCommand.GIVE_FOCUS,
            )
        }
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCommandPayload(
                command = CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE,
            )
        }
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCommandPayload(
                command = CarPlayUiSyncCommand.UI_CONFIGURATION_CHANGE,
            )
        }
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCommandPayload(
                command = CarPlayUiSyncCommand.DRIVE_MODE_DYNAMIC_ASSIGNMENT_CHANGE,
            )
        }
    }

    @Test
    fun commandPayloadValidatesKnownFieldTypes() {
        val focus = CarPlayUiSyncCommandPayload(
            command = CarPlayUiSyncCommand.REQUEST_FOCUS,
            fields = mapOf("focusToken" to 42L),
        )
        assertEquals(42L, focus.fields["focusToken"])

        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCommandPayload(
                command = CarPlayUiSyncCommand.REQUEST_LAYOUT,
                fields = mapOf("fadeOutOldLayout" to "yes"),
            )
        }
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCommandPayload(
                command = CarPlayUiSyncCommand.UI_CONFIGURATION_CHANGE,
                fields = mapOf(
                    "uiConfiguration" to "extendedMode",
                    "data" to "not-a-dictionary",
                ),
            )
        }
    }

    @Test
    fun decodeRejectsMalformedRequiredEnvelopeFields() {
        val valid = CarPlayUiSyncMessageFactory.reset(
            sessionSequenceNumber = 0,
            packetSequenceNumber = 0,
            acknowledgementSequenceNumber = 0,
        )
        val missingNoAck = LinkedHashMap(valid.toWireMap()).apply { remove("noAck") }

        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncMessageFactory.decodeWireValue(missingNoAck)
        }
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncMessageFactory.decodeWireValue(
                valid.toWireMap().toMutableMap().apply { put("type", "futureType") },
            )
        }
    }

    @Test
    fun commandNamesMatchTheRecoveredEnum() {
        val expected = listOf(
            "transitionStart",
            "transitionData",
            "layoutChange",
            "transitionEnd",
            "requestLayout",
            "endLayoutChange",
            "giveFocus",
            "requestFocus",
            "metadataTransfer",
            "paletteChange",
            "allowTransitions",
            "targetAppearanceChange",
            "appearancePreferenceChange",
            "driveModeChange",
            "appearanceChangeStart",
            "appearanceChangeEnd",
            "uiConfigurationChange",
            "driveModeThemeConfigurationOverrideChange",
            "driveModeDynamicAssignmentChange",
        )

        assertEquals(expected, CarPlayUiSyncCommand.entries.map(CarPlayUiSyncCommand::wireName))
        assertTrue(expected.all { CarPlayUiSyncCommand.fromWireName(it).wireName == it })
    }
}
