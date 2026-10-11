package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import com.shilapi.xcertplay.airplay.VideoCodec

/** Process-owned log sink shared by Activities and the background CarPlay session. */
internal class SessionLog : Closeable {
    private val lock = Any()
    @Volatile private var activeLog: SessionLogFile? = null
    @Volatile var videoFramesEnabled = false

    fun start(file: File, header: String, onError: (String) -> Unit = {}) {
        synchronized(lock) {
            if (activeLog != null) return
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

    fun appendVideoPacket(
        type: Int, codec: VideoCodec, sequence: Long,
        header: ByteArray, wire: ByteArray, plain: ByteArray?,
    ) {
        if (!videoFramesEnabled) return
        activeLog?.appendVideoPacket(type, codec.name, sequence, header, wire, plain)
    }

    override fun close() {
        synchronized(lock) {
            activeLog?.close()
            activeLog = null
        }
    }
}
