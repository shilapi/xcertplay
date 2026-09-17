package com.shilapi.xcertplay.airplay

/**
 * Safe detailed rendering for protocol trace files.
 *
 * Structured values stay readable, while binary values are reduced to a short preview. Callers
 * that need the exact wire bytes also log a complete hex string.
 */
object ProtocolTraceFormatter {
    fun hex(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "<empty>"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
        }
    }

    fun preview(bytes: ByteArray, maximumBytes: Int = 32): String {
        require(maximumBytes >= 0)
        if (bytes.isEmpty()) return "<empty>"
        val shown = bytes.take(maximumBytes).joinToString(" ") {
            "%02x".format(it.toInt() and 0xff)
        }
        return if (bytes.size <= maximumBytes) {
            shown
        } else {
            "$shown ... (+${bytes.size - maximumBytes}B)"
        }
    }

    fun pretty(value: Any?): String = formatValue(value, depth = 0)

    fun bplist(bytes: ByteArray): String = try {
        pretty(BplistCodec.decode(bytes))
    } catch (error: Exception) {
        "decode-failed=${error.javaClass.simpleName}: ${error.message ?: "no detail"}"
    }

    private fun formatValue(value: Any?, depth: Int): String {
        if (depth > MAX_DEPTH) return "<max-depth>"
        return when (value) {
            null -> "null"
            is ByteArray -> "data(${value.size})=[${preview(value)}]"
            is Boolean,
            is Byte,
            is Short,
            is Int,
            is Long,
            is Float,
            is Double,
            is String,
            is Char,
            -> value.toString()

            is Map<*, *> -> formatMap(value, depth)
            is Iterable<*> -> formatList(value.toList(), depth)
            is Array<*> -> formatList(value.toList(), depth)
            else -> value.toString()
        }
    }

    private fun formatMap(value: Map<*, *>, depth: Int): String {
        if (value.isEmpty()) return "{}"
        val shown = value.entries.take(MAX_ITEMS)
        return buildString {
            append('{')
            shown.forEachIndexed { index, (key, entry) ->
                if (index > 0) append(", ")
                append(formatValue(key, depth + 1))
                append('=')
                append(formatValue(entry, depth + 1))
            }
            if (value.size > shown.size) append(", ... +${value.size - shown.size}")
            append('}')
        }
    }

    private fun formatList(value: List<*>, depth: Int): String {
        if (value.isEmpty()) return "[]"
        val shown = value.take(MAX_ITEMS)
        return buildString {
            append('[')
            shown.forEachIndexed { index, entry ->
                if (index > 0) append(", ")
                append(formatValue(entry, depth + 1))
            }
            if (value.size > shown.size) append(", ... +${value.size - shown.size}")
            append(']')
        }
    }

    private const val MAX_DEPTH = 8
    private const val MAX_ITEMS = 128
}
