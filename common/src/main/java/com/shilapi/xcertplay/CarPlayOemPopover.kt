package com.shilapi.xcertplay

/** Layout.lua 的远端弹层裁切数据，坐标已经处于整屏空间。 */
internal data class OemPopover(val frame: OemFrame, val transform: List<Float>, val opacity: Float, val radius: Float) {
    companion object {
        fun from(layout: Map<String, Any?>, data: Map<String, Any?>): List<OemPopover> {
            val regions = when (val raw = data["regions"]) {
                is Map<*, *> -> raw.values.toList()
                is List<*> -> raw
                else -> return emptyList()
            }
            val zones = (layout["zones"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
            val result = linkedMapOf<String, OemPopover>()
            regions.filterIsInstance<Map<*, *>>().forEach { region ->
                val id = region["identifier"] as? String ?: return@forEach
                if (!id.startsWith("popover:") || id.length <= 8) return@forEach
                val query = id.substringAfter('?', "").split('&').associate {
                    it.substringBefore('=') to it.substringAfter('=', "")
                }
                val zone = zones.firstOrNull { it["id"] == query["zone"] } ?: return@forEach
                val regionId = (zone["type"] as? Map<*, *>)?.values?.filterIsInstance<Map<*, *>>()
                    ?.firstNotNullOfOrNull { it["zoneRegion"] as? String }
                if (regionId != "zone1" && regionId != "zone2") return@forEach
                val zoneFrame = OemScenePlan.frame(zone["frame"]) ?: return@forEach
                fun floats(raw: Any?, count: Int): List<Float>? = (raw as? List<*>)?.takeIf { it.size == count }
                    ?.map { (it as? Number)?.toFloat()?.takeIf(Float::isFinite) ?: return null }
                val bounds = floats(region["bounds"], 4) ?: return@forEach
                val transform = floats(region["transform2D"], 6) ?: return@forEach
                val opacity = ((region["cumulative_opacity"] ?: region["opacity"]) as? Number)?.toFloat()
                    ?.takeIf { it.isFinite() } ?: return@forEach
                if (opacity <= 0 || bounds[2] <= 0 || bounds[3] <= 0) return@forEach
                if (bounds[0] + bounds[2] > zoneFrame.x + zoneFrame.width ||
                    bounds[1] + bounds[3] > zoneFrame.y + zoneFrame.height) return@forEach
                val radius = (region["corner_radius"] as? Number)?.toFloat()?.takeIf { it.isFinite() && it >= 0 } ?: 0f
                result[id.substring(8)] = OemPopover(OemFrame(bounds[0], bounds[1], bounds[2], bounds[3]),
                    transform, opacity.coerceAtMost(1f), radius)
            }
            return result.values.toList()
        }
    }
}
