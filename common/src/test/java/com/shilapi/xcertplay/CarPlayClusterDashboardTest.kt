package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayClusterDashboardTest {
    private fun drive(seconds: Double, step: Double = 0.1): List<Map<String, Any?>> {
        val vehicle = CarPlayClusterMockVehicle()
        return (0 until (seconds / step).toInt()).map { vehicle.advance(step) }
    }

    @Test fun mockLoopStaysInsidePlausibleRanges() {
        val samples = drive(CarPlayClusterMockVehicle.LOOP_SECONDS * 2)
        samples.forEach { v ->
            val speed = v["vehicleSpeed"] as Double
            val rpm = v["engineRPM"] as Double
            assertTrue("speed $speed", speed in 0.0..115.0)
            assertTrue("rpm $rpm", rpm in 700.0..(v["engineMarkerRedlineRPM"] as Double))
            assertTrue((v["fuelLevel"] as Double) in 5.0..100.0)
            assertTrue((v["engineTemperature"] as Double) in 40.0..92.0)
            assertEquals("mock", v["dataSource"])
            assertEquals(1032L, v["vehicleSpeedUnit"])
        }
        assertEquals(setOf("P", "N", "D", "R"), samples.map { it["prndl"] }.toSet())
        assertTrue(samples.maxOf { it["vehicleSpeed"] as Double } > 100)
    }

    @Test fun gearAndRpmFollowSpeedAndSelector() {
        assertEquals(1, CarPlayClusterMockVehicle.gearFor(5.0))
        assertEquals(3, CarPlayClusterMockVehicle.gearFor(40.0))
        assertEquals(6, CarPlayClusterMockVehicle.gearFor(110.0))
        drive(CarPlayClusterMockVehicle.LOOP_SECONDS).forEach { v ->
            val gear = (v["transmission"] as Long).toInt()
            if (v["prndl"] == "D") assertEquals(CarPlayClusterMockVehicle.gearFor(v["vehicleSpeed"] as Double), gear)
            else assertEquals(0, gear)
            if (v["prndl"] == "P" || v["prndl"] == "N") assertEquals(0.0, v["vehicleSpeed"] as Double, 0.0)
        }
    }

    @Test fun mockOdometerAndFuelTrackDistanceAndCoolantWarmsUp() {
        val samples = drive(CarPlayClusterMockVehicle.LOOP_SECONDS)
        val first = samples.first(); val last = samples.last()
        assertTrue((last["odometer"] as Double) - (first["odometer"] as Double) in 2.0..4.0)
        assertTrue((last["fuelLevel"] as Double) < (first["fuelLevel"] as Double))
        assertTrue((first["engineTemperature"] as Double) < 50)
        assertTrue((last["engineTemperature"] as Double) > 85)
    }

    @Test fun readingsParseMockSnapshotAndKeepMissingValuesUnavailable() {
        val r = ClusterReadings.from(CarPlayClusterMockVehicle().advance(0.0))
        assertEquals(0.0, r.speed!!, 0.0)
        assertEquals("km/h", r.speedUnit)
        assertEquals("P", r.gearLabel)
        assertTrue(r.simulated)
        assertEquals(6500.0, r.redlineRpm, 0.0)

        val empty = ClusterReadings.from(mapOf("vehicleSpeed" to Double.NaN, "engineRPM" to -1))
        assertNull(empty.speed); assertNull(empty.rpm); assertNull(empty.fuelPercent)
        assertEquals("—", empty.gearLabel)
        assertFalse(empty.simulated)
        assertEquals("D4", ClusterReadings.from(mapOf("prndl" to "D", "transmission" to 4L)).gearLabel)
        assertEquals("R", ClusterReadings.from(mapOf("prndl" to "R", "transmission" to 0L)).gearLabel)
    }

    @Test fun styleFollowsAssetLayoutStyleId() {
        assertEquals(ClusterDialStyle.COBRANDED, ClusterDialStyle.of("Cobranded"))
        assertEquals(ClusterDialStyle.DIGI_RING, ClusterDialStyle.of("DigiRing"))
        assertEquals(ClusterDialStyle.MODULAR, ClusterDialStyle.of("Modular"))
        assertEquals(ClusterDialStyle.MAPS_MEDIA, ClusterDialStyle.of("MapsMedia"))
        assertEquals(ClusterDialStyle.DIGI_BAND_TICKS, ClusterDialStyle.of("NoStyle"))
    }

    @Test fun colorsUseDarkModeFirstStopAndStylePrefix() {
        fun dynamic(dark: String, light: String) = mapOf("dynamic" to mapOf("value" to mapOf(
            "darkMode" to mapOf("stops" to mapOf("0.0" to dark)), "lightMode" to mapOf("stops" to mapOf("0.0" to light)))))
        assertEquals(0xFFEC5B36.toInt(), ClusterColors.argb(dynamic("#ec5b36ff", "#000000ff")))
        val gradient = mapOf("single" to mapOf("value" to mapOf("stops" to mapOf("1.0" to "#000000ff", "0.0" to "#1dc5a74c"))))
        assertEquals(0x4C1DC5A7, ClusterColors.argb(gradient))
        assertNull(ClusterColors.parseRgba("#fff"))

        val palette = mapOf(
            "partner.cobranded.innerRingActive" to dynamic("#1dc5a7ff", "#1dc5a7ff"),
            "radial.primaryActive" to dynamic("#ffffffff", "#ffffffff"),
            "modular.leftTopActive" to dynamic("#438ba8ff", "#1d6583ff"))
        assertEquals(0xFF1DC5A7.toInt(), ClusterColors.resolve(ClusterDialStyle.COBRANDED) { palette[it] }.primary)
        assertEquals(0xFFFFFFFF.toInt(), ClusterColors.resolve(ClusterDialStyle.DIGI_RING) { palette[it] }.primary)
        assertEquals(0xFF438BA8.toInt(), ClusterColors.resolve(ClusterDialStyle.MODULAR) { palette[it] }.primary)
        // Missing keys fall back instead of failing to draw.
        assertEquals(0xFFFF9F0A.toInt(), ClusterColors.resolve(ClusterDialStyle.MAPS_MEDIA) { null }.lowLevel)
    }

    @Test fun dialGeometryAndSmoothing() {
        assertEquals(20.0, ClusterDialGeometry.majorStep(260.0), 0.0)
        assertEquals(1000.0, ClusterDialGeometry.majorStep(8000.0), 0.0)
        assertEquals(1f, ClusterDialGeometry.fraction(300.0, 260.0), 0f)
        assertEquals(0f, ClusterDialGeometry.fraction(null, 260.0), 0f)

        val value = ClusterSmoothedValue()
        assertFalse(value.step(50.0, 0.016))
        assertEquals(50.0, value.value!!, 0.0)
        assertTrue(value.step(100.0, 0.016))
        assertTrue(value.value!! in 50.0..100.0)
        repeat(200) { value.step(100.0, 0.016) }
        assertEquals(100.0, value.value!!, 0.0)
        assertFalse(value.step(null, 0.016))
        assertNull(value.value)
    }

    @Test fun zonesComeFromLayoutGauges() {
        fun zone(name: String, x: Double, kind: String = "single") = mapOf("id" to name, "type" to mapOf(kind to emptyMap<String, Any?>()),
            "frame" to listOf(listOf(x, 30.0), listOf(600.0, 600.0)),
            "components" to listOf(mapOf("id" to name, "name" to name, "type" to "primary")))
        val layout = mapOf("zones" to listOf(zone("Speedometer", 30.0), zone("Tachometer", 1290.0),
            mapOf("type" to mapOf("single" to emptyMap<String, Any?>()), "frame" to listOf(listOf(1254.0, 3.0), listOf(96.0, 38.0)),
                "components" to listOf(mapOf("name" to "OutsideTemperature")))))
        val zones = ClusterZones.from(layout, OemScenePlan.build(layout, emptyMap(), emptyMap()))
        assertEquals(30f, zones.speed!!.x, 0f)
        assertEquals(1290f, zones.rpm!!.x, 0f)
        assertNull(zones.centerSpeed)
        assertEquals(OemFrame(1254f, 3f, 96f, 38f), zones.outsideTemperature)
    }
}
