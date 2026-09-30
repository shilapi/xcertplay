package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.min

/**
 * Blocking CSM facade over one owned [Iap2LinkChannel].
 *
 * It keeps CSM framing separate from typed control messages.  Sending waits for a completed iAP2
 * negotiation so every complete CSM frame can be split to the peer's advertised session-10 limit.
 * One receiver owns the stateful framer; concurrent senders are serialized so their split frames
 * cannot interleave. Internal session-12 datagram access lets file transfer share that same link.
 * Closing this channel closes its link and the link's owned byte stream.
 */
class Iap2CsmChannel private constructor(
    private val link: Iap2LinkChannel,
) : AutoCloseable {
    private val stateLock = Object()
    private val sendLock = Object()
    private val receiveLock = Object()
    private val framer = Iap2CsmFramer()
    private val receivedFrames = ArrayDeque<Iap2Frame>()
    private var receivedBytes = 0
    private var closed = false
    private var terminalFailure: Throwable? = null

    /** True once this channel is closed or has recorded a terminal transport failure. */
    val isClosed: Boolean
        get() = synchronized(stateLock) {
            closed || terminalFailure != null
        }

    /** Waits for the underlying link to negotiate a writable session 10. */
    fun awaitReady(timeoutMillis: Long): Boolean {
        requireTimeout(timeoutMillis)
        try {
            checkOpen()
        } catch (failure: Error) {
            fatal(failure)
        }
        return try {
            val ready = link.awaitReady(timeoutMillis)
            if (ready && link.peerMaxControlPayloadBytes() == null) {
                throw IOException("iAP2 link became writable without a peer control payload limit")
            }
            ready
        } catch (failure: Throwable) {
            if (failure is Error) fatal(failure)
            failClosed(failure)
        }
    }

    /**
     * Queues one complete CSM frame.  Call [awaitReady] successfully first.
     *
     * A false result from the link can mean that a previous chunk of this frame was accepted.  In
     * that case continuing would corrupt CSM framing, so the owned link is closed and an exception
     * is thrown instead of returning a partial-send result.
     */
    fun send(frame: Iap2Frame, timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS) {
        requireTimeout(timeoutMillis)
        synchronized(sendLock) {
            try {
                checkOpen()
            } catch (failure: Error) {
                fatal(failure)
            }
            val peerLimit = link.peerMaxControlPayloadBytes()
                ?: throw IllegalStateException("CSM send requires a successful iAP2 awaitReady")
            val chunks = Iap2CsmFramer.splitForLink(frame, peerLimit)
            val deadlineNanos = deadlineAfter(timeoutMillis)
            for (chunk in chunks) {
                val queued = try {
                    link.sendControlAwaitCapacity(chunk, remainingMillis(deadlineNanos))
                } catch (failure: Throwable) {
                    if (failure is Error) fatal(failure)
                    failClosed(failure)
                }
                if (!queued) {
                    failClosed(IOException("iAP2 could not queue the complete CSM frame before its timeout"))
                }
            }
        }
    }

    internal fun sendFileTransfer(bytes: ByteArray, timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS) {
        requireTimeout(timeoutMillis)
        synchronized(sendLock) {
            checkOpen()
            val queued = try {
                link.sendFileTransferAwaitCapacity(bytes, timeoutMillis)
            } catch (failure: Throwable) {
                if (failure is Error) fatal(failure)
                failClosed(failure)
            }
            if (!queued) failClosed(IOException("iAP2 could not queue a file-transfer response"))
        }
    }

    internal fun recvFileTransfer(timeoutMillis: Long): ByteArray? {
        requireTimeout(timeoutMillis)
        checkOpen()
        return try {
            link.recvFileTransfer(timeoutMillis)
        } catch (failure: Throwable) {
            if (failure is Error) fatal(failure)
            failClosed(failure)
        }
    }

    /**
     * Returns the next complete CSM frame, null on timeout or clean close, and throws on failure.
     * A single underlying control payload can decode into several frames; the remainder stays in a
     * bounded queue for later calls.
     */
    fun recv(timeoutMillis: Long): Iap2Frame? {
        requireTimeout(timeoutMillis)
        synchronized(receiveLock) {
            takeReceived()?.let { return it }
            val deadlineNanos = deadlineAfter(timeoutMillis)
            while (true) {
                try {
                    checkOpen()
                } catch (failure: Error) {
                    fatal(failure)
                }
                val control = try {
                    link.recvControl(remainingMillis(deadlineNanos))
                } catch (failure: Throwable) {
                    if (failure is Error) fatal(failure)
                    failClosed(failure)
                } ?: return null

                val frames = try {
                    framer.offer(control)
                } catch (failure: Throwable) {
                    if (failure is Error) fatal(failure)
                    failClosed(IOException("Invalid CSM control payload", failure))
                }
                for (frame in frames) enqueue(frame)
                takeReceived()?.let { return it }
                if (remainingMillis(deadlineNanos) == 0L) return null
            }
        }
    }

    /** Closes the owned iAP2 link and its owned byte stream. */
    override fun close() {
        val close = synchronized(stateLock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (!close) return
        try {
            link.close()
        } catch (failure: Throwable) {
            if (failure is Error) {
                terminalize(failure)
                throw failure
            }
            throw terminalize(failure)
        }
    }

    private fun enqueue(frame: Iap2Frame) {
        val encodedBytes = frame.encodedFrame().size
        if (receivedFrames.size >= MAX_PENDING_FRAMES || encodedBytes > MAX_PENDING_FRAME_BYTES - receivedBytes) {
            failClosed(IOException("CSM received-frame queue limit exceeded"))
        }
        receivedFrames += frame
        receivedBytes += encodedBytes
    }

    private fun takeReceived(): Iap2Frame? {
        val frame = receivedFrames.pollFirst() ?: return null
        receivedBytes -= frame.encodedFrame().size
        return frame
    }

    private fun checkOpen() {
        synchronized(stateLock) {
            terminalFailure?.let { throw it }
            if (closed) throw IOException("CSM channel is closed")
        }
    }

    /** Records a terminal error, breaks a blocked receiver, then always throws that error. */
    private fun failClosed(failure: Throwable): Nothing {
        val terminal = terminalize(failure)
        try {
            close()
        } catch (closeFailure: Throwable) {
            if (closeFailure is Error) throw closeFailure
            if (closeFailure !== terminal) terminal.addSuppressed(closeFailure)
        }
        throw terminal
    }

    /** Closes best-effort before propagating the original fatal VM or transport error. */
    private fun fatal(error: Error): Nothing {
        terminalize(error)
        try {
            close()
        } catch (_: Throwable) {
            // The original Error is the failure the caller must observe.
        }
        throw error
    }

    private fun terminalize(failure: Throwable): Throwable {
        synchronized(stateLock) {
            val prior = terminalFailure
            if (prior == null) {
                terminalFailure = failure
                return failure
            }
            if (prior !== failure) prior.addSuppressed(failure)
            return prior
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        return System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) return 0
        return min(MAX_TIMEOUT_MILLIS, (remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
    }

    private fun requireTimeout(timeoutMillis: Long) {
        require(timeoutMillis in 0..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 0..$MAX_TIMEOUT_MILLIS"
        }
    }

    companion object {
        private const val MAX_PENDING_FRAMES = 64
        private const val MAX_PENDING_FRAME_BYTES = 1_048_576
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val DEFAULT_SEND_TIMEOUT_MILLIS = 5_000L

        /** Opens the owned iAP2 link over an owned carkit stream. */
        fun open(underlying: BlockingDuplexByteStream): Iap2CsmChannel =
            Iap2CsmChannel(Iap2LinkChannel.open(underlying))

        /** Opens the owned iAP2 link over an owned wireless RFCOMM stream. */
        fun openWireless(underlying: BlockingDuplexByteStream): Iap2CsmChannel =
            Iap2CsmChannel(Iap2LinkChannel.openWireless(underlying))

        /** Opens the owned iAP2 link over an AirPlay type-130 tunnel. */
        fun openTunnel(underlying: BlockingDuplexByteStream): Iap2CsmChannel =
            Iap2CsmChannel(Iap2LinkChannel.openTunnel(underlying))
    }
}
