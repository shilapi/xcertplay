package com.shilapi.xcertplay

/** Screen shows progress; full protocol records remain in file/logcat. */
internal object ScreenLogPolicy {
    fun summary(message: String): String? {
        if (message.startsWith("TRACE ")) return null
        if (!message.startsWith("IAP2 ")) return message
        val firstLine = message.lineSequence().firstOrNull().orEmpty()
        return if (firstLine.length <= 480) firstLine else firstLine.take(480) + "..."
    }
}
