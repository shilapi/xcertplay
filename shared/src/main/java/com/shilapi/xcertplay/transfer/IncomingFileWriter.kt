package com.shilapi.xcertplay.transfer

import java.io.Closeable
import java.io.OutputStream
import java.security.MessageDigest

/** Shared bounded, sequential sink for iAP2 artwork and RCS theme transfers. */
internal class IncomingFileWriter(
    private val output: OutputStream,
    private val maximumBytes: Long,
    private val expectedBytes: Long? = null,
) : Closeable {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var closed = false
    var receivedBytes = 0L
        private set

    init {
        require(maximumBytes > 0)
        require(expectedBytes == null || expectedBytes in 0..maximumBytes)
    }

    fun write(bytes: ByteArray) {
        check(!closed) { "File writer is closed" }
        require(bytes.size.toLong() <= maximumBytes - receivedBytes) { "File exceeds receive limit" }
        require(expectedBytes == null || bytes.size.toLong() <= expectedBytes - receivedBytes) {
            "File exceeds advertised size"
        }
        output.write(bytes)
        digest.update(bytes)
        receivedBytes += bytes.size
    }

    fun finish(): String {
        check(!closed) { "File writer is closed" }
        require(expectedBytes == null || receivedBytes == expectedBytes) { "File is incomplete" }
        output.flush()
        if (output is java.io.FileOutputStream) output.fd.sync()
        close()
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    override fun close() {
        if (closed) return
        closed = true
        output.close()
    }
}
