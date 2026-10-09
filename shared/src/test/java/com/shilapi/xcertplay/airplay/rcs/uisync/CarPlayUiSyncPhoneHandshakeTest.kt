package com.shilapi.xcertplay.airplay.rcs.uisync

import org.junit.Assert.*
import org.junit.Test

/** ultra28/29: both sides RESET ssn=1 at once; answering the phone's RESET breaks the deadlock. */
class CarPlayUiSyncPhoneHandshakeTest {
    @Test fun bootstrapFieldsMatchAssetAndCollisionKeepsTheTransmitSequence() {
        val output = mutableListOf<CarPlayUiSyncMessage>()
        val session = CarPlayUiSyncSession("vehicle", output::add,
            expectedRemoteIdentifier = "paired-phone")
        session.start()
        assertEquals(setOf("type", "ssn", "psn", "asn", "version", "id"), output.single().toWireMap().keys)
        session.receive(CarPlayUiSyncMessageFactory.reset(1, 1, 0,
            version = CarPlayUiSyncProtocolVersion.V3).copy(remoteIdentifier = "paired-phone"))
        assertEquals(2L, output.last().packetSequenceNumber)
        assertFalse(output.last().toWireMap().containsKey("noAck"))
    }

    @Test fun experimentalReadinessSendsUiAfterBoundedPeerWaitAndStopsOnConfirmation() {
        val output = mutableListOf<CarPlayUiSyncMessage>()
        val traces = mutableListOf<String>()
        var time = 0L
        var ready = 0
        lateinit var session: CarPlayUiSyncSession
        session = CarPlayUiSyncSession("vehicle", output::add, trace = traces::add, now = { time },
            onReady = { ready++; session.command(CarPlayUiSyncCommand.REQUEST_LAYOUT) },
            peerResetReadyTimeoutMillis = 6_000)
        session.start()
        session.receive(CarPlayUiSyncMessageFactory.reset(1, 1, 0,
            version = CarPlayUiSyncProtocolVersion.V3).copy(remoteIdentifier = "phone"))
        for (t in listOf(2_000L, 4_000L, 5_999L)) { time = t; session.tick(); assertEquals(0, ready) }
        time = 6_000; session.tick()
        assertEquals(1, ready)
        assertEquals(CarPlayUiSyncMessageType.COMMAND, output.last().type)
        assertEquals(3L, output.last().packetSequenceNumber)
        assertTrue(traces.any { it.contains("experimental ready") })
        session.receive(CarPlayUiSyncMessageFactory.commandAck(1, 2, 3))
        val count = output.size
        time += 2_000; session.tick()
        assertEquals(count, output.size)
    }

    @Test fun slowPhoneConfirmationRetriesTheAcceptedResetWithoutReplacingItsSession() {
        val output = mutableListOf<CarPlayUiSyncMessage>()
        var time = 0L
        var ready = 0
        val session = CarPlayUiSyncSession("vehicle", output::add, now = { time },
            onReady = { ready++ })
        session.start()
        // ultra30: Cluster_Display RESET is accepted, but no confirmation arrives before 2 s.
        session.receive(CarPlayUiSyncMessageFactory.reset(1, 1, 0,
            version = CarPlayUiSyncProtocolVersion.V3)
            .copy(remoteIdentifier = "D0E41446-0011-4EEC-A6DD-51A3D61800F8"))
        val answer = output.last()
        for (round in 1..3) {
            time = round * 2_000L
            assertTrue(session.tick())
            assertEquals("Retry the same packet, including ssn/psn/asn/id", answer, output.last())
            assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED, session.state)
            assertEquals("No UI is sent before the phone confirms", 0, ready)
        }
        session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 2, 1,
            version = CarPlayUiSyncProtocolVersion.V3).copy(remoteIdentifier = answer.remoteIdentifier))
        assertEquals(CarPlayUiSyncSessionState.READY, session.state)
        assertEquals(1, ready)
        val count = output.size
        time += 10_000
        session.tick()
        assertEquals("Confirmation stops handshake retransmission", count, output.size)
    }

    @Test fun collidingPhoneResetIsAnsweredAndThePhonesResetCompleteMakesTheSessionReady() {
        val output = mutableListOf<CarPlayUiSyncMessage>()
        var time = 0L
        var ready = 0
        val session = CarPlayUiSyncSession("vehicle", output::add, now = { time },
            onReady = { ready++ })
        session.start()
        assertEquals(CarPlayUiSyncProtocolVersion.V3, output.single().version)
        session.receive(CarPlayUiSyncMessageFactory.reset(1, 1, 0,
            version = CarPlayUiSyncProtocolVersion.V3).copy(remoteIdentifier = "phone-vehicle-id"))
        val answer = output.last()
        assertEquals(CarPlayUiSyncMessageType.RESET_COMPLETE, answer.type)
        assertEquals(1L, answer.sessionSequenceNumber)
        assertEquals(2L, answer.packetSequenceNumber)
        assertEquals(1L, answer.acknowledgementSequenceNumber)
        assertEquals(CarPlayUiSyncProtocolVersion.V3, answer.version)
        assertEquals("phone-vehicle-id", answer.remoteIdentifier)
        assertFalse(answer.noAck)
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED, session.state)

        session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 2, 1,
            version = CarPlayUiSyncProtocolVersion.V3).copy(remoteIdentifier = "phone-vehicle-id"))
        assertEquals("The phone's RESET_COMPLETE is final; nothing is sent back", 2, output.size)
        assertEquals(CarPlayUiSyncSessionState.READY, session.state)
        assertEquals(CarPlayUiSyncProtocolVersion.V3, session.version)
        assertEquals(1, ready)
        time = 10_000
        assertTrue(session.tick())
        assertEquals("A ready session is not reset by the watchdog", 2, output.size)
    }

    @Test fun phoneAnsweringOurResetInsteadStillCompletesTheSharedSession() {
        val output = mutableListOf<CarPlayUiSyncMessage>()
        val session = CarPlayUiSyncSession("vehicle", output::add, now = { 0L })
        session.start()
        session.receive(CarPlayUiSyncMessageFactory.reset(1, 1, 0, version = CarPlayUiSyncProtocolVersion.V3))
        // The phone confirms our original RESET (same ssn) rather than our RESET_COMPLETE.
        session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 1, 1, version = CarPlayUiSyncProtocolVersion.V3))
        assertEquals(CarPlayUiSyncSessionState.READY, session.state)
        assertEquals(2, output.size)
    }
}
