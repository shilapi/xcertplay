package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.uisync.*
import com.shilapi.xcertplay.iap2.message.*
import org.junit.Assert.*
import org.junit.Test

class UltraSessionContractTest {
    @Test fun unsolicitedResetCompleteCannotMakeUiReadyWithoutAResetHandshake() {
        var ready = 0
        val session = CarPlayUiSyncSession("vehicle", {}, onReady = { ready++ })
        session.receive(CarPlayUiSyncMessageFactory.resetComplete(0, 0, 0, noAck = true))
        assertEquals(CarPlayUiSyncSessionState.INITIALIZED, session.state)
        assertEquals(0, ready)
        session.start()
        session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 1, 1, noAck = true))
        assertEquals(CarPlayUiSyncSessionState.READY, session.state)
        assertEquals(1, ready)
    }
    @Test fun nestedRelayRetainsBothCompleteCsmEnvelopesOnReply() {
        val output = mutableListOf<com.shilapi.xcertplay.iap2.wire.Iap2Frame>()
        val inner = Iap2UltraMessages.requestAuthCert(byteArrayOf(1))
        val session = Iap2UltraRelaySession(output::add,
            authenticate = { Iap2UltraMessages.authCert(0, byteArrayOf(4, 5)) })
        assertTrue(session.handle(Iap2UltraMessages.relay(Iap2UltraMessages.tunnel(inner, 1, false))))
        val tunnel = Iap2UltraMessages.nestedFrame(Iap2Messages.reader(output.single()).bytes(0))
        val certificate = Iap2UltraMessages.nestedFrame(Iap2Messages.reader(tunnel).bytes(1))
        assertEquals(0x5501, tunnel.messageId)
        assertEquals(0x5113, certificate.messageId)
        assertArrayEquals(byteArrayOf(4, 5), Iap2Messages.reader(certificate).bytes(1))
    }
    @Test fun truncatedNestedFrameIsRejected() {
        val raw = Iap2UltraMessages.requestAuthStart().encodedFrame()
        assertThrows(IllegalArgumentException::class.java) { Iap2UltraMessages.nestedFrame(raw.copyOf(raw.size - 1)) }
    }
    @Test fun uiCommandsAreAppliedOnceAndRetransmitsRemainAcknowledged() {
        val output = mutableListOf<CarPlayUiSyncMessage>()
        var calls = 0
        val session = CarPlayUiSyncSession("vehicle", output::add, onCommand = { calls++ })
        session.start()
        session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 1, 1, noAck = true))
        val command = CarPlayUiSyncMessageFactory.command(1, 2, 2,
            CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE, mapOf("appearanceMode" to "dark"))
        session.receive(command); session.receive(command)
        assertEquals(1, calls)
        assertEquals(2, output.count { it.type == CarPlayUiSyncMessageType.COMMAND_ACK })
    }
    @Test fun unknownOemCommandTypeIsAcknowledgedWithoutDroppingTheSession() {
        // ultra52: the phone sent type 21 after layoutChange; the asset logs "Unhandled command" and continues.
        val output = mutableListOf<CarPlayUiSyncMessage>()
        val seen = mutableListOf<CarPlayUiSyncCommand>()
        val session = CarPlayUiSyncSession("vehicle", output::add, onCommand = { seen += it.command })
        session.start()
        session.receive(CarPlayUiSyncMessageFactory.resetComplete(1, 1, 1, noAck = true))
        session.receive(CarPlayUiSyncMessage(CarPlayUiSyncMessageType.COMMAND, 1, 2, 2,
            payload = mapOf("type" to 21L, "data" to mapOf("targetAppearance" to 1L))))
        session.receive(CarPlayUiSyncMessageFactory.command(1, 3, 3,
            CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE, mapOf("appearanceMode" to "dark")))
        assertEquals(CarPlayUiSyncSessionState.READY, session.state)
        assertEquals(listOf(CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE), seen)
        assertEquals(2, output.count { it.type == CarPlayUiSyncMessageType.COMMAND_ACK })
    }
    @Test fun opackAndExplicitBinaryPlistAreBothDecodedWithoutChangingEncoding() {
        val request = CarAccessoryMessages.configRequest(7, 8)
        val binary = CafRcsCodec.encode(request.envelope, CafPayloadEncoding.BINARY_PLIST)
        assertEquals(CafPayloadEncoding.OPACK, CafRcsCodec.decode(request.rcsBody).encoding)
        assertEquals(CafPayloadEncoding.BINARY_PLIST, CafRcsCodec.decode(binary.rcsBody).encoding)
    }
    @Test fun defaultVehicleContainsWritableStateAndDocumentedNumericTypes() {
        val provisioning = CarPlayUltraVehicleProvisioning.create()
        val handler = provisioning.pluginRegistrations.single().handler as CafVehicleStatePlugin
        val tree = CafConfigTree.decode(handler.config)
        val climate = tree.accessories.first()
        assertEquals(0x01000001L, climate.type.value)
        assertTrue(climate.services.flatMap { it.characteristics }.any { it.writable == true })
        // Read-only telemetry for the local cluster follows the climate accessory.
        assertEquals(setOf(0x09000001L, 0x09000003L, 0x09000005L, 0x09000006L),
            tree.accessories.drop(1).map { it.type.value }.toSet())
    }
    @Test fun wirelessIdentificationCarriesConsistentMacAndPowerCoupling() {
        val config = com.shilapi.xcertplay.transport.Iap2IdentificationConfig(
            "vehicle", "model", "manufacturer", "serial", "1.0", "1.0", 1,
            wireless = com.shilapi.xcertplay.transport.Iap2WirelessIdentification("12:34:56:78:9A:BC", "wifi"))
        val frame = com.shilapi.xcertplay.transport.Iap2IdentificationClient.identificationInformation(config)
        val reader = Iap2Messages.reader(frame)
        assertEquals(0, reader.u8(8))
        assertFalse(0xae03 in reader.u16List(6)); assertFalse(0xae06 in reader.u16List(6))
        assertTrue(0xae00 in reader.u16List(6)); assertTrue(0xae01 in reader.u16List(7))
        // accessoryd consumes wireless child 6 as a theme asset ID, not another MAC field.
        assertFalse(reader.group(24).has(6))
        assertFalse(reader.group(24).has(5))
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9a.toByte(), 0xbc.toByte()), reader.group(17).bytes(3))
    }
    @Test fun latestDisplayCommandIsRetainedUntilEventChannelOpens() {
        val config = AirPlayConfig("vehicle", "device", "bt", "1.0", AirPlayDisplayConfig(1280, 720),
            ultra = AirPlayUltraConfig(AirPlayDisplayConfig(640, 240)))
        var ready = false
        val output = mutableListOf<Map<String, Any?>>()
        val control = CarPlayDisplayControlSession(config, { command -> if (ready) { output.add(command); true } else false }, {})
        control.negotiated(setOf(AirPlayFeature.UI_CONTEXT, AirPlayFeature.ALT_SCREEN))
        control.receive("changeUIContext", mapOf("url" to "first"))
        control.receive("changeUIContext", mapOf("url" to "second"))
        assertTrue(output.isEmpty())
        ready = true; control.flush()
        assertEquals("second", (output.single()["params"] as Map<*, *>)["url"])
    }

}
