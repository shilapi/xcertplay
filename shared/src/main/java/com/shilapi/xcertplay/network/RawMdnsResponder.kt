package com.shilapi.xcertplay.network

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal DNS-SD responder bound to a single interface.
 *
 * Android's NsdManager registers the AirPlay service with no address record
 * ("hostAddresses: , hostname: null, network: null" in dumpsys servicediscovery),
 * so the SRV target never resolves and AirPlay clients can never learn the
 * receiver IP. This responder publishes PTR/SRV/TXT plus an explicit A record.
 */
class RawMdnsResponder(
    private val hostLabel: String,
    private val address: Inet4Address,
    private val port: Int,
    private val serviceType: String,
    private val instanceName: String,
    private val txt: Map<String, String>,
) : Closeable {

    private val running = AtomicBoolean(false)
    private val seenPackets = AtomicInteger(0)
    private var socket: MulticastSocket? = null
    private var thread: Thread? = null

    private val typeName = "$serviceType.local"
    private val instanceFqdn = "$instanceName.$typeName"
    private val hostFqdn = "$hostLabel.local"

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val nic = findInterface(address)
            ?: throw IOException("No interface owns ${address.hostAddress}")
        val sock = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(MDNS_PORT))
            networkInterface = nic
            timeToLive = 255
            soTimeout = 30_000
            joinGroup(InetSocketAddress(MDNS_GROUP, MDNS_PORT), nic)
        }
        socket = sock
        thread = Thread({ loop(sock) }, "raw-mdns").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "serving $instanceFqdn on ${address.hostAddress}:$port via ${nic.name}")
        announce(TTL_SECONDS)
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { announce(0) }
        runCatching { socket?.close() }
        socket = null
        thread?.interrupt()
        thread = null
    }

    private fun loop(sock: MulticastSocket) {
        val buffer = ByteArray(9000)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                sock.receive(packet)
                val query = DnsQuery.parse(packet.data, packet.length) ?: continue
                if (seenPackets.incrementAndGet() <= 40) {
                    Log.d(TAG, "rx ${packet.socketAddress} q=${query.describe()}")
                }
                val wanted = query.asksFor(instanceFqdn) || query.asksFor(typeName) ||
                    query.asksFor(hostFqdn)
                if (!wanted) continue
                Log.d(TAG, "query from ${packet.socketAddress} names=${query.describe()}")
                val payload = buildRecords(TTL_SECONDS)
                val target = if (packet.port == MDNS_PORT) {
                    InetSocketAddress(MDNS_GROUP, MDNS_PORT)
                } else {
                    packet.socketAddress
                }
                sock.send(DatagramPacket(payload, payload.size, target))
                // Answer the querier directly as well: multicast delivery towards
                // stations is filtered on some SoftAP drivers, unicast is not.
                if (packet.port == MDNS_PORT) {
                    runCatching {
                        sock.send(DatagramPacket(payload, payload.size, packet.socketAddress))
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Re-announce periodically so clients that missed the initial burst
                // still learn the address record.
                if (running.get()) announce(TTL_SECONDS)
            } catch (_: IOException) {
                if (!running.get()) return
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun announce(ttl: Int) {
        val sock = socket ?: return
        repeat(2) {
            val payload = buildRecords(ttl)
            runCatching {
                sock.send(
                    DatagramPacket(payload, payload.size, InetSocketAddress(MDNS_GROUP, MDNS_PORT))
                )
            }
        }
    }

    private fun buildRecords(ttl: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeShort(out, 0)
        writeShort(out, FLAGS_RESPONSE)
        writeShort(out, 0)
        writeShort(out, 3)
        writeShort(out, 0)
        writeShort(out, 1)

        writeName(out, typeName)
        writeRecord(out, TYPE_PTR, ttl, writeNameTo(instanceFqdn))

        writeName(out, instanceFqdn)
        val srv = ByteArrayOutputStream().apply {
            writeShort(this, 0)
            writeShort(this, 0)
            writeShort(this, port)
            write(writeNameTo(hostFqdn))
        }.toByteArray()
        writeRecord(out, TYPE_SRV, ttl, srv)

        val txtBytes = ByteArrayOutputStream().apply {
            txt.forEach { (key, value) ->
                val entry = "$key=$value".toByteArray(StandardCharsets.UTF_8)
                write(entry.size.coerceAtMost(255))
                write(entry)
            }
            if (size() == 0) write(0)
        }.toByteArray()
        writeName(out, instanceFqdn)
        writeRecord(out, TYPE_TXT, ttl, txtBytes)

        writeName(out, hostFqdn)
        writeRecord(out, TYPE_A, ttl, address.address)
        return out.toByteArray()
    }

    private fun writeRecord(out: ByteArrayOutputStream, type: Int, ttl: Int, rdata: ByteArray) {
        writeShort(out, type)
        writeShort(out, CLASS_IN_FLUSH)
        writeInt(out, ttl)
        writeShort(out, rdata.size)
        out.write(rdata)
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        out.write(writeNameTo(name))
    }

    private fun writeNameTo(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        name.split('.').filter { it.isNotEmpty() }.forEach { label ->
            val bytes = label.toByteArray(StandardCharsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun findInterface(target: Inet4Address): NetworkInterface? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nic in interfaces) {
            if (!nic.isUp) continue
            for (candidate in nic.inetAddresses) {
                if (candidate is Inet4Address && candidate.address.contentEquals(target.address)) {
                    return nic
                }
            }
        }
        return null
    }

    /** Parses just enough DNS to answer our own service names. */
    private class DnsQuery(private val names: List<String>) {
        fun asksFor(name: String): Boolean = names.any { it.equals(name, ignoreCase = true) }

        fun describe(): String = names.joinToString(",")

        companion object {
            fun parse(data: ByteArray, length: Int): DnsQuery? {
                if (length < 12) return null
                val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                if (flags and 0x8000 != 0) return null
                val questions = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
                if (questions <= 0) return null
                val names = mutableListOf<String>()
                var offset = 12
                var index = 0
                while (index < questions && index < 8 && offset < length) {
                    val builder = StringBuilder()
                    var guard = 0
                    while (offset < length && guard < 128) {
                        val size = data[offset].toInt() and 0xFF
                        if (size == 0) {
                            offset++
                            break
                        }
                        if (size and 0xC0 != 0) {
                            offset += 2
                            break
                        }
                        offset++
                        if (offset + size > length) break
                        if (builder.isNotEmpty()) builder.append('.')
                        builder.append(String(data, offset, size, StandardCharsets.UTF_8))
                        offset += size
                        guard++
                    }
                    if (builder.isNotEmpty()) names.add(builder.toString())
                    offset += 4
                    index++
                }
                return if (names.isEmpty()) null else DnsQuery(names)
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-mdns"
        const val MDNS_PORT = 5353
        const val TTL_SECONDS = 120
        const val FLAGS_RESPONSE = 0x8400
        const val CLASS_IN_FLUSH = 0x8001
        const val TYPE_A = 1
        const val TYPE_PTR = 12
        const val TYPE_TXT = 16
        const val TYPE_SRV = 33
        val MDNS_GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
    }
}
