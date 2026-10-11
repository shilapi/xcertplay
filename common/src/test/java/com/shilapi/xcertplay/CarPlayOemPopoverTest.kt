package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayOemPopoverTest {
    private val layout = mapOf("zones" to listOf(mapOf("id" to "z", "type" to mapOf("dca" to mapOf("zoneRegion" to "zone1")),
        "frame" to listOf(listOf(30, 30), listOf(600, 600)))))
    private fun region(opacity: Double = 0.5) = mapOf("identifier" to "popover:alert?zone=z",
        "bounds" to listOf(30, 30, 100, 200), "transform2D" to listOf(1, 0, 0, 1, 12, 34),
        "cumulative_opacity" to opacity, "opacity" to 1.0, "corner_radius" to 16)

    @Test fun parsesWorldCoordinatesAffineAndPartialOpacity() {
        val item = OemPopover.from(layout, mapOf("regions" to listOf(region()))).single()
        assertEquals(OemFrame(30f, 30f, 100f, 200f), item.frame)
        assertEquals(listOf(1f, 0f, 0f, 1f, 12f, 34f), item.transform)
        assertEquals(0.5f, item.opacity, 0f)
        assertEquals(16f, item.radius, 0f)
    }

    @Test fun zeroCumulativeOpacityDoesNotFallBackAndEmptySnapshotClears() {
        assertTrue(OemPopover.from(layout, mapOf("regions" to listOf(region(0.0)))).isEmpty())
        assertTrue(OemPopover.from(layout, mapOf("regions" to emptyMap<String, Any?>())).isEmpty())
    }

    @Test fun ignoresUnknownZoneMalformedBoundsAndNonfiniteTransforms() {
        val invalid = listOf(region() + ("identifier" to "popover:alert?zone=unknown"),
            region() + ("bounds" to listOf(30, 30, 900, 200)),
            region() + ("transform2D" to listOf(1, 0, 0, 1, Double.NaN, 0)))
        assertTrue(OemPopover.from(layout, mapOf("regions" to invalid)).isEmpty())
    }
}
