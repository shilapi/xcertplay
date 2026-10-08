package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Coordinates Android AudioFocus requests for active CarPlay audio streams.
 *
 * Requests system audio focus for active media and voice streams according to priority:
 * - MEDIA: AUDIOFOCUS_GAIN
 * - PHONE: AUDIOFOCUS_GAIN_TRANSIENT
 * - ASSISTANT: AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
 * - NAVIGATION: does not take exclusive focus
 *
 * When focus changes:
 * - AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: Ducks media volume to 20%
 * - AUDIOFOCUS_LOSS_TRANSIENT: If [muteMediaOnTransientLoss] is true, mutes media volume to 0.0
 *   (e.g., during incoming/outgoing car Bluetooth phone calls or native car voice interruptions)
 * - AUDIOFOCUS_GAIN: Restores media volume to 100%
 */
class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val muteMediaOnTransientLoss: Boolean = true,
    private val report: (String) -> Unit = {},
) {
    private data class Entry(
        val channel: AudioChannel,
        val attributes: AudioAttributes,
        var appliedVolume: Float = FULL_VOLUME,
    )

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: AudioFocusRequest? = null
    private var requestedChannel: AudioChannel? = null
    private var mediaVolume = FULL_VOLUME
    private var closed = false
    private var focusGeneration = 0L
    private var currentListener = listenerFor(focusGeneration)
    internal val listener: AudioManager.OnAudioFocusChangeListener get() = currentListener

    private fun listenerFor(generation: Long) = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            if (generation != focusGeneration || request == null || active.isEmpty()) return@synchronized
            runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
            Log.i(TAG, "Audio: focus change=$change activeTracks=${active.size}")
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setMediaVolume(DUCKED_VOLUME)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (muteMediaOnTransientLoss) {
                        Log.i(TAG, "Audio: muting media during transient focus loss")
                        setMediaVolume(0f)
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> setMediaVolume(FULL_VOLUME)
                AudioManager.AUDIOFOCUS_LOSS -> {
                    Log.i(TAG, "Audio: permanent focus loss")
                }
            }
        }
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (closed || !enabled || manager == null || channel == AudioChannel.NAVIGATION) return
        active[track] = Entry(channel, attributes)
        refreshRequest()
        applyMediaVolume(track, active.getValue(track))
    }

    @Synchronized
    fun release(track: AudioTrack) {
        if (active.remove(track) != null) refreshRequest()
    }

    fun onExternalFocusChange(change: Int) {
        val current = synchronized(this) { currentListener }
        current.onAudioFocusChange(change)
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        active.clear()
        refreshRequest()
    }

    private fun refreshRequest() {
        val primary = active.values.maxByOrNull { it.channel.focusPriority() }
        if (primary == null) {
            focusGeneration += 1
            val abandoned = request
            request = null
            requestedChannel = null
            mediaVolume = FULL_VOLUME
            abandoned?.let { manager?.abandonAudioFocusRequest(it) }
            return
        }
        if (request != null && requestedChannel == primary.channel) return
        focusGeneration += 1
        request?.let { manager?.abandonAudioFocusRequest(it) }
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.NAVIGATION -> return
        }
        currentListener = listenerFor(focusGeneration)
        val next = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(primary.attributes)
            .setOnAudioFocusChangeListener(currentListener, Handler(Looper.getMainLooper()))
            .build()
        request = next
        requestedChannel = primary.channel
        val result = manager?.requestAudioFocus(next)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) setMediaVolume(FULL_VOLUME)
        val line = "Audio: focus requested channel=${primary.channel} gain=$gain granted=$result activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun setMediaVolume(volume: Float) {
        mediaVolume = volume
        active.forEach { (track, entry) -> applyMediaVolume(track, entry) }
    }

    private fun applyMediaVolume(track: AudioTrack, entry: Entry) {
        if (entry.channel != AudioChannel.MEDIA || entry.appliedVolume == mediaVolume) return
        val applied = runCatching {
            track.setVolume(mediaVolume) == AudioTrack.SUCCESS
        }.getOrDefault(false)
        if (applied) entry.appliedVolume = mediaVolume
    }

    internal companion object {
        const val TAG = "CarPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f

        fun focusPriority(channel: AudioChannel): Int = when (channel) {
            AudioChannel.MEDIA -> 3
            AudioChannel.PHONE -> 2
            AudioChannel.ASSISTANT -> 1
            AudioChannel.NAVIGATION -> 0
        }
    }

    private fun AudioChannel.focusPriority(): Int = focusPriority(this)
}
