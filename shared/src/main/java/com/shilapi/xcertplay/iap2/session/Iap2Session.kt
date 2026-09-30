package com.shilapi.xcertplay.iap2.session

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoint
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.trace.Iap2FrameFormatter
import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2CsmChannel

/**
 * One immediately readable and writable iAP2 connection facade.
 *
 * [Iap2CsmChannel] remains the lower-level frame transport. This class is the protocol-facing
 * facade used by services: it accepts endpoint builders, sends and receives complete control
 * frames, exposes the typed body reader, and internally carries session-12 file transfers over the
 * same owned link.
 */
class Iap2Session private constructor(
    private val channel: Iap2CsmChannel,
    private val traceContext: String,
    private val onTrace: (String) -> Unit,
) : AutoCloseable {
    val isClosed: Boolean get() = channel.isClosed

    fun awaitReady(timeoutMillis: Long): Boolean {
        val ready = channel.awaitReady(timeoutMillis)
        emitTrace("IAP2 READY [$traceContext] ready=$ready")
        return ready
    }

    fun send(frame: Iap2Frame, timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS) {
        try {
            channel.send(frame, timeoutMillis)
            emitFrameTrace(Iap2TraceDirection.TX, frame)
        } catch (failure: Throwable) {
            emitTrace(
                Iap2FrameFormatter.formatFailure(
                    Iap2TraceDirection.TX,
                    traceContext,
                    frame.messageId,
                    failure,
                ),
            )
            throw failure
        }
    }

    fun send(
        endpoint: Iap2Endpoint,
        timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
        block: Iap2BodyBuilder.() -> Unit,
    ) {
        send(Iap2Messages.build(endpoint, block), timeoutMillis)
    }

    fun sendRaw(
        messageId: Int,
        timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
        block: Iap2BodyBuilder.() -> Unit,
    ) {
        send(Iap2Messages.buildRaw(messageId, block), timeoutMillis)
    }

    fun recv(timeoutMillis: Long): Iap2Frame? {
        return try {
            channel.recv(timeoutMillis)?.also { emitFrameTrace(Iap2TraceDirection.RX, it) }
        } catch (failure: Throwable) {
            emitTrace(
                Iap2FrameFormatter.formatFailure(
                    Iap2TraceDirection.RX,
                    traceContext,
                    messageId = null,
                    failure = failure,
                ),
            )
            throw failure
        }
    }

    fun reader(frame: Iap2Frame): Iap2BodyReader = Iap2Messages.reader(frame)

    internal fun sendFileTransfer(bytes: ByteArray, timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS) {
        channel.sendFileTransfer(bytes, timeoutMillis)
    }

    internal fun recvFileTransfer(timeoutMillis: Long): ByteArray? =
        channel.recvFileTransfer(timeoutMillis)

    override fun close() {
        try {
            channel.close()
        } finally {
            emitTrace("IAP2 CLOSE [$traceContext]")
        }
    }

    private fun emitFrameTrace(direction: Iap2TraceDirection, frame: Iap2Frame) {
        try {
            emitTrace(Iap2FrameFormatter.format(direction, traceContext, frame))
        } catch (failure: Exception) {
            emitTrace(
                Iap2FrameFormatter.formatFailure(
                    direction,
                    traceContext,
                    frame.messageId,
                    failure,
                ),
            )
        }
    }

    private fun emitTrace(message: String) {
        try {
            onTrace(message)
        } catch (_: Exception) {
            // Logging must never change protocol behavior.
        }
    }

    companion object {
        private const val DEFAULT_SEND_TIMEOUT_MILLIS = 5_000L

        fun open(
            underlying: BlockingDuplexByteStream,
            traceContext: String = "wired",
            onTrace: (String) -> Unit = {},
        ): Iap2Session =
            Iap2Session(Iap2CsmChannel.open(underlying), traceContext, onTrace)

        fun openWireless(
            underlying: BlockingDuplexByteStream,
            traceContext: String = "wireless",
            onTrace: (String) -> Unit = {},
        ): Iap2Session =
            Iap2Session(Iap2CsmChannel.openWireless(underlying), traceContext, onTrace)

        fun openTunnel(
            underlying: BlockingDuplexByteStream,
            traceContext: String = "wireless-tunnel",
            onTrace: (String) -> Unit = {},
        ): Iap2Session =
            Iap2Session(Iap2CsmChannel.openTunnel(underlying), traceContext, onTrace)

        fun wrap(
            channel: Iap2CsmChannel,
            traceContext: String = "iap2",
            onTrace: (String) -> Unit = {},
        ): Iap2Session = Iap2Session(channel, traceContext, onTrace)
    }
}
