package com.shilapi.xcertplay.transport

import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.min

/**
 * Blocking facade for an iAP2 link over a caller-supplied byte stream.
 *
 * [open] starts one worker.  That worker is the only code that drives [Iap2LinkEngine] or calls
 * [BlockingDuplexByteStream.send]/[BlockingDuplexByteStream.recv].  Callers may concurrently wait
 * for readiness, queue complete session payloads, receive control/file-transfer payloads, or close
 * this channel. This class owns and closes [underlying].
 *
 * It deliberately does not parse CSM or file-transfer payloads, and does not implement EA, media,
 * UI, or any Lockdown setup.
 */
class Iap2LinkChannel private constructor(
    private val underlying: BlockingDuplexByteStream,
    private val linkConfig: Iap2LinkConfig,
    private val initiateNegotiation: Boolean,
) : AutoCloseable {
    private data class Command(
        val sessionId: Int,
        val bytes: ByteArray,
    )

    private val lock = Object()
    private val commands = ArrayDeque<Command>()
    private var commandBytes = 0
    private val controls = ArrayDeque<ByteArray>()
    private var controlBytes = 0
    private val fileTransfers = ArrayDeque<ByteArray>()
    private var fileTransferBytes = 0

    private var ready = false
    private var peerMaxControlPayloadBytes: Int? = null
    private var closing = false
    private var terminated = false
    private var terminalFailure: Throwable? = null
    private var streamCloseStarted = false

    private val worker = Thread(::runPump, "xcertplay-iap2-link").apply {
        isDaemon = true
    }

    /** Waits until session 10 is writable; returns false on timeout or clean close, and throws on failure. */
    fun awaitReady(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        synchronized(lock) {
            waitFor(timeoutMillis) { (ready && !terminated) || terminated }
            throwTerminalFailureLocked()
            return ready && !terminated
        }
    }

    /**
     * The peer's negotiated maximum session-10 payload length, available after [awaitReady]
     * returns true.  It excludes the nine-byte iAP2 header and one-byte payload checksum.
     */
    fun peerMaxControlPayloadBytes(): Int? = synchronized(lock) { peerMaxControlPayloadBytes }

    /**
     * Copies and queues one raw session-10 payload.  Returns false after close/termination or when
     * the bounded command queue is full.  A peer LSP length violation is discovered by the worker
     * when it reaches the engine and terminates the channel.
     */
    fun sendControl(bytes: ByteArray): Boolean {
        require(bytes.size <= Iap2LinkEngine.MAX_PAYLOAD_BYTES) {
            "iAP2 control payload exceeds ${Iap2LinkEngine.MAX_PAYLOAD_BYTES} bytes"
        }
        val copy = bytes.copyOf()
        synchronized(lock) {
            if (terminated || closing) return false
            return enqueueCommandLocked(Iap2LinkEngine.CONTROL_SESSION_ID, copy)
        }
    }

    /**
     * Like [sendControl], but waits for command-queue capacity.  CSM uses this to queue every
     * fragment of one frame without converting link backpressure into a partial CSM write.
     */
    internal fun sendControlAwaitCapacity(bytes: ByteArray, timeoutMillis: Long): Boolean {
        require(bytes.size <= Iap2LinkEngine.MAX_PAYLOAD_BYTES) {
            "iAP2 control payload exceeds ${Iap2LinkEngine.MAX_PAYLOAD_BYTES} bytes"
        }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val copy = bytes.copyOf()
        synchronized(lock) {
            waitFor(timeoutMillis) {
                terminated || closing || hasCommandCapacityLocked(copy.size)
            }
            throwTerminalFailureLocked()
            if (terminated || closing) return false
            return enqueueCommandLocked(Iap2LinkEngine.CONTROL_SESSION_ID, copy)
        }
    }

    /** Queues one file-transfer-session datagram, waiting for bounded queue capacity. */
    internal fun sendFileTransferAwaitCapacity(bytes: ByteArray, timeoutMillis: Long): Boolean {
        require(bytes.size <= Iap2LinkEngine.MAX_PAYLOAD_BYTES) {
            "iAP2 file-transfer payload exceeds ${Iap2LinkEngine.MAX_PAYLOAD_BYTES} bytes"
        }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val copy = bytes.copyOf()
        synchronized(lock) {
            waitFor(timeoutMillis) {
                terminated || closing || hasCommandCapacityLocked(copy.size)
            }
            throwTerminalFailureLocked()
            if (terminated || closing) return false
            return enqueueCommandLocked(Iap2LinkEngine.FILE_TRANSFER_SESSION_ID, copy)
        }
    }

    /** Returns the next control payload; returns null on timeout or clean close, and throws on failure. */
    fun recvControl(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        synchronized(lock) {
            waitFor(timeoutMillis) { controls.isNotEmpty() || terminated }
            throwTerminalFailureLocked()
            if (controls.isEmpty()) return null
            val bytes = controls.removeFirst()
            controlBytes -= bytes.size
            return bytes
        }
    }

    /** Returns the next session-12 datagram, or null on timeout or clean close. */
    internal fun recvFileTransfer(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        synchronized(lock) {
            waitFor(timeoutMillis) { fileTransfers.isNotEmpty() || terminated }
            throwTerminalFailureLocked()
            if (fileTransfers.isEmpty()) return null
            val bytes = fileTransfers.removeFirst()
            fileTransferBytes -= bytes.size
            return bytes
        }
    }

    /**
     * Marks the channel closed, directly closes its owned stream to break a blocking recv, then
     * waits a bounded time for the worker.  Stream or worker failures are rethrown, including
     * [Error]s; they are never silently discarded.
     */
    override fun close() {
        synchronized(lock) {
            if (!closing) {
                closing = true
                terminalLocked(null)
            }
        }

        var failure = closeUnderlyingOnce()
        if (failure != null) {
            synchronized(lock) {
                terminalFailure = combineFailures(terminalFailure, failure)
                lock.notifyAll()
            }
        }
        try {
            worker.join(CLOSE_JOIN_MILLIS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            failure = combineFailures(failure, interrupted)
        }
        if (worker.isAlive) {
            failure = combineFailures(failure, IOException("iAP2 link worker did not stop after close"))
        }
        synchronized(lock) {
            failure = combineFailures(failure, terminalFailure)
        }
        if (failure != null) throw failure
    }

    private fun runPump() {
        val engine = Iap2LinkEngine(linkConfig)
        try {
            engine.start(wiredInitiator = initiateNegotiation, nowMillis = nowMillis())
            while (!isClosing()) {
                drainCommands(engine)
                engine.advanceTime(nowMillis())
                flush(engine)
                if (!drainEvents(engine)) return
                if (isClosing()) return

                val next = engine.nextDeadlineMillis()
                val waitMillis = min(
                    RECEIVE_POLL_MILLIS,
                    next?.let { (it - nowMillis()).coerceAtLeast(0) } ?: RECEIVE_POLL_MILLIS,
                ).coerceAtLeast(MINIMUM_RECEIVE_POLL_MILLIS)
                val received = underlying.recv(RECEIVE_CHUNK_BYTES, waitMillis)
                if (isClosing()) return
                when {
                    received == null -> Unit
                    received.isEmpty() -> engine.feedEof()
                    else -> engine.feed(received, nowMillis())
                }
            }
            finish(null)
        } catch (failure: Throwable) {
            if (failure is Error) {
                finish(failure)
                throw failure
            }
            if (isClosing()) finish(null) else finish(failure)
        }
    }

    private fun drainCommands(engine: Iap2LinkEngine) {
        while (engine.writable()) {
            val command = synchronized(lock) {
                if (commands.isEmpty()) {
                    null
                } else {
                    commands.removeFirst().also {
                        commandBytes -= it.bytes.size
                        lock.notifyAll()
                    }
                }
            } ?: return
            when (command.sessionId) {
                Iap2LinkEngine.CONTROL_SESSION_ID -> engine.sendControl(command.bytes, nowMillis())
                Iap2LinkEngine.FILE_TRANSFER_SESSION_ID -> engine.sendFileTransfer(command.bytes, nowMillis())
            }
            if (isClosing()) return
        }
    }

    private fun flush(engine: Iap2LinkEngine) {
        val bytes = engine.takeOutput()
        if (bytes.isNotEmpty()) underlying.send(bytes)
    }

    /** Returns false when a link terminal event has been observed. */
    private fun drainEvents(engine: Iap2LinkEngine): Boolean {
        while (true) {
            when (val event = engine.pollEvent() ?: return true) {
                is Iap2LinkEngine.Event.Writable -> synchronized(lock) {
                    if (event.value) {
                        // The engine only emits writable after validating this is positive.
                        peerMaxControlPayloadBytes = engine.peerSynchronization().maxLength - 10
                    }
                    ready = event.value && !terminated
                    lock.notifyAll()
                }

                is Iap2LinkEngine.Event.Control -> {
                    val overflow = synchronized(lock) {
                        if (controls.size >= MAX_PENDING_CONTROLS ||
                            event.bytes.size > MAX_PENDING_CONTROL_BYTES - controlBytes
                        ) {
                            true
                        } else {
                            controls += event.bytes
                            controlBytes += event.bytes.size
                            lock.notifyAll()
                            false
                        }
                    }
                    if (overflow) {
                        finish(IOException("iAP2 received-control queue limit exceeded"))
                        return false
                    }
                }

                is Iap2LinkEngine.Event.FileTransfer -> {
                    val overflow = synchronized(lock) {
                        if (fileTransfers.size >= MAX_PENDING_FILE_TRANSFERS ||
                            event.bytes.size > MAX_PENDING_FILE_TRANSFER_BYTES - fileTransferBytes
                        ) {
                            true
                        } else {
                            fileTransfers += event.bytes
                            fileTransferBytes += event.bytes.size
                            lock.notifyAll()
                            false
                        }
                    }
                    if (overflow) {
                        finish(IOException("iAP2 received file-transfer queue limit exceeded"))
                        return false
                    }
                }

                is Iap2LinkEngine.Event.Dead -> {
                    finish(IOException(event.reason ?: "iAP2 link ended"))
                    return false
                }
            }
        }
    }

    private fun finish(failure: Throwable?) {
        synchronized(lock) {
            finishLocked(failure)
        }
        closeUnderlyingOnce()?.let { closeFailure ->
            synchronized(lock) {
                terminalFailure = combineFailures(terminalFailure, closeFailure)
                lock.notifyAll()
            }
        }
    }

    private fun finishLocked(failure: Throwable?) {
        if (!terminated) terminalLocked(failure) else if (failure != null) {
            terminalFailure = combineFailures(terminalFailure, failure)
            lock.notifyAll()
        }
    }

    private fun terminalLocked(failure: Throwable?) {
        terminated = true
        ready = false
        commands.clear()
        commandBytes = 0
        controls.clear()
        controlBytes = 0
        fileTransfers.clear()
        fileTransferBytes = 0
        terminalFailure = combineFailures(terminalFailure, failure)
        lock.notifyAll()
    }

    /** lock must already be held. */
    private fun throwTerminalFailureLocked() {
        terminalFailure?.let { throw it }
    }

    private fun isClosing(): Boolean = synchronized(lock) { closing || terminated }

    private fun closeUnderlyingOnce(): Throwable? {
        synchronized(lock) {
            if (streamCloseStarted) return null
            streamCloseStarted = true
        }
        return try {
            underlying.close()
            null
        } catch (failure: Throwable) {
            failure
        }
    }

    /** lock must already be held. */
    private fun hasCommandCapacityLocked(bytes: Int): Boolean =
        commands.size < MAX_PENDING_COMMANDS && bytes <= MAX_PENDING_COMMAND_BYTES - commandBytes

    /** lock must already be held. */
    private fun enqueueCommandLocked(sessionId: Int, bytes: ByteArray): Boolean {
        if (!hasCommandCapacityLocked(bytes.size)) return false
        commands += Command(sessionId, bytes)
        commandBytes += bytes.size
        lock.notifyAll()
        return true
    }

    /** lock must already be held. */
    private fun waitFor(timeoutMillis: Long, readyPredicate: () -> Boolean): Boolean {
        if (readyPredicate()) return true
        if (timeoutMillis == 0L) return false
        val budgetNanos = timeoutMillis.coerceAtMost(MAX_WAIT_MILLIS) * NANOS_PER_MILLISECOND
        val startedNanos = System.nanoTime()
        while (!readyPredicate()) {
            val elapsedNanos = System.nanoTime() - startedNanos
            if (elapsedNanos >= budgetNanos) return false
            val remainingNanos = budgetNanos - elapsedNanos
            try {
                lock.wait(
                    remainingNanos / NANOS_PER_MILLISECOND,
                    (remainingNanos % NANOS_PER_MILLISECOND).toInt(),
                )
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    private fun combineFailures(first: Throwable?, second: Throwable?): Throwable? {
        if (first == null) return second
        if (second != null && second !== first) first.addSuppressed(second)
        return first
    }

    companion object {
        private const val RECEIVE_CHUNK_BYTES = 8_192
        private const val RECEIVE_POLL_MILLIS = 100L
        private const val MINIMUM_RECEIVE_POLL_MILLIS = 1L
        private const val CLOSE_JOIN_MILLIS = 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_WAIT_MILLIS = Long.MAX_VALUE / NANOS_PER_MILLISECOND
        private const val MAX_PENDING_COMMANDS = 64
        private const val MAX_PENDING_COMMAND_BYTES = 1_048_576
        private const val MAX_PENDING_CONTROLS = 64
        private const val MAX_PENDING_CONTROL_BYTES = 1_048_576
        private const val MAX_PENDING_FILE_TRANSFERS = 256
        private const val MAX_PENDING_FILE_TRANSFER_BYTES = 16 * 1_048_576

        private val WIRED_LINK_CONFIG = Iap2LinkConfig(
            maxOutgoing = 4,
            controlSessionVersion = 2,
            zeroAcknowledgements = true,
        )
        private val WIRELESS_LINK_CONFIG = Iap2LinkConfig(
            maxOutgoing = 4,
            controlSessionVersion = 2,
        )

        /** Opens and immediately starts a wired iAP2 link, taking ownership of the supplied stream. */
        fun open(underlying: BlockingDuplexByteStream): Iap2LinkChannel =
            Iap2LinkChannel(underlying, WIRED_LINK_CONFIG, initiateNegotiation = true)
                .also { it.worker.start() }

        /**
         * Opens a wireless RFCOMM link. LIVI sends the iAP2 marker but lets the phone initiate
         * synchronization, and keeps acknowledgements enabled for Bluetooth.
         */
        fun openWireless(underlying: BlockingDuplexByteStream): Iap2LinkChannel =
            Iap2LinkChannel(underlying, WIRELESS_LINK_CONFIG, initiateNegotiation = false)
                .also { it.worker.start() }

        /**
         * Opens the iAP2 link carried by an AirPlay type-130 tunnel. The accessory initiates
         * synchronization and zero-acknowledgement mode matches the Wi-Fi transport.
         */
        fun openTunnel(underlying: BlockingDuplexByteStream): Iap2LinkChannel =
            Iap2LinkChannel(underlying, WIRED_LINK_CONFIG, initiateNegotiation = true)
                .also { it.worker.start() }

        private fun nowMillis(): Long = System.nanoTime() / NANOS_PER_MILLISECOND
    }
}
