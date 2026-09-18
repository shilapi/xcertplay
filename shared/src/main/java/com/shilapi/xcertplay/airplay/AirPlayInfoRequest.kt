package com.shilapi.xcertplay.airplay

/**
 * Typed view of the phone's `GET /info` request body.
 *
 * Direction is phone -> accessory. `altScreenURLs` is confirmed by the Ultra capture.
 * The three `uiContext` URL arrays are retained as inferred counterparts and keep the distinction
 * between an absent key and an explicitly empty array. Unrecognized fields are preserved instead
 * of being folded into the response.
 */
data class AirPlayInfoRequest(
    val altScreenUrls: List<String>,
    val uiContext: AirPlayUiContextRequest?,
    val unrecognized: Map<String, Any?>,
) {
    val uiContextUrls: List<String>?
        get() = uiContext?.urls

    val uiContextLastOnDisplayUrls: List<String>?
        get() = uiContext?.lastOnDisplayUrls

    val uiContextNowOnDisplayUrls: List<String>?
        get() = uiContext?.nowOnDisplayUrls

    fun toWireMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        putAll(unrecognized)
        put("altScreenURLs", altScreenUrls)
        putAll(uiContext?.toWireMap().orEmpty())
    }
}

/**
 * UI-context URLs supplied by the phone in `GET /info`.
 *
 * A null property means that its wire key was absent. An empty list means the key was present with
 * an empty array, which is materially different for feature validation and diagnostics.
 */
data class AirPlayUiContextRequest(
    val urls: List<String>? = null,
    val lastOnDisplayUrls: List<String>? = null,
    val nowOnDisplayUrls: List<String>? = null,
) {
    fun toWireMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        urls?.let { put("uiContextURLs", it) }
        lastOnDisplayUrls?.let { put("uiContextLastOnDisplayURLs", it) }
        nowOnDisplayUrls?.let { put("uiContextNowOnDisplayURLs", it) }
    }

    companion object {
        fun fromWireMap(values: Map<String, Any?>): AirPlayUiContextRequest? {
            val urls = values.optionalStringList("uiContextURLs")
            val lastOnDisplayUrls = values.optionalStringList("uiContextLastOnDisplayURLs")
            val nowOnDisplayUrls = values.optionalStringList("uiContextNowOnDisplayURLs")
            if (urls == null && lastOnDisplayUrls == null && nowOnDisplayUrls == null) {
                return null
            }
            return AirPlayUiContextRequest(
                urls = urls,
                lastOnDisplayUrls = lastOnDisplayUrls,
                nowOnDisplayUrls = nowOnDisplayUrls,
            )
        }
    }
}

object AirPlayInfoRequestFactory {
    private val knownKeys = setOf(
        "altScreenURLs",
        "uiContextURLs",
        "uiContextLastOnDisplayURLs",
        "uiContextNowOnDisplayURLs",
    )

    fun decode(body: ByteArray): AirPlayInfoRequest? {
        if (body.isEmpty()) return null
        val map = BplistCodec.decode(body) as? Map<*, *> ?: return null
        val typed = LinkedHashMap<String, Any?>(map.size)
        map.forEach { (key, value) -> typed[key.toString()] = value }
        return AirPlayInfoRequest(
            altScreenUrls = stringList(typed["altScreenURLs"]),
            uiContext = AirPlayUiContextRequest.fromWireMap(typed),
            unrecognized = typed.filterKeys { it !in knownKeys },
        )
    }

    fun encode(request: AirPlayInfoRequest): ByteArray = BplistCodec.encode(request.toWireMap())

    private fun stringList(value: Any?): List<String> =
        (value as? List<*>)
            .orEmpty()
            .filterIsInstance<String>()
}

private fun Map<String, Any?>.optionalStringList(key: String): List<String>? {
    if (!containsKey(key)) return null
    return (this[key] as? List<*>)
        .orEmpty()
        .filterIsInstance<String>()
}
