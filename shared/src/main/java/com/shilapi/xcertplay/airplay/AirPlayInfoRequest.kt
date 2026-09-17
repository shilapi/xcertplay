package com.shilapi.xcertplay.airplay

/**
 * Typed view of the phone's `GET /info` request body.
 *
 * Direction is phone -> accessory. `altScreenURLs` is confirmed by the Ultra capture.
 * `uiContextURLs` is retained as an optional, inferred counterpart and remains absent when the
 * field is not present. Unrecognized fields are preserved instead of being folded into the
 * response.
 */
data class AirPlayInfoRequest(
    val altScreenUrls: List<String>,
    val uiContextUrls: List<String>?,
    val unrecognized: Map<String, Any?>,
)

object AirPlayInfoRequestFactory {
    private val knownKeys = setOf("altScreenURLs", "uiContextURLs")

    fun decode(body: ByteArray): AirPlayInfoRequest? {
        if (body.isEmpty()) return null
        val map = BplistCodec.decode(body) as? Map<*, *> ?: return null
        val typed = LinkedHashMap<String, Any?>(map.size)
        map.forEach { (key, value) -> typed[key.toString()] = value }
        return AirPlayInfoRequest(
            altScreenUrls = stringList(typed["altScreenURLs"]),
            uiContextUrls = typed["uiContextURLs"]?.let(::stringList),
            unrecognized = typed.filterKeys { it !in knownKeys },
        )
    }

    private fun stringList(value: Any?): List<String> =
        (value as? List<*>)
            .orEmpty()
            .filterIsInstance<String>()
}
