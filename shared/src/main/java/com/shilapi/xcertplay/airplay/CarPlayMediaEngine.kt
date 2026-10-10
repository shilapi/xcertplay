package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/** Rendering seam for the decrypted CarPlay media streams. */
interface MediaSink {
    fun onVideoCodec(type: Int, codec: VideoCodec) {}
    fun onVideoRecoveryHandler(type: Int, requestKeyFrame: (() -> Boolean)?) {}
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
 * decrypts their payloads, and hands decoded media to a [MediaSink]. Main audio streams can
 * additionally return microphone audio when the phone supplies an input port.
 */
class CarPlayMediaEngine(
    private val sink: MediaSink,
    private val microphoneEnabled: Boolean = false,
    private val audioCaptureDirectory: File? = null,
    private val videoPacketLog: ((Int, VideoCodec, Long, ByteArray, ByteArray, ByteArray?) -> Unit)? = null,
) : AirPlayMediaHandler {
    internal data class StreamKey(
        val session: AirPlaySession,
        val type: Int,
    )

    private data class AudioMeta(
        val type: Int,
        val format: AudioFormat,
        val connectionId: Any?,
        val playoutLatencyMs: Int,
        @Volatile var firstSample: Int? = null,
        @Volatile var originNs: Long? = null,
    )

    private data class PendingIapTunnel(
        val bridge: AirPlayIapTunnelStream,
        val handler: (BlockingDuplexByteStream) -> Boolean,
    )

    private val streams = ConcurrentHashMap<StreamKey, Closeable>()
    private val audioMeta = ConcurrentHashMap<Int, AudioMeta>()
    private val pendingMicrophone = ConcurrentHashMap<StreamKey, MicrophoneConfig>()
    private val startedMicrophone = ConcurrentHashMap.newKeySet<StreamKey>()
    private val audioCaptures = ConcurrentHashMap<Int, AudioPacketCapture>()
    private val pendingIapTunnels = ConcurrentHashMap<AirPlaySession, PendingIapTunnel>()
    @Volatile private var iapTunnelHandler: ((BlockingDuplexByteStream) -> Boolean)? = null

    override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
        iapTunnelHandler = handler
    }

    override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? {
        val key = outputKey(session, stream) ?: return null
        val streamKey = StreamKey(session, type)
        Log.i(TAG, "airplay screen key connectionID=${unsignedPlistDecimal(stream["streamConnectionID"])}")
        val screen = ScreenStream(key)
        var streamCodec = VideoCodec.H264
        var firstFrameLogged = false
        // The no-display-UUID forceKeyFrame command targets the primary screen.
        // Do not accidentally restart the main screen when the alternate decoder loses sync.
        if (type == STREAM_TYPE_MAIN_SCREEN) {
            sink.onVideoRecoveryHandler(type) {
                session.sendCommand(mainScreenKeyFrameCommand())
            }
        }
        val port = screen.listen(
            object : ScreenStream.Listener {
                override fun onCodec(codec: VideoCodec) {
                    streamCodec = codec
                    sink.onVideoCodec(type, codec)
                }
                override fun onPacket(sequence: Long, header: ByteArray, wire: ByteArray, plain: ByteArray?) {
                    videoPacketLog?.invoke(type, streamCodec, sequence, header, wire, plain)
                }
                override fun onConfig(codecData: ByteArray) {
                    session.logDebug("airplay screen config type=$type codec=$streamCodec bytes=${codecData.size}")
                    sink.onVideoConfig(type, codecData)
                }
                override fun onFrame(naluBytes: ByteArray) {
                    if (!firstFrameLogged) {
                        firstFrameLogged = true
                        session.logDebug("airplay screen first frame type=$type codec=$streamCodec bytes=${naluBytes.size}")
                    }
                    sink.onVideoFrame(type, naluBytes)
                }
                override fun onClosed(cause: Throwable?) {
                    Log.w(
                        TAG,
                        "screen stream ended type=$type reason=${cause?.message ?: "peer EOF"}",
                    )
                    if (streams.remove(streamKey, screen)) {
                        sink.onVideoRecoveryHandler(type, null)
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
        stopMicrophone(streamKey)
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
        if (microphone != null) pendingMicrophone[streamKey] = microphone

        val capture = audioCaptureDirectory?.let { AudioPacketCapture(it, type) }
        if (capture != null) audioCaptures[type] = capture
        val audio = AudioStream(key, type)
        val (dataPort, controlPort) = audio.listen(
            object : AudioStream.Listener {
                override fun onStarted(firstSample: Int) {
                    meta.firstSample = firstSample
                    meta.originNs = System.nanoTime()
                    sink.onAudioStarted(type, format, firstSample)
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
        val uuid = (stream["clientTypeUUID"] as? String)?.uppercase() ?: return null
        if (uuid != IAP_DATASTREAM_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        session.logDebug(
            "AirPlay iAP SETUP uuid=$uuid seed=$seed " +
                "streamConnectionID=${unsignedPlistDecimal(stream["streamConnectionID"]) ?: "none"}",
        )
        val key = AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            DATASTREAM_OUTPUT_KEY.toByteArray(Charsets.US_ASCII),
            32,
        )
        val tunnel = IapTunnel(
            readKey = key,
            bindAddress = if (session.isWireless) {
                InetAddress.getByName("::")
            } else {
                session.localAddress
                    ?: when (session.remoteAddress) {
                        is Inet6Address -> InetAddress.getByName("::")
                        is Inet4Address -> InetAddress.getByName("0.0.0.0")
                        else -> InetAddress.getByName("0.0.0.0")
                    }
            },
        )
        val bridge = AirPlayIapTunnelStream(session, tunnel)
        val handler = iapTunnelHandler
        val port = try {
            if (handler != null) {
                val boundPort = bridge.listen()
                session.logDebug(
                    "AirPlay iAP tunnel listening address=" +
                        "${if (session.isWireless) "::" else session.localAddress?.hostAddress ?: "wildcard"} " +
                        "port=$boundPort",
                )
                replacePendingIapTunnel(session, PendingIapTunnel(bridge, handler))
                boundPort
            } else {
                tunnel.listen(
                    object : IapTunnel.Listener {
                        override fun onIap(bytes: ByteArray) = sink.onIapMessage(bytes)

                        override fun onClosed(cause: Throwable?) {
                            Log.w(
                                TAG,
                                "iAP tunnel ended reason=${cause?.message ?: "peer EOF"}",
                            )
                            session.close()
                        }
                    },
                )
            }
        } catch (error: Throwable) {
            bridge.close()
            throw error
        }
        streams[StreamKey(session, STREAM_TYPE_DATA)] = if (handler != null) bridge else tunnel
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to 1L, "dataPort" to port)
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }
    }

    override fun onSetupResponseSent(session: AirPlaySession) {
        pendingMicrophone.forEach { (key, config) ->
            if (key.session === session && startedMicrophone.add(key)) {
                sink.onMicrophoneStarted(key.type, config)
            }
        }
        val pending = pendingIapTunnels.remove(session) ?: return
        val attached = try {
            pending.handler(pending.bridge)
        } catch (error: Throwable) {
            Log.w(TAG, "iAP tunnel relay attachment failed", error)
            false
        }
        if (!attached) {
            Log.w(TAG, "iAP tunnel relay attachment was rejected after SETUP")
            pending.bridge.close()
            session.close()
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
        if (type == STREAM_TYPE_DATA) clearPendingIapTunnel(session)
        stopMicrophone(StreamKey(session, type))
        audioMeta.remove(type)
        audioCaptures.remove(type)?.close()
        sink.onAudioStopped(type)
        streams.remove(StreamKey(session, type))?.close()
        if (isScreenStreamType(type)) {
            sink.onVideoRecoveryHandler(type, null)
            sink.onScreenStreamActive(type, false)
        }
    }

    override fun onSessionClosed(session: AirPlaySession) {
        clearPendingIapTunnel(session)
        pendingMicrophone.keys.filter { it.session === session }.forEach(::stopMicrophone)
        val sessionStreams = streams.keys.filter { it.session === session }
        sessionStreams
            .filter { isScreenStreamType(it.type) }
            .forEach {
                sink.onVideoRecoveryHandler(it.type, null)
                sink.onScreenStreamActive(it.type, false)
            }
        sessionStreams.forEach { streams.remove(it)?.close() }
        sessionStreams.filter { it.type in STREAM_TYPE_MAIN_AUDIO..STREAM_TYPE_MAIN_HIGH_AUDIO }
            .forEach { sink.onAudioStopped(it.type) }
        audioMeta.clear()
        audioCaptures.values.forEach(AudioPacketCapture::close)
        audioCaptures.clear()
    }

    private fun replacePendingIapTunnel(session: AirPlaySession, next: PendingIapTunnel) {
        val previous = pendingIapTunnels.put(session, next)
        previous?.bridge?.close()
    }

    private fun clearPendingIapTunnel(session: AirPlaySession? = null) {
        if (session == null) {
            val pending = pendingIapTunnels.values.toList()
            pendingIapTunnels.clear()
            pending.forEach { it.bridge.close() }
            return
        }
        pendingIapTunnels.remove(session)?.bridge?.close()
    }

    private fun outputKey(session: AirPlaySession, stream: Map<String, Any?>): ByteArray? {
        return dataStreamKey(session, stream, DATASTREAM_OUTPUT_KEY)
    }

    private fun stopMicrophone(key: StreamKey) {
        pendingMicrophone.remove(key)
        if (startedMicrophone.remove(key)) sink.onMicrophoneStopped(key.type)
    }

    private fun microphoneConfig(
        session: AirPlaySession,
        type: Int,
        stream: Map<String, Any?>,
        format: AudioFormat,
    ): MicrophoneConfig? {
        val port = requestedMicrophonePort(microphoneEnabled, type, stream) ?: return null
        if (format.codec == AudioCodecKind.AAC_LC) {
            Log.w(TAG, "microphone input requested with unsupported AAC-LC format")
            return null
        }
        val host = session.remoteAddress ?: return null
        val key = dataStreamKey(session, stream, DATASTREAM_INPUT_KEY) ?: return null
        val formatBits = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
        val micRate = if (format.codec == AudioCodecKind.OPUS) {
            AudioStreamCodec.opusCaptureRate(formatBits)
        } else {
            format.sampleRate
        }
        val framesPerPacket = (stream["framesPerPacket"] as? Number)?.toInt() ?: 0
        val frameMillis = if (format.codec == AudioCodecKind.OPUS) {
            20
        } else if (framesPerPacket > 0) {
            Math.round(framesPerPacket * 1000.0 / micRate).toInt().coerceIn(5, 60)
        } else {
            20
        }
        val opusBitrate = if (micRate <= 24_000) 48_000 else 96_000
        return MicrophoneConfig(
            audioType = format.audioType,
            sampleRate = micRate,
            channels = if (format.codec == AudioCodecKind.OPUS) 1 else format.channels,
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
        const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
        const val STREAM_TYPE_DATA = 130
        const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
        const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
    }
}

/** The input port, rather than the downlink category, signals that the phone requests a mic. */
internal fun requestedMicrophonePort(
    microphoneEnabled: Boolean,
    type: Int,
    stream: Map<String, Any?>,
): Int? {
    if (!microphoneEnabled || type != 100) return null
    return (stream["dataPort"] as? Number)?.toLong()?.takeIf { it in 1..65535 }?.toInt()
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

/** AirPlaySender carEndpoint_forceKeyFrame defaults to the primary stream with empty params. */
internal fun mainScreenKeyFrameCommand(): Map<String, Any?> =
    linkedMapOf("type" to "forceKeyFrame", "params" to emptyMap<String, Any?>())
