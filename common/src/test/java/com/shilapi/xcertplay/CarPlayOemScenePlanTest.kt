package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayOemScenePlanTest {
    private fun zone(id: String, kind: String, component: String, name: String = "DCA_Map") = mapOf(
        "id" to id, "type" to mapOf(kind to emptyMap<String, Any?>()),
        "frame" to listOf(listOf(660.0, 120.0), listOf(600.0, 480.0)),
        "components" to listOf(mapOf("id" to component, "name" to name, "type" to "primary")))

    @Test fun tachometerBindsTheAssetEngineRPMIdentifier() {
        val layout = mapOf("zones" to listOf(zone("z", "single", "tach", "Tachometer")))
        val plan = OemScenePlan.build(layout, emptyMap(), mapOf("engineRPM" to 2200.0))
        assertEquals(2200.0, plan.gauges.single().reading!!, 0.0)
    }

    @Test fun modularLayoutUsesDataIdentifiersInsteadOfComponentNames() {
        val dataSource = mapOf("tachometer" to mapOf("dataIdentifiers" to mapOf("dataIdentifier" to "engineRPM")))
        val style = mapOf("progressBar" to mapOf("dataSource" to dataSource))
        val slots = listOf(mapOf("instrument" to mapOf("kind" to mapOf("gauge" to mapOf("configuration" to mapOf("style" to style))))))
        val component = mapOf("name" to "Right", "slots" to slots)
        val zone = zone("z", "single", "g").toMutableMap().apply { put("components", listOf(component)) }
        val reading = OemScenePlan.build(mapOf("zones" to listOf(zone)), emptyMap(), mapOf("engineRPM" to 1500)).gauges.single()
        assertEquals("engineRPM", reading.source)
        assertEquals(1500.0, reading.reading!!, 0.0)
    }

    @Test fun remoteMaskKeepsCompleteVideoCoordinatesAndRequiresConfirmedComponent() {
        val layout = mapOf("zones" to listOf(zone("z", "dca", "map")))
        assertTrue(OemScenePlan.build(layout, emptyMap(), emptyMap()).remoteFrames.isEmpty())
        assertTrue(OemScenePlan.build(layout, mapOf("z" to "foreign"), emptyMap()).remoteFrames.isEmpty())
        assertEquals(OemFrame(660f, 120f, 600f, 480f),
            OemScenePlan.build(layout, mapOf("z" to "map"), emptyMap()).remoteFrames.single())
    }

    @Test fun mapsMediaIsARemoteZoneAndInvalidTelemetryNeverBecomesZero() {
        val layout = mapOf("zones" to listOf(zone("z", "mapsMedia", "map"), zone("speed", "single", "g", "Speedometer")))
        val plan = OemScenePlan.build(layout, mapOf("z" to "map"), mapOf("vehicleSpeed" to Double.NaN, "vehicleSpeedUnit" to 1032L))
        assertEquals(1, plan.remoteFrames.size)
        assertNull(plan.gauges.single().reading)
        assertEquals("km/h", plan.gauges.single().unit)
        assertEquals("mph", OemScenePlan.speedUnit("1033"))
        assertNull(OemScenePlan.frame(listOf(listOf(0, 0), listOf(-1, 1))))
    }

    @Test fun artworkKeepsSlotCoordinatesAndExcludesVerifiedOrConditionalIndicators() {
        fun slot(identifier: String, verified: Boolean = false, conditional: Boolean = false): Map<String, Any?> {
            val image = mapOf("type" to mapOf("image" to mapOf("_0" to mapOf("identifier" to identifier))))
            val group = mapOf("configuration" to mapOf("defaultState" to "default", "states" to mapOf("default" to mapOf("image" to image))))
            val rules = if (conditional) listOf(mapOf("transmissionMode" to emptyMap<String, Any?>())) else emptyList()
            val gauge = mapOf("rendersLocally" to true, "visibilityConfiguration" to mapOf("rules" to rules),
                "style" to mapOf("imageGroup" to group))
            val instrument = mapOf("configuration" to if (verified) mapOf("verifierIdentifier" to "prndl") else emptyMap(),
                "kind" to mapOf("gauge" to mapOf("configuration" to gauge)))
            return mapOf("frame" to listOf(listOf(10.0, 20.0), listOf(90.0, 80.0)), "zIndex" to 3, "instrument" to instrument)
        }
        val layout = mapOf("zones" to listOf(mapOf("type" to mapOf("single" to emptyMap<String, Any?>()),
            "frame" to listOf(listOf(600.0, 100.0), listOf(600.0, 480.0)),
            "components" to listOf(mapOf("slots" to listOf(slot("decorative"), slot("verified", verified = true), slot("conditional", conditional = true)))))))
        val art = OemScenePlan.artwork(layout).single()
        assertEquals("decorative", art.identifier)
        assertEquals(OemFrame(610f, 120f, 90f, 80f), art.frame)
    }
}
