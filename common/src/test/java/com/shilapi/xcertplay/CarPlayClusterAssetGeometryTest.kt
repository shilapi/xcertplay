package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayOemThemeDescriptor
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CarPlayClusterAssetGeometryTest {
    private val descriptor = CarPlayOemThemeDescriptor.decode(
        File("../shared/src/main/assets/carplay-ultra/manifest.bplist").readBytes())
    private fun slots(style: String): List<OemLocalSlot> {
        val layout = descriptor.layouts.first { it["layoutStyleID"] == style }
        return OemScenePlan.build(layout, emptyMap(), emptyMap()).localSlots
    }

    @Test fun assetScaleAndRedlineUseIndependentGeometry() {
        assertEquals(270.0 to 10, ClusterDialGeometry.scale(260.0, false))
        val r = ClusterReadings.from(mapOf("vehicleSpeed" to 135, "vehicleSpeedMax" to 260,
            "engineRPM" to 4000, "engineRPMMax" to 8000, "engineMarkerRedlineRPM" to 6500))
        val gauges = slots("DigiRing").mapNotNull { ClusterSlotGauge.progress(it, r) }
        val redline = gauges.first { it.redline }
        assertEquals(10f, (redline.radial!!["trackWidth"] as Number).toFloat(), 0f)
        assertEquals(30f, (redline.radial["trackInset"] as Number).toFloat(), 0f)
        assertEquals(0.8125f, redline.startFraction, 0f)
        assertTrue(gauges.any { it.background })
        assertTrue(gauges.any { !it.background && !it.redline && !it.secondary && it.fraction == 0.5f })
    }

    @Test fun modularReadoutUsesAssetAlignmentAndFont() {
        val r = ClusterReadings.from(mapOf("vehicleSpeed" to 85, "vehicleSpeedUnit" to 1032L))
        val text = slots("Modular").mapNotNull { ClusterSlotContent.text(it, r) }
        val speed = text.first { it.value == "85" }
        assertEquals(1, (speed.configuration["alignment"] as Number).toInt())
        assertEquals("Stable-SoftNumeric-Medium-200", (speed.configuration["font"] as Map<*, *>)["name"])
        val unit = text.first { it.value == "km/h" }
        assertEquals(2, (unit.configuration["alignment"] as Number).toInt())
        assertEquals(419.33334f, unit.frame.x, 0.001f)
    }
}
