package com.shilapi.xcertplay.airplay

/** Reusable binary-plist event command construction. iAP payloads remain complete raw CSM frames. */
object AirPlayEventCommands {
    fun requiredFeature(command: Map<String, Any?>): AirPlayFeature? = when (command["type"]) {
        "iAPSendMessage" -> AirPlayFeature.IAP_CHANNEL
        "updateViewArea", "updateDisplayInfo" -> AirPlayFeature.VIEW_AREAS
        "showUI", "stopUI" -> AirPlayFeature.UI_CONTEXT
        "updateVehicleInformation" -> AirPlayFeature.VEHICLE_STATE_PROTOCOL
        "stopSession", "resetSession" -> AirPlayFeature.SESSION_MANAGEMENT
        else -> null
    }
    fun command(type: String, params: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        require(type.isNotBlank())
        return linkedMapOf("type" to type, "params" to LinkedHashMap(params))
    }
    fun iapSendMessage(data: ByteArray) = command("iAPSendMessage", mapOf("data" to data.copyOf()))
    fun showUi(uuid: String, url: String) = command("showUI", mapOf("uuid" to uuid, "url" to url))
    fun stopUi(uuid: String) = command("stopUI", mapOf("uuid" to uuid))
    fun updateViewArea(uuid: String, index: Long, adjacent: List<Long>, animationMillis: Long = 0): Map<String, Any?> {
        require(index >= 0 && animationMillis >= 0 && adjacent.all { it >= 0 })
        return command("updateViewArea", mapOf("uuid" to uuid, "viewAreaIndex" to index,
            "adjacentViewAreas" to adjacent, "animationDurationMillis" to animationMillis))
    }
    fun updateDisplayInfo(display: AirPlayDisplayConfig, type: Long, uuid: String) =
        command("updateDisplayInfo", AirPlayDisplayDescriptionFactory.create(display, type.toInt(), uuid))
    fun nightMode(night: Boolean, uuid: String? = null) = command("setNightMode",
        linkedMapOf<String, Any?>("nightMode" to night).apply { uuid?.let { put("uuid", it) } })
    fun limitedUi(uuid: String, limited: Boolean) = command("setLimitedUI", mapOf("uuid" to uuid, "limitedUI" to limited))
    fun vehicleInformation(info: Map<String, Any?>) = command("updateVehicleInformation", mapOf("vehicleInformation" to info))
    fun siri(action: Int) = command("requestSiri", mapOf("siriAction" to action))
    fun hidDeviceReport(uid: Int, report: ByteArray): Map<String, Any?> =
        linkedMapOf("type" to "hidSendReport", "uuid" to uid.toString(16), "hidReport" to report.copyOf())
    fun hidReport(uuid: String, report: ByteArray) = command("hidSendReport", mapOf("displayUUID" to uuid, "hidReport" to report.copyOf()))
}
