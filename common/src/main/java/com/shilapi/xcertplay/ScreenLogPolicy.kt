package com.shilapi.xcertplay

/** Screen shows progress; full protocol records remain in file/logcat. */
internal object ScreenLogPolicy {
    fun summary(message: String): String? {
        if (message.startsWith("TRACE ")) return null
        val firstLine = message.lineSequence().firstOrNull().orEmpty()
        val line = when {
            firstLine.startsWith("airplay SETUP stream type=") -> firstLine.substringBefore(" payload=")
            else -> firstLine
        }
        return if (line.length <= 480) line else line.take(480) + "..."
    }
}
