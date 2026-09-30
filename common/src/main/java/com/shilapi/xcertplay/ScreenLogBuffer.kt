package com.shilapi.xcertplay

import java.util.ArrayDeque

internal class ScreenLogBuffer(private val maxLines: Int = 100) {
    private data class Entry(val timestampMillis: Long, val text: String)

    private val lines = ArrayDeque<Entry>()

    val firstTimestampMillis: Long?
        get() = lines.firstOrNull()?.timestampMillis

    val size: Int
        get() = lines.size

    fun add(timestampMillis: Long, text: String) {
        text.lineSequence().forEach { line ->
            lines.addLast(Entry(timestampMillis, line))
            if (lines.size > maxLines) lines.removeFirst()
        }
    }

    fun expireBefore(cutoffMillis: Long) {
        while (lines.firstOrNull()?.timestampMillis?.let { it <= cutoffMillis } == true) {
            lines.removeFirst()
        }
    }

    fun clear() = lines.clear()

    fun renderedText(): String = lines.joinToString("\n") { it.text }
}
