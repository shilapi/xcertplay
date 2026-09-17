package com.shilapi.xcertplay.airplay.rcs

import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackage
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import com.shilapi.xcertplay.airplay.rcs.transport.RcsFrameCodec
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import kotlin.math.min

data class RcsMessage(
    val messageType: Int,
    val body: ByteArray,
) {
    fun encoded(): ByteArray = ApTransportPackageCodec.create(messageType, body).encoded()

    companion object {
        fun comm(body: ByteArray): RcsMessage =
            RcsMessage(ApTransportPackageCodec.MESSAGE_TYPE_COMM, body)
    }
}

class RcsProtocolException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * One connected RCS stream.
 *
 * This is a deliberately small duplex facade: application code sends semantic [RcsMessage]
 * values and receives complete APTransport packages. It owns no UI or AirPlay session state.
 */
class RcsChannel private constructor(
    private val socket: Socket,
    private val frameCodec: RcsFrameCodec,
    private val onTrace: (String) -> Unit,
) : Closeable {
    private val sendLock = Object()
    private val receiveLock = Object()
    private val pendingPackages = ArrayDeque<ApTransportPackage>()
    private var incomingCiphertext = ByteArray(0)
    private var incomingPlaintext = ByteArray(0)

    @Volatile
    private var closed = false

    @Volatile
    private var peerEnded = false

    val isClosed: Boolean
        get() = closed

    val isPeerEnded: Boolean
        get() = peerEnded

    fun send(message: RcsMessage) {
        synchronized(sendLock) {
            ensureOpen()
            try {
                val encoded = frameCodec.encrypt(message.encoded())
                socket.getOutputStream().apply {
                    write(encoded)
                    flush()
                }
                trace("RCS send type=0x${message.messageType.toString(16)} bytes=${message.body.size}")
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }
    }

    fun sendComm(body: ByteArray) {
        send(RcsMessage.comm(body))
    }

    /**
     * Receives the next complete APTransport package. Returns null on timeout or a clean peer
     * close, and throws [RcsProtocolException] for malformed framing.
     */
    fun receiveNext(timeoutMillis: Long): RcsMessage? {
        requireTimeout(timeoutMillis)
        synchronized(receiveLock) {
            takePending()?.let { return it }
            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            val input = socket.getInputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)

            while (true) {
                ensureOpen()
                if (peerEnded) return null
                val remaining = remainingMillis(deadline)
                if (timeoutMillis != 0L && remaining == 0L) return null
                socket.soTimeout = if (remaining == 0L) 1 else min(remaining, Int.MAX_VALUE.toLong()).toInt()

                val read = try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    return null
                } catch (failure: Throwable) {
                    close()
                    throw failure
                }
                if (read < 0) {
                    peerEnded = true
                    trace("RCS peer closed the stream")
                    return null
                }
                if (read == 0) continue

                incomingCiphertext += buffer.copyOf(read)
                val decrypted = try {
                    frameCodec.decrypt(incomingCiphertext)
                } catch (failure: Throwable) {
                    close()
                    throw RcsProtocolException("Invalid RCS encrypted frame", failure)
                }
                incomingCiphertext = decrypted.rest
                incomingPlaintext += decrypted.data

                val decoded = try {
                    ApTransportPackageCodec.decodeAvailable(incomingPlaintext)
                } catch (failure: Throwable) {
                    close()
                    throw RcsProtocolException("Invalid APTransport package", failure)
                }
                incomingPlaintext = decoded.remainder
                decoded.packages.forEach(pendingPackages::addLast)
                takePending()?.let { return it }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
        trace("RCS channel closed")
    }

    private fun ensureOpen() {
        if (closed) throw IOException("RCS channel is closed")
    }

    private fun takePending(): RcsMessage? {
        val packageValue = pendingPackages.pollFirst() ?: return null
        trace(
            "RCS receive type=0x${packageValue.messageType.toString(16)}" +
                " bytes=${packageValue.body.size}",
        )
        return RcsMessage(packageValue.messageType, packageValue.body)
    }

    private fun trace(message: String) {
        runCatching { onTrace(message) }
    }

    private fun requireTimeout(timeoutMillis: Long) {
        require(timeoutMillis in 0..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 0..$MAX_TIMEOUT_MILLIS"
        }
    }

    private fun remainingMillis(deadline: Long): Long {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) return 0
        return (remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
    }

    companion object {
        private const val READ_CHUNK_BYTES = 16 * 1024
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_TIMEOUT_MILLIS = 5 * 60 * 1_000L

        /** Wraps an already accepted socket, taking ownership of it. */
        fun open(
            socket: Socket,
            readKey: ByteArray,
            writeKey: ByteArray,
            onTrace: (String) -> Unit = {},
        ): RcsChannel {
            socket.tcpNoDelay = true
            return RcsChannel(socket, RcsFrameCodec.duplex(readKey, writeKey), onTrace)
        }

        fun connect(
            remote: InetSocketAddress,
            readKey: ByteArray,
            writeKey: ByteArray,
            connectTimeoutMillis: Int = 5_000,
            onTrace: (String) -> Unit = {},
        ): RcsChannel {
            val socket = Socket()
            try {
                socket.connect(remote, connectTimeoutMillis)
                return open(socket, readKey, writeKey, onTrace)
            } catch (failure: Throwable) {
                runCatching { socket.close() }
                throw failure
            }
        }

        /** Binds the receive-side RCS listener. */
        fun listen(
            readKey: ByteArray,
            writeKey: ByteArray,
            bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
            port: Int = 0,
            backlog: Int = 4,
            onTrace: (String) -> Unit = {},
        ): RcsListener =
            RcsListener(
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindAddress, port), backlog)
                },
                readKey = readKey,
                writeKey = writeKey,
                onTrace = onTrace,
            )
    }
}

/**
 * Receive-side factory returned by [RcsChannel.listen].
 */
class RcsListener internal constructor(
    private val serverSocket: ServerSocket,
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
    private val onTrace: (String) -> Unit,
) : Closeable {
    val port: Int
        get() = serverSocket.localPort

    @Volatile
    private var closed = false

    fun accept(timeoutMillis: Long = 0): RcsChannel? {
        require(timeoutMillis >= 0)
        if (closed) throw IOException("RCS listener is closed")
        serverSocket.soTimeout = if (timeoutMillis == 0L) 0 else min(timeoutMillis, Int.MAX_VALUE.toLong()).toInt()
        val accepted = try {
            serverSocket.accept()
        } catch (_: SocketTimeoutException) {
            if (timeoutMillis == 0L) throw EOFException("RCS listener timed out")
            return null
        }
        onTrace("RCS accepted peer=${accepted.remoteSocketAddress}")
        accepted.tcpNoDelay = true
        return RcsChannel.open(accepted, readKey, writeKey, onTrace)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { serverSocket.close() }
        onTrace("RCS listener closed")
    }
}
