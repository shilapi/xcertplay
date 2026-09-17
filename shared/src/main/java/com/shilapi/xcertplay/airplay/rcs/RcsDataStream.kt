package com.shilapi.xcertplay.airplay.rcs

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application callback for one type-130 RCS stream.
 *
 * The callback receives transport-level [RcsMessage] values. CAF and iAP2 payload decoding belong
 * to the selected channel strategy, not to the stream listener.
 */
interface RcsDataStreamHandler {
    fun onStreamOpened(stream: RcsDataStream) {}
    fun onMessage(stream: RcsDataStream, message: RcsMessage) {}
    fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {}
}

/**
 * Selects the application-level codec for a newly created RCS stream.
 *
 * This factory is where CAF, cluster-control, update, and logging handlers are instantiated. It
 * keeps client-type selection independent from the stream's common framing.
 */
fun interface RcsDataStreamHandlerFactory {
    fun create(stream: RcsDataStream): RcsDataStreamHandler?

    /** Declares whether this factory can decode the selected client type. */
    fun supports(clientType: RcsClientType): Boolean = true
}

/**
 * Reusable server-side transport for a CarPlay type-130 data stream.
 *
 * It owns the dual-stack listener, directional ChaCha20-Poly1305 framing, APTransport package
 * framing, peer wait, and callback lifecycle. iAPChannel and the CAF/cluster strategies all use
 * this class; they differ only in the [RcsClientType] used to create them and the handler attached
 * after SETUP.
 */
class RcsDataStream private constructor(
    val clientType: RcsClientType,
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
    private val bindAddress: InetAddress,
    private val onTrace: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val peerConnected = CountDownLatch(1)
    private val stateLock = Object()
    private val callbackLock = Object()
    private val listeners = mutableListOf<RcsListener>()
    private val acceptThreads = mutableListOf<Thread>()
    private val pending = ArrayDeque<RcsMessage>()
    private var pendingBytes = 0
    private var opened = false
    private var closedCallbackSent = false
    private var handler: RcsDataStreamHandler? = null

    @Volatile
    private var channel: RcsChannel? = null

    val port: Int
        get() = synchronized(stateLock) {
            listeners.firstOrNull()?.port
                ?: throw IllegalStateException("RCS data stream has not been opened")
        }

    fun listen(handler: RcsDataStreamHandler? = null): Int {
        synchronized(stateLock) {
            check(listeners.isEmpty()) { "RCS data stream is already listening" }
            if (closed.get()) throw IOException("RCS data stream is closed")
            this.handler = handler
        }

        val primary = RcsChannel.listen(
            readKey = readKey,
            writeKey = writeKey,
            bindAddress = bindAddress,
            onTrace = ::trace,
        )
        listeners += primary

        val secondaryAddress = if (bindAddress is Inet6Address) {
            InetAddress.getByName("0.0.0.0")
        } else {
            InetAddress.getByName("::")
        }
        runCatching {
            RcsChannel.listen(
                readKey = readKey,
                writeKey = writeKey,
                bindAddress = secondaryAddress,
                port = primary.port,
                onTrace = ::trace,
            )
        }.onSuccess { listeners += it }
            .onFailure {
                trace(
                    "RCS secondary listener unavailable address=$secondaryAddress " +
                        "port=${primary.port}: ${it.message ?: it.javaClass.simpleName}",
                )
            }

        listeners.forEach { listener ->
            acceptThreads += Thread(
                { acceptLoop(listener) },
                "carplay-rcs-${clientType.name}-${primary.port}",
            ).apply {
                isDaemon = true
                start()
            }
        }
        trace("RCS stream listening clientType=${clientType.name} port=${primary.port}")
        return primary.port
    }

    /** Attaches the application handler after the AirPlay SETUP response has been sent. */
    fun attach(handler: RcsDataStreamHandler) {
        val callbacks = ArrayList<() -> Unit>()
        synchronized(stateLock) {
            if (closed.get()) throw IOException("RCS data stream is closed")
            this.handler = handler
            if (opened) callbacks += { handler.onStreamOpened(this) }
            while (pending.isNotEmpty()) {
                val message = pending.pollFirst() ?: break
                pendingBytes -= message.body.size
                callbacks += { handler.onMessage(this, message) }
            }
            synchronized(callbackLock) {
                callbacks.forEach { it() }
            }
        }
    }

    fun awaitPeerConnection(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            peerConnected.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun send(message: RcsMessage) {
        if (!awaitPeerConnection(PEER_CONNECT_TIMEOUT_MILLIS)) {
            throw IOException("RCS peer did not connect for ${clientType.name}")
        }
        val active = channel ?: throw IOException("RCS channel is not connected")
        active.send(message)
    }

    internal fun traceProtocol(message: String) {
        trace(message)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(stateLock) {
            listeners.toList().forEach { runCatching { it.close() } }
            listeners.clear()
            acceptThreads.toList().forEach(Thread::interrupt)
            acceptThreads.clear()
            peerConnected.countDown()
        }
        runCatching { channel?.close() }
        channel = null
        notifyClosed(null)
    }

    private fun acceptLoop(listener: RcsListener) {
        var failure: Throwable? = null
        var accepted: RcsChannel? = null
        try {
            val acceptedChannel = listener.accept(0) ?: return
            accepted = acceptedChannel
            if (closed.get()) return
            channel = acceptedChannel
            peerConnected.countDown()
            notifyOpened()

            while (!closed.get()) {
                val message = acceptedChannel.receiveNext(RECEIVE_POLL_MILLIS)
                if (message != null) {
                    dispatch(message)
                    continue
                }
                if (acceptedChannel.isPeerEnded || acceptedChannel.isClosed) break
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { accepted?.close() }
            if (channel === accepted) channel = null
            if (!closed.get()) {
                if (failure != null) trace("RCS stream failed clientType=${clientType.name}: $failure")
                notifyClosed(failure)
            }
        }
    }

    private fun notifyOpened() {
        val callback = synchronized(stateLock) {
            opened = true
            handler
        }
        var failure: Throwable? = null
        synchronized(callbackLock) {
            runCatching { callback?.onStreamOpened(this) }
                .onFailure {
                    failure = it
                    trace("RCS open callback failed: $it")
                }
        }
        if (failure != null) {
            close()
        }
    }

    private fun dispatch(message: RcsMessage) {
        val callback = synchronized(stateLock) {
            val target = handler
            if (target == null) {
                if (pendingBytes + message.body.size > MAX_PENDING_BYTES) {
                    throw IOException("RCS pending-message limit exceeded for ${clientType.name}")
                }
                pending.addLast(message)
                pendingBytes += message.body.size
            }
            target
        }
        var failure: Throwable? = null
        synchronized(callbackLock) {
            runCatching { callback?.onMessage(this, message) }
                .onFailure {
                    failure = it
                    trace("RCS message callback failed: $it")
                }
        }
        if (failure != null) {
            close()
        }
    }

    private fun notifyClosed(cause: Throwable?) {
        val callback = synchronized(stateLock) {
            if (closedCallbackSent) return
            closedCallbackSent = true
            handler
        }
        synchronized(callbackLock) {
            runCatching { callback?.onStreamClosed(this, cause) }
                .onFailure { trace("RCS close callback failed: $it") }
        }
    }

    private fun trace(message: String) {
        runCatching { onTrace("RCS [${clientType.name}] $message") }
    }

    companion object {
        private const val RECEIVE_POLL_MILLIS = 1_000L
        private const val PEER_CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val MAX_PENDING_BYTES = 1_048_576

        fun open(
            clientType: RcsClientType,
            readKey: ByteArray,
            writeKey: ByteArray,
            bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
            onTrace: (String) -> Unit = {},
        ): RcsDataStream {
            require(readKey.size == 32) { "RCS read key must be 32 bytes" }
            require(writeKey.size == 32) { "RCS write key must be 32 bytes" }
            val normalizedBind = if (bindAddress is Inet4Address) {
                InetAddress.getByName("0.0.0.0")
            } else {
                bindAddress
            }
            return RcsDataStream(
                clientType = clientType,
                readKey = readKey.copyOf(),
                writeKey = writeKey.copyOf(),
                bindAddress = normalizedBind,
                onTrace = onTrace,
            )
        }
    }
}
