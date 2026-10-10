package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec

/**
 * Receives the phone's compressed screen streams and decoded audio before local rendering,
 * so another device can present them. Calls arrive on media worker threads and must not block.
 */
interface MediaTap {
    /** True when [type] is presented remotely; it is then decoded locally only for a preview Surface. */
    fun forwardsVideo(type: Int): Boolean

    /** [codecData] is the phone's avcC/hvcC record. */
    fun onVideoConfig(type: Int, codec: VideoCodec, codecData: ByteArray) {}

    /** One complete access unit with four-byte NAL length prefixes. */
    fun onVideoFrame(type: Int, accessUnit: ByteArray, arrivalUs: Long) {}

    fun onVideoActive(type: Int, active: Boolean) {}

    /** True while decoded audio should play remotely instead of through the local AudioTrack. */
    fun takesAudio(): Boolean = false

    /** Interleaved signed 16-bit little-endian PCM. The buffer is reused after this call returns. */
    fun onAudioPcm(stream: Int, sampleRate: Int, channels: Int, pcm: ByteArray, offset: Int, length: Int) {}

    fun onAudioStopped(stream: Int) {}
}
