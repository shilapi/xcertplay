package com.shilapi.xcertplay.airplay.rcs.transfer

import com.shilapi.xcertplay.transfer.IncomingFileWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

/** Ordered reliable RCS stream. Only publishes a complete, checksummed temporary file. */
class CarPlayThemeTransferReceiver(
    private val directory: File,
    private val onComplete: (File, CarPlayThemeFileHeader) -> Unit,
    private val maximumBytes: Long = 64L * 1024 * 1024,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val timeoutMillis: Long = 120_000,
    private val trace: (String) -> Unit = {},
) : Closeable {
    private data class Transfer(val header: CarPlayThemeFileHeader, val file: File,
        val writer: IncomingFileWriter, var lastActivity: Long, var nextIndex: Long = 0)
    private var active: Transfer? = null
    private var closed = false

    init { require(maximumBytes > 0 && timeoutMillis > 0) }

    @Synchronized fun receive(bytes: ByteArray): ByteArray? {
        check(!closed) { "Theme receiver closed" }
        return try {
            when (val message = CarPlayFileTransferCodec.decode(bytes)) {
                is CarPlayThemeFileHeader -> {
                    require(active == null) { "Theme transfer already active" }
                    require(directory.isDirectory || directory.mkdirs()) { "Cannot create theme receive directory" }
                    val file = File.createTempFile("theme-", ".partial", directory)
                    try {
                        active = Transfer(message, file,
                            IncomingFileWriter(FileOutputStream(file), maximumBytes), nowMillis())
                    } catch (failure: Exception) { file.delete(); throw failure }
                    trace("Theme transfer header version=${message.version} chunks=${message.chunkCount}")
                    null // Firmware sends accept only after the complete file, not after the header.
                }
                is CarPlayFileChunk -> {
                    val transfer = active ?: throw IllegalArgumentException("Theme header missing")
                    require(message.sessionId == transfer.header.sessionId) { "Theme session mismatch" }
                    require(message.index == transfer.nextIndex) { "Theme chunk sequence mismatch" }
                    val last = message.index == transfer.header.chunkCount - 1
                    require(last == (message.checksum != null)) { "Checksum required only on final theme chunk" }
                    transfer.writer.write(message.data)
                    transfer.nextIndex++
                    transfer.lastActivity = nowMillis()
                    if (transfer.nextIndex == 1L || transfer.nextIndex % 16L == 0L || last)
                        trace("Theme transfer progress version=${transfer.header.version} " +
                            "chunks=${transfer.nextIndex}/${transfer.header.chunkCount} bytes=${transfer.writer.receivedBytes}")
                    if (!last) null else {
                        val digest = transfer.writer.finish()
                        require(digest.equals(message.checksum, ignoreCase = true)) { "Theme checksum mismatch" }
                        // Signature/identity/ZIP validation and durable staging must finish before success.
                        onComplete(transfer.file, transfer.header)
                        trace("Theme transfer received version=${transfer.header.version} bytes=${transfer.writer.receivedBytes} sha256=$digest")
                        clear()
                        CarPlayFileTransferCodec.accept(true)
                    }
                }
            }
        } catch (failure: Exception) {
            trace("Theme transfer rejected: ${failure.message}")
            clear()
            CarPlayFileTransferCodec.accept(false)
        }
    }

    @Synchronized fun expire(): ByteArray? {
        val transfer = active ?: return null
        if (nowMillis() - transfer.lastActivity < timeoutMillis) return null
        trace("Theme transfer timed out version=${transfer.header.version}")
        clear()
        return CarPlayFileTransferCodec.accept(false)
    }

    @Synchronized override fun close() { closed = true; clear() }

    private fun clear() {
        val transfer = active ?: return
        active = null
        runCatching { transfer.writer.close() }
        transfer.file.delete()
    }
}
