package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayAltScreenConfigTest {
    @Test fun defaultInstrumentPaneUsesRequestedAspectInsteadOfOneThirdOfTheScreen() {
        val config = CarPlayAltScreenConfig.DEFAULT
        assertEquals(1920, config.widthPixels)
        assertEquals(720, config.heightPixels)
        assertEquals(CarPlayAltScreenConfig.Size(1080, 405), config.fitWithin(1080, 2160))
        assertEquals(CarPlayAltScreenConfig.Size(1920, 720), config.fitWithin(1920, 1080))
    }

    @Test fun configurableResolutionAndHeightLimitKeepTheWholeImageVisible() {
        val config = CarPlayAltScreenConfig(1280, 480)
        assertEquals(CarPlayAltScreenConfig.Size(800, 300), config.fitWithin(1920, 300))
        assertEquals(CarPlayAltScreenConfig.Size(1280, 480), config.fitWithin(1280, 900))
        assertEquals(CarPlayAltScreenConfig.Size(1000, 750),
            CarPlayAltScreenConfig(1024, 768).fitWithin(1000, 1200))
        assertEquals(CarPlayAltScreenConfig.Size(1081, 405),
            CarPlayAltScreenConfig.DEFAULT.fitWithin(1081, 2160))
    }

    @Test fun anUnmeasuredPaneDoesNotProduceInvalidDimensions() {
        assertEquals(CarPlayAltScreenConfig.Size(0, 0), CarPlayAltScreenConfig.DEFAULT.fitWithin(0, 2160))
        assertEquals(CarPlayAltScreenConfig.Size(0, 0), CarPlayAltScreenConfig.DEFAULT.fitWithin(1920, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRequestResolutionIsRejected() {
        CarPlayAltScreenConfig(1920, 0)
    }
}
