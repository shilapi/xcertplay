package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioRecord
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import com.shilapi.xcertplay.airplay.microphoneBindAddress
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures 16 kHz mono PCM from Android and sends it in the format negotiated by CarPlay.
 *
 * Wired sessions use PCM; wireless sessions use Opus. Both share the same recorder and socket
 * setup so the device-specific audio mode and address-family requirements stay consistent.
 */
internal class MicrophoneUplink(
    private val context: Context,
    private val config: MicrophoneConfig,
    microphoneGainPercent: Int,
) : Closeable {
    private val microphoneGainPercent = MicrophoneGain.sanitize(microphoneGainPercent)
    private val running = AtomicBoolean(false)
    private val firstPacketLogged = AtomicBoolean(false)
    @Volatile private var audioModeLease: Closeable? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusEncoder? = null
    private var thread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            startCapture()
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone start failed", error)
            release()
            false
        }
    }

    private fun startCapture() {
        audioModeLease = MicrophoneAudioMode.acquire(context)

        if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder = OpusEncoder(
                config.sampleRate,
                config.bitrate ?: 48_000,
                onFallback = { reason -> Log.w(TAG, "Opus encoder fallback: $reason") },
            )
        }

        val captureFrameBytes =
            MICROPHONE_CAPTURE_RATE_HZ * config.frameMillis / 1000 * BYTES_PER_SAMPLE
        val nextRecorder = openMicrophoneRecorder(captureFrameBytes * 4)
        recorder = nextRecorder

        val nextSocket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(microphoneBindAddress(config.host), 0))
        }
        socket = nextSocket

        nextRecorder.startRecording()
        thread = Thread({ capture(nextRecorder, nextSocket) }, "carplay-mic").apply {
            isDaemon = true
            start()
        }
        Log.i(
            TAG,
            "microphone uplink started type=${config.audioType} codec=${config.codec} " +
                "captureRate=$MICROPHONE_CAPTURE_RATE_HZ outputRate=${config.sampleRate} " +
                "channels=${config.channels} gain=${microphoneGainPercent}% " +
                "frameMs=${config.frameMillis} port=${config.port}",
        )
    }

    private fun capture(activeRecorder: AudioRecord, activeSocket: DatagramSocket) {
        val frame = ByteArray(config.samplesPerPacket * BYTES_PER_SAMPLE)
        val readBuffer = ByteArray(maxOf(CAPTURE_READ_BYTES, frame.size))
        val resampler = if (config.sampleRate == MICROPHONE_CAPTURE_RATE_HZ) {
            null
        } else {
            PcmMonoResampler(MICROPHONE_CAPTURE_RATE_HZ, config.sampleRate)
        }
        val counters = MicrophoneCounters()
        var filled = 0
        try {
            while (running.get()) {
                val count = activeRecorder.read(
                    readBuffer,
                    0,
                    readBuffer.size,
                    AudioRecord.READ_BLOCKING,
                )
                if (count < 0) {
                    if (running.get()) Log.e(TAG, "microphone read failed code=$count")
                    return
                }
                if (count == 0) continue

                MicrophoneGain.applyPcm16InPlace(readBuffer, count, microphoneGainPercent)
                val samples = resampler?.convert(readBuffer, 0, count) ?: readBuffer
                val sampleBytes = if (resampler == null) count else samples.size
                var offset = 0
                while (offset < sampleBytes && running.get()) {
                    val copied = minOf(frame.size - filled, sampleBytes - offset)
                    samples.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        sendFrame(activeSocket, counters, frame)
                        filled = 0
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) Log.e(TAG, "microphone capture failed", error)
        } finally {
            running.set(false)
            try {
                activeRecorder.stop()
            } catch (_: Exception) {
                // The recorder may already be stopped by close().
            }
            release()
        }
    }

    private fun sendFrame(
        activeSocket: DatagramSocket,
        counters: MicrophoneCounters,
        monoFrame: ByteArray,
    ) {
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(monoFrame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(expandMonoPcm(monoFrame, config.channels)))
        }
        bodies.forEach { body ->
            val packet = MicrophonePacketizer.sealPacket(
                key = config.key,
                payloadType = config.payloadType,
                counters = counters,
                body = body,
                samples = config.rtpSamplesPerPacket,
            )
            try {
                activeSocket.send(DatagramPacket(packet, packet.size, config.host, config.port))
                if (firstPacketLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "microphone first packet bytes=${packet.size} body=${body.size} " +
                            "head=${packet.copyOf(minOf(packet.size, 16)).toHexString()} " +
                            "peer=${config.host.hostAddress}:${config.port}",
                    )
                }
            } catch (error: Exception) {
                if (running.get()) throw error
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)

        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }

        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }

        val currentEncoder = opusEncoder
        opusEncoder = null
        try {
            currentEncoder?.close()
        } catch (_: Exception) {
            // Best effort.
        }

        val currentAudioModeLease = audioModeLease
        audioModeLease = null
        try {
            currentAudioModeLease?.close()
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val BYTES_PER_SAMPLE = 2
        const val CAPTURE_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
    }
}
