package com.shilapi.xcertplay.media

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Lock-free media event accumulator with a UI-side rolling chart history.
 *
 * [Snapshot.fps] is the count of frames released to the video Surface during the previous second.
 * [Snapshot.videoLatencyMs] is the one-second average from arrival at
 * [AndroidMediaSink.onVideoFrame] until that frame is released to the Surface.
 * [Snapshot.audioLatencyMs] is the one-second average of locally buffered PCM: total frames
 * written to AudioTrack minus its playback-head position.
 * [Snapshot.audioOutputLatencyMs] additionally carries the hardware-timestamp estimate when
 * AudioTrack provides one.
 * Event writers do not allocate or acquire locks. [snapshot] may allocate and lock because it is
 * intended to run from the UI thread at a low fixed rate.
 */
class MediaMetricsMonitor(
    enabled: Boolean = true,
    chartWindowNs: Long = DEFAULT_CHART_WINDOW_NS,
    private val chartSampleCapacity: Int = DEFAULT_CHART_SAMPLE_CAPACITY,
    eventCapacity: Int = DEFAULT_EVENT_CAPACITY,
) {
    val chartWindowNs = chartWindowNs.coerceAtLeast(1L)

    data class Sample(
        val timestampNs: Long,
        val videoLatencyMs: Float,
        val fps: Float,
    )

    data class Snapshot(
        val timestampNs: Long,
        val videoLatencyMs: Float,
        val maxVideoLatencyMs: Float,
        val fps: Float,
        val audioLatencyMs: Float,
        val audioOutputLatencyMs: Float?,
        val chartWindowNs: Long,
        val samples: List<Sample>,
    )

    private val enabledState = AtomicBoolean(enabled)
    private val eventCapacity = eventCapacity.coerceAtLeast(1)
    private val videoSequence = AtomicLong()
    private val videoTimestampsNs = AtomicLongArray(this.eventCapacity)
    private val videoLatenciesUs = AtomicLongArray(this.eventCapacity)
    private val audioSequence = AtomicLong()
    private val audioTimestampsNs = AtomicLongArray(this.eventCapacity)
    private val audioLatenciesUs = AtomicLongArray(this.eventCapacity)
    private val audioOutputLatenciesUs = AtomicLongArray(this.eventCapacity)
    private val maxVideoLatencyUs = AtomicLong()

    private val chartLock = Any()
    private val chartTimestampsNs = LongArray(chartSampleCapacity.coerceAtLeast(1))
    private val chartVideoLatencyMs = FloatArray(chartTimestampsNs.size)
    private val chartFps = FloatArray(chartTimestampsNs.size)
    private var chartSequence = 0L

    fun setEnabled(enabled: Boolean) {
        if (enabledState.getAndSet(enabled) == enabled) return
        clear()
    }

    /**
     * Records a frame whose timestamps share the [System.nanoTime] clock domain.
     *
     * AndroidMediaSink correlates MediaCodec output PTS to the local frame-arrival time, even
     * when input PTS comes from the sender clock. Non-positive or implausibly large
     * deltas are discarded so a future switch to a remote/RTP clock cannot corrupt the metrics.
     */
    fun recordVideoFrameRendered(arrivalNs: Long, renderedNs: Long) {
        if (!enabledState.get()) return
        if (renderedNs <= arrivalNs) return
        val latencyNs = renderedNs - arrivalNs
        if (latencyNs > MAX_VIDEO_LATENCY_NS) return
        val latencyUs = latencyNs / NANOS_PER_MICROSECOND
        val index = (videoSequence.getAndIncrement() % eventCapacity).toInt()
        videoLatenciesUs.set(index, latencyUs)
        videoTimestampsNs.set(index, renderedNs)
        updateMax(maxVideoLatencyUs, latencyUs)
    }

    fun recordAudioBuffer(
        totalFramesWritten: Long,
        playbackHeadFrames: Long,
        sampleRate: Int,
        nowNs: Long,
        outputLatencyMs: Float? = null,
    ) {
        if (!enabledState.get()) return
        val latencyUs = (audioLatencyMs(totalFramesWritten, playbackHeadFrames, sampleRate) *
            MICROSECONDS_PER_MILLISECOND).toLong()
        val index = (audioSequence.getAndIncrement() % eventCapacity).toInt()
        audioLatenciesUs.set(index, latencyUs)
        audioOutputLatenciesUs.set(
            index,
            outputLatencyMs?.takeIf { it.isFinite() }?.coerceAtLeast(0f)
                ?.times(MICROSECONDS_PER_MILLISECOND)?.toLong() ?: OUTPUT_LATENCY_UNAVAILABLE,
        )
        audioTimestampsNs.set(index, nowNs)
    }

    fun snapshot(nowNs: Long = System.nanoTime()): Snapshot {
        if (!enabledState.get()) return emptySnapshot(nowNs)
        val cutoffNs = nowNs - METRIC_AVERAGE_WINDOW_NS
        var renderedFrames = 0
        var videoLatencyTotalUs = 0L
        var videoLatencyCount = 0
        for (index in 0 until eventCapacity) {
            val timestampNs = videoTimestampsNs.get(index)
            if (timestampNs > cutoffNs && timestampNs <= nowNs) {
                renderedFrames++
                videoLatencyTotalUs += videoLatenciesUs.get(index)
                videoLatencyCount++
            }
        }
        var audioLatencyTotalUs = 0L
        var audioLatencyCount = 0
        var audioOutputLatencyTotalUs = 0L
        var audioOutputLatencyCount = 0
        for (index in 0 until eventCapacity) {
            val timestampNs = audioTimestampsNs.get(index)
            if (timestampNs > cutoffNs && timestampNs <= nowNs) {
                audioLatencyTotalUs += audioLatenciesUs.get(index)
                audioLatencyCount++
                val outputLatencyUs = audioOutputLatenciesUs.get(index)
                if (outputLatencyUs >= 0L) {
                    audioOutputLatencyTotalUs += outputLatencyUs
                    audioOutputLatencyCount++
                }
            }
        }
        val fps = renderedFrames.toFloat()
        val videoLatencyMs = averageMilliseconds(videoLatencyTotalUs, videoLatencyCount)
        val audioLatencyMs = averageMilliseconds(audioLatencyTotalUs, audioLatencyCount)
        val samples = synchronized(chartLock) {
            if (videoSequence.get() > 0L) appendChartSample(nowNs, videoLatencyMs, fps)
            chartSamples(nowNs - chartWindowNs)
        }
        return Snapshot(
            timestampNs = nowNs,
            videoLatencyMs = videoLatencyMs,
            maxVideoLatencyMs = maxVideoLatencyUs.get() / MICROSECONDS_PER_MILLISECOND,
            fps = fps,
            audioLatencyMs = audioLatencyMs,
            audioOutputLatencyMs = if (audioOutputLatencyCount == 0) {
                null
            } else {
                averageMilliseconds(audioOutputLatencyTotalUs, audioOutputLatencyCount)
            },
            chartWindowNs = chartWindowNs,
            samples = samples,
        )
    }

    private fun appendChartSample(timestampNs: Long, videoLatencyMs: Float, fps: Float) {
        val index = (chartSequence % chartTimestampsNs.size).toInt()
        chartTimestampsNs[index] = timestampNs
        chartVideoLatencyMs[index] = videoLatencyMs
        chartFps[index] = fps
        chartSequence++
    }

    private fun chartSamples(cutoffNs: Long): List<Sample> {
        val firstSequence = maxOf(0L, chartSequence - chartTimestampsNs.size)
        val result = ArrayList<Sample>(minOf(chartSequence, chartTimestampsNs.size.toLong()).toInt())
        for (sequence in firstSequence until chartSequence) {
            val index = (sequence % chartTimestampsNs.size).toInt()
            val timestampNs = chartTimestampsNs[index]
            if (timestampNs >= cutoffNs) {
                result.add(
                    Sample(
                        timestampNs = timestampNs,
                        videoLatencyMs = chartVideoLatencyMs[index],
                        fps = chartFps[index],
                    ),
                )
            }
        }
        return result
    }

    private fun clear() {
        videoSequence.set(0L)
        audioSequence.set(0L)
        maxVideoLatencyUs.set(0L)
        for (index in 0 until eventCapacity) {
            videoTimestampsNs.set(index, 0L)
            videoLatenciesUs.set(index, 0L)
            audioTimestampsNs.set(index, 0L)
            audioLatenciesUs.set(index, 0L)
            audioOutputLatenciesUs.set(index, OUTPUT_LATENCY_UNAVAILABLE)
        }
        synchronized(chartLock) {
            chartTimestampsNs.fill(0L)
            chartVideoLatencyMs.fill(0f)
            chartFps.fill(0f)
            chartSequence = 0L
        }
    }

    private fun emptySnapshot(nowNs: Long): Snapshot = Snapshot(
        timestampNs = nowNs,
        videoLatencyMs = 0f,
        maxVideoLatencyMs = 0f,
        fps = 0f,
        audioLatencyMs = 0f,
        audioOutputLatencyMs = null,
        chartWindowNs = chartWindowNs,
        samples = emptyList(),
    )

    companion object {
        const val DEFAULT_UPDATE_INTERVAL_MILLIS = 200L
        const val DEFAULT_CHART_WINDOW_NS = 30_000_000_000L
        const val DEFAULT_CHART_SAMPLE_CAPACITY = 151
        private const val DEFAULT_EVENT_CAPACITY = 512
        private const val METRIC_AVERAGE_WINDOW_NS = 1_000_000_000L
        private const val MAX_VIDEO_LATENCY_NS = 5_000_000_000L
        private const val NANOS_PER_MICROSECOND = 1_000L
        private const val MICROSECONDS_PER_MILLISECOND = 1_000f
        private const val OUTPUT_LATENCY_UNAVAILABLE = -1L

        /** Returns buffered output ahead of the playback head, clamped to a finite non-negative value. */
        fun audioLatencyMs(
            totalFramesWritten: Long,
            playbackHeadFrames: Long,
            sampleRate: Int,
        ): Float {
            if (sampleRate <= 0) return 0f
            val bufferedFrames = (totalFramesWritten - playbackHeadFrames).coerceAtLeast(0L)
            val milliseconds = bufferedFrames.toDouble() * 1_000.0 / sampleRate
            return if (milliseconds.isFinite()) milliseconds.toFloat().coerceAtLeast(0f) else 0f
        }

        private fun averageMilliseconds(totalUs: Long, count: Int): Float =
            if (count == 0) 0f else totalUs.toFloat() / count / MICROSECONDS_PER_MILLISECOND

        private fun updateMax(target: AtomicLong, value: Long) {
            var previous = target.get()
            while (value > previous && !target.compareAndSet(previous, value)) previous = target.get()
        }
    }
}
