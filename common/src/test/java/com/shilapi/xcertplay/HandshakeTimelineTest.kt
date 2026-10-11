package com.shilapi.xcertplay

import com.shilapi.xcertplay.HandshakeTimeline.ChipState
import com.shilapi.xcertplay.HandshakeTimeline.Direction
import com.shilapi.xcertplay.HandshakeTimeline.LinkKind
import com.shilapi.xcertplay.HandshakeTimeline.LinkState
import com.shilapi.xcertplay.HandshakeTimeline.PhaseKind
import com.shilapi.xcertplay.HandshakeTimeline.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeTimelineTest {
    @Test
    fun wirelessSessionCompletesEveryPhase() {
        val timeline = replay("handshake-wireless.txt")

        assertNull(timeline.failure)
        timeline.phases.forEach { assertEquals(it.label, State.DONE, it.state) }
        assertEquals(LinkState.UP, link(timeline, LinkKind.WIFI))
        assertEquals(LinkState.RELEASED, link(timeline, LinkKind.RFCOMM))
        assertEquals(LinkState.ENCRYPTED, link(timeline, LinkKind.AIRPLAY))
        assertEquals(LinkState.UP, link(timeline, LinkKind.TUNNEL))
        assertEquals("Test iPhone · iPhone15,4", timeline.peer)
        assertNull(timeline.currentAwaiting())
        assertTrue(timeline.completedAtMillis > 0)
    }

    @Test
    fun iap2ExchangeIsGroupedWithDirectionAndMeaning() {
        val timeline = replay("handshake-wireless.txt")
        val auth = timeline.groups.first { it.title == HandshakeTimeline.G_AUTH && it.phase.kind == PhaseKind.IAP2 }

        assertEquals(listOf("0xaa00", "0xaa01", "0xaa02", "0xaa03", "0xaa05"), auth.rows.map { it.code })
        assertEquals(Direction.PHONE_TO_ACCESSORY, auth.rows[0].direction)
        assertEquals(Direction.ACCESSORY_TO_PHONE, auth.rows[1].direction)
        assertEquals("MFi certificate", auth.rows[1].meaning)
        assertEquals(617, auth.rows[1].bytes)
        assertEquals(State.DONE, auth.state)

        val tunnelAuth = timeline.groups.first { it.title == HandshakeTimeline.G_AUTH && it.phase.kind == PhaseKind.HANDOFF }
        assertEquals("Tunnel", tunnelAuth.tag)
    }

    @Test
    fun liveUpdatesCollapseIntoOneRow() {
        val timeline = replay("handshake-wireless.txt")
        val updates = timeline.groups.filter { it.title == HandshakeTimeline.G_UPDATES }

        assertEquals(2, updates.size)
        assertEquals(1, updates[0].rows.size)
        assertTrue(updates[0].rows[0].name, updates[0].rows[0].name.contains("Now playing ×4"))
        assertTrue(updates[0].rows[0].name, updates[0].rows[0].name.contains("Power ×3"))
    }

    @Test
    fun rtspRequestsCarryRepliesAndLatency() {
        val timeline = replay("handshake-wireless.txt")
        val pairSetup = timeline.groups.first { it.title == HandshakeTimeline.G_PAIR_SETUP }

        assertEquals(3, pairSetup.rows.size)
        assertEquals("/pair-setup", pairSetup.rows[0].name)
        assertTrue(pairSetup.rows[0].meaning.startsWith("M1"))
        val reply = pairSetup.rows[0].reply!!
        assertEquals("200", reply.status)
        assertEquals(409, reply.bytes)
        assertEquals(38L, reply.latencyMillis)
        assertTrue(reply.meaning!!.startsWith("M2"))

        val streams = timeline.groups.first { it.title == HandshakeTimeline.G_STREAMS }
        assertEquals(
            listOf("type 111 · cluster screen", "type 110 · main screen", "type 130 · CarPlayProtocolData",
                "type 130 · CarPlayProtocolData2", "type 100 · main audio"),
            streams.rows.map { it.name },
        )
        assertEquals("dataPort 41113 · streamID 1", streams.rows[2].reply!!.meaning)

        val tunnel = timeline.groups.first { it.title == HandshakeTimeline.G_TUNNEL_STREAM }
        assertEquals("type 130 · iAPChannel", tunnel.rows.first { it.code != null }.name)
        assertTrue(timeline.groups.flatMap { it.rows }.none { it.name.contains("feedback") })
    }

    @Test
    fun negotiationChipsShowEnabledAndMissingFeatures() {
        val timeline = replay("handshake-wireless.txt")
        val chips = timeline.groups.flatMap { it.rows }.first { it.name.startsWith("Features:") }.chips

        assertTrue(chips.contains(HandshakeTimeline.Chip("hevc", ChipState.OK)))
        assertTrue(chips.any { it.state == ChipState.MISSING })
        val availability = timeline.groups.flatMap { it.rows }.first { it.code == "0x4300" }.chips
        assertEquals(
            listOf(HandshakeTimeline.Chip("Wired", ChipState.OK), HandshakeTimeline.Chip("Wireless", ChipState.OK),
                HandshakeTimeline.Chip("Theme assets", ChipState.MISSING)),
            availability,
        )
        val ultra = timeline.groups.flatMap { it.rows }.first { it.name.startsWith("CarPlay Ultra UI") }
        assertTrue(ultra.warning)
    }

    @Test
    fun waitingForIphoneIsReportedWhileInProgress() {
        val timeline = HandshakeTimeline(wireless = true, startedAtMillis = 0)
        lines("handshake-wireless.txt").takeWhile { !it.second.startsWith("wireless RFCOMM connected") }
            .forEach { timeline.accept(it.second, it.first) }

        val (group, awaiting) = timeline.currentAwaiting()!!
        assertEquals(HandshakeTimeline.G_RFCOMM, group.title)
        assertTrue(awaiting.text.contains("RFCOMM"))
        assertEquals(State.ACTIVE, timeline.phases.first { it.kind == PhaseKind.BLUETOOTH }.state)
        assertEquals(LinkState.CONNECTING, link(timeline, LinkKind.RFCOMM))
        assertEquals("AA:BB:CC:••:••:01", group.rows.first().detail!!.lines().first { it.startsWith("Address") }.substringAfter(": "))
    }

    @Test
    fun authenticationFailureStopsTheAttempt() {
        val timeline = HandshakeTimeline(wireless = true, startedAtMillis = 0)
        lines("handshake-wireless.txt").takeWhile { !it.second.startsWith("IAP2 RX [wireless-rfcomm] 0xaa05") }
            .forEach { timeline.accept(it.second, it.first) }
        timeline.accept("IAP2 RX [wireless-rfcomm] 0xaa04 AuthenticationFailed frame=6B body=0 params", 5_000)

        assertEquals("MFi authentication failed", timeline.failure!!.title)
        assertEquals(State.FAILED, timeline.phases.first { it.kind == PhaseKind.IAP2 }.state)
        assertEquals(State.SKIPPED, timeline.phases.first { it.kind == PhaseKind.PAIR }.state)
        assertTrue(timeline.latestRow!!.failed)
        assertNull(timeline.currentAwaiting())
    }

    @Test
    fun detailHidesSecretsAndWireDumps() {
        val timeline = HandshakeTimeline(wireless = true, startedAtMillis = 0)
        timeline.accept(
            """
            IAP2 TX [wireless-rfcomm] 0x5703 AccessoryWiFiConfigurationInformation frame=57B body=4 params
              [1] componentName: "xcertplay-test"
              [2] passphrase: "secret-pass"
              raw-body=00 15 00 01
              frameHex=40400039
            """.trimIndent(),
            0,
        )
        timeline.accept(
            """
            IAP2 RX [wireless-rfcomm] 0x4e0e DeviceTransportIdentifierNotification frame=57B body=2 params
              [0] bluetoothMAC: "c0:17:54:bb:b4:a5"
              [1] usbTransportIdentifier: "00008120000918360250A01E"
            """.trimIndent(),
            1,
        )
        val rows = timeline.groups.flatMap { it.rows }
        val wifi = rows.first { it.code == "0x5703" }.detail!!
        assertFalse(wifi.contains("secret-pass"))
        assertFalse(wifi.contains("40400039"))
        assertTrue(wifi.contains("componentName"))
        val identifiers = rows.first { it.code == "0x4e0e" }.detail!!
        assertFalse(identifiers, identifiers.contains("bb:b4"))
        assertFalse(identifiers, identifiers.contains("000918360250"))
    }

    @Test
    fun traceAndAudioRecordsAreIgnored() {
        assertFalse(HandshakeTimeline.isRelevant("TRACE airplay rx 00 01"))
        assertFalse(HandshakeTimeline.isRelevant("audio underrun"))
        assertTrue(HandshakeTimeline.isRelevant("STEP mfi/start: preparing"))
        assertTrue(HandshakeTimeline.isRelevant("iap2 rx=0x4300 availability=x"))
    }

    @Test
    fun unknownMasterEndpointStillAppearsInTheExchange() {
        val timeline = HandshakeTimeline(wireless = false, startedAtMillis = 0)
        timeline.accept("IAP2 RX [wired] 0x1234 frame=12B body=0 params", 100)

        assertEquals("0x1234", timeline.latestRow?.code)
        assertEquals(Direction.PHONE_TO_ACCESSORY, timeline.latestRow?.direction)
        assertEquals(12, timeline.latestRow?.bytes)
    }

    @Test
    fun ordinaryWirelessCarPlayDoesNotWaitForAnUltraTunnel() {
        val timeline = HandshakeTimeline(wireless = true, startedAtMillis = 1)
        timeline.accept("STEP mfi/ready: ready", 2)
        timeline.accept("airplay rx SETUP /stream cseq=9 body=120", 3)
        timeline.accept("airplay SETUP keys=[streams]", 4)
        timeline.accept("airplay SETUP stream type=110 payload={type=110}", 5)
        timeline.accept("airplay SETUP stream type=100 payload={type=100, audioType=media}", 6)
        timeline.accept("airplay SETUP response streams=[{type=110, dataPort=5100}, {type=100, dataPort=5101, controlPort=5102}]", 7)
        timeline.accept("airplay tx status=200 cseq=9 body=200", 8)

        val rows = timeline.groups.first { it.title == HandshakeTimeline.G_STREAMS }.rows
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.reply?.status == "200" })
        assertEquals("dataPort 5100", rows[0].reply?.meaning)
        assertEquals("dataPort 5101 · controlPort 5102", rows[1].reply?.meaning)
        assertEquals(State.DONE, timeline.phases.first { it.kind == PhaseKind.STREAMS }.state)
        assertTrue(timeline.completedAtMillis > 0)
        assertTrue(timeline.phases.none { it.kind == PhaseKind.HANDOFF })
    }

    @Test
    fun emptySuccessfulSetupDoesNotClaimThatVideoStarted() {
        val timeline = HandshakeTimeline(wireless = false, startedAtMillis = 1)
        timeline.accept("airplay rx SETUP /stream cseq=1 body=100", 2)
        timeline.accept("airplay SETUP keys=[streams]", 3)
        timeline.accept("airplay SETUP stream type=110 payload={type=110}", 4)
        timeline.accept("airplay SETUP response streams=[]", 5)
        timeline.accept("airplay tx status=200 cseq=1 body=20", 6)

        assertEquals(0, timeline.streamCount)
        assertEquals(0L, timeline.completedAtMillis)
        assertTrue(timeline.latestRow!!.failed)
    }

    @Test
    fun concurrentStreamSetupsKeepTheirOwnRepliesAndPorts() {
        val timeline = HandshakeTimeline(wireless = false, startedAtMillis = 1)
        timeline.accept("airplay rx SETUP /stream cseq=1 body=100", 2)
        timeline.accept("airplay SETUP keys=[streams]", 3)
        timeline.accept("airplay SETUP stream type=110 payload={type=110}", 4)
        timeline.accept("airplay SETUP response streams=[{type=110, dataPort=5100}]", 5)
        timeline.accept("airplay rx SETUP /stream cseq=2 body=100", 6)
        timeline.accept("airplay SETUP keys=[streams]", 7)
        timeline.accept("airplay SETUP stream type=111 payload={type=111}", 8)
        timeline.accept("airplay SETUP response streams=[{type=111, dataPort=5200}]", 9)
        timeline.accept("airplay tx status=200 cseq=1 body=100", 10)
        timeline.accept("airplay tx status=500 cseq=2 body=0", 11)

        val rows = timeline.groups.first { it.title == HandshakeTimeline.G_STREAMS }.rows
        assertEquals("dataPort 5100", rows[0].reply?.meaning)
        assertEquals("500", rows[1].reply?.status)
        assertTrue(rows[1].failed)
        assertEquals(1, timeline.streamCount)
    }

    @Test
    fun bluetoothClosureAloneDoesNotInventATunnelHandoff() {
        val timeline = HandshakeTimeline(wireless = true, startedAtMillis = 1)
        timeline.accept("IAP2 CLOSE [wireless-rfcomm] peer EOF", 2)
        timeline.accept("wireless RFCOMM EOF: control ended", 3)
        assertTrue(timeline.phases.none { it.kind == PhaseKind.HANDOFF })
    }

    @Test
    fun streamSetupMayContainOtherTopLevelKeys() {
        val timeline = HandshakeTimeline(wireless = false, startedAtMillis = 1)
        timeline.accept("airplay rx SETUP /stream cseq=1 body=100", 2)
        timeline.accept("airplay SETUP keys=[name, streams]", 3)
        timeline.accept("airplay SETUP stream type=110 payload={type=110}", 4)
        timeline.accept("airplay SETUP response streams=[{type=110, dataPort=5100}]", 5)
        timeline.accept("airplay tx status=200 cseq=1 body=20", 6)
        assertEquals("type 110 · main screen", timeline.latestRow?.name)
        assertEquals("dataPort 5100", timeline.latestRow?.reply?.meaning)
    }

    private fun replay(resource: String): HandshakeTimeline {
        val timeline = HandshakeTimeline(wireless = true, startedAtMillis = 0)
        lines(resource).forEach { timeline.accept(it.second, it.first) }
        return timeline
    }

    private fun link(timeline: HandshakeTimeline, kind: LinkKind): LinkState =
        timeline.links.first { it.kind == kind }.state

    /** Reads `HH:MM:SS.mmm  message` records as (millis, message). */
    private fun lines(resource: String): List<Pair<Long, String>> {
        val text = javaClass.classLoader!!.getResource(resource)!!.readText()
        return text.lineSequence().filter { it.length > 14 }.map { line ->
            val (h, m, s) = line.substring(0, 12).split(':')
            val millis = ((h.toLong() * 60 + m.toLong()) * 60 + s.substringBefore('.').toLong()) * 1000 + s.substringAfter('.').toLong()
            millis to line.substring(14)
        }.toList()
    }
}
