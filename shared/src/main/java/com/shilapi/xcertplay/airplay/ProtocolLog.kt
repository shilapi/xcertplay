package com.shilapi.xcertplay.airplay

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/** Every destination receives the same complete record; logcat chunks can be reassembled by ID. */
object ProtocolLog {
    private val sequence = AtomicLong()
    fun info(tag: String, message: String) { chunks(message).forEach { Log.i(tag, it) } }
    fun chunks(message: String, maximumUtf8Bytes: Int = 3000): List<String> {
        require(maximumUtf8Bytes >= 256)
        val id = sequence.incrementAndGet()
        val parts = mutableListOf<String>()
        val budget = maximumUtf8Bytes - 80
        var start = 0
        var position = 0
        var bytes = 0
        while (position < message.length) {
            val code = Character.codePointAt(message, position)
            val length = when { code <= 0x7f -> 1; code <= 0x7ff -> 2; code <= 0xffff -> 3; else -> 4 }
            if (bytes + length > budget) { parts.add(message.substring(start, position)); start = position; bytes = 0 }
            bytes += length; position += Character.charCount(code)
        }
        parts.add(message.substring(start))
        return parts.mapIndexed { index, part -> "[record=$id part=${index + 1}/${parts.size}] $part" }
    }
}
