package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File

/** Process-owned log sink shared by Activities and the background CarPlay session. */
internal class SessionLog : Closeable {
    private val lock = Any()
    private var activeLog: SessionLogFile? = null

    fun start(file: File, header: String, onError: (String) -> Unit = {}) {
        synchronized(lock) {
            // Drain and close the previous stream before truncating its file. An Activity's
            // destruction may arrive after the next Activity has already started.
            activeLog?.close()
            activeLog = null
            val next = SessionLogFile(file, onError = onError)
            activeLog = next
            next.reset(header)
        }
    }

    fun append(line: String) {
        synchronized(lock) { activeLog?.append(line) }
    }

    fun appendTimestamped(message: String, timestampMillis: Long) {
        synchronized(lock) { activeLog?.appendTimestamped(message, timestampMillis) }
    }

    override fun close() {
        synchronized(lock) {
            activeLog?.close()
            activeLog = null
        }
    }
}
