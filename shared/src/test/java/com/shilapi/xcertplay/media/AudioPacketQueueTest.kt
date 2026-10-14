package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPacketQueueTest {
    /** A packet: its id and how much audio it holds. */
    private data class Packet(val id: Int, val durationUs: Long)

    private fun queue(maxMs: Long = 2_000) = AudioPacketQueue<Packet>(maxMs * 1_000) { it.durationUs }

    @Test
    fun wiredAudioDuringTheOutputStartUpFitsWithoutDrops() {
        // The Qin PLUS log: wired LPCM packets of about 7.3 ms arriving for the 1.17 s the output took.
        val packets = queue()
        val lpcmUs = AudioPacketDuration.of(AudioCodecKind.LPCM, 48_000, 2, rtp(payloadBytes = 1_408))
        var dropped = 0
        repeat(160) { dropped += packets.offer(Packet(it, lpcmUs)) }
        assertEquals(0, dropped)
        assertEquals(160, packets.size)
        assertEquals(0, packets.take().id)
    }

    @Test
    fun aacPacketsGetTheSameTimeBudget() {
        val packets = queue()
        val aacUs = AudioPacketDuration.of(AudioCodecKind.AAC_LC, 48_000, 2, rtp(payloadBytes = 300))
        var dropped = 0
        repeat(100) { dropped += packets.offer(Packet(it, aacUs)) }
        assertEquals("About 2 s of 21.3 ms packets is 93", 7, dropped)
        assertTrue(packets.queuedMs <= 2_000)
    }

    @Test
    fun overTheBudgetTheOldestAudioGoesAndTheNewestStays() {
        val packets = queue(maxMs = 100)
        repeat(15) { packets.offer(Packet(it, 10_000)) }
        assertEquals(10, packets.size)
        assertEquals("Packets 0-4 were dropped", 5, packets.take().id)
        var last = -1
        while (packets.size > 0) last = packets.take().id
        assertEquals(14, last)
    }

    @Test
    fun theEndOfStreamMarkerIsNeverDropped() {
        val packets = queue(maxMs = 100)
        packets.offer(Packet(-1, 0))
        repeat(20) { packets.offer(Packet(it, 10_000)) }
        assertEquals(-1, packets.take().id)
        assertEquals(10, packets.take().id)
    }

    @Test
    fun takeWaitsForAPacket() {
        val packets = queue()
        var taken = -1
        val reader = thread { taken = packets.take().id }
        Thread.sleep(50)
        packets.offer(Packet(7, 10_000))
        reader.join(1_000)
        assertEquals(7, taken)
    }

    @Test
    fun packetDurationsFollowEachCodec() {
        assertEquals(7_333L, AudioPacketDuration.of(AudioCodecKind.LPCM, 48_000, 2, rtp(1_408)))
        assertEquals(21_333L, AudioPacketDuration.of(AudioCodecKind.AAC_LC, 48_000, 2, rtp(300)))
        assertEquals(23_219L, AudioPacketDuration.of(AudioCodecKind.AAC_LC, 44_100, 2, rtp(300)))
        assertEquals(0L, AudioPacketDuration.of(AudioCodecKind.LPCM, 48_000, 2, rtp(0)))
        // Opus TOC: CELT 20 ms (config 31) one frame; SILK 20 ms (config 1) two frames; code 3 with a count.
        assertEquals(20_000L, AudioPacketDuration.of(AudioCodecKind.OPUS, 48_000, 2, rtp(opus = byteArrayOf((31 shl 3).toByte(), 0))))
        assertEquals(40_000L, AudioPacketDuration.of(AudioCodecKind.OPUS, 48_000, 2, rtp(opus = byteArrayOf(((1 shl 3) or 1).toByte(), 0))))
        assertEquals(60_000L, AudioPacketDuration.of(AudioCodecKind.OPUS, 48_000, 2, rtp(opus = byteArrayOf(((31 shl 3) or 3).toByte(), 3))))
    }

    private fun rtp(payloadBytes: Int = 0, opus: ByteArray? = null): ByteArray =
        ByteArray(12) + (opus ?: ByteArray(payloadBytes))
}
