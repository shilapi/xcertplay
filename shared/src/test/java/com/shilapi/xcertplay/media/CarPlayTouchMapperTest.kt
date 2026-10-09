package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CarPlayTouchMapperTest {
    @Test
    fun matchingStreamFillsTheView() {
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 1080.0, 1756.0),
            CarPlayTouchMapper.contentRect(1080, 1756, 1080, 1756), 1e-9)
    }

    @Test
    fun widerStreamIsLetterboxedVertically() {
        // 1920x720 in a 1080x1756 pane: 1080x405 centered, 675.5 px bars above and below.
        assertArrayEquals(doubleArrayOf(0.0, 675.5, 1080.0, 405.0),
            CarPlayTouchMapper.contentRect(1080, 1756, 1920, 720), 1e-9)
    }

    @Test
    fun tallerStreamIsPillarboxedHorizontally() {
        assertArrayEquals(doubleArrayOf(660.0, 0.0, 600.0, 1080.0),
            CarPlayTouchMapper.contentRect(1920, 1080, 1000, 1800), 1e-9)
    }
}
