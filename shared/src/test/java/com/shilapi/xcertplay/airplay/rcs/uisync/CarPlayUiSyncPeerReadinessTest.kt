package com.shilapi.xcertplay.airplay.rcs.uisync

import org.junit.Assert.*
import org.junit.Test

class CarPlayUiSyncPeerReadinessTest {
    @Test fun oemWaitsForPhoneResetThenInitiatesAccessoryHandshakeWithPeerIdentity() {
        val sent = mutableListOf<CarPlayUiSyncMessage>()
        var time = 0L
        var ready = 0
        val session = CarPlayUiSyncSession("local-vehicle", sent::add, now = { time },
            onReady = { ready++ }, waitForPeerReset = true)
        session.start()
        assertTrue("Do not transmit before phone channel/session readiness", sent.isEmpty())
        time = 150
        session.receive(CarPlayUiSyncMessageFactory.reset(1, 1, 0,
            version = CarPlayUiSyncProtocolVersion.V3).copy(remoteIdentifier = "phone-vehicle"))
        val reset = sent.single()
        assertEquals(CarPlayUiSyncMessageType.RESET, reset.type)
        assertEquals("phone-vehicle", reset.remoteIdentifier)
        assertEquals(CarPlayUiSyncProtocolVersion.V2, reset.version)
        assertEquals(1L, reset.packetSequenceNumber)
        assertEquals(0L, reset.acknowledgementSequenceNumber)
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED, session.state)
        session.receive(CarPlayUiSyncMessageFactory.reset(1, 1, 0,
            version = CarPlayUiSyncProtocolVersion.V3).copy(remoteIdentifier = "phone-vehicle"))
        assertEquals(1, sent.size)
        time = 1_150
        assertTrue(session.tick())
        assertEquals(reset, sent.last())
        // Phone receives accessory RESET, enters state 2, and responds.
        session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 1, 1,
            version = CarPlayUiSyncProtocolVersion.V2).copy(remoteIdentifier = "phone-vehicle"))
        val complete = sent.last()
        assertEquals(CarPlayUiSyncMessageType.RESET_COMPLETE, complete.type)
        assertEquals(2L, complete.packetSequenceNumber)
        assertEquals(1L, complete.acknowledgementSequenceNumber)
        assertTrue(complete.noAck)
        assertEquals(1, ready)
        // Recovery inside an opened session must actively reset, not wait for another
        // phone readiness announcement that might never arrive.
        session.receive(CarPlayUiSyncMessageFactory.commandAck(1, 3, 2, noAck = true))
        assertEquals(CarPlayUiSyncMessageType.RESET, sent.last().type)
        assertEquals(2L, sent.last().sessionSequenceNumber)
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED, session.state)
    }

    @Test fun absentOrForeignPeerCannotWaitForeverOrTriggerTransmission() {
        val sent = mutableListOf<CarPlayUiSyncMessage>()
        var time = 0L
        val session = CarPlayUiSyncSession("vehicle", sent::add, now = { time },
            expectedRemoteIdentifier = "paired-phone", waitForPeerReset = true)
        session.start()
        session.receive(CarPlayUiSyncMessageFactory.reset(1, 1, 0,
            version = CarPlayUiSyncProtocolVersion.V3).copy(remoteIdentifier = "other-phone"))
        assertTrue(sent.isEmpty())
        time = 3_999
        assertTrue(session.tick())
        time = 4_000
        assertFalse(session.tick())
        session.close()
        assertTrue(session.tick())
    }
}
