package com.shilapi.xcertplay.airplay

/** Per connection context/view-area state; all outgoing commands use the shared factory. */
internal class CarPlayDisplayControlSession(private val config: AirPlayConfig,
    private val send: (Map<String, Any?>) -> Boolean, private val trace: (String) -> Unit) {
    private var enabled: Set<AirPlayFeature>? = null
    @Synchronized fun negotiated(features: Set<AirPlayFeature>) {
        enabled = features.toSet()
        trace("Display control negotiated=${features.map { it.wireName }}")
        flush()
    }
    private fun required(uuid: String, feature: AirPlayFeature): Set<AirPlayFeature> =
        if (uuid == AirPlayInfoPlist.ALT_UUID) setOf(feature, AirPlayFeature.ALT_SCREEN) else setOf(feature)
    private fun requireEnabled(uuid: String, feature: AirPlayFeature) {
        require(enabled?.containsAll(required(uuid, feature)) == true) { "Display command feature not negotiated: ${required(uuid, feature)}" }
    }
    private val displays = linkedMapOf(AirPlayInfoPlist.MAIN_UUID to config.main).apply {
        config.ultra?.cluster?.let { put(AirPlayInfoPlist.ALT_UUID, it) }
    }
    @Synchronized fun updateDisplay(uuid: String, display: AirPlayDisplayConfig) {
        require(uuid in displays) { "Unknown displayUUID=$uuid" }
        val hevc = enabled?.contains(AirPlayFeature.HEVC) ?: config.hevc
        val command = AirPlayEventCommands.updateDisplayInfo(display, if (uuid == AirPlayInfoPlist.MAIN_UUID) 110 else 111, uuid, hevc)
        displays[uuid] = display
        queue("display:$uuid", command, required(uuid, AirPlayFeature.VIEW_AREAS))
    }
    @Synchronized fun updateViewArea(uuid: String, index: Int, adjacent: List<Int>, animationMillis: Long) {
        val display = displays[uuid] ?: throw IllegalArgumentException("Unknown displayUUID=$uuid")
        val indices = AirPlayDisplayDescriptionFactory.viewAreas(display).map { (it["viewAreaIndex"] as Number).toInt() }
        require(index in indices && adjacent.all { it in indices }) { "Unknown view area" }
        queue("area:$uuid", AirPlayEventCommands.updateViewArea(uuid, index.toLong(), adjacent.map(Int::toLong), animationMillis), required(uuid, AirPlayFeature.VIEW_AREAS))
    }
    private data class Pending(val command: Map<String, Any?>, val features: Set<AirPlayFeature>)
    private val pending = linkedMapOf<String, Pending>()
    private val urls = linkedMapOf<String, String>()
    @Synchronized fun info(request: AirPlayInfoRequest?) {
        if (config.ultra == null || request == null) return
        request.altScreenUrls.firstOrNull()?.let { displayUrl(AirPlayInfoPlist.ALT_UUID, it) }
        request.uiContextNowOnDisplayUrls?.firstOrNull()?.let { displayUrl(AirPlayInfoPlist.MAIN_UUID, it) }
    }
    @Synchronized fun receive(type: String?, params: Map<String, Any?>) {
        if (config.ultra == null) return
        when (type) {
            "suggestUI" -> {
                requireEnabled(AirPlayInfoPlist.MAIN_UUID, AirPlayFeature.UI_CONTEXT)
                val offered = params["urls"] as? List<*> ?: throw IllegalArgumentException("suggestUI.urls must be an array")
                val selected = offered.filterIsInstance<String>().firstOrNull() ?: return
                displayUrl(AirPlayInfoPlist.MAIN_UUID, selected)
            }
            "changeUIContext" -> {
                requireEnabled(AirPlayInfoPlist.MAIN_UUID, AirPlayFeature.UI_CONTEXT)
                val url = params["url"] as? String ?: throw IllegalArgumentException("changeUIContext.url missing")
                displayUrl(AirPlayInfoPlist.MAIN_UUID, url)
            }
            "requestViewArea" -> {
                val uuid = params["displayUUID"] as? String ?: throw IllegalArgumentException("displayUUID missing")
                requireEnabled(uuid, AirPlayFeature.VIEW_AREAS)
                val display = displays[uuid] ?: throw IllegalArgumentException("Unknown displayUUID=$uuid")
                val raw = params["viewArea"]
                val index = (raw as? Number)?.toLong() ?: ((raw as? Map<*, *>)?.get("viewAreaIndex") as? Number)?.toLong()
                    ?: throw IllegalArgumentException("viewArea must identify a viewAreaIndex")
                require(AirPlayDisplayDescriptionFactory.viewAreas(display).any { (it["viewAreaIndex"] as? Number)?.toLong() == index }) { "Unknown viewAreaIndex=$index" }
                queue("area:$uuid", AirPlayEventCommands.updateViewArea(uuid, index, display.adjacentViewAreas.map { it.toLong() }), required(uuid, AirPlayFeature.VIEW_AREAS))
            }
        }
        trace("Ultra display state type=$type urls=$urls pending=${pending.keys}")
    }
    @Synchronized fun flush() {
        val accepted = enabled ?: return
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!accepted.containsAll(entry.value.features)) {
                trace("Display command dropped key=${entry.key} required=${entry.value.features} enabled=$accepted")
                iterator.remove(); continue
            }
            if (!send(entry.value.command)) break
            iterator.remove()
        }
    }
    private fun displayUrl(uuid: String, url: String) {
        require(url.isNotBlank()) { "Empty UI context URL" }
        urls[uuid] = url; queue("url:$uuid", AirPlayEventCommands.showUi(uuid, url), required(uuid, AirPlayFeature.UI_CONTEXT))
    }
    private fun queue(key: String, command: Map<String, Any?>, features: Set<AirPlayFeature>) { pending[key] = Pending(command, features); flush() }
}
