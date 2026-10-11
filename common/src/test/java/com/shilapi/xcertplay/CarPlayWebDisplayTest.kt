package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.KeyStore
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import org.junit.Assert.*
import org.junit.Test

class CarPlayWebDisplayTest {
    private class Recorder : WebDisplayViewer.Transport {
        override fun send(message: ByteArray) = Unit
        override fun send(message: String) = Unit
        override fun close() = Unit
    }

    private fun WebDisplayViewer.drain(): List<Any> = generateSequence { next(0) }.toList()

    private val idr = byteArrayOf(0, 0, 0, 2, 0x65, 0x88.toByte())
    private val delta = byteArrayOf(0, 0, 0, 2, 0x41, 0x9a.toByte())

    @Test fun overridesAreIndependentAndDisabledOutputPreservesTheOriginalDisplay() {
        val original = AirPlayDisplayConfig(800, 480, widthPhysicalMm = 200, safeArea = AirPlayInsets(left = 20))
        assertSame(original, CarPlayWebDisplayConfig().override(original))
        val main = CarPlayWebDisplayConfig(true, 1920, 1080).override(original)
        val cluster = CarPlayWebDisplayConfig(true, 1280, 480, false).override(original)
        assertEquals(1920, main.widthPixels); assertEquals(1080, main.heightPixels)
        assertEquals(1280, cluster.widthPixels); assertEquals(480, cluster.heightPixels)
        assertNull(main.safeArea); assertEquals(113, main.heightPhysicalMm)
        val info = AirPlayInfoPlist.build(AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "950.7.1", main, cluster))
        val displays = info["displays"] as List<*>
        assertEquals(listOf(110, 111), displays.map { (it as Map<*, *>)["type"] })
        assertEquals(listOf(1920, 1280), displays.map { (it as Map<*, *>)["widthPixels"] })
    }

    @Test fun invalidDimensionsAreRejectedBeforeAllocatingOutputBuffers() {
        for ((w, h) in listOf(0 to 720, 319 to 720, 1281 to 720, 1280 to 721, 3842 to 2160, 1280 to 2162)) {
            assertThrows(IllegalArgumentException::class.java) { CarPlayWebDisplayConfig(true, w, h) }
        }
        CarPlayWebDisplayConfig(true, 3840, 2160)
    }

    @Test fun previewsFitTheRequestedAspectRatioWithoutChangingStreamDimensions() {
        assertArrayEquals(doubleArrayOf(0.0, 75.0, 800.0, 450.0), CarPlayTouchMapper.contentRect(800, 600, 1920, 1080), 0.001)
        assertArrayEquals(doubleArrayOf(0.0, 210.0, 480.0, 180.0), CarPlayTouchMapper.contentRect(480, 600, 1920, 720), 0.001)
    }

    @Test fun channelsForwardTheCompressedStreamAndStartEveryBrowserAtRandomAccess() {
        var keyRequests = 0
        var now = 0L
        val channel = WebDisplayChannel(110, 1280, 720) { now }
        channel.keyFrameSource = { keyRequests++; true }
        channel.setActive(true)
        channel.config(VideoCodec.H264, byteArrayOf(1, 0x64, 0, 0x1f))
        val viewer = WebDisplayViewer(Recorder(), start = false)
        channel.attach(viewer)
        assertEquals(1, keyRequests)
        var messages = viewer.drain()
        assertTrue((messages[0] as String).contains("\"active\":true"))
        assertArrayEquals(byteArrayOf(1, 0, 1, 0x64, 0, 0x1f), messages[1] as ByteArray)

        channel.frame(delta, 10)
        assertTrue(viewer.drain().isEmpty())
        assertEquals("the request made on attach is still in flight", 1, keyRequests)
        now += 600_000_000; channel.frame(delta, 20); assertEquals(2, keyRequests)
        now += 100_000_000; channel.frame(delta, 25)
        assertEquals("waiting browsers ask at a bounded rate", 2, keyRequests)

        channel.frame(idr, 30); channel.frame(delta, 40)
        messages = viewer.drain()
        assertEquals(2, messages.size)
        val key = messages[0] as ByteArray
        assertEquals(2.toByte(), key[0]); assertEquals(1.toByte(), key[1])
        assertEquals(30L, ByteBuffer.wrap(key, 2, 8).long)
        assertArrayEquals(idr, key.copyOfRange(10, key.size))
        assertEquals(0.toByte(), (messages[1] as ByteArray)[1])

        channel.config(VideoCodec.H264, byteArrayOf(1, 0x64, 0, 0x1f))
        assertTrue("repeated parameter sets do not restart browsers", viewer.drain().isEmpty())
        channel.frame(delta, 50); assertEquals(1, viewer.drain().size)

        channel.setActive(false); channel.frame(idr, 60)
        messages = viewer.drain()
        assertEquals(1, messages.size); assertTrue((messages[0] as String).contains("\"active\":false"))
    }

    @Test fun aSlowBrowserSkipsAheadToRandomAccessInsteadOfQueueingLatency() {
        val viewer = WebDisplayViewer(Recorder(), start = false)
        viewer.config(VideoCodec.H264, byteArrayOf(1))
        val key = listOf(5)
        val other = listOf(1)
        assertFalse(viewer.video(key, byteArrayOf(2)))
        repeat(11) { assertFalse(viewer.video(other, byteArrayOf(2))) }
        assertTrue(viewer.video(other, byteArrayOf(2)))
        assertTrue(viewer.video(other, byteArrayOf(2)))
        assertFalse(viewer.video(key, byteArrayOf(9)))
        val messages = viewer.drain()
        assertEquals(2, messages.size)
        assertArrayEquals(byteArrayOf(9), messages[1] as ByteArray)
    }

    @Test fun audioReachesEveryConnectedBrowser() {
        val channel = WebDisplayChannel(110, 1280, 720)
        assertFalse(channel.hasViewer)
        val first = WebDisplayViewer(Recorder(), start = false)
        val second = WebDisplayViewer(Recorder(), start = false)
        channel.attach(first); channel.attach(second); first.drain(); second.drain()
        assertTrue(channel.hasViewer)
        channel.audio(100, 48000, 2, byteArrayOf(9, 1, 2, 3, 9), 1, 3)
        val expected = byteArrayOf(3, 100, 0, 0, 0xbb.toByte(), 0x80.toByte(), 2, 1, 2, 3)
        assertArrayEquals(expected, first.drain().single() as ByteArray)
        assertArrayEquals(expected, second.drain().single() as ByteArray)
    }

    @Test fun audioIsSentAheadOfQueuedPictures() {
        val viewer = WebDisplayViewer(Recorder(), start = false)
        viewer.config(VideoCodec.H264, byteArrayOf(1))
        viewer.video(listOf(5), byteArrayOf(2, 1)); viewer.video(listOf(1), byteArrayOf(2, 2))
        viewer.audio(byteArrayOf(3, 1))
        val messages = viewer.drain().map { (it as ByteArray).toList() }
        assertEquals(listOf<Byte>(3, 1), messages.first())
        assertEquals("pictures keep their order", listOf(listOf<Byte>(1), listOf<Byte>(2, 1), listOf<Byte>(2, 2)), messages.drop(1))
    }

    @Test fun touchOwnershipPreservesFingerSlotsAndExpiresLostReleaseEvents() {
        val reports = mutableListOf<List<AirPlayContact>>()
        val touch = WebDisplayTouch({ reports.add(it); true }, {})
        assertTrue(touch.submit("browser-a", "0,0.2,0.3;1,0.8,0.9", 0))
        assertFalse(touch.submit("browser-b", "0,0.5,0.5", 100))
        assertTrue(touch.submit("browser-a", "1,0.7,0.8", 200))
        assertEquals(listOf(0, 1), reports.last().map { it.id })
        assertFalse(reports.last()[0].down); assertTrue(reports.last()[1].down)
        touch.expire(1699); assertTrue(reports.last()[1].down)
        touch.expire(1700); assertTrue(reports.last().none { it.down })
        assertTrue(touch.submit("browser-b", "0,0.5,0.5", 1701))
        touch.release(); assertTrue(reports.last().none { it.down })
    }

    @Test fun quickTapsStayVisibleAndAClosedBrowserReleasesItsFingers() {
        val reports = mutableListOf<List<AirPlayContact>>()
        val sleeps = mutableListOf<Long>()
        val touch = WebDisplayTouch({ reports.add(it); true }, { sleeps.add(it) })
        touch.submit("a", "0,0.5,0.5", 1000); touch.submit("a", "", 1004)
        assertEquals(listOf(26L), sleeps); assertTrue(reports.last().none { it.down })
        touch.submit("a", "0,0.5,0.5", 2000); touch.submit("a", "0,0.6,0.5", 2100); touch.submit("a", "", 2200)
        assertEquals(1, sleeps.size)
        touch.submit("a", "0,0.5,0.5", 3000)
        touch.release("b"); assertTrue(reports.last()[0].down)
        touch.release("a"); assertTrue(reports.last().none { it.down })
        assertTrue(touch.submit("b", "0,0.5,0.5", 3001))
    }

    @Test fun fingersLiftWhereTheyWereLastReported() {
        val reports = mutableListOf<List<AirPlayContact>>()
        val touch = WebDisplayTouch({ reports.add(it); true }, {})
        touch.submit("a", "0,0.2,0.3", 0)
        touch.submit("a", "0,0.6,0.7;1,0.4,0.5", 100)
        touch.submit("a", "1,0.45,0.55", 200)
        assertEquals(AirPlayContact(0, 0.6, 0.7, false), reports.last()[0])
        assertEquals(AirPlayContact(1, 0.45, 0.55, true), reports.last()[1])
        touch.submit("a", "", 300)
        assertEquals(listOf(AirPlayContact(0, 0.6, 0.7, false), AirPlayContact(1, 0.45, 0.55, false)), reports.last())
        touch.submit("a", "0,0.9,0.1", 400); touch.expire(1900)
        assertEquals("a vanished browser's finger lifts in place too", AirPlayContact(0, 0.9, 0.1, false), reports.last()[0])
    }

    @Test fun malformedTouchCannotReplaceTheActiveGesture() {
        val reports = mutableListOf<List<AirPlayContact>>()
        val touch = WebDisplayTouch({ reports.add(it); true }, {})
        touch.submit("a", "0,0.5,0.5", 0)
        for (value in listOf("0,NaN,0", "0,2,0", "0,0,0;0,1,1", "2,0,0", "0,0", "0,0,0;1,0,0;2,0,0")) {
            assertThrows(IllegalArgumentException::class.java) { touch.submit("a", value, 100) }
        }
        assertEquals(1, reports.size)
    }

    /** A server on a fresh self-signed identity, and a client that trusts exactly that certificate. */
    private fun tlsServer(channels: Map<Int, WebDisplayChannel>, sendTouch: (List<AirPlayContact>) -> Boolean): Pair<WebDisplayServer, HttpClient> {
        val file = File(Files.createTempDirectory("tls").toFile(), "web.p12")
        val identity = WebDisplayCertificate.identity(file, emptyList(), System.currentTimeMillis())
        val server = WebDisplayServer("page".toByteArray(), channels, "test-key", sendTouch, 0, WebDisplayCertificate.sslContext(file, emptyList()))
        server.start(3000, true)
        val trusted = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("web", WebDisplayCertificate.certificate(identity)) }
        val tls = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }.trustManagers, null)
        }
        return server to HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).build()
    }

    @Test fun webSocketCarriesStateAndTouchAndRejectsUnpairedOrCrossOriginClients() {
        val main = WebDisplayChannel(110, 1280, 720)
        val cluster = WebDisplayChannel(111, 1920, 480)
        main.setActive(true)
        val reports = CopyOnWriteArrayList<List<AirPlayContact>>()
        val (server, client) = tlsServer(mapOf(110 to main, 111 to cluster)) { reports.add(it); true }
        val port = server.listeningPort
        val origin = "https://127.0.0.1:$port"
        fun get(path: String): HttpResponse<String> = client.send(
            HttpRequest.newBuilder(URI(origin + path)).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString())
        fun waitUntil(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition()) { check(System.nanoTime() < deadline) { "Timed out" }; Thread.sleep(10) }
        }
        val texts = LinkedBlockingQueue<String>()
        val listener = object : WebSocket.Listener {
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                texts.add(data.toString()); webSocket.request(1); return null
            }
        }
        fun open(path: String, from: String) = client.newWebSocketBuilder().header("Origin", from)
            .buildAsync(URI("wss://127.0.0.1:$port$path"), listener).get(3, TimeUnit.SECONDS)
        try {
            assertEquals(401, get("/main?key=wrong").statusCode())
            get("/main?key=test-key").let { assertEquals(200, it.statusCode()); assertEquals("page", it.body()) }
            assertEquals(400, get("/main/ws?key=test-key").statusCode())
            assertTrue(runCatching { open("/main/ws?key=test-key", "https://other.test") }.isFailure)
            assertTrue("plain http pages cannot open the socket", runCatching { open("/main/ws?key=test-key", "http://127.0.0.1:$port") }.isFailure)
            assertTrue(runCatching { open("/main/ws?key=wrong", origin) }.isFailure)

            val socket = open("/main/ws?key=test-key", origin)
            assertTrue(texts.poll(2, TimeUnit.SECONDS)!!.contains("\"control\":true"))
            waitUntil { main.hasViewer }
            socket.sendText("t:0,0.5,0.5", true).get(2, TimeUnit.SECONDS)
            waitUntil { reports.isNotEmpty() }
            assertTrue(reports.last()[0].down)
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS)
            waitUntil { reports.last().none { it.down } && !main.hasViewer }

            val instrument = open("/cluster/ws?key=test-key", origin)
            assertTrue(texts.poll(2, TimeUnit.SECONDS)!!.contains("\"control\":false"))
            val count = reports.size
            instrument.sendText("t:0,0.5,0.5", true).get(2, TimeUnit.SECONDS)
            Thread.sleep(100)
            assertEquals("the instrument display cannot touch", count, reports.size)
        } finally { server.stop() }
    }

    @Test fun theCertificateIsReusedUntilAnAddressOrExpiryNeedsANewOne() {
        val file = File(Files.createTempDirectory("tls").toFile(), "web.p12")
        val now = 1_800_000_000_000L
        val first = WebDisplayCertificate.certificate(WebDisplayCertificate.identity(file, listOf("192.168.1.5"), now))
        first.checkValidity(java.util.Date(now)); first.verify(first.publicKey)
        assertEquals(setOf("192.168.1.5", "127.0.0.1"), first.subjectAlternativeNames.map { it[1] }.toSet())
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), first.extendedKeyUsage)
        assertEquals(first, WebDisplayCertificate.certificate(WebDisplayCertificate.identity(file, listOf("192.168.1.5"), now + 1000)))
        val moved = WebDisplayCertificate.certificate(WebDisplayCertificate.identity(file, listOf("10.0.0.2"), now))
        assertNotEquals(first, moved)
        assertEquals("earlier addresses stay valid", setOf("192.168.1.5", "127.0.0.1", "10.0.0.2"),
            moved.subjectAlternativeNames.map { it[1] }.toSet())
        val renewed = WebDisplayCertificate.certificate(WebDisplayCertificate.identity(file, listOf("10.0.0.2"), now + TimeUnit.DAYS.toMillis(380)))
        assertNotEquals(moved, renewed)
    }
}
