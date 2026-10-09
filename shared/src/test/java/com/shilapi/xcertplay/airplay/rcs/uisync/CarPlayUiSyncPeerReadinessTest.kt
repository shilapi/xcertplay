package com.shilapi.xcertplay.airplay.rcs.uisync

import org.junit.Assert.*
import org.junit.Test

/** Handshake rules based on the asset's APL/ClusterControlSession.lua, answering peer RESETs (ultra28/29). */
class CarPlayUiSyncPeerReadinessTest {
    private val phoneId = "156377D7-3C1D-49F4-8F31-2CF79F8D7532"
    private fun phoneReset(ssn: Long = 1, id: String? = phoneId) = CarPlayUiSyncMessageFactory.reset(ssn, 1, 0,
        version = CarPlayUiSyncProtocolVersion.V3).copy(remoteIdentifier = id)

    @Test fun accessoryResetsFirstAndAnswersThePhonesInitialReset() {
        val sent = mutableListOf<CarPlayUiSyncMessage>()
        val session = CarPlayUiSyncSession("vehicle", sent::add, now = { 0L })
        session.start()
        val reset = sent.single()
        assertEquals(CarPlayUiSyncMessageType.RESET, reset.type)
        assertEquals(1L, reset.sessionSequenceNumber)
        assertEquals(1L, reset.packetSequenceNumber)
        assertEquals(0L, reset.acknowledgementSequenceNumber)
        assertEquals(CarPlayUiSyncProtocolVersion.V3, reset.version)
        assertNull("First connection has no persisted peer id", reset.remoteIdentifier)

        session.receive(phoneReset(ssn = 7))
        val answer = sent.last()
        assertEquals(CarPlayUiSyncMessageType.RESET_COMPLETE, answer.type)
        assertEquals("The phone's session is adopted", 7L, answer.sessionSequenceNumber)
        assertEquals(phoneId, answer.remoteIdentifier)
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED, session.state)
    }

    @Test fun resetWatchdogRestartsAPendingHandshakeEveryTwoSecondsAndNeverClosesTheChannel() {
        val sent = mutableListOf<CarPlayUiSyncMessage>()
        var time = 0L
        val session = CarPlayUiSyncSession("vehicle", sent::add, now = { time })
        session.start()
        time = 1_999
        assertTrue(session.tick())
        assertEquals(1, sent.size)
        for (round in 1..5) {
            time = round * 2_000L
            assertTrue("Handshake timeouts must not close ClusterControl", session.tick())
            val reset = sent.last()
            assertEquals(CarPlayUiSyncMessageType.RESET, reset.type)
            assertEquals(round + 1L, reset.sessionSequenceNumber)
            assertEquals(1L, reset.packetSequenceNumber)
        }
        // Once the phone's RESET is accepted, preserve its session while awaiting confirmation.
        session.receive(phoneReset(ssn = 40))
        val answer = sent.last()
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED, session.state)
        time += 2_000
        assertTrue(session.tick())
        assertEquals(answer, sent.last())
        assertEquals(40L, sent.last().sessionSequenceNumber)
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED, session.state)
    }

    @Test fun disabledWatchdogWaitsForASlowResetComplete() {
        val sent = mutableListOf<CarPlayUiSyncMessage>()
        var time = 0L
        val session = CarPlayUiSyncSession("vehicle", sent::add, now = { time }, resetWatchdogMillis = null)
        session.start()
        for (second in 1..30) { time = second * 1_000L; assertTrue(session.tick()) }
        assertEquals("Exactly one RESET while waiting", 1, sent.size)
    }

    @Test fun phoneResetCompleteToOurResetIsConfirmedOnceAndUnlocksTheLayoutRequest() {
        val sent = mutableListOf<CarPlayUiSyncMessage>()
        var time = 0L
        var ready = 0
        lateinit var session: CarPlayUiSyncSession
        session = CarPlayUiSyncSession("vehicle", sent::add, now = { time },
            onReady = { ready++; session.command(CarPlayUiSyncCommand.REQUEST_LAYOUT) })
        session.start()
        session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 1, 1,
            version = CarPlayUiSyncProtocolVersion.V2).copy(remoteIdentifier = phoneId))
        val confirm = sent[1]
        assertEquals(CarPlayUiSyncMessageType.RESET_COMPLETE, confirm.type)
        assertEquals(1L, confirm.sessionSequenceNumber)
        assertEquals(2L, confirm.packetSequenceNumber)
        assertEquals(1L, confirm.acknowledgementSequenceNumber)
        assertEquals("Negotiated down to the phone's version", CarPlayUiSyncProtocolVersion.V2, confirm.version)
        assertEquals(phoneId, confirm.remoteIdentifier)
        assertFalse(confirm.noAck)
        assertEquals(CarPlayUiSyncSessionState.READY, session.state)
        assertEquals(1, ready)
        val request = sent[2]
        assertEquals(CarPlayUiSyncMessageType.COMMAND, request.type)
        assertEquals(3L, request.packetSequenceNumber)

        session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 1, 1,
            version = CarPlayUiSyncProtocolVersion.V3).copy(remoteIdentifier = phoneId))
        assertEquals("A repeated resetComplete is ignored once ready", 3, sent.size)
        time = 1_000
        assertTrue(session.tick())
        assertEquals("Only the command retransmits, never RESET_COMPLETE", request, sent.last())
        assertEquals(4, sent.size)
    }

    @Test fun unacknowledgedCommandRestartsTheHandshakeKeepingThePairedId() {
        val sent = mutableListOf<CarPlayUiSyncMessage>()
        var time = 0L
        lateinit var session: CarPlayUiSyncSession
        session = CarPlayUiSyncSession("vehicle", sent::add, now = { time },
            onReady = { session.command(CarPlayUiSyncCommand.REQUEST_LAYOUT) })
        session.start()
        session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 1, 1,
            version = CarPlayUiSyncProtocolVersion.V2).copy(remoteIdentifier = phoneId))
        for (second in 1..3) { time = second * 1_000L; assertTrue(session.tick()) }
        assertEquals(CarPlayUiSyncSessionState.READY, session.state)
        time = 4_000
        assertTrue("Command loss resets the session instead of closing it", session.tick())
        val reset = sent.last()
        assertEquals(CarPlayUiSyncMessageType.RESET, reset.type)
        assertEquals(2L, reset.sessionSequenceNumber)
        assertEquals(phoneId, reset.remoteIdentifier)
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED, session.state)
    }

    @Test fun laterPeerResetIsAnsweredWithResetComplete() {
        val sent = mutableListOf<CarPlayUiSyncMessage>()
        var resets = 0
        val session = CarPlayUiSyncSession("vehicle", sent::add, now = { 0L }, onReset = { resets++ })
        session.start()
        session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 1, 1,
            version = CarPlayUiSyncProtocolVersion.V2).copy(remoteIdentifier = phoneId))
        assertEquals(CarPlayUiSyncSessionState.READY, session.state)
        session.receive(phoneReset(ssn = 2))
        val answer = sent.last()
        assertEquals(CarPlayUiSyncMessageType.RESET_COMPLETE, answer.type)
        assertEquals(2L, answer.sessionSequenceNumber)
        assertEquals(1L, answer.packetSequenceNumber)
        assertEquals(1L, answer.acknowledgementSequenceNumber)
        assertEquals("Leaving READY hides the OEM UI", 2, resets)
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED, session.state)
    }

    @Test fun foreignPeerIdCannotCompleteOrResetTheSession() {
        val sent = mutableListOf<CarPlayUiSyncMessage>()
        val session = CarPlayUiSyncSession("vehicle", sent::add, now = { 0L }, expectedRemoteIdentifier = "paired-phone")
        session.start()
        assertEquals("paired-phone", sent.single().remoteIdentifier)
        session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 1, 1,
            version = CarPlayUiSyncProtocolVersion.V2).copy(remoteIdentifier = "other-phone"))
        session.receive(phoneReset(ssn = 3, id = "other-phone"))
        assertEquals(1, sent.size)
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED, session.state)
        session.close()
        assertTrue(session.tick())
        assertEquals(1, sent.size)
    }
}
