package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneGainTest {
    @Test
    fun gainRangeIsClamped() {
        assertEquals(80, MicrophoneGain.sanitize(20))
        assertEquals(130, MicrophoneGain.sanitize(130))
        assertEquals(200, MicrophoneGain.sanitize(500))
    }

    @Test
    fun pcmGainAmplifiesAndSaturatesInPlace() {
        val pcm = pcm(-20_000, -1_000, 0, 1_000, 20_000)

        val peak = MicrophoneGain.applyPcm16InPlace(pcm, pcm.size, 200)

        assertArrayEquals(pcm(-32_768, -2_000, 0, 2_000, 32_767), pcm)
        assertEquals(32_768, peak)
        assertEquals(100, MicrophoneGain.peakPercent(peak))
    }

    @Test
    fun minimumGainAttenuatesSamples() {
        val pcm = pcm(-10_000, 10_000)

        val peak = MicrophoneGain.applyPcm16InPlace(pcm, pcm.size, 80)

        assertArrayEquals(pcm(-8_000, 8_000), pcm)
        assertEquals(8_000, peak)
        assertEquals(24, MicrophoneGain.peakPercent(peak))
    }

    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, value ->
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value ushr 8).toByte()
        }
    }
}
