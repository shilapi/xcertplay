package com.shilapi.xcertplay.airplay.rcs.uisync

import org.junit.Assert.*
import org.junit.Test

/** DashBoard 24A437 accepts resetComplete only after replying to an accessory reset. */
class CarPlayUiSyncPhoneHandshakeTest {
    @Test fun phoneSelfInitiatedResetMustNotReverseAccessoryHandshake() {
        val output = mutableListOf<CarPlayUiSyncMessage>()
        var time = 0L
        var ready = 0
        val session = CarPlayUiSyncSession("vehicle", output::add, now = { time },
            onReady = { ready++ })
        session.start()
        val reset = output.single()
        session.receive(CarPlayUiSyncMessageFactory.reset(1, 1, 0,
            version = CarPlayUiSyncProtocolVersion.V3).copy(remoteIdentifier = "phone-vehicle-id"))
        // A phone in state 1 rejects resetComplete. Preserve our reset and retry it instead.
        assertEquals(1, output.size)
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED, session.state)
        time = 1_000
        assertTrue(session.tick())
        assertEquals(reset, output.last())
        session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 1, 1,
            version = CarPlayUiSyncProtocolVersion.V2).copy(remoteIdentifier = "phone-vehicle-id"))
        val confirmation = output.last()
        assertEquals(CarPlayUiSyncMessageType.RESET_COMPLETE, confirmation.type)
        assertEquals(2L, confirmation.packetSequenceNumber)
        assertEquals(1L, confirmation.acknowledgementSequenceNumber)
        assertEquals("phone-vehicle-id", confirmation.remoteIdentifier)
        assertTrue(confirmation.noAck)
        assertEquals(CarPlayUiSyncSessionState.READY, session.state)
        assertEquals(1, ready)
    }
}
