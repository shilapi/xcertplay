package com.shilapi.xcertplay.airplay

/** Shared by /info and updateDisplayInfo. Geometry is validated before sending a success response. */
object AirPlayDisplayDescriptionFactory {
    /**
     * CarKit's logical-display fallback leaves supportsDDPContent false. The loaded OEM plugin
     * needs a physical panel whose initial stream matches the existing alternate-screen UUID.
     * Main gets the asset's Center_Display plugin when present, so DashBoard resolves the asset
     * display ID instead of falling back to the main video UUID.
     */
    fun createPanels(main: Map<String, Any?>, cluster: Map<String, Any?>,
        clusterPluginId: String, mainPluginId: String? = null): List<Map<String, Any?>> {
        require(clusterPluginId.isNotBlank()) { "OEM display plugin UID must be non-empty" }
        require(mainPluginId == null || mainPluginId.isNotBlank()) { "OEM display plugin UID must be non-empty" }
        fun stream(display: Map<String, Any?>) = linkedMapOf<String, Any?>(
            "uuid" to display.getValue("uuid"), "originXPixels" to 0,
            "originYPixels" to 0, "zIndex" to 0,
        )
        fun panel(display: Map<String, Any?>, properties: List<String>) = linkedMapOf<String, Any?>(
            "uid" to display.getValue("uuid"),
            "widthPixels" to display.getValue("widthPixels"),
            "heightPixels" to display.getValue("heightPixels"),
            "widthPhysical" to display.getValue("widthPhysical"),
            "heightPhysical" to display.getValue("heightPhysical"),
            "properties" to properties,
            "initialVideoStreams" to listOf(stream(display)),
        )
        fun plugin(uid: String, display: Map<String, Any?>) = listOf(linkedMapOf(
            "uid" to uid, "managedVideoStreams" to listOf(stream(display)),
        ))
        return listOf(
            panel(main, listOf("CarPlay")).apply { mainPluginId?.let { put("displayPlugins", plugin(it, main)) } },
            panel(cluster, listOf("Instrument Cluster", "Map", "showsInstruments")).apply {
                put("displayPlugins", plugin(clusterPluginId, cluster))
            },
        )
    }

    fun create(display: AirPlayDisplayConfig, type: Int, uuid: String): Map<String, Any?> {
        val views = viewAreas(display)
        val indices = views.map { it["viewAreaIndex"] as Int }
        require(display.widthPixels > 0 && display.heightPixels > 0) { "Display $uuid has invalid pixels" }
        require(indices.distinct().size == indices.size && display.initialViewArea in indices) {
            "Display $uuid has duplicate indices or unknown initialViewArea"
        }
        require(display.adjacentViewAreas.all { it in indices }) { "Display $uuid has unknown adjacentViewAreas" }
        val physicalWidth = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
            display.widthPhysicalMm ?: AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM)
        val physicalHeight = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(display.heightPhysicalMm
            ?: Math.round(physicalWidth * display.heightPixels.toDouble() / display.widthPixels).toInt())
        return linkedMapOf<String, Any?>(
            "uuid" to uuid, "type" to type,
            "widthPixels" to display.widthPixels, "heightPixels" to display.heightPixels,
            "widthPixelsMax" to display.widthPixels, "heightPixelsMax" to display.heightPixels,
            "widthPhysical" to physicalWidth, "heightPhysical" to physicalHeight,
            "maxFPS" to AirPlayDisplaySettings.sanitizeFps(display.fps),
            "features" to 0x0a, "primaryInputDevice" to display.primaryInputDevice,
            "viewAreas" to views, "initialViewArea" to display.initialViewArea,
            "adjacentViewAreas" to display.adjacentViewAreas,
        ).apply { display.initialUrl?.let { put("initialURL", it) } }
    }

    fun viewAreas(display: AirPlayDisplayConfig): List<Map<String, Any?>> {
        val explicit = display.viewAreas
        if (explicit.isNotEmpty()) return explicit.map { view ->
            require(view.index >= 0 && view.widthPixels > 0 && view.heightPixels > 0 &&
                view.originXPixels >= 0 && view.originYPixels >= 0 &&
                view.originXPixels.toLong() + view.widthPixels <= display.widthPixels &&
                view.originYPixels.toLong() + view.heightPixels <= display.heightPixels) { "Invalid viewArea ${view.index}" }
            require(view.cornerMasks == null || view.safeArea == null) { "cornerMasks and safeArea are mutually exclusive" }
            linkedMapOf<String, Any?>(
                "viewAreaIndex" to view.index, "widthPixels" to view.widthPixels, "heightPixels" to view.heightPixels,
                "originXPixels" to view.originXPixels, "originYPixels" to view.originYPixels,
                "viewAreaTransitionControl" to view.transitionControl,
                "viewAreaStatusBarEdge" to view.statusBarEdge,
                "viewAreaSupportsFocusTransfer" to view.supportsFocusTransfer,
            ).apply {
                view.safeArea?.let { put("safeArea", it) }
                view.cornerMasks?.let { put("cornerMasks", it) }
            }
        }
        val view = display.viewArea ?: AirPlayInsets()
        val safe = display.safeArea ?: AirPlayInsets()
        require(listOf(view.top, view.bottom, view.left, view.right, safe.top, safe.bottom, safe.left, safe.right).all { it >= 0 }) {
            "Display insets must be non-negative"
        }
        require(view.left.toLong() + view.right < display.widthPixels && view.top.toLong() + view.bottom < display.heightPixels &&
            safe.left >= view.left && safe.right >= view.right && safe.top >= view.top && safe.bottom >= view.bottom &&
            safe.left.toLong() + safe.right < display.widthPixels && safe.top.toLong() + safe.bottom < display.heightPixels) {
            "viewArea/safeArea must fit within display and safeArea within viewArea"
        }
        return listOf(linkedMapOf(
            "viewAreaIndex" to 0,
            "widthPixels" to display.widthPixels - view.left - view.right,
            "heightPixels" to display.heightPixels - view.top - view.bottom,
            "originXPixels" to view.left, "originYPixels" to view.top,
            "viewAreaTransitionControl" to 0, "viewAreaStatusBarEdge" to 0,
            "viewAreaSupportsFocusTransfer" to false,
            "safeArea" to linkedMapOf(
                "widthPixels" to display.widthPixels - safe.left - safe.right,
                "heightPixels" to display.heightPixels - safe.top - safe.bottom,
                "originXPixels" to safe.left, "originYPixels" to safe.top,
                "drawUIOutsideSafeArea" to (display.safeAreaDrawOutside ?: true),
            ),
        ))
    }
}
