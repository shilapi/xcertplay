package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayIapTunnelStreamTest {
    @Test
    fun inputConnectionCloseKeepsEventChannelAvailableForOutboundIap() {
        val sent = mutableListOf<ByteArray>()
        val session = testSession()
        val bridge = AirPlayIapTunnelStream(
            session = session,
            tunnel = IapTunnel(ByteArray(32)),
            sendCommand = { data, _ ->
                sent += data.copyOf()
                true
            },
        )

        try {
            val port = bridge.listen()
            Socket(InetAddress.getByName("127.0.0.1"), port).use { }

            val payload = byteArrayOf(1, 2, 3)
            bridge.send(payload)

            assertTrue(sent.size == 1)
            assertArrayEquals(payload, sent.single())
        } finally {
            bridge.close()
            session.close()
        }
    }

    @Test
    fun ipv6ListenerAcceptsIpv6Loopback() {
        val bridge = AirPlayIapTunnelStream(
            session = testSession(),
            tunnel = IapTunnel(
                readKey = ByteArray(32),
                bindAddress = InetAddress.getByName("::"),
            ),
            sendCommand = { _, _ -> true },
        )

        try {
            val port = bridge.listen()
            Socket(InetAddress.getByName("::1"), port).use { }
        } finally {
            bridge.close()
        }
    }

    @Test
    fun encryptedCommPayloadReachesIapListenerVerbatim() {
        val serverReadKey = ByteArray(32) { 5 }
        val received = ArrayList<ByteArray>()
        val receivedLatch = CountDownLatch(1)
        val tunnel = IapTunnel(
            readKey = serverReadKey,
            bindAddress = InetAddress.getByName("127.0.0.1"),
        )
        var client: RcsChannel? = null
        try {
            val port = tunnel.listen(
                object : IapTunnel.Listener {
                    override fun onIap(bytes: ByteArray) {
                        received += bytes.copyOf()
                        receivedLatch.countDown()
                    }
                },
            )
            client = RcsChannel.connect(
                remote = InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
                readKey = ByteArray(32) { 6 },
                writeKey = serverReadKey,
            )
            val payload = byteArrayOf(0x40, 0x40, 4, 5, 6)
            client.send(RcsMessage.comm(payload))

            assertTrue(receivedLatch.await(2, TimeUnit.SECONDS))
            assertArrayEquals(payload, received.single())
        } finally {
            client?.close()
            tunnel.close()
        }
    }

    private fun testSession(): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )

}
