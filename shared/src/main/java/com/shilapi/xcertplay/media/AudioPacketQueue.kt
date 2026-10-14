package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Packets waiting for the audio output, bounded by the audio they hold rather than by count, as
 * jitter buffers do (GStreamer rtpjitterbuffer, WebRTC NetEq). A wired LPCM packet holds about 7 ms
 * and an AAC one about 21 ms, so a count bound kept under half a second of wired audio and dropped
 * the start of every stream while the head unit created its output. Over [maxQueuedUs] the oldest
 * audio goes, so a stalled output catches up on the newest and latency stays bounded. Items with no
 * duration (the end-of-stream marker) are never dropped.
 */
internal class AudioPacketQueue<T : Any>(
    private val maxQueuedUs: Long,
    private val maxItems: Int = MAX_ITEMS,
    private val durationUs: (T) -> Long,
) {
    private class Entry<T>(val item: T, val durationUs: Long)

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val entries = ArrayDeque<Entry<T>>()
    private var queuedUs = 0L

    /** Adds [item]; returns how many older packets were dropped to stay within the budget. */
    fun offer(item: T): Int = lock.withLock {
        val duration = durationUs(item).coerceAtLeast(0L)
        var dropped = 0
        while ((queuedUs + duration > maxQueuedUs || entries.size >= maxItems) && dropOldestAudio()) dropped++
        entries.addLast(Entry(item, duration))
        queuedUs += duration
        notEmpty.signal()
        dropped
    }

    /** Waits for the oldest item. */
    @Throws(InterruptedException::class)
    fun take(): T = lock.withLock {
        while (entries.isEmpty()) notEmpty.await()
        val entry = entries.removeFirst()
        queuedUs -= entry.durationUs
        entry.item
    }

    /** The audio waiting, in milliseconds. */
    val queuedMs: Long get() = lock.withLock { queuedUs / 1_000L }

    val size: Int get() = lock.withLock { entries.size }

    private fun dropOldestAudio(): Boolean {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.durationUs > 0L) {
                iterator.remove()
                queuedUs -= entry.durationUs
                return true
            }
        }
        return false
    }

    private companion object {
        /** A guard against packets that report no duration; the time budget normally binds first. */
        const val MAX_ITEMS = 2_048
    }
}

/** How much audio one decrypted RTP packet (12-byte header, then the payload) holds. */
internal object AudioPacketDuration {
    private const val RTP_HEADER_BYTES = 12
    private const val AAC_LC_FRAMES = 1_024
    private val SILK_FRAME_US = longArrayOf(10_000, 20_000, 40_000, 60_000)
    private val HYBRID_FRAME_US = longArrayOf(10_000, 20_000)
    private val CELT_FRAME_US = longArrayOf(2_500, 5_000, 10_000, 20_000)

    fun of(codec: AudioCodecKind, sampleRate: Int, channels: Int, rtp: ByteArray): Long {
        val payloadBytes = rtp.size - RTP_HEADER_BYTES
        if (payloadBytes <= 0 || sampleRate <= 0) return 0L
        return when (codec) {
            AudioCodecKind.LPCM -> framesUs(payloadBytes / (channels.coerceAtLeast(1) * BYTES_PER_SAMPLE), sampleRate)
            AudioCodecKind.AAC_LC -> framesUs(AAC_LC_FRAMES, sampleRate)
            AudioCodecKind.OPUS -> opusUs(rtp, RTP_HEADER_BYTES)
        }
    }

    /** RFC 6716 section 3.1: the TOC byte's configuration gives the frame size, its low bits the count. */
    fun opusUs(packet: ByteArray, offset: Int): Long {
        if (packet.size <= offset) return 0L
        val toc = packet[offset].toInt() and 0xff
        val config = toc ushr 3
        val frameUs = when {
            config < 12 -> SILK_FRAME_US[config % 4]
            config < 16 -> HYBRID_FRAME_US[config % 2]
            else -> CELT_FRAME_US[config % 4]
        }
        val frames = when (toc and 0x3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size > offset + 1) packet[offset + 1].toInt() and 0x3f else 0
        }
        return frameUs * frames
    }

    private fun framesUs(frames: Int, sampleRate: Int): Long = frames * 1_000_000L / sampleRate

    private const val BYTES_PER_SAMPLE = 2
}
