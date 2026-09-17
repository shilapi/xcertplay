package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import com.shilapi.xcertplay.airplay.rcs.transport.RcsFrameCodec
import java.io.Closeable
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receive-only iAP2-over-CarPlay DataStream tunnel (stream type 130).
 *
 * The TCP stream is NetSocketChaCha20Poly1305 framed, then carries APTransportPackage records.
 * iAP2 bodies (messageType "comm") are emitted verbatim for the wired iAP2 relay.
 */
class IapTunnel(
    private val readKey: ByteArray,
    bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
) : Closeable {
    interface Listener {
        fun onOpen(remoteAddress: String?) {}
        fun onIap(bytes: ByteArray) {}
        fun onDebug(message: String) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val bindAddress =
        if (bindAddress is Inet4Address) InetAddress.getByName("0.0.0.0") else bindAddress
    private val servers = mutableListOf<ServerSocket>()
    private var socket: Socket? = null
    private val threads = mutableListOf<Thread>()
    private val peerConnected = CountDownLatch(1)
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = bindAny()
        servers += bound
        listener.onDebug("AirPlay iAP tunnel listener bound=${bound.localSocketAddress}")
        val secondaryAddress = if (bindAddress is java.net.Inet6Address) {
            InetAddress.getByName("0.0.0.0")
        } else {
            InetAddress.getByName("::")
        }
        val secondary = ServerSocket()
        runCatching {
            secondary.apply {
                reuseAddress = true
                bind(InetSocketAddress(secondaryAddress, bound.localPort))
            }
        }.onSuccess { secondary ->
            servers += secondary
            listener.onDebug(
                "AirPlay iAP tunnel secondary listener bound=" +
                    "${secondary.localSocketAddress}",
            )
        }.onFailure { error ->
            safeClose(secondary)
            listener.onDebug(
                "AirPlay iAP tunnel secondary listener failed address=" +
                    "$secondaryAddress port=${bound.localPort}: ${error.message}",
            )
        }
        servers.forEach { server ->
            threads += Thread({ accept(server) }, "airplay-iap-tunnel").apply {
                isDaemon = true
                start()
            }
        }
        return bound.localPort
    }

    private fun bindAny(): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, 0))
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
            accepted.setSoLinger(true, 0)
            socket = accepted
            peerConnected.countDown()
            listener.onOpen(accepted.remoteSocketAddress?.toString())
            run(accepted)
        }
    }

    private fun run(sock: Socket) {
        var ciphertext = ByteArray(0)
        var plaintext = ByteArray(0)
        var failure: Throwable? = null
        var announcedData = false
        try {
            val frameCodec = RcsFrameCodec.reader(readKey)
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
                val decrypted = frameCodec.decrypt(ciphertext)
                plaintext += decrypted.data
                ciphertext = decrypted.rest
                plaintext = parsePackages(plaintext)
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get() && failure != null) listener.onClosed(failure)
        }
    }

    private fun parsePackages(buffer: ByteArray): ByteArray {
        val decoded = ApTransportPackageCodec.decodeAvailable(buffer)
        decoded.packages.forEach { packageValue ->
            if (packageValue.messageType == ApTransportPackageCodec.MESSAGE_TYPE_COMM) {
                listener.onDebug(
                    "AirPlay iAP tunnel package type=comm body=${packageValue.body.size}",
                )
                listener.onIap(packageValue.body)
            }
        }
        return decoded.remainder
    }

    private companion object {
        const val READ_CHUNK_BYTES = 16 * 1024
    }
}
