package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Receive-only iAP2-over-CarPlay DataStream tunnel (stream type 130).
 *
 * The TCP stream is NetSocketChaCha20Poly1305 framed, then carries APTransportPackage records.
 * iAP2 bodies (messageType "comm") are emitted verbatim for the wired iAP2 relay.
 */
class IapTunnel(
    private val readKey: ByteArray,
    private val bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
) : Closeable {
    interface Listener {
        fun onOpen(remoteAddress: String?) {}
        fun onIap(bytes: ByteArray) {}
        fun onDebug(message: String) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val readCounter = AtomicLong(0)
    // Written by the accept thread while `close` iterates, so these have to tolerate concurrent
    // mutation without a `ConcurrentModificationException` mid-shutdown.
    private val servers = CopyOnWriteArrayList<ServerSocket>()
    private var socket: Socket? = null
    private val threads = CopyOnWriteArrayList<Thread>()
    private val peerConnected = CountDownLatch(1)
    /** True while the single peer this tunnel serves is attached. */
    private val peerAttached = AtomicBoolean(false)
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        // Bind to the address the controller already reached us on. A wildcard listener would also
        // expose the tunnel on every other interface the device happens to have, which matters
        // because the tunnel carries unauthenticated framing that this class must decrypt.
        val bound = runCatching { bindSpecific() }.getOrElse { error ->
            listener.onDebug(
                "AirPlay iAP tunnel bind to $bindAddress failed, using the wildcard: ${error.message}",
            )
            bindWildcard()
        }
        servers += bound
        listener.onDebug("AirPlay iAP tunnel listener bound=${bound.localSocketAddress}")
        threads += Thread({ accept(bound) }, "airplay-iap-tunnel").apply {
            isDaemon = true
            start()
        }
        return bound.localPort
    }

    private fun bindSpecific(): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, 0))
        }

    /** Only used when binding the specific address fails; matches the requested address family. */
    private fun bindWildcard(): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            val wildcard = if (bindAddress is Inet6Address) "::" else "0.0.0.0"
            bind(InetSocketAddress(InetAddress.getByName(wildcard), 0))
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        servers.toList().forEach(::safeClose)
        servers.clear()
        threads.toList().forEach(Thread::interrupt)
        threads.clear()
        peerConnected.countDown()
    }

    /** Waits until the iPhone has connected to the advertised dataPort. */
    fun awaitPeerConnection(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            peerConnected.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun accept(bound: ServerSocket) {
        listener.onDebug(
            "AirPlay iAP tunnel accepting local=${bound.localSocketAddress}",
        )
        while (!closed.get()) {
            val accepted = try {
                bound.accept()
            } catch (error: Exception) {
                if (!closed.get()) listener.onClosed(error)
                return
            }
            if (closed.get()) {
                safeClose(accepted)
                return
            }
            // The tunnel carries exactly one iAP2 data stream, so only the first peer is served.
            // Refusing the rest keeps `socket` meaning "the peer" and stops a stray connection
            // from occupying the stream the real iPhone needs.
            if (!peerAttached.compareAndSet(false, true)) {
                listener.onDebug(
                    "AirPlay iAP tunnel refused an extra peer ${accepted.remoteSocketAddress}",
                )
                safeClose(accepted)
                continue
            }
            accepted.setSoLinger(true, 0)
            socket = accepted
            readCounter.set(0)
            peerConnected.countDown()
            listener.onOpen(accepted.remoteSocketAddress?.toString())
            // Run the receive loop on its own thread. Calling it inline would block this loop,
            // so a peer that connects and then stays silent would stop the real peer connecting.
            threads += Thread({ run(accepted) }, "airplay-iap-tunnel-read").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun run(sock: Socket) {
        var ciphertext = ByteArray(0)
        var plaintext = ByteArray(0)
        var failure: Throwable? = null
        var announcedData = false
        try {
            val input = sock.getInputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) {
                    listener.onDebug("AirPlay iAP tunnel peer EOF")
                    break
                }
                if (!announcedData) {
                    announcedData = true
                    listener.onDebug("AirPlay iAP tunnel received data")
                }
                ciphertext += buffer.copyOf(read)
                val decrypted = decryptFrames(ciphertext)
                plaintext += decrypted.first
                ciphertext = decrypted.second
                plaintext = parsePackages(plaintext)
                if (plaintext.size > MAX_PENDING_BYTES) {
                    throw IOException(
                        "CarPlay iAP tunnel receive buffer exceeded $MAX_PENDING_BYTES bytes",
                    )
                }
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            if (socket === sock) {
                socket = null
                // Let a replacement peer attach: the iPhone can reopen the data stream without
                // tearing the whole AirPlay session down.
                peerAttached.set(false)
            }
            safeClose(sock)
            if (!closed.get() && failure != null) listener.onClosed(failure)
        }
    }

    private fun decryptFrames(buffer: ByteArray): Pair<ByteArray, ByteArray> {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= FRAME_HEADER_LEN) {
            val length = readU16Le(buffer, offset)
            val frameLength = FRAME_HEADER_LEN + length + TAG_SIZE
            if (buffer.size - offset < frameLength) break
            val aad = buffer.copyOfRange(offset, offset + FRAME_HEADER_LEN)
            val sealed = buffer.copyOfRange(offset + FRAME_HEADER_LEN, offset + frameLength)
            val plain = AirPlayCrypto.chachaOpen(
                readKey, AirPlayCrypto.nonce64(readCounter.get()), sealed, aad,
            )
            readCounter.incrementAndGet()
            output.add(plain)
            offset += frameLength
        }
        return concatBytes(*output.toTypedArray()) to buffer.copyOfRange(offset, buffer.size)
    }

    private fun parsePackages(buffer: ByteArray): ByteArray {
        var offset = 0
        while (buffer.size - offset >= PACKAGE_HEADER_LEN) {
            val size = readU32Be(buffer, offset)
            if (size < PACKAGE_HEADER_LEN || size > MAX_PACKAGE) {
                // There is no resynchronisation point in this framing, so a bad length cannot be
                // skipped. Retaining the bytes would only grow the buffer until the process dies.
                throw IOException("CarPlay iAP tunnel package length $size is out of range")
            }
            if (buffer.size - offset < size) break
            val messageType = readU32Be(buffer, offset + MESSAGE_TYPE_OFFSET)
            if (messageType == MSG_TYPE_COMM) {
                listener.onDebug(
                    "AirPlay iAP tunnel package type=comm body=${size - PACKAGE_HEADER_LEN}",
                )
                listener.onIap(buffer.copyOfRange(offset + PACKAGE_HEADER_LEN, offset + size))
            }
            offset += size
        }
        return buffer.copyOfRange(offset, buffer.size)
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        const val FRAME_HEADER_LEN = 2
        const val TAG_SIZE = 16
        const val PACKAGE_HEADER_LEN = 32
        const val MESSAGE_TYPE_OFFSET = 16
        const val MSG_TYPE_COMM = 0x636f6d6d
        const val MAX_PACKAGE = 4 * 1024 * 1024
        const val READ_CHUNK_BYTES = 16 * 1024

        /** One package may legitimately be in flight, so this only has to exceed MAX_PACKAGE. */
        const val MAX_PENDING_BYTES = MAX_PACKAGE + READ_CHUNK_BYTES * 2
    }
}
