package com.shilapi.xcertplay

/** Geometry in the OEM's complete video-frame coordinate system, not an independently scaled DCA. */
internal data class OemFrame(val x: Float, val y: Float, val width: Float, val height: Float)
internal data class OemGauge(val frame: OemFrame, val source: String, val reading: Double?, val unit: String)
internal data class OemArtwork(val frame: OemFrame, val identifier: String, val colorId: String?, val z: Int)
internal data class OemScenePlan(val remoteFrames: List<OemFrame>, val gauges: List<OemGauge>) {
    companion object {
        fun build(layout: Map<String, Any?>, active: Map<String, String>, values: Map<String, Any?>): OemScenePlan {
            val remote = mutableListOf<OemFrame>()
            val gauges = mutableListOf<OemGauge>()
            (layout["zones"] as? List<*>)?.forEach { raw ->
                val zone = raw as? Map<*, *> ?: return@forEach
                val frame = frame(zone["frame"]) ?: return@forEach
                val type = zone["type"] as? Map<*, *> ?: return@forEach
                val components = (zone["components"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
                if (type.containsKey("dca") || type.containsKey("mapsMedia")) {
                    val selected = active[zone["id"]]
                    if (selected != null && components.any { it["id"] == selected && it["type"] == "primary" }) remote += frame
                } else {
                    components.forEach componentLoop@ { component ->
                        val declaredSources = linkedSetOf<String>()
                        collectReadingIdentifiers(component["slots"], declaredSources)
                        val source = declaredSources.firstOrNull { it == "vehicleSpeed" || it == "engineRPM" } ?: when (component["name"]) {
                            "Speedometer" -> "vehicleSpeed"
                            "Tachometer" -> "engineRPM"
                            else -> return@componentLoop
                        }
                        val unit = if (source == "vehicleSpeed") speedUnit(values["vehicleSpeedUnit"]) else "rpm"
                        val reading = (values[source] as? Number)?.toDouble()?.takeIf {
                            it.isFinite() && it >= 0 && unit.isNotEmpty()
                        }
                        gauges += OemGauge(frame, source, reading, unit)
                    }
                }
            }
            return OemScenePlan(remote, gauges)
        }

        private fun collectReadingIdentifiers(value: Any?, into: MutableSet<String>) {
            when (value) {
                is Map<*, *> -> value.forEach { (key, nested) ->
                    if (key is String && key.endsWith("Identifier") && nested is String) into += nested
                    else collectReadingIdentifiers(nested, into)
                }
                is List<*> -> value.forEach { collectReadingIdentifiers(it, into) }
            }
        }

        fun speedUnit(raw: Any?): String = when (raw) {
            1032, 1032L, "1032", "km/h" -> "km/h"
            1033, 1033L, "1033", "mph" -> "mph"
            else -> ""
        }

        /** Only static decorative artwork is supported; data-driven images and verified telltales are excluded. */
        fun artwork(layout: Map<String, Any?>): List<OemArtwork> {
            val result = mutableListOf<OemArtwork>()
            (layout["zones"] as? List<*>)?.forEach zoneLoop@ { raw ->
                val zone = raw as? Map<*, *> ?: return@zoneLoop
                val zoneFrame = frame(zone["frame"]) ?: return@zoneLoop
                if ((zone["type"] as? Map<*, *>)?.containsKey("single") != true) return@zoneLoop
                (zone["components"] as? List<*>)?.forEach { component ->
                    ((component as? Map<*, *>)?.get("slots") as? List<*>)?.forEach slotLoop@ { item ->
                        val slot = item as? Map<*, *> ?: return@slotLoop
                        val slotFrame = frame(slot["frame"]) ?: return@slotLoop
                        val instrument = slot["instrument"] as? Map<*, *> ?: return@slotLoop
                        val instrumentConfig = instrument["configuration"] as? Map<*, *>
                        if (instrumentConfig?.containsKey("verifierIdentifier") == true) return@slotLoop
                        val gauge = ((instrument["kind"] as? Map<*, *>)?.get("gauge") as? Map<*, *>)?.get("configuration") as? Map<*, *> ?: return@slotLoop
                        if (gauge["rendersLocally"] != true) return@slotLoop
                        val rules = ((gauge["visibilityConfiguration"] as? Map<*, *>)?.get("rules") as? List<*>) ?: emptyList<Any>()
                        if (rules.any { (it as? Map<*, *>)?.keys?.any { key -> key != "layoutMode" } != false }) return@slotLoop
                        val imageGroup = ((gauge["style"] as? Map<*, *>)?.get("imageGroup") as? Map<*, *>)?.get("configuration") as? Map<*, *> ?: return@slotLoop
                        if (imageGroup.containsKey("dataIdentifier") || imageGroup.containsKey("dataTransformationType")) return@slotLoop
                        val state = (imageGroup["states"] as? Map<*, *>)?.get(imageGroup["defaultState"]) as? Map<*, *> ?: return@slotLoop
                        val image = (((state["image"] as? Map<*, *>)?.get("type") as? Map<*, *>)?.get("image") as? Map<*, *>)?.get("_0") as? Map<*, *> ?: return@slotLoop
                        val id = image["identifier"] as? String ?: return@slotLoop
                        result += OemArtwork(slotFrame.copy(x = zoneFrame.x + slotFrame.x, y = zoneFrame.y + slotFrame.y),
                            id, (state["tintColorIDs"] as? Map<*, *>)?.get("0") as? String,
                            (slot["zIndex"] as? Number)?.toInt() ?: 0)
                    }
                }
            }
            return result.sortedBy { it.z }
        }

        fun frame(raw: Any?): OemFrame? {
            val items = raw as? List<*> ?: return null
            val origin = items.getOrNull(0) as? List<*> ?: return null
            val size = items.getOrNull(1) as? List<*> ?: return null
            val numbers = listOf(origin.getOrNull(0), origin.getOrNull(1), size.getOrNull(0), size.getOrNull(1))
                .map { (it as? Number)?.toFloat() ?: return null }
            if (numbers.any { !it.isFinite() } || numbers[2] <= 0 || numbers[3] <= 0) return null
            return OemFrame(numbers[0], numbers[1], numbers[2], numbers[3])
        }
    }
}
