package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoAccessUnit
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Call [close] when the session tears down.
 */
class AndroidMediaSink(
    private val context: Context,
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val mainMediaAudioBufferDurationMs: Int = MainMediaAudioBuffer.DEFAULT_DURATION_MS,
    private val microphoneGainPercent: Int = MicrophoneGain.DEFAULT_PERCENT,
    mediaMetricsMonitor: MediaMetricsMonitor? = null,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val videoSizes: Map<Int, Pair<Int, Int>> = emptyMap(),
    private val mediaTap: MediaTap? = null,
    private val onClosed: () -> Unit = {},
    private val audioFocus: AudioFocusSession? = null,
) : MediaSink {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Boolean>()
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoConfigs = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val previewLock = Any()
    @Volatile private var mediaMetricsMonitor = mediaMetricsMonitor

    init {
        audioFocus?.retain(this)
    }

    fun setSurface(type: Int, surface: Surface) {
        surfaces[type] = surface
        if (!forwarded(type)) {
            videoDecoders[type]?.setSurface(surface)
            return
        }
        // A forwarded stream has no local decoder until a preview appears; start at the last config.
        synchronized(previewLock) {
            val decoder = videoDecoders[type]
            if (decoder != null) decoder.setSurface(surface)
            else videoConfigs[type]?.let { (codec, data) -> videoDecoder(type, midStream = true).configure(codec, data) }
        }
    }

    fun clearSurface(type: Int, surface: Surface) {
        if (!forwarded(type)) {
            if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
            return
        }
        // Holding frames for a future Surface would apply backpressure to the forwarded stream.
        synchronized(previewLock) {
            if (surfaces.remove(type, surface)) videoDecoders.remove(type)?.apply { setSurface(null) }?.close()
        }
    }

    /** Asks the phone for a random access picture; false when the stream cannot be asked. */
    fun requestKeyFrame(type: Int): Boolean = videoRecoveryHandlers[type]?.invoke() ?: false

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        screenStreamActiveChanged = listener
    }

    fun setMediaMetricsMonitor(monitor: MediaMetricsMonitor?) {
        mediaMetricsMonitor = monitor
        videoDecoders.values.forEach { it.setMediaMetricsMonitor(monitor) }
        audioRenderers.values.forEach { it.setMediaMetricsMonitor(monitor) }
    }

    override fun onVideoRecoveryHandler(type: Int, requestKeyFrame: (() -> Boolean)?) {
        if (requestKeyFrame == null) videoRecoveryHandlers.remove(type)
        else videoRecoveryHandlers[type] = requestKeyFrame
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        videoConfigs[type] = codec to codecData.copyOf()
        if (!forwarded(type)) {
            videoDecoder(type).configure(codec, codecData)
            return
        }
        mediaTap?.onVideoConfig(type, codec, codecData.copyOf())
        synchronized(previewLock) {
            if (surfaces[type] != null) videoDecoder(type).configure(codec, codecData)
        }
    }

    override fun onVideoFrame(type: Int, accessUnit: VideoAccessUnit) {
        val arrivalNs = System.nanoTime()
        val arrivalUs = arrivalNs / 1_000L
        if (type == STREAM_TYPE_MAIN_SCREEN) mediaMetricsMonitor?.recordVideoFrameReceived(arrivalNs)
        if (!forwarded(type)) {
            videoDecoder(type).submit(accessUnit, arrivalUs)
            return
        }
        if (mediaTap?.wantsVideoFrames(type) == true) {
            val forwardedUnit = MediaCodecSupport.toLengthPrefixed(accessUnit.bytes.copyOf(accessUnit.size))
            if (forwardedUnit.isNotEmpty()) mediaTap.onVideoFrame(type, forwardedUnit, arrivalUs)
        }
        // Only a preview decoder created with a Surface may take frames; it never holds them.
        val decoder = videoDecoders[type]
        if (decoder != null) decoder.submit(accessUnit, arrivalUs) else accessUnit.close()
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) {
            videoDecoders.remove(type)?.close()
            pendingVideoCodec.remove(type)
            videoConfigs.remove(type)
            videoRecoveryHandlers.remove(type)
        }
        mediaTap?.onVideoActive(type, active)
        screenStreamActiveChanged?.invoke(type, active)
    }

    private fun forwarded(type: Int) = mediaTap?.forwardsVideo(type) == true

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        audioRenderer(id, format).start()
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(id, format).submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        audioRenderers.remove(id)?.close()
        mediaTap?.onAudioStopped(id.type)
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        val uplink = microphoneUplinks.computeIfAbsent(id) {
            MicrophoneUplink(context, config, microphoneGainPercent)
        }
        if (!uplink.start()) microphoneUplinks.remove(id, uplink)
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        microphoneUplinks.remove(id)?.close()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        videoRecoveryHandlers.clear()
        pendingVideoCodec.clear()
        videoConfigs.clear()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        microphoneUplinks.values.forEach(MicrophoneUplink::close)
        microphoneUplinks.clear()
        audioFocus?.release(this)
        onClosed()
    }

    private fun videoDecoder(type: Int, midStream: Boolean = false): VideoDecoder =
        videoDecoders.computeIfAbsent(type) {
            VideoDecoder(
                surfaces[type] ?: defaultSurface,
                videoSizes[type]?.first ?: videoWidth,
                videoSizes[type]?.second ?: videoHeight,
                preferSoftwareHevcDecoder,
                mediaMetricsMonitor,
                requestKeyFrame = { videoRecoveryHandlers[type]?.invoke() ?: false },
                midStream = midStream,
            )
        }

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[id]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(
            id.type,
            format,
            advancedAudioChannelMapping,
            mainMediaAudioBufferDurationMs,
            mediaMetricsMonitor,
            mediaTap,
        ).also {
            audioRenderers[id] = it
        }
    }

    companion object {
        private const val STREAM_TYPE_MAIN_SCREEN = 110

        fun createAudioFocus(context: Context): AudioFocusSession = AudioFocusSession(
            context.applicationContext.getSystemService(AudioManager::class.java),
        )
    }

    /** Shared by the visible host and the background media pipeline. */
    class AudioFocusSession internal constructor(private val manager: AudioManager) {
        private val owners = mutableSetOf<Any>()
        private var enabled = true
        private var requested = false
        private var hasFocus = false
        private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(::onFocusChanged, Handler(Looper.getMainLooper()))
            .build()

        @Synchronized
        fun retain(owner: Any) {
            owners.add(owner)
        }

        /** When disabled, focus is never requested so phone audio mixes with other apps. */
        @Synchronized
        fun setEnabled(value: Boolean) {
            if (enabled == value) return
            enabled = value
            if (!value && requested) abandon()
            Log.i(TAG, "audio focus ${if (value) "enabled" else "disabled"}")
        }

        /** Called only when the user enters the host, never by arriving audio packets. */
        @Synchronized
        fun requestFromHost() {
            if (!enabled || owners.isEmpty() || hasFocus) return
            val result = manager.requestAudioFocus(request)
            requested = result != AudioManager.AUDIOFOCUS_REQUEST_FAILED
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.i(TAG, "audio focus request result=$result")
        }

        @Synchronized
        fun release(owner: Any) {
            owners.remove(owner)
            if (owners.isNotEmpty()) return
            abandon()
        }

        private fun abandon() {
            manager.abandonAudioFocusRequest(request)
            requested = false
            hasFocus = false
            Log.i(TAG, "audio focus released")
        }

        @Synchronized
        private fun onFocusChanged(change: Int) {
            if (owners.isEmpty() || !requested) return
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> hasFocus = true
                AudioManager.AUDIOFOCUS_LOSS -> {
                    requested = false
                    hasFocus = false
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                -> hasFocus = false
            }
            // Focus tracks ownership only; the phone's audio keeps flowing at its normal volume.
            Log.i(TAG, "audio focus change=$change hasFocus=$hasFocus")
        }

        private companion object {
            const val TAG = "xcertplay-usb"
        }
    }
}

private sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val accessUnit: VideoAccessUnit, val receivedUs: Long) : VideoJob
    /** Wakes an idle worker to notice a Surface change. */
    data object Wake : VideoJob
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    mediaMetricsMonitor: MediaMetricsMonitor?,
    private val requestKeyFrame: () -> Boolean,
    /** Joining a running stream: the next random access picture may be a GOP away, or never on a static screen. */
    midStream: Boolean = false,
) : Closeable {
    private val queue = VideoWorkQueue<VideoJob>(8) { job ->
        if (job is VideoJob.Frame) job.accessUnit.close()
    }
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var pump: VideoDecodePump? = null
    private var syncGate: VideoSyncGate? = null
    private var pendingSinceNs = 0L
    /** Pictures queued to the codec and not yet released; the worker waits on output only for these. */
    private var inFlight = 0
    private var lastQueuedNs = 0L
    private var lastPtsUs = 0L
    private var statsStartNs = System.nanoTime()
    private var inputCount = 0
    private var outputCount = 0
    private var inputRetries = 0
    private var syncSkips = 0
    private var maxOutputAgeUs = 0L
    private var needsKeyFrame = midStream
    private var lastKeyFrameRequestNs = 0L
    @Volatile private var requestedSurface: Surface? = surface
    @Volatile private var detached: CountDownLatch? = null
    private var outputSurface: Surface? = surface
    private var heldFrame: VideoJob.Frame? = null
    private var lastConfig: VideoJob.Config? = null
    private val drainInfo = MediaCodec.BufferInfo()
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var awaitingConfigLogged = false
    @Volatile private var mediaMetricsMonitor = mediaMetricsMonitor
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.control(VideoJob.Config(codec, codecData.copyOf()))
    }

    fun submit(accessUnit: VideoAccessUnit, arrivalUs: Long) {
        if (!queue.frame(VideoJob.Frame(accessUnit, arrivalUs))) accessUnit.close()
    }

    /**
     * Clearing waits until the worker has let go of the old Surface. A SurfaceView must not be
     * drawn to once it is destroyed, and it hands out the same Surface object for its next
     * window, so a detach the worker never saw would also hide the swap.
     */
    fun setSurface(surface: Surface?) {
        if (surface != null || Thread.currentThread() === thread || !thread.isAlive) {
            requestedSurface = surface
            wake()
            return
        }
        val latch = CountDownLatch(1)
        detached = latch
        requestedSurface = null
        wake()
        if (!latch.await(SURFACE_DETACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "video decoder did not detach from surface within ${SURFACE_DETACH_TIMEOUT_MS}ms")
        }
    }

    fun setMediaMetricsMonitor(monitor: MediaMetricsMonitor?) {
        mediaMetricsMonitor = monitor
    }

    override fun close() {
        running = false
        queue.close()
        thread.interrupt()
    }

    /** The worker blocks instead of polling, so a Surface change must interrupt its wait. */
    private fun wake() {
        queue.control(VideoJob.Wake)
        LockSupport.unpark(thread)
    }

    private fun run() {
        // Decode/render cadence is what the driver sees; keep it ahead of background work.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        try {
            while (running) {
                try {
                    if (outputSurface !== requestedSurface) changeSurface(requestedSurface)
                    if (outputSurface == null) detached?.countDown()
                    if (needsKeyFrame && outputSurface != null &&
                        System.nanoTime() - lastKeyFrameRequestNs >= KEY_FRAME_REQUEST_INTERVAL_NS
                    ) {
                        lastKeyFrameRequestNs = System.nanoTime()
                        Log.i(TAG, "video recovery key frame requested sent=${requestKeyFrame()}")
                    }
                    // Poll output independently of input arrival, including the final/static frame.
                    pump?.tick()
                    logVideoStats()
                    if (pump?.hasPending == true) {
                        check(System.nanoTime() - pendingSinceNs < INPUT_STALL_NS) {
                            "Video decoder input stalled; waiting for a new random access frame"
                        }
                        Thread.sleep(2)
                        continue
                    }
                    if (heldFrame != null && outputSurface == null) {
                        // setSurface unparks; the timeout only bounds a missed wake.
                        LockSupport.parkNanos(IDLE_WAIT_NS)
                        continue
                    }
                    when (val job = heldFrame ?: nextJob()) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            // Preserve the initial random access picture until a Surface exists.
                            heldFrame = if (outputSurface == null) job else null
                            if (outputSurface != null) feed(job)
                        }
                        VideoJob.Wake, null -> Unit
                    }
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder failed; restarting at random access", error)
                    releaseDecoder()
                    needsKeyFrame = true
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            heldFrame?.accessUnit?.close()
            heldFrame = null
            releaseDecoder()
            detached?.countDown()
        }
    }

    /**
     * Waits for work without spinning: on the codec while a picture is still decoding (so the
     * final or only picture of a static screen is rendered promptly), otherwise on the queue.
     */
    private fun nextJob(): VideoJob? {
        queue.poll(0)?.let { return it }
        val codec = decoder
        if (codec != null && inFlight > 0) {
            if (System.nanoTime() - lastQueuedNs < OUTPUT_WAIT_LIMIT_NS) {
                drainOutput(codec, OUTPUT_WAIT_US)
                return null
            }
            // A picture the codec dropped never comes out; stop waiting on it.
            inFlight = 0
        }
        return queue.poll(IDLE_WAIT_MS)
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        awaitingConfigLogged = false
        releaseDecoder()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd: ByteArray
        val pps: ByteArray?
        if (codec == VideoCodec.H265) {
            csd = MediaCodecSupport.hevcCodecSpecificData(codecData)
            pps = null
        } else {
            val sets = MediaCodecSupport.avcParameterSets(codecData)
            require(sets.first.isNotEmpty() && sets.second.isNotEmpty()) { "Missing AVC parameter sets" }
            csd = START_CODE + sets.first
            pps = START_CODE + sets.second
        }
        require(csd.isNotEmpty()) { "Missing or invalid video parameter sets" }
        val parameters = VideoParameters.parse(codec, csd)
        val format = MediaFormat.createVideoFormat(mime, parameters.width, parameters.height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            if (pps != null) setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            // Unspecified VUI fields remain unspecified; do not force full range or BT.709.
            if (parameters.colorStandard != -1) setInteger(MediaFormat.KEY_COLOR_STANDARD, parameters.colorStandard)
            if (parameters.colorRange != -1) setInteger(MediaFormat.KEY_COLOR_RANGE, parameters.colorRange)
            if (parameters.colorTransfer != -1) setInteger(MediaFormat.KEY_COLOR_TRANSFER, parameters.colorTransfer)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val next = createDecoder(mime)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                next.codecInfo.getCapabilitiesForType(mime)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            ) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            next.configure(format, surface, null, 0)
            next.start()
        } catch (error: Exception) {
            next.release()
            throw error
        }
        decoder = next
        syncGate = VideoSyncGate(codec)
        pump = VideoDecodePump(object : VideoDecodePump.Port {
            override fun drain() = drainOutput(next)
            override fun queue(bytes: ByteArray, size: Int, presentationTimeUs: Long): Boolean {
                val index = next.dequeueInputBuffer(0)
                if (index < 0) {
                    inputRetries++
                    return false
                }
                val input = checkNotNull(next.getInputBuffer(index)) { "Missing video input buffer" }
                input.clear()
                check(size <= input.remaining()) { "Video access unit exceeds decoder input capacity" }
                input.put(bytes, 0, size)
                next.queueInputBuffer(index, 0, size, presentationTimeUs, 0)
                inputCount++
                inFlight++
                lastQueuedNs = System.nanoTime()
                return true
            }
        })
        Log.i(TAG, "video SPS parameters=$parameters negotiated=${width}x$height inputFormat=$format")
        renderedFrameLogged = false
        submittedFrameLogged = false
        Log.i(TAG, "video decoder configured name=${next.name} mime=$mime; waiting for random access")
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                // The new Surface stays empty until the next picture, which a static screen never sends.
                needsKeyFrame = true
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(frame: VideoJob.Frame) {
        val accessUnit = frame.accessUnit
        if (decoder == null) {
            if (outputSurface == null) { accessUnit.close(); return }
            val config = lastConfig
            if (config == null) {
                // The phone emits its codec config (opcode=1) before the first
                // frame; frames without one mean it skipped that step (seen with
                // HEVC). Nudge it instead of dropping frames forever.
                if (!awaitingConfigLogged) {
                    awaitingConfigLogged = true
                    Log.i(TAG, "video frames without codec config; requesting keyframe")
                }
                needsKeyFrame = true
                accessUnit.close()
                return
            }
            try { configureDecoder(config) } catch (error: Throwable) {
                accessUnit.close()
                throw error
            }
        }
        val activePump = pump ?: run { accessUnit.close(); return }
        val accepted = try {
            syncGate?.accept(accessUnit.bytes, accessUnit.size) == true
        } catch (error: Throwable) {
            accessUnit.close()
            throw error
        }
        if (!accepted) {
            // RASL intentionally skipped after CRA does not mean sync was lost.
            if (syncGate?.waitingForRandomAccess == true) {
                needsKeyFrame = true
                syncSkips++
            }
            accessUnit.close()
            return
        }
        needsKeyFrame = false
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(TAG, "video decoder first random access input bytes=${accessUnit.size}")
        }
        lastPtsUs = maxOf(lastPtsUs + 1, frame.receivedUs)
        try {
            activePump.submit(accessUnit.bytes, accessUnit.size, lastPtsUs, accessUnit::close)
        } catch (error: Throwable) {
            accessUnit.close()
            throw error
        }
        pendingSinceNs = System.nanoTime()
        activePump.tick()
    }

    private fun drainOutput(codec: MediaCodec, firstTimeoutUs: Long = 0) {
        val info = drainInfo
        var timeoutUs = firstTimeoutUs
        while (running) {
            val index = codec.dequeueOutputBuffer(info, timeoutUs)
            timeoutUs = 0
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val render = outputSurface != null &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    outputCount++
                    if (inFlight > 0) inFlight--
                    maxOutputAgeUs = maxOf(maxOutputAgeUs, System.nanoTime() / 1000 - info.presentationTimeUs)
                    codec.releaseOutputBuffer(index, render)
                    if (render) {
                        // feed() assigns the System.nanoTime-based arrival as input PTS. This
                        // measurement assumes MediaCodec preserves that PTS in BufferInfo.
                        mediaMetricsMonitor?.recordVideoFrameRendered(
                            arrivalNs = info.presentationTimeUs * 1_000L,
                            renderedNs = System.nanoTime(),
                        )
                    }
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logVideoStats() {
        val now = System.nanoTime()
        if (now - statsStartNs < 5_000_000_000L) return
        if (inputCount + outputCount + inputRetries + syncSkips > 0) {
            val seconds = (now - statsStartNs) / 1e9
            Log.i(TAG, "video stats inputFps=${inputCount / seconds} decodedFps=${outputCount / seconds} " +
                "inputRetries=$inputRetries syncSkips=$syncSkips queued=${queue.size} " +
                "maxOutputAgeMs=${maxOutputAgeUs / 1000} waitingForSync=${syncGate?.waitingForRandomAccess}")
        }
        statsStartNs = now
        inputCount = 0
        outputCount = 0
        inputRetries = 0
        syncSkips = 0
        maxOutputAgeUs = 0
    }

    private fun logOutputFormat(format: MediaFormat) {
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        pump?.clear()
        pump = null
        syncGate = null
        inFlight = 0
        if (codec != null) {
            needsKeyFrame = true
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_STALL_NS = 2_000_000_000L
        const val KEY_FRAME_REQUEST_INTERVAL_NS = 2_000_000_000L
        const val SURFACE_DETACH_TIMEOUT_MS = 1_000L
        /** Longest single block on the codec, so a newly queued picture is fed without much delay. */
        const val OUTPUT_WAIT_US = 8_000L
        /** After this, a queued picture with no output is treated as dropped by the codec. */
        const val OUTPUT_WAIT_LIMIT_NS = 500_000_000L
        /** Idle wait; Surface changes and new work wake the worker earlier. */
        const val IDLE_WAIT_MS = 100L
        const val IDLE_WAIT_NS = IDLE_WAIT_MS * 1_000_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    private val stream: Int,
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val mainMediaAudioBufferDurationMs: Int,
    mediaMetricsMonitor: MediaMetricsMonitor?,
    private val mediaTap: MediaTap?,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private var trackBytesPerFrame = 0
    private var totalBytesWritten = 0L
    private var playbackHeadWraps = 0L
    private var lastPlaybackHeadRaw = 0L
    private var lastPlaybackHeadFrames = 0L
    private val audioTimestamp = AudioTimestamp()
    private var lastAudioTimestampQueryNs = 0L
    private var timestampHeadWraps = 0L
    private var lastTimestampHeadRaw = 0L
    private var lastTimestampHeadFrames = 0L
    private var latestOutputLatencyMs: Float? = null
    @Volatile private var mediaMetricsMonitor = mediaMetricsMonitor
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
            }
        }
    }

    fun setMediaMetricsMonitor(monitor: MediaMetricsMonitor?) {
        mediaMetricsMonitor = monitor
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        try {
            when (format.codec) {
                AudioCodecKind.AAC_LC, AudioCodecKind.AAC_ELD, AudioCodecKind.OPUS -> configureCodec()
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            while (running) handle(queue.take())
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) Log.e(TAG, "audio renderer worker failed", error)
        } finally {
            release()
        }
    }

    private fun configureCodec() {
        // 与 AudioQualityCapabilities 的探测走同一套格式和解码器选择，探测通过就一定能在这里打开。
        val mediaFormat = AudioDecoders.format(format.codec, format.sampleRate, format.channels, format.framesPerPacket)
        val mime = mediaFormat.getString(MediaFormat.KEY_MIME)
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            val (decoder, name) = AudioDecoders.open(mediaFormat, AudioDecoders.candidates(format.codec))
            Log.i(TAG, "audio decoder configured mime=$mime name=$name")
            decoder
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            null
        }
    }

    private fun createTrack() {
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val trackChannelCount = if (format.channels >= 2) 2 else 1
        val channelMask = if (trackChannelCount == 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            return
        }
        val configuredMainMediaBuffer = MainMediaAudioBuffer.isMainMedia(
            audioType = format.audioType,
            payloadType = format.payloadType,
        )
        val bufferBytes = if (configuredMainMediaBuffer) {
            MainMediaAudioBuffer.bufferSizeBytes(
                durationMs = mainMediaAudioBufferDurationMs,
                sampleRate = format.sampleRate,
                channelCount = trackChannelCount,
                minBufferBytes = minBuffer,
            )
        } else {
            maxOf(minBuffer * 4, MIN_TRACK_BUFFER_BYTES)
        }
        startThresholdBytes = if (format.audioType == "telephony" || format.audioType == "speechrecognition") {
            maxOf(minBuffer, MIN_START_BUFFER_BYTES)
        } else {
            maxOf(minBuffer, MIN_START_BUFFER_BYTES)
        }
        track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes())
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // AudioTrack consumes 16-bit PCM, so frame size follows the channel mask configured above.
        trackBytesPerFrame = trackChannelCount * BYTES_PER_PCM_16_SAMPLE
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "bufferBytes=$bufferBytes" +
                if (configuredMainMediaBuffer) {
                    " configuredDurationMs=${MainMediaAudioBuffer.sanitizeDurationMs(mainMediaAudioBufferDurationMs)}"
                } else {
                    ""
                },
        )
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        if (format.codec == AudioCodecKind.AAC_ELD) {
            return AacEldFormat.audioSpecificConfig(format.sampleRate, format.channels,
                format.framesPerPacket ?: AacEldFormat.DEFAULT_FRAMES_PER_PACKET)
        }
        return AacLcFormat.audioSpecificConfig(format.sampleRate, format.channels)
    }

    private fun audioAttributes(): AudioAttributes {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        val selection = AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
        val usage = usageFor(selection.channel)
        val contentType = contentTypeFor(selection.contentType)
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()
            .also {
                Log.i(
                    TAG,
                    "audio route type=${format.payloadType} audioType=${format.audioType} " +
                        "mode=$mode channel=${selection.channel} " +
                        "usage=$usage contentType=$contentType",
                )
            }
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size} " +
                                "head=${accessUnit.copyOf(minOf(accessUnit.size, 16)).toHexString()}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.AAC_ELD -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) feedCodec(accessUnit, timestampUs)
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size} " +
                                "head=${accessUnit.toHexString()}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: return
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size} " +
                        "head=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (mediaTap?.takesAudio() == true) {
            forwardPcm(data, offset, length)
            return
        }
        val track = track ?: return
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            val end = minOf(data.size, offset + minOf(length, 16))
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length " +
                    "head=${data.copyOfRange(offset, end).toHexString()}",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val count = track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            if (count <= 0) break
            written += count
            totalBytesWritten += count
            val monitor = mediaMetricsMonitor
            if (monitor != null) {
                val nowNs = System.nanoTime()
                val totalFramesWritten = totalBytesWritten / trackBytesPerFrame
                monitor.recordAudioBuffer(
                    totalFramesWritten = totalFramesWritten,
                    playbackHeadFrames = playbackHeadFrames(track),
                    sampleRate = format.sampleRate,
                    nowNs = nowNs,
                    outputLatencyMs = outputLatencyMs(track, totalFramesWritten, nowNs),
                )
            }
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    track.play()
                    playbackStarted = true
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
    }

    /** Remote playback silences the local track; returning to it starts over with prebuffering. */
    private fun forwardPcm(data: ByteArray, offset: Int, length: Int) {
        val tap = mediaTap ?: return
        val track = track
        if (track != null && (playbackStarted || prebufferBytes > 0)) {
            try {
                track.pause()
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            playbackStarted = false
            prebufferBytes = 0
            fadeApplied = false
            Log.i(TAG, "audio forwarded remotely type=${format.payloadType}")
        }
        tap.onAudioPcm(stream, format.sampleRate, if (format.channels >= 2) 2 else 1, data, offset, length)
    }

    private fun playbackHeadFrames(track: AudioTrack): Long {
        val raw = track.playbackHeadPosition.toLong() and UINT32_MASK
        if (raw < lastPlaybackHeadRaw && lastPlaybackHeadRaw - raw > UINT32_HALF_RANGE) {
            playbackHeadWraps++
        }
        lastPlaybackHeadRaw = raw
        val unwrapped = playbackHeadWraps * UINT32_MODULUS + raw
        lastPlaybackHeadFrames = maxOf(lastPlaybackHeadFrames, unwrapped)
        return lastPlaybackHeadFrames
    }

    private fun outputLatencyMs(
        track: AudioTrack,
        totalFramesWritten: Long,
        nowNs: Long,
    ): Float? {
        if (!playbackStarted) return null
        if (nowNs - lastAudioTimestampQueryNs < AUDIO_TIMESTAMP_INTERVAL_NS) {
            return latestOutputLatencyMs
        }
        lastAudioTimestampQueryNs = nowNs
        if (!track.getTimestamp(audioTimestamp)) {
            latestOutputLatencyMs = null
            return null
        }
        val raw = audioTimestamp.framePosition and UINT32_MASK
        if (raw < lastTimestampHeadRaw && lastTimestampHeadRaw - raw > UINT32_HALF_RANGE) {
            timestampHeadWraps++
        }
        lastTimestampHeadRaw = raw
        val unwrapped = timestampHeadWraps * UINT32_MODULUS + raw
        lastTimestampHeadFrames = maxOf(lastTimestampHeadFrames, unwrapped)
        val elapsedNs = (nowNs - audioTimestamp.nanoTime).coerceAtLeast(0L)
        val elapsedFrames = (elapsedNs.toDouble() * format.sampleRate / NANOS_PER_SECOND).toLong()
        val presentedFrames = minOf(totalFramesWritten, lastTimestampHeadFrames + elapsedFrames)
        return MediaMetricsMonitor.audioLatencyMs(
            totalFramesWritten,
            presentedFrames,
            format.sampleRate,
        ).also { latestOutputLatencyMs = it }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MIN_OPUS_PACKET_BYTES = 4
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_QUEUED_PACKETS = 64
        const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
        const val MIN_START_BUFFER_BYTES = 4 * 1024
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        const val AUDIO_TIMESTAMP_INTERVAL_NS = 200_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val BYTES_PER_PCM_16_SAMPLE = 2
        const val UINT32_MASK = 0xffff_ffffL
        const val UINT32_HALF_RANGE = 0x8000_0000L
        const val UINT32_MODULUS = 0x1_0000_0000L
    }
}
