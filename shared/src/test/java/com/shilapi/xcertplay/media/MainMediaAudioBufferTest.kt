package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainMediaAudioBufferTest {
    @Test
    fun durationIsClampedAndSnappedToHundredMilliseconds() {
        assertEquals(100, MainMediaAudioBuffer.sanitizeDurationMs(0))
        assertEquals(100, MainMediaAudioBuffer.sanitizeDurationMs(149))
        assertEquals(200, MainMediaAudioBuffer.sanitizeDurationMs(150))
        assertEquals(1000, MainMediaAudioBuffer.sanitizeDurationMs(1500))
    }

    @Test
    fun durationConvertsToPcm16TrackCapacity() {
        assertEquals(96_000, MainMediaAudioBuffer.bufferSizeBytes(500, 48_000, 2, 16_384))
    }

    @Test
    fun systemMinimumRemainsTheLowerBound() {
        assertEquals(24_000, MainMediaAudioBuffer.bufferSizeBytes(100, 8_000, 1, 24_000))
    }

    @Test
    fun onlyMediaCategoryUsesConfiguredTrackBuffer() {
        assertTrue(MainMediaAudioBuffer.isMainMedia("media", 100))
        assertTrue(MainMediaAudioBuffer.isMainMedia("MEDIA", 102))
        assertFalse(MainMediaAudioBuffer.isMainMedia("media", 101))
        for (audioType in listOf("default", "compatibility", "alert", "telephony", "speechrecognition")) {
            assertFalse(MainMediaAudioBuffer.isMainMedia(audioType, 100))
        }
    }
}
