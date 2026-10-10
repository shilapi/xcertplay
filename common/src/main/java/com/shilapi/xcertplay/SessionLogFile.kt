package com.shilapi.xcertplay

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Ordered capture retaining the session head and a rolling tail in one size-limited file. */
internal class SessionLogFile(
    val file: File,
    private val maxBytes: Int = MAX_BYTES,
    private val headLimitBytes: Int = maxBytes / 3,
    private val onError: (String) -> Unit = {},
) : Closeable {
    init {
        require(maxBytes >= 2048)
        require(headLimitBytes in 1..(maxBytes - RETENTION_MARKER_RESERVE - TRUNCATION_MARKER_RESERVE - 128))
    }
    private val lock = ReentrantLock()
    private val writerExecutor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(16),
        { task -> Thread(task, "xcertplay-log-writer").apply { isDaemon = true } },
        { task, executor ->
            check(!executor.isShutdown) { "Log writer is closed" }
            executor.queue.put(task)
        })
    private var output: BufferedOutputStream? = null
    private var bytesWritten = 0L
    private var headBytes = 0L
    private var headFrozen = false
    private val tailRecordSizes = ArrayDeque<Int>()
    private var retentionMarker = ByteArray(0)
    private var removedBytes = 0L
    private var removedRecords = 0L
    private var rotations = 0L
    private var closed = false
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var failedRecords = 0L
    private val videoPending = AtomicBoolean(false)
    private val droppedVideoPackets = AtomicLong(0)

    /** Clears previous records and starts a new session. */
    fun reset(header: String) {
        lock.withLock {
            check(!closed)
            writerExecutor.submit {
                try {
                    openOutput(append = false)
                    headBytes = 0
                    headFrozen = false
                    tailRecordSizes.clear()
                    retentionMarker = ByteArray(0)
                    removedBytes = 0
                    removedRecords = 0
                    rotations = 0
                    failedRecords = 0
                    writeLine(header)
                } catch (error: Exception) {
                    reportWriteFailure(error)
                }
            }.get()
        }
    }
    fun append(line: String) = enqueue { line }
    fun appendTimestamped(message: String, timestampMillis: Long) = enqueue {
        "${formatter.format(Date(timestampMillis))}  $message"
    }
    /** Raw capture is best-effort: reserve queue space for connection records, never stall video. */
    fun appendVideoPacket(
        type: Int, codec: String, sequence: Long,
        header: ByteArray, wire: ByteArray, plain: ByteArray?,
    ) {
        if (!lock.tryLock()) {
            droppedVideoPackets.incrementAndGet()
            return
        }
        try {
            if (closed) return
            val payload = plain?.takeUnless { it === wire }
            val size = header.size.toLong() + wire.size + (payload?.size ?: 0)
            val recordBudget = maxBytes - headLimitBytes - RETENTION_MARKER_RESERVE - TRUNCATION_MARKER_RESERVE - 1024
            if (size > MAX_PENDING_VIDEO_BYTES || size * 2 > recordBudget ||
                writerExecutor.queue.remainingCapacity() < 8 || !videoPending.compareAndSet(false, true)) {
                droppedVideoPackets.incrementAndGet()
                return
            }
            val capturedHeader = header.copyOf()
            val capturedWire = wire.copyOf()
            val capturedPlain = payload?.copyOf()
            val at = System.currentTimeMillis()
            writerExecutor.execute {
                try {
                    writeVideoLoss()
                    writeLine(buildString {
                        append(formatter.format(Date(at)))
                        append("  TRACE MEDIA VIDEO type=$type codec=$codec sequence=$sequence")
                        append(" opcode=${capturedHeader.getOrNull(4)?.toInt()?.and(0xff)}")
                        append(" headerBytes=${capturedHeader.size} wireBytes=${capturedWire.size}")
                        append(" plainBytes=${capturedPlain?.size ?: 0}\n  headerHex=")
                        append(hex(capturedHeader))
                        append("\n  wireHex="); append(hex(capturedWire))
                        capturedPlain?.let { append("\n  plainHex="); append(hex(it)) }
                    })
                } finally { videoPending.set(false) }
            }
        } finally { lock.unlock() }
    }

    private fun writeVideoLoss() {
        val dropped = droppedVideoPackets.getAndSet(0)
        if (dropped > 0) writeLine("LOG VIDEO LOSS droppedPackets=$dropped reason=capture-capacity")
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val output = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = digits[value ushr 4]
            output[index * 2 + 1] = digits[value and 15]
        }
        return String(output)
    }

    private fun enqueue(line: () -> String) {
        lock.withLock {
            if (closed) { onError("Log append rejected after close path=${file.absolutePath}"); return }
            writerExecutor.execute { writeVideoLoss(); writeLine(line()) }
        }
    }
    private fun writeLine(line: String) {
        try {
            val lossMarker = if (failedRecords > 0) {
                "\nLOG LOSS priorFailedRecords=$failedRecords\n".toByteArray(StandardCharsets.UTF_8)
            } else null
            // A single oversized record must leave room for the pinned head and markers.
            val maxRecordBytes = maxBytes - headBytes.toInt() - RETENTION_MARKER_RESERVE -
                TRUNCATION_MARKER_RESERVE - 1 - (lossMarker?.size ?: 0)
            var bytes = line.toByteArray(StandardCharsets.UTF_8)
            var truncationMarker = ByteArray(0)
            if (bytes.size > maxRecordBytes) {
                val originalSize = bytes.size
                var start = bytes.size - maxRecordBytes
                while (start < bytes.size && bytes[start].toInt() and 0xc0 == 0x80) start++
                bytes = bytes.copyOfRange(start, bytes.size)
                truncationMarker = ("LOG TRUNCATED originalRecordBytes=$originalSize " +
                    "keptSuffixBytes=${bytes.size}\n").toByteArray(StandardCharsets.UTF_8)
                check(truncationMarker.size <= TRUNCATION_MARKER_RESERVE)
            }
            val recordSize = (lossMarker?.size ?: 0) + truncationMarker.size + bytes.size + 1
            if (output == null) openOutput(append = true)
            if (bytesWritten + recordSize > maxBytes) {
                compactTail(recordSize)
            }
            val active = output!!
            if (lossMarker != null) {
                active.write(lossMarker)
            }
            active.write(truncationMarker)
            active.write(bytes)
            active.write('\n'.code)
            active.flush()
            bytesWritten += recordSize
            failedRecords = 0
            if (!headFrozen && headBytes + recordSize <= headLimitBytes) {
                headBytes += recordSize
            } else {
                headFrozen = true
                tailRecordSizes.addLast(recordSize)
            }
        } catch (error: Exception) {
            reportWriteFailure(error)
        }
    }

    /** Evicts whole oldest tail records in batches; the pinned head is never discarded. */
    private fun compactTail(incomingRecordSize: Int) {
        val target = maxOf(maxBytes / 6L,
            bytesWritten + incomingRecordSize + RETENTION_MARKER_RESERVE - maxBytes)
        var droppedBytes = 0L
        var droppedRecords = 0
        for (size in tailRecordSizes) {
            droppedBytes += size
            droppedRecords++
            if (droppedBytes >= target) break
        }
        val marker = ("LOG RETENTION maxBytes=$maxBytes headLimitBytes=$headLimitBytes " +
            "headBytes=$headBytes rotations=${rotations + 1} " +
            "removedRecords=${removedRecords + droppedRecords} " +
            "removedBytes=${removedBytes + droppedBytes}\n").toByteArray(StandardCharsets.UTF_8)
        check(marker.size <= RETENTION_MARKER_RESERVE)
        val oldTailStart = headBytes + retentionMarker.size
        val remainingTailBytes = bytesWritten - oldTailStart - droppedBytes
        val nextSize = headBytes + marker.size + remainingTailBytes
        check(remainingTailBytes >= 0 && nextSize + incomingRecordSize <= maxBytes)

        // Replace the complete snapshot atomically in the same directory. Readers see either
        // the old or new head+tail, and a failed copy leaves the original file intact.
        output?.close()
        output = null
        val temporary = File.createTempFile(file.name + ".", ".rotate", file.absoluteFile.parentFile)
        try {
            RandomAccessFile(file, "r").use { source ->
                temporary.outputStream().buffered().use { destination ->
                    val buffer = ByteArray(64 * 1024)
                    copyRange(source, destination, 0, headBytes, buffer)
                    destination.write(marker)
                    copyRange(source, destination, oldTailStart + droppedBytes, remainingTailBytes, buffer)
                }
            }
            check(temporary.renameTo(file)) { "Could not replace rotated log ${file.absolutePath}" }
            repeat(droppedRecords) { tailRecordSizes.removeFirst() }
            removedBytes += droppedBytes
            removedRecords += droppedRecords
            rotations++
            retentionMarker = marker
            bytesWritten = nextSize
            openOutput(append = true)
        } finally {
            temporary.delete()
        }
    }

    private fun copyRange(source: RandomAccessFile, destination: BufferedOutputStream,
        start: Long, length: Long, buffer: ByteArray) {
        source.seek(start)
        var remaining = length
        while (remaining > 0) {
            val count = minOf(buffer.size.toLong(), remaining).toInt()
            source.readFully(buffer, 0, count)
            destination.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun openOutput(append: Boolean) {
        output?.close()
        output = null
        file.parentFile?.mkdirs()
        output = java.io.FileOutputStream(file, append).buffered()
        bytesWritten = if (append) file.length() else 0L
    }

    private fun reportWriteFailure(error: Exception) {
        failedRecords++
        runCatching { output?.close() }; output = null
        // Roll back a partial append so record offsets remain valid after I/O recovery.
        runCatching { RandomAccessFile(file, "rw").use { it.setLength(bytesWritten) } }
        onError("Log write failed path=${file.absolutePath} failedRecords=$failedRecords reason=${error.message}")
    }
    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            writerExecutor.execute {
                writeVideoLoss()
                runCatching { output?.close() }.onFailure { onError("Log close failed: ${it.message}") }
                output = null
            }
            writerExecutor.shutdown()
        }
        writerExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
    }

    private companion object {
        const val MAX_BYTES = 30 * 1024 * 1024
        const val MAX_PENDING_VIDEO_BYTES = 8 * 1024 * 1024L
        const val RETENTION_MARKER_RESERVE = 512
        const val TRUNCATION_MARKER_RESERVE = 128
    }
}
