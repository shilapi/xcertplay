package com.shilapi.xcertplay

import org.robolectric.RuntimeEnvironment
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.MediaTap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebDisplayMediaForwardingTest {
    @Test fun senderTimestampedMainAndUltraClusterFramesReachTheWebTapWithoutLocalPreview() {
        val configs = mutableListOf<Pair<Int, VideoCodec>>()
        val frames = mutableListOf<Pair<Int, ByteArray>>()
        val activeStates = mutableListOf<Pair<Int, Boolean>>()
        val tap = object : MediaTap {
            override fun forwardsVideo(type: Int) = type == 110 || type == 111
            override fun onVideoConfig(type: Int, codec: VideoCodec, codecData: ByteArray) { configs += type to codec }
            override fun onVideoFrame(type: Int, accessUnit: ByteArray, arrivalUs: Long) { frames += type to accessUnit }
            override fun onVideoActive(type: Int, active: Boolean) { activeStates += type to active }
        }
        val sink = AndroidMediaSink(RuntimeEnvironment.getApplication(), mediaTap = tap)
        try {
            for (type in listOf(110, 111)) {
                sink.onVideoCodec(type, VideoCodec.H264)
                sink.onVideoConfig(type, byteArrayOf(1, 0x64, 0, 0x1f))
                sink.onScreenStreamActive(type, true)
                // Both streams carry the phone's composed image; PTS is in the phone's clock domain.
                sink.onVideoFrame(type, byteArrayOf(0, 0, 0, 1, 0x65, 0x22), 12345L)
            }
            assertEquals(listOf(110, 111), configs.map { it.first })
            assertEquals(listOf(110 to true, 111 to true), activeStates)
            assertEquals(listOf(110, 111), frames.map { it.first })
            frames.forEach { assertArrayEquals(byteArrayOf(0, 0, 0, 2, 0x65, 0x22), it.second) }
        } finally { sink.close() }
    }
}
