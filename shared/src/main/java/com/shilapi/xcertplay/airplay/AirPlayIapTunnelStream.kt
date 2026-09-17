package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.min

/**
 * Full-duplex adapter for the CarPlay iAP2 DataStream.
 *
 * Inbound iAP2 bytes come from [IapTunnel]; outbound bytes are sent over the encrypted AirPlay
 * event channel using the `iAPSendMessage` command.
 */
internal class AirPlayIapTunnelStream(
    private val session: AirPlaySession,
    private val tunnel: IapTunnel,
    private val sendCommand: (ByteArray, Long) -> Boolean = { data, timeoutMillis ->
        session.sendIapMessage(data, timeoutMillis)
    },
) : BlockingDuplexByteStream {
    private val lock = Object()
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var closed = false
    private var terminalFailure: Throwable? = null
    private var closeLocation: String? = null

    fun listen(): Int = tunnel.listen(
        object : IapTunnel.Listener {
            override fun onOpen(remoteAddress: String?) {
                session.logTrace(
                    "AirPlay iAP tunnel connected remote=${remoteAddress ?: "unknown"}",
                )
            }

            override fun onIap(bytes: ByteArray) {
                session.logTrace(
                    "iAP2 tunnel RX bytes=${bytes.size} bodyHex=${ProtocolTraceFormatter.hex(bytes)}",
                )
                offer(bytes)
            }

            override fun onDebug(message: String) {
                session.logTrace(message)
            }

            override fun onClosed(cause: Throwable?) {
                session.logTrace(
                    "AirPlay iAP tunnel closed cause=" +
                        "${cause?.javaClass?.simpleName ?: "peer EOF"}: " +
                        "${cause?.message ?: "no detail"}",
                )
                val shouldCloseSession = markFailed(cause)
                if (shouldCloseSession) session.close()
            }
        },
    )

    override fun send(data: ByteArray) {
        session.logTrace(
            "iAP2 tunnel TX bytes=${data.size} bodyHex=${ProtocolTraceFormatter.hex(data)}",
        )
        synchronized(lock) {
            throwTerminalFailureLocked()
            if (closed) throw closedFailureLocked()
        }
        if (!tunnel.awaitPeerConnection(PEER_CONNECT_TIMEOUT_MILLIS)) {
            throw IOException("CarPlay iAP tunnel peer did not connect")
        }
        synchronized(lock) {
            throwTerminalFailureLocked()
            if (closed) throw closedFailureLocked()
        }
        if (!sendCommand(data, EVENT_READY_TIMEOUT_MILLIS)) {
            throw IOException("AirPlay event channel rejected an iAP message")
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }

        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        synchronized(lock) {
            while (true) {
                takePendingLocked(maxBytes)?.let { return it }
                throwTerminalFailureLocked()
                if (closed) return EMPTY
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) return null
                try {
                    lock.wait(
                        remainingNanos / NANOS_PER_MILLISECOND,
                        (remainingNanos % NANOS_PER_MILLISECOND).toInt(),
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            closeLocation = closeCaller()
            lock.notifyAll()
        }
        tunnel.close()
    }

    private fun offer(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(lock) {
            if (closed || terminalFailure != null) return
            if (pendingBytes + bytes.size > MAX_PENDING_BYTES) {
                terminalFailure = IOException("AirPlay iAP tunnel pending-data limit exceeded")
                lock.notifyAll()
                tunnel.close()
                return
            }
            pending.addLast(bytes.copyOf())
            pendingBytes += bytes.size
            lock.notifyAll()
        }
    }

    private fun markFailed(cause: Throwable?): Boolean {
        synchronized(lock) {
            if (closed || terminalFailure != null) return false
            terminalFailure = cause ?: IOException("AirPlay iAP tunnel terminated")
            lock.notifyAll()
            return true
        }
    }

    private fun throwTerminalFailureLocked() {
        val failure = terminalFailure ?: return
        throw IOException(
            "AirPlay iAP tunnel failed: " +
                "${failure.javaClass.simpleName}: ${failure.message ?: "unknown error"}",
            failure,
        )
    }

    private fun closedFailureLocked(): IOException = IOException(
        buildString {
            append("AirPlay iAP tunnel is closed")
            closeLocation?.let { append("; requested by $it") }
        },
    )

    private fun closeCaller(): String =
        Thread.currentThread().stackTrace
            .drop(2)
            .take(8)
            .joinToString(" <- ") { frame ->
                "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
            }

    private fun takePendingLocked(maxBytes: Int): ByteArray? {
        val chunk = pending.pollFirst() ?: return null
        pendingBytes -= chunk.size
        if (chunk.size <= maxBytes) return chunk

        val head = chunk.copyOf(maxBytes)
        val tail = chunk.copyOfRange(maxBytes, chunk.size)
        pending.addFirst(tail)
        pendingBytes += tail.size
        return head
    }

    private companion object {
        const val MAX_PENDING_BYTES = 1_048_576
        const val PEER_CONNECT_TIMEOUT_MILLIS = 15_000L
        const val EVENT_READY_TIMEOUT_MILLIS = 10_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val EMPTY = ByteArray(0)
    }
}
