package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class Iap2IdentificationPowerTest {
    private fun identity(wireless: Boolean, location: Boolean = false) = Iap2IdentificationConfig(
        "vehicle", "model", "manufacturer", "serial", "1", "1", 1,
        wireless = if (wireless) Iap2WirelessIdentification("12:34:56:78:9A:BC", "wifi") else null,
        locationInformationEnabled = location,
    )

    // accessoryd 24A437 _iap2_power_checkIdentificationInfo, 0x1000c0cd0..0x1000c0d40:
    // wireless OR powerProvidingCapability == 0 rejects AE03 first, then AE06.
    // The rejection names identification parameter 6, not the offending message ID.
    private fun rejectedPowerMessage(wireless: Boolean, capability: Int, sent: List<Int>): Int? {
        if (!wireless && capability != 0) return null
        return listOf(0xae03, 0xae06).firstOrNull { it in sent }
    }

    @Test fun ultra1WirelessProfilePassesFirmwarePowerValidation() {
        // Exact param6 from ultra1.log. Removing AE03 alone still fails on AE06.
        val logged = listOf(0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02,
            0x4157, 0x4159, 0x4154, 0x4156, 0xae03, 0xae06, 0x4301,
            0x6800, 0x6802, 0x6803, 0xfffb, 0x5703)
        assertEquals(0xae03, rejectedPowerMessage(true, 0, logged))
        assertEquals(0xae06, rejectedPowerMessage(true, 0, logged - 0xae03))

        val body = Iap2Messages.reader(Iap2IdentificationClient.identificationInformation(identity(true, true)))
        assertEquals(0, body.u8(8))
        assertNull(rejectedPowerMessage(true, body.u8(8), body.u16List(6)))
        assertEquals(logged.filterNot { it in setOf(0xae03, 0xae06) }, body.u16List(6))
    }

    @Test fun wirelessPowerSubscriptionDoesNotClaimUsbSupplyEvenWithNonzeroCapability() {
        for (location in listOf(false, true)) {
            val body = Iap2Messages.reader(Iap2IdentificationClient.identificationInformation(identity(true, location)))
            assertNull(rejectedPowerMessage(true, 2, body.u16List(6)))
            assertTrue(body.u16List(6).containsAll(listOf(0xae00, 0xae02)))
            assertTrue(0xae01 in body.u16List(7))
            assertEquals(location, 0xfffb in body.u16List(6))
        }
    }

    @Test fun wiredUsbSupplyRemainsAvailable() {
        val body = Iap2Messages.reader(Iap2IdentificationClient.identificationInformation(identity(false)))
        assertEquals(2, body.u8(8))
        assertTrue(body.u16List(6).containsAll(listOf(0xae03, 0xae06)))
        assertNull(rejectedPowerMessage(false, body.u8(8), body.u16List(6)))
        assertEquals(0xae03, Iap2WiredControlClient.powerSourceUpdate(1000).messageId)
    }
}
