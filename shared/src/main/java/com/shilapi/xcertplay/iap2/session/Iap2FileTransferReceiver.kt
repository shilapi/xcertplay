package com.shilapi.xcertplay.iap2.session

import java.io.ByteArrayOutputStream

internal data class Iap2TransferredArtwork(
    val fileTransferId: Int,
    val bytes: ByteArray,
)

/** Receives iAP2 session-12 transfers and acknowledges supported Now Playing artwork. */
internal class Iap2FileTransferReceiver(
    private val session: Iap2Session,
    private val isArtworkCached: (Int) -> Boolean,
    private val onArtwork: (Iap2TransferredArtwork) -> Unit,
    private val onLog: (String) -> Unit = {},
) : AutoCloseable {
    private val handler = Iap2FileTransferHandler()

    @Volatile private var closed = false
    private val worker = Thread(::run, "xcertplay-iap2-file-transfer").apply {
        isDaemon = true
    }

    fun start() {
        worker.start()
    }

    override fun close() {
        closed = true
        worker.interrupt()
        if (Thread.currentThread() !== worker) worker.join(CLOSE_JOIN_MILLIS)
    }

    private fun run() {
        try {
            while (!closed && !session.isClosed) {
                session.recvFileTransfer(RECEIVE_POLL_MILLIS)?.let(::handle)
                handler.expire().forEach { transferId ->
                    send(byteArrayOf(transferId.toByte(), DATAGRAM_FAILURE.toByte()))
                    onLog("iap2 file transfer id=$transferId timed out")
                }
            }
        } catch (failure: Throwable) {
            if (failure is Error) throw failure
            if (!closed && !session.isClosed) {
                onLog("iap2 file transfer stopped: ${failure.message ?: failure.javaClass.simpleName}")
            }
        }
    }

    private fun handle(datagram: ByteArray) {
        val outcome = handler.handle(datagram, isArtworkCached)
        outcome.response?.let(::send)
        outcome.artwork?.let { artwork ->
            onArtwork(artwork)
            onLog(
                "iap2 artwork received id=${artwork.fileTransferId} bytes=${artwork.bytes.size}",
            )
        }
    }

    private fun send(datagram: ByteArray) {
        session.sendFileTransfer(datagram, SEND_TIMEOUT_MILLIS)
    }

    private companion object {
        const val DATAGRAM_FAILURE = 0x06
        const val RECEIVE_POLL_MILLIS = 500L
        const val SEND_TIMEOUT_MILLIS = 5_000L
        const val CLOSE_JOIN_MILLIS = 1_000L
    }
}

internal data class Iap2FileTransferOutcome(
    val response: ByteArray? = null,
    val artwork: Iap2TransferredArtwork? = null,
)

/** Stateful protocol core kept independent from threads and Media3. */
internal class Iap2FileTransferHandler(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Transfer(
        val expectedBytes: Int,
        val startedMillis: Long,
        val data: ByteArrayOutputStream,
        var receiving: Boolean = false,
    )

    private val transfers = HashMap<Int, Transfer>()

    fun handle(
        datagram: ByteArray,
        isArtworkCached: (Int) -> Boolean = { false },
    ): Iap2FileTransferOutcome {
        if (datagram.size < 2) return Iap2FileTransferOutcome()
        val transferId = datagram[0].toInt() and 0xff
        return when (datagram[1].toInt() and 0xff) {
            DATAGRAM_SETUP -> setup(transferId, datagram, isArtworkCached)
            DATAGRAM_FIRST_DATA -> first(transferId, datagram.copyOfRange(2, datagram.size))
            DATAGRAM_DATA -> append(transferId, datagram.copyOfRange(2, datagram.size), last = false)
            DATAGRAM_LAST_DATA -> append(transferId, datagram.copyOfRange(2, datagram.size), last = true)
            DATAGRAM_FIRST_AND_ONLY -> firstAndOnly(
                transferId,
                datagram.copyOfRange(2, datagram.size),
            )
            DATAGRAM_CANCEL -> {
                transfers.remove(transferId)
                Iap2FileTransferOutcome()
            }
            DATAGRAM_PAUSE -> Iap2FileTransferOutcome()
            else -> Iap2FileTransferOutcome()
        }
    }

    fun expire(): List<Int> {
        val now = nowMillis()
        val expired = transfers.filterValues { now - it.startedMillis >= TRANSFER_TIMEOUT_MILLIS }.keys
        expired.forEach(transfers::remove)
        return expired.toList()
    }

    private fun setup(
        transferId: Int,
        datagram: ByteArray,
        isArtworkCached: (Int) -> Boolean,
    ): Iap2FileTransferOutcome {
        transfers.remove(transferId)
        if (datagram.size < SETUP_BYTES) return failure(transferId)
        val size = readU64(datagram, 2)
        val fileType = readU16(datagram, 10)
        if (fileType != FILE_TYPE_NOW_PLAYING_ARTWORK || size > MAX_ARTWORK_BYTES.toULong()) {
            return failure(transferId)
        }
        if (isArtworkCached(transferId)) return response(transferId, DATAGRAM_SUCCESS)

        val expectedBytes = size.toInt()
        transfers[transferId] = Transfer(
            expectedBytes = expectedBytes,
            startedMillis = nowMillis(),
            data = ByteArrayOutputStream(expectedBytes.coerceAtMost(INITIAL_CAPACITY_BYTES)),
        )
        return response(transferId, DATAGRAM_START)
    }

    private fun first(transferId: Int, bytes: ByteArray): Iap2FileTransferOutcome {
        val transfer = transfers[transferId] ?: return Iap2FileTransferOutcome()
        if (transfer.receiving) return failure(transferId)
        transfer.receiving = true
        return write(transferId, transfer, bytes, last = false)
    }

    private fun append(
        transferId: Int,
        bytes: ByteArray,
        last: Boolean,
    ): Iap2FileTransferOutcome {
        val transfer = transfers[transferId] ?: return Iap2FileTransferOutcome()
        if (!transfer.receiving) return failure(transferId)
        return write(transferId, transfer, bytes, last)
    }

    private fun firstAndOnly(transferId: Int, bytes: ByteArray): Iap2FileTransferOutcome {
        val transfer = transfers[transferId] ?: return Iap2FileTransferOutcome()
        if (transfer.receiving) return failure(transferId)
        return write(transferId, transfer, bytes, last = true)
    }

    private fun write(
        transferId: Int,
        transfer: Transfer,
        bytes: ByteArray,
        last: Boolean,
    ): Iap2FileTransferOutcome {
        val receivedBytes = transfer.data.size() + bytes.size
        if (receivedBytes > MAX_ARTWORK_BYTES ||
            transfer.expectedBytes > 0 && receivedBytes > transfer.expectedBytes
        ) {
            return failure(transferId)
        }
        transfer.data.write(bytes)
        if (!last) return Iap2FileTransferOutcome()
        if (transfer.expectedBytes > 0 && transfer.data.size() != transfer.expectedBytes) {
            return failure(transferId)
        }

        transfers.remove(transferId)
        return Iap2FileTransferOutcome(
            response = command(transferId, DATAGRAM_SUCCESS),
            artwork = Iap2TransferredArtwork(transferId, transfer.data.toByteArray()),
        )
    }

    private fun failure(transferId: Int): Iap2FileTransferOutcome {
        transfers.remove(transferId)
        return response(transferId, DATAGRAM_FAILURE)
    }

    private fun response(transferId: Int, command: Int): Iap2FileTransferOutcome =
        Iap2FileTransferOutcome(response = command(transferId, command))

    private fun command(transferId: Int, command: Int): ByteArray =
        byteArrayOf(transferId.toByte(), command.toByte())

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readU64(bytes: ByteArray, offset: Int): ULong {
        var value = 0uL
        repeat(8) { index ->
            value = (value shl 8) or (bytes[offset + index].toInt() and 0xff).toULong()
        }
        return value
    }

    private companion object {
        const val DATAGRAM_SETUP = 0x04
        const val DATAGRAM_START = 0x01
        const val DATAGRAM_FIRST_DATA = 0x80
        const val DATAGRAM_DATA = 0x00
        const val DATAGRAM_LAST_DATA = 0x40
        const val DATAGRAM_FIRST_AND_ONLY = 0xc0
        const val DATAGRAM_CANCEL = 0x02
        const val DATAGRAM_PAUSE = 0x03
        const val DATAGRAM_SUCCESS = 0x05
        const val DATAGRAM_FAILURE = 0x06
        const val FILE_TYPE_NOW_PLAYING_ARTWORK = 0x0002
        const val SETUP_BYTES = 12
        const val MAX_ARTWORK_BYTES = 16 * 1_048_576
        const val INITIAL_CAPACITY_BYTES = 256 * 1_024
        const val TRANSFER_TIMEOUT_MILLIS = 30_000L
    }
}
