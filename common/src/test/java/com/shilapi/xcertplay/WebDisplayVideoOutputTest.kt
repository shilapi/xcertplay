package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.MediaCodecSupport
import org.junit.Assert.*
import org.junit.Test

class WebDisplayVideoOutputTest {
    private fun viewer() = WebDisplayViewer(object : WebDisplayViewer.Transport {
        override fun send(message: ByteArray) = Unit
        override fun send(message: String) = Unit
        override fun close() = Unit
    }, start = false)
    private fun WebDisplayViewer.drain() = generateSequence { next(0) }.toList()
    private val key = byteArrayOf(0, 0, 0, 2, 0x65, 0x11)

    @Test fun ultraClusterPublishesOnlyEncodedCompositionAndKeepsGaugesWithoutPhoneVideo() {
        val channel = WebDisplayChannel(111, 1920, 720)
        val browser = viewer(); channel.attach(browser); browser.drain()
        val output = WebDisplayVideoOutput(mapOf(111 to channel), composedCluster = true)
        output.phoneConfig(111, VideoCodec.H265, byteArrayOf(1))
        output.phoneActive(111, true); output.phoneFrame(111, key, 1)
        assertTrue("phone packets must not leak to the composed output", browser.drain().isEmpty())
        assertFalse(channel.active)
        output.composedConfig(byteArrayOf(1, 0x64, 0, 0x1f))
        assertTrue(channel.active); browser.drain()
        output.composedFrame(key, 2)
        val frame = browser.drain().single() as ByteArray
        assertArrayEquals(key, frame.copyOfRange(10, frame.size))
        output.phoneActive(111, false)
        assertTrue("local gauges remain available without the phone layer", channel.active)
        assertTrue(browser.drain().isEmpty())
    }

    @Test fun mainAndClassicClusterContinueToForwardPhoneFrames() {
        for (type in listOf(110, 111)) {
            val channel = WebDisplayChannel(type, 1280, 720)
            val browser = viewer(); channel.attach(browser); browser.drain()
            val output = WebDisplayVideoOutput(mapOf(type to channel), composedCluster = type == 110)
            output.phoneActive(type, true)
            output.phoneConfig(type, VideoCodec.H264, byteArrayOf(1, 0x64, 0, 0x1f)); browser.drain()
            output.phoneFrame(type, key, 1)
            assertEquals(1, browser.drain().size)
            output.phoneActive(type, false)
            assertFalse(channel.active)
        }
    }

    @Test fun encoderParameterSetsBecomeValidWebCodecsConfiguration() {
        val sps = byteArrayOf(0x67, 0x64, 0, 0x1f, 1)
        val pps = byteArrayOf(0x68, 2, 3)
        val start = byteArrayOf(0, 0, 0, 1)
        for (csd in listOf(arrayOf(start + sps, start + pps), arrayOf(start + sps + start + pps))) {
            val config = ClusterAvcConfig.create(*csd)
            val sets = MediaCodecSupport.avcParameterSets(config)
            assertArrayEquals(sps, sets.first); assertArrayEquals(pps, sets.second)
        }
        assertThrows(IllegalStateException::class.java) { ClusterAvcConfig.create(start + sps) }
    }
}
