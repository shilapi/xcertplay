package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.BplistCodec
import org.junit.Assert.*
import org.junit.Test

/** Independent wire fields recovered from the production package's ClusterControlSession.lua. */
class CarPlayUiSyncAssetContractTest {
    @Test fun consumerReplyAcknowledgesTheCommandBeingHandled() {
        val output = mutableListOf<CarPlayUiSyncMessage>()
        lateinit var session: CarPlayUiSyncSession
        session = CarPlayUiSyncSession("vehicle", output::add, onCommand = {
            session.command(CarPlayUiSyncCommand.GIVE_FOCUS, mapOf("focusToken" to "focus"))
        })
        session.start(); session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 1, 1, noAck = true))
        session.receive(CarPlayUiSyncMessageFactory.command(1, 2, 2,
            CarPlayUiSyncCommand.REQUEST_FOCUS, mapOf("focusToken" to "focus")))
        assertEquals(2L, output.first { it.type == CarPlayUiSyncMessageType.COMMAND }.acknowledgementSequenceNumber)
    }

    @Test fun pairedIdentifierIsValidatedAndAdvertisedWithoutSyntheticVehicleId() {
        val output = mutableListOf<CarPlayUiSyncMessage>()
        val session = CarPlayUiSyncSession("vehicle", output::add, expectedRemoteIdentifier = "paired-phone")
        session.start()
        assertEquals(3L, session.version.wireValue)
        assertEquals("paired-phone", output.single().toWireMap()["id"])
        assertFalse(output.single().toWireMap().containsKey("vehicleID"))
        val complete = CarPlayUiSyncMessageFactory.resetComplete(1, 1, 1, version = CarPlayUiSyncProtocolVersion.V3)
        session.receive(complete.copy(remoteIdentifier = "another-phone"))
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED, session.state)
        session.receive(complete.copy(remoteIdentifier = "paired-phone"))
        assertEquals(CarPlayUiSyncSessionState.READY, session.state)
        assertEquals(CarPlayUiSyncProtocolVersion.V3, session.version)
        assertEquals("paired-phone", output.last().remoteIdentifier)
    }

    @Test fun simultaneousInitialResetIsAnsweredAndLaterResetIsAnsweredAgain() {
        val output = mutableListOf<CarPlayUiSyncMessage>()
        val session = CarPlayUiSyncSession("vehicle", output::add)
        session.start()
        session.receive(CarPlayUiSyncMessageFactory.reset(99, 1, 0, version = CarPlayUiSyncProtocolVersion.V2))
        assertEquals(2, output.size)
        val answer = output.last()
        assertEquals(CarPlayUiSyncMessageType.RESET_COMPLETE, answer.type)
        assertEquals(99L, answer.sessionSequenceNumber)
        assertEquals(1L, answer.acknowledgementSequenceNumber)
        assertEquals(CarPlayUiSyncProtocolVersion.V2, answer.version)
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED, session.state)
        session.receive(CarPlayUiSyncMessageFactory.resetComplete(99, 2, 1, version = CarPlayUiSyncProtocolVersion.V2))
        assertEquals(CarPlayUiSyncSessionState.READY, session.state)
        assertEquals(2, output.size)
        session.receive(CarPlayUiSyncMessageFactory.reset(100, 1, 0, version = CarPlayUiSyncProtocolVersion.V2))
        assertEquals(CarPlayUiSyncMessageType.RESET_COMPLETE, output.last().type)
        assertEquals(100L, output.last().sessionSequenceNumber)
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED, session.state)
    }

    @Test fun numericResetAndAbsentNoAckDecodeAsTheAssetProtocol() {
        val decoded = CarPlayUiSyncMessageFactory.decode(BplistCodec.encode(mapOf(
            "type" to 0L, "ssn" to 1L, "psn" to 1L, "asn" to 0L, "version" to 2L, "id" to "paired-phone")))
        assertEquals(CarPlayUiSyncMessageType.RESET, decoded.type)
        assertEquals(CarPlayUiSyncProtocolVersion.V2, decoded.version)
        assertFalse(decoded.noAck)
    }

    @Test fun outgoingCommandsUseNestedBinaryPlistDataAndIntegerType() {
        val message = CarPlayUiSyncMessageFactory.command(1, 2, 1,
            CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE, mapOf("appearanceMode" to "dark"))
        val outer = BplistCodec.decode(message.toBplist()) as Map<*, *>
        assertEquals(2L, outer["type"])
        assertFalse(outer.containsKey("payload"))
        val inner = BplistCodec.decode(outer["data"] as ByteArray) as Map<*, *>
        assertEquals(mapOf("command" to "targetAppearanceChange", "appearanceMode" to "dark"), inner)
    }

    @Test fun independentNestedCommandFixtureIsDecoded() {
        val decoded = CarPlayUiSyncMessageFactory.decode(BplistCodec.encode(mapOf(
            "type" to 2L, "ssn" to 1L, "psn" to 2L, "asn" to 1L,
            "data" to BplistCodec.encode(mapOf("command" to "driveModeChange", "driveMode" to "sport")))))
        assertEquals(CarPlayUiSyncCommand.DRIVE_MODE_CHANGE, decoded.commandPayload()!!.command)
        assertEquals("sport", decoded.commandPayload()!!.fields["driveMode"])
    }

    @Test fun missingPacketTriggersResetBeforeConsumerSeesCommand() {
        val output = mutableListOf<CarPlayUiSyncMessage>()
        var calls = 0
        val session = CarPlayUiSyncSession("vehicle", output::add, onCommand = { calls++ })
        session.start(); session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 1, 1, noAck = true))
        session.receive(CarPlayUiSyncMessageFactory.command(1, 4, 2,
            CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE, mapOf("appearanceMode" to "dark")))
        assertEquals(0, calls)
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED, session.state)
        assertEquals(CarPlayUiSyncMessageType.RESET, output.last().type)
        assertEquals(2L, output.last().sessionSequenceNumber)
    }

    @Test fun acknowledgedPacketsAdvanceReceiveSequenceAndRegressiveAckIsRejected() {
        var calls = 0
        val session = CarPlayUiSyncSession("vehicle", {}, onCommand = { calls++ })
        session.start(); session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 1, 1, noAck = true))
        session.command(CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE, mapOf("appearanceMode" to "dark"))
        session.receive(CarPlayUiSyncMessageFactory.commandAck(1, 2, 3, noAck = true))
        session.receive(CarPlayUiSyncMessageFactory.command(1, 3, 2,
            CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE, mapOf("appearanceMode" to "dark")))
        assertEquals(0, calls)
        session.receive(CarPlayUiSyncMessageFactory.command(1, 3, 3,
            CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE, mapOf("appearanceMode" to "dark")))
        assertEquals(1, calls)
    }
}
