package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2LocationReporterTest {
    private val provider = FakeProvider()
    private val sent = mutableListOf<Iap2Frame>()
    private val progress = mutableListOf<String>()
    private val request = Iap2LocationRequest()
    private var nowMillis = 0L

    // What the iPhone sent on the Bluetooth link in the car: GGA, RMC, PASCD and their intervals.
    private val start = Iap2Messages.buildRaw(Iap2LocationMessages.START_LOCATION_INFORMATION) {
        u16(0, 0)
        void(1)
        void(2)
        void(4)
        u32(0x8001, 1000)
    }
    private val stop = Iap2Messages.buildRaw(Iap2LocationMessages.STOP_LOCATION_INFORMATION) {}

    private fun bluetooth() = Iap2LocationReporter(provider, { progress += it }, request, nanoTime = { nowMillis * 1_000_000 })
    private fun wifi() =
        Iap2LocationReporter(provider, { progress += it }, request, continueRequest = true, nanoTime = { nowMillis * 1_000_000 })

    @Test
    fun theBluetoothLinkSendsAndRecordsTheRequest() {
        val link = bluetooth()

        assertTrue(link.handle(start) { sent += it })
        assertEquals(setOf(0, 1, 2, 4, 0x8001), request.components)
        assertTrue(provider.started)
        nowMillis += 1_000
        link.tick { sent += it }
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.messageId == Iap2LocationMessages.LOCATION_INFORMATION })

        assertTrue(link.handle(stop) { sent += it })
        // The stop ends this Bluetooth link only; the request stays for the Wi-Fi link.
        assertEquals(setOf(0, 1, 2, 4, 0x8001), request.components)
        assertFalse(provider.started)
        link.tick { sent += it }
        assertEquals(2, sent.size)
    }

    @Test
    fun theWifiLinkContinuesAfterTheBluetoothLinkWasStopped() {
        // In the car the iPhone sent 0xFFFC on Bluetooth 28 ms after 0xFFFA, before Wi-Fi was up.
        val bluetoothLink = bluetooth()
        bluetoothLink.handle(start) { }
        bluetoothLink.handle(stop) { }
        assertFalse(provider.started)

        val link = wifi()
        link.tick { sent += it }
        nowMillis += 1_000
        link.tick { sent += it }

        assertTrue(provider.started)
        assertEquals(2, sent.size)
        assertTrue(progress.any { it.startsWith("iap2 location request continues from the Bluetooth link") })
    }

    @Test
    fun aBluetoothStopAfterTheWifiTakeoverKeepsWifiReporting() {
        val bluetoothLink = bluetooth()
        bluetoothLink.handle(start) { }
        val wifiLink = wifi()
        wifiLink.tick { sent += it }
        bluetoothLink.handle(stop) { }

        assertTrue(provider.started)
        nowMillis += 1_000
        wifiLink.tick { sent += it }
        assertEquals(2, sent.size)
    }

    @Test
    fun theFirstReportNamesItsSentences() {
        provider.nmea = "\$GPGGA,1*00\r\n\$GPRMC,2*00\r\n\$GPGSV,1,1,00*00\r\n"
        bluetooth().handle(start) { sent += it }
        assertTrue(progress.contains("iap2 tx=0xfffb location-information sentences=GPGGA+GPRMC+GPGSV"))
    }

    @Test
    fun theWifiLinkContinuesTheBluetoothRequest() {
        bluetooth().handle(start) { }
        provider.stop() // the Bluetooth link closed without 0xFFFC

        val link = wifi()
        link.tick { sent += it }
        nowMillis += 1_000
        link.tick { sent += it }

        assertTrue(provider.started)
        // The provider learns what was asked for, so vehicle speed follows the request onto Wi-Fi.
        assertEquals(setOf(0, 1, 2, 4, 0x8001), provider.requested)
        assertEquals(2, sent.size)
        assertTrue(progress.any { it.startsWith("iap2 location request continues from the Bluetooth link") })
        assertEquals(1_000L, link.pollTimeout(60_000L))
    }

    @Test
    fun closingTheBluetoothLinkKeepsTheProviderForTheWifiLink() {
        val bluetoothLink = bluetooth()
        bluetoothLink.handle(start) { }
        val wifiLink = wifi()
        wifiLink.tick { sent += it }
        assertTrue(provider.started)

        // The iPhone closes the Bluetooth link about 2 s later without 0xFFFC.
        bluetoothLink.close()
        assertTrue(provider.started)

        wifiLink.close()
        assertFalse(provider.started)
    }

    @Test
    fun aWiredLinkStopsItsProviderWhenClosed() {
        val link = Iap2LocationReporter(provider, { })
        link.handle(start) { sent += it }
        assertTrue(provider.started)
        link.close()
        assertFalse(provider.started)
    }

    @Test
    fun theWifiLinkSendsNothingWithoutARequest() {
        val link = wifi()
        link.tick { sent += it }

        assertFalse(provider.started)
        assertTrue(sent.isEmpty())
        // It still wakes every second, in case the Bluetooth request comes after the Wi-Fi link starts.
        assertEquals(1_000L, link.pollTimeout(60_000L))
        assertEquals(60_000L, Iap2LocationReporter(null, {}, request, continueRequest = true).pollTimeout(60_000L))
    }

    @Test
    fun aStopOnTheWifiLinkIsNotUndone() {
        bluetooth().handle(start) { }
        val link = wifi()
        link.tick { sent += it }

        link.handle(stop) { sent += it }
        link.tick { sent += it }

        assertFalse(provider.started)
        assertEquals(1, sent.size)
        assertEquals(60_000L, link.pollTimeout(60_000L))
    }

    @Test
    fun aBurstOfIncomingMessagesStillSendsOneFixASecond() {
        // The loop ticks after every incoming message; at session start the iPhone sends dozens at once.
        val link = bluetooth()
        link.handle(start) { sent += it }
        repeat(12) { link.tick { sent += it } }
        assertEquals(1, sent.size)

        nowMillis += 400
        repeat(5) { link.tick { sent += it } }
        assertEquals(1, sent.size)
        assertEquals(600L, link.pollTimeout(60_000L))

        nowMillis += 600
        link.tick { sent += it }
        assertEquals(2, sent.size)
        assertEquals(1_000L, link.pollTimeout(60_000L))
    }

    @Test
    fun noFixMeansNothingIsSent() {
        provider.nmea = null
        val link = bluetooth()
        link.handle(start) { sent += it }
        link.tick { sent += it }

        assertTrue(provider.started)
        assertTrue(sent.isEmpty())
    }

    private class FakeProvider : Iap2LocationProvider {
        var started = false
        var requested: Set<Int>? = null
        var nmea: String? = "\$GPGGA,123519.00,4807.0380,N,01131.0000,E,1,08,1.0,545.4,M,0.0,M,,*00\r\n"
        override fun onRequested(components: Set<Int>) { requested = components }
        override fun start(): Boolean { started = true; return true }
        override fun stop() { started = false }
        override fun latestNmea() = nmea
    }
}
