package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.io.File
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/** Rendering seam for the decrypted CarPlay media streams. */
interface MediaSink {
    fun onVideoCodec(type: Int, codec: VideoCodec) {}
    fun onVideoConfig(type: Int, codecData: ByteArray) {}
    fun onVideoFrame(type: Int, naluBytes: ByteArray) {}
    fun onScreenStreamActive(type: Int, active: Boolean) {}
    fun onAudioStarted(type: Int, format: AudioFormat, firstSample: Int) {}
    fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int) {}
    fun onAudioStopped(type: Int) {}
    fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {}
    fun onMicrophoneStopped(type: Int) {}
    fun onIapMessage(bytes: ByteArray) {}
}

/**
 * Concrete [AirPlayMediaHandler] that binds the screen, audio and iAP2 DataStream ports,
 * decrypts their payloads, and hands decoded media to a [MediaSink]. Telephony and speech
 * streams can additionally return a PCM microphone uplink through the sink.
 */
class CarPlayMediaEngine(
    private val sink: MediaSink,
    private val microphoneEnabled: Boolean = false,
    private val audioCaptureDirectory: File? = null,
    rcsHandlerFactory: RcsDataStreamHandlerFactory? = null,
) : AirPlayMediaHandler {
    internal data class StreamKey(
        val session: AirPlaySession,
        val type: Int,
        val discriminator: String = "",
    )

    private data class AudioMeta(
        val type: Int,
        val format: AudioFormat,
        val connectionId: Any?,
        val playoutLatencyMs: Int,
        @Volatile var firstSample: Int? = null,
        @Volatile var originNs: Long? = null,
    )

    private data class PendingDataStream(
        val closeable: Closeable,
        val attach: () -> Boolean,
    )

    private val streams = ConcurrentHashMap<StreamKey, Closeable>()
    private val audioMeta = ConcurrentHashMap<Int, AudioMeta>()
    private val pendingMicrophone = ConcurrentHashMap<Int, MicrophoneConfig>()
    private val audioCaptures = ConcurrentHashMap<Int, AudioPacketCapture>()
    private val pendingDataStreams = ConcurrentHashMap<AirPlaySession, MutableList<PendingDataStream>>()
    private val pendingLock = Any()
    private val dataStreamFactory = CarPlayDataStreamFactory()
    @Volatile private var iapTunnelHandler: ((BlockingDuplexByteStream) -> Boolean)? = null
    @Volatile private var rcsDataStreamHandlerFactory: RcsDataStreamHandlerFactory? = rcsHandlerFactory

    override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
        iapTunnelHandler = handler
    }

    override fun setRcsDataStreamHandlerFactory(factory: RcsDataStreamHandlerFactory?) {
        rcsDataStreamHandlerFactory = factory
    }

    override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? {
        val key = outputKey(session, stream) ?: return null
        val streamKey = StreamKey(session, type)
        Log.i(TAG, "airplay screen key connectionID=${unsignedPlistDecimal(stream["streamConnectionID"])}")
        val screen = ScreenStream(key)
        val port = screen.listen(
            object : ScreenStream.Listener {
                override fun onCodec(codec: VideoCodec) = sink.onVideoCodec(type, codec)
                override fun onConfig(codecData: ByteArray) = sink.onVideoConfig(type, codecData)
                override fun onFrame(naluBytes: ByteArray) = sink.onVideoFrame(type, naluBytes)
                override fun onClosed(cause: Throwable?) {
                    Log.w(
                        TAG,
                        "screen stream ended type=$type reason=${cause?.message ?: "peer EOF"}",
                    )
                    if (streams.remove(streamKey, screen)) {
                        sink.onScreenStreamActive(type, false)
                    }
                    session.close()
                }
            },
        )
        streams.put(streamKey, screen)?.close()
        sink.onScreenStreamActive(type, true)
        return port
    }

    override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
        val streamKey = StreamKey(session, type)
        streams.remove(streamKey)?.close()
        audioMeta.remove(type)
        audioCaptures.remove(type)?.close()
        if (pendingMicrophone.remove(type) != null) sink.onMicrophoneStopped(type)
        sink.onAudioStopped(type)

        val key = outputKey(session, stream) ?: return null
        val audioType = stream["audioType"]?.toString()?.lowercase() ?: "default"
        val format = AudioStreamCodec.fromFormatBits(
            (stream["audioFormat"] as? Number)?.toLong() ?: 0L,
            type,
            audioType,
        )
        Log.i(
            TAG,
            "airplay audio format type=$type audioType=$audioType codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "micPort=${(stream["dataPort"] as? Number)?.toInt() ?: 0}",
        )
        val connectionId = stream["streamConnectionID"]
        val latencyMs = (stream["audioLatencyMs"] as? Number)?.toInt() ?: 0
        val meta = AudioMeta(type, format, connectionId, latencyMs)
        val microphone = microphoneConfig(session, type, stream, format)
        if (microphone != null) pendingMicrophone[type] = microphone

        val capture = audioCaptureDirectory?.let { AudioPacketCapture(it, type) }
        if (capture != null) audioCaptures[type] = capture
        val audio = AudioStream(key, type)
        val (dataPort, controlPort) = audio.listen(
            object : AudioStream.Listener {
                override fun onStarted(firstSample: Int) {
                    meta.firstSample = firstSample
                    meta.originNs = System.nanoTime()
                    sink.onAudioStarted(type, format, firstSample)
                    microphone?.let { sink.onMicrophoneStarted(type, it) }
                }

                override fun onRtp(rtp: ByteArray, sample: Int) =
                    sink.onAudioRtp(type, format, rtp, sample)

                override fun onPacket(
                    wire: ByteArray,
                    rtp: ByteArray?,
                    sample: Int?,
                    error: Throwable?,
                ) {
                    capture?.record(wire, rtp, sample, error)
                }
            },
        )
        streams[streamKey] = audio
        audioMeta[type] = meta
        return linkedMapOf(
            "type" to type,
            "dataPort" to dataPort,
            "controlPort" to controlPort,
            "streamConnectionID" to unsignedPlistInteger(connectionId ?: 0L),
        )
    }

    override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        val uuid = (stream["clientTypeUUID"] as? String) ?: return null
        val clientType = RcsClientTypes.findByUuid(uuid)
        if (clientType == null) {
            session.logDebug("AirPlay RCS SETUP rejected unknown clientTypeUUID=$uuid")
            return null
        }
        if (!session.isFeatureEnabled(clientType.feature)) {
            session.logDebug(
                "AirPlay RCS SETUP rejected clientType=${clientType.name}: " +
                    "feature=${clientType.feature?.wireName ?: "none"} is not enabled",
            )
            return null
        }
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        session.logDebug(
            "AirPlay RCS SETUP clientType=${clientType.name} uuid=${clientType.uuid} seed=$seed " +
                "streamConnectionID=${unsignedPlistDecimal(stream["streamConnectionID"]) ?: "none"}",
        )
        val opened = dataStreamFactory.open(
            session = session,
            clientType = clientType,
            seed = seed,
            handlers = CarPlayDataStreamHandlers(
                iap = iapTunnelHandler,
                rcs = rcsDataStreamHandlerFactory,
                onIapFallback = sink::onIapMessage,
            ),
        ) ?: return null
        val streamKey = StreamKey(session, STREAM_TYPE_DATA, clientType.name)
        streams.put(streamKey, opened.closeable)?.close()
        addPendingDataStream(session, PendingDataStream(opened.closeable, opened.attach))
        return linkedMapOf<String, Any?>(
            "type" to STREAM_TYPE_DATA,
            "streamID" to 1L,
            "dataPort" to opened.port,
        )
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }
    }

    override fun onSetupResponseSent(session: AirPlaySession) {
        takePendingDataStreams(session).forEach { pending ->
            val attached = try {
                pending.attach()
            } catch (error: Throwable) {
                Log.w(TAG, "data stream attachment failed", error)
                false
            }
            if (!attached) {
                Log.w(TAG, "data stream attachment was rejected after SETUP")
                pending.closeable.close()
                session.close()
            }
        }
    }

    override fun onFeedback(session: AirPlaySession): Map<String, Any?>? {
        val active = audioMeta.values.toList()
        if (active.isEmpty()) return null
        val streams = active.map { meta ->
            val entry = linkedMapOf<String, Any?>(
                "type" to meta.type,
                "sampleRate" to meta.format.sampleRate,
            )
            val firstSample = meta.firstSample
            val originNs = meta.originNs
            if (firstSample != null && originNs != null) {
                val nowNs = System.nanoTime()
                val elapsedSec = Math.max(
                    0.0,
                    (nowNs - originNs) / 1e9 - meta.playoutLatencyMs / 1000.0,
                )
                val firstUnsigned = firstSample.toLong() and 0xffff_ffffL
                val sampleTime = (firstUnsigned + Math.round(elapsedSec * meta.format.sampleRate)) and
                    0xffff_ffffL
                entry["streamConnectionID"] = unsignedPlistInteger(meta.connectionId ?: 0L)
                entry["timestamp"] = session.syncedNtp()
                entry["timestampRawNs"] = nowNs
                entry["sampleTime"] = sampleTime
            }
            entry
        }
        return linkedMapOf("streams" to streams)
    }

    override fun onTeardown(session: AirPlaySession, type: Int) {
        if (type == STREAM_TYPE_DATA) clearPendingDataStreams(session)
        if (pendingMicrophone.remove(type) != null) sink.onMicrophoneStopped(type)
        audioMeta.remove(type)
        audioCaptures.remove(type)?.close()
        sink.onAudioStopped(type)
        streams.keys
            .filter { it.session === session && it.type == type }
            .forEach { streams.remove(it)?.close() }
        if (isScreenStreamType(type)) sink.onScreenStreamActive(type, false)
    }

    override fun onSessionClosed(session: AirPlaySession) {
        clearPendingDataStreams(session)
        val sessionStreams = streams.keys.filter { it.session === session }
        sessionStreams
            .filter { isScreenStreamType(it.type) }
            .forEach { sink.onScreenStreamActive(it.type, false) }
        sessionStreams.forEach { streams.remove(it)?.close() }
        audioMeta.clear()
        pendingMicrophone.clear()
        audioCaptures.values.forEach(AudioPacketCapture::close)
        audioCaptures.clear()
    }

    private fun addPendingDataStream(session: AirPlaySession, pending: PendingDataStream) {
        synchronized(pendingLock) {
            pendingDataStreams.getOrPut(session) { arrayListOf() }.add(pending)
        }
    }

    private fun takePendingDataStreams(session: AirPlaySession): List<PendingDataStream> =
        synchronized(pendingLock) {
            pendingDataStreams.remove(session).orEmpty()
        }

    private fun clearPendingDataStreams(session: AirPlaySession? = null) {
        if (session == null) {
            val pending = synchronized(pendingLock) {
                pendingDataStreams.values.flatten().also { pendingDataStreams.clear() }
            }
            pending.forEach { it.closeable.close() }
            return
        }
        takePendingDataStreams(session).forEach { it.closeable.close() }
    }

    private fun outputKey(session: AirPlaySession, stream: Map<String, Any?>): ByteArray? {
        return dataStreamKey(session, stream, DATASTREAM_OUTPUT_KEY)
    }

    private fun microphoneConfig(
        session: AirPlaySession,
        type: Int,
        stream: Map<String, Any?>,
        format: AudioFormat,
    ): MicrophoneConfig? {
        if (!microphoneEnabled || type != STREAM_TYPE_MAIN_AUDIO) return null
        if (format.audioType != "telephony" && format.audioType != "speechrecognition") return null
        val port = (stream["dataPort"] as? Number)?.toInt() ?: return null
        if (port !in 1..65535) return null
        val host = session.remoteAddress ?: return null
        val key = dataStreamKey(session, stream, DATASTREAM_INPUT_KEY) ?: return null
        val formatBits = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
        val framesPerPacket = (stream["framesPerPacket"] as? Number)?.toInt() ?: 0
        val frameMillis = if (format.codec == AudioCodecKind.OPUS) {
            20
        } else if (framesPerPacket > 0) {
            Math.round(framesPerPacket * 1000.0 / format.sampleRate).toInt().coerceIn(5, 60)
        } else {
            20
        }
        val opusBitrate = when {
            formatBits and OPUS_48K != 0L -> 96_000
            formatBits and OPUS_24K != 0L -> 64_000
            else -> 48_000
        }
        return MicrophoneConfig(
            audioType = format.audioType,
            sampleRate = format.sampleRate,
            channels = format.channels,
            payloadType = type,
            frameMillis = frameMillis,
            host = host,
            port = port,
            key = key,
            codec = format.codec,
            bitrate = if (format.codec == AudioCodecKind.OPUS) opusBitrate else null,
        )
    }

    private fun dataStreamKey(
        session: AirPlaySession,
        stream: Map<String, Any?>,
        label: String,
    ): ByteArray? {
        val shared = session.sharedSecret ?: return null
        val connectionId = unsignedPlistDecimal(stream["streamConnectionID"]) ?: return null
        return AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            label.toByteArray(Charsets.US_ASCII),
            32,
        )
    }

    private fun isScreenStreamType(type: Int): Boolean =
        type == STREAM_TYPE_MAIN_SCREEN || type == STREAM_TYPE_ALT_SCREEN

    private companion object {
        const val TAG = "xcertplay-usb"
        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_ALT_SCREEN = 111
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_DATA = 130
        const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
        const val OPUS_24K = 0x20000000L
        const val OPUS_48K = 0x40000000L
    }
}

internal fun unsignedPlistDecimal(value: Any?): String? = when (value) {
    is Long -> java.lang.Long.toUnsignedString(value)
    is Int -> Integer.toUnsignedString(value)
    is Short -> (value.toInt() and 0xffff).toString()
    is Byte -> (value.toInt() and 0xff).toString()
    is BigInteger -> if (value.signum() >= 0) value.toString() else null
    else -> (value as? Number)?.toLong()?.let(java.lang.Long::toUnsignedString)
}

internal fun unsignedPlistInteger(value: Any?): Any = when (value) {
    is Long -> if (value < 0) BigInteger(java.lang.Long.toUnsignedString(value)) else value
    is Int -> if (value < 0) BigInteger(Integer.toUnsignedString(value)) else value
    else -> value ?: 0L
}
