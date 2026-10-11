package com.shilapi.xcertplay.media

/**
 * Sizing rules for the main media AudioTrack (AirPlay stream types 100 and 102).
 *
 * [sanitizeDurationMs] keeps the configured buffer duration between [MIN_DURATION_MS] and
 * [MAX_DURATION_MS] on a [STEP_DURATION_MS] grid, so the settings slider, the persisted value and
 * the real track capacity always agree.
 */
object MainMediaAudioBuffer {
    /** Shortest buffered duration the settings slider offers. */
    const val MIN_DURATION_MS = 100

    /** Longest buffered duration the settings slider offers. */
    const val MAX_DURATION_MS = 1_000

    /** Slider granularity of the buffered duration. */
    const val STEP_DURATION_MS = 100

    /** Default buffered duration: the lowest-latency option. */
    const val DEFAULT_DURATION_MS = MIN_DURATION_MS

    private const val BYTES_PER_PCM_16_SAMPLE = 2L
    private const val MILLIS_PER_SECOND = 1_000L
    private const val STREAM_TYPE_MAIN_AUDIO = 100
    private const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102

    /** Clamps [durationMs] into range and snaps it to the nearest [STEP_DURATION_MS] multiple. */
    fun sanitizeDurationMs(durationMs: Int): Int {
        val clamped = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        val steps = (clamped - MIN_DURATION_MS + STEP_DURATION_MS / 2) / STEP_DURATION_MS
        return MIN_DURATION_MS + steps * STEP_DURATION_MS
    }

    /**
     * Capacity in bytes for [durationMs] of 16-bit PCM at [sampleRate] and [channelCount].
     *
     * The system minimum reported by AudioTrack stays the lower bound, and the result never
     * exceeds [Int.MAX_VALUE] so it can be handed to the AudioTrack builder directly.
     */
    fun bufferSizeBytes(
        durationMs: Int,
        sampleRate: Int,
        channelCount: Int,
        minBufferBytes: Int,
    ): Int {
        require(sampleRate > 0)
        require(channelCount > 0)
        require(minBufferBytes > 0)
        val capacity = sampleRate.toLong() * channelCount * BYTES_PER_PCM_16_SAMPLE *
            sanitizeDurationMs(durationMs) / MILLIS_PER_SECOND
        return maxOf(minBufferBytes.toLong(), capacity).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** True only for the main media stream, whose buffered duration is user configurable. */
    fun isMainMedia(audioType: String, payloadType: Int): Boolean =
        audioType.equals("media", ignoreCase = true) &&
            (payloadType == STREAM_TYPE_MAIN_AUDIO || payloadType == STREAM_TYPE_MAIN_HIGH_AUDIO)
}
