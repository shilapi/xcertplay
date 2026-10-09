package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test

class CarPlayWebDisplayTest {
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

    @Test fun channelsKeepOnlyTheirLatestFrameAndClearItWhenTheSourceStops() {
        var now = 0L
        val main = WebDisplayChannel(1280, 720) { now }
        val cluster = WebDisplayChannel(1920, 720) { now }
        main.setActive(true); cluster.setActive(true)
        main.publish(byteArrayOf(1)); main.publish(byteArrayOf(2)); cluster.publish(byteArrayOf(3))
        assertArrayEquals(byteArrayOf(2), main.frame!!.bytes)
        assertArrayEquals(byteArrayOf(3), cluster.frame!!.bytes)
        assertFalse(main.hasViewer); main.viewed(); assertTrue(main.hasViewer)
        now = 3_000_000_000; assertFalse(main.hasViewer)
        main.setActive(false); main.publish(byteArrayOf(4)); assertNull(main.frame)
        assertNotNull(cluster.frame)
    }

    @Test fun touchOwnershipPreservesFingerSlotsAndExpiresLostReleaseEvents() {
        val reports = mutableListOf<List<AirPlayContact>>()
        val touch = WebDisplayTouch { reports.add(it); true }
        assertTrue(touch.submit("browser-a", "0,0.2,0.3;1,0.8,0.9", 0))
        assertFalse(touch.submit("browser-b", "0,0.5,0.5", 100))
        assertTrue(touch.submit("browser-a", "1,0.7,0.8", 200))
        assertEquals(listOf(0, 1), reports.last().map { it.id })
        assertFalse(reports.last()[0].down); assertTrue(reports.last()[1].down)
        touch.expire(1699); assertTrue(reports.last()[1].down)
        touch.expire(1700); assertTrue(reports.last().isEmpty())
        assertTrue(touch.submit("browser-b", "0,0.5,0.5", 1701))
        touch.release(); assertTrue(reports.last().isEmpty())
    }

    @Test fun malformedTouchCannotReplaceTheActiveGesture() {
        val reports = mutableListOf<List<AirPlayContact>>()
        val touch = WebDisplayTouch { reports.add(it); true }
        touch.submit("a", "0,0.5,0.5", 0)
        for (value in listOf("0,NaN,0", "0,2,0", "0,0,0;0,1,1", "2,0,0", "0,0", "0,0,0;1,0,0;2,0,0")) {
            assertThrows(IllegalArgumentException::class.java) { touch.submit("a", value, 100) }
        }
        assertEquals(1, reports.size)
    }

    @Test fun httpSeparatesDisplaysRejectsUnpairedAndCrossOriginControlAndStopsCleanly() {
        val main = WebDisplayChannel(1280, 720)
        val cluster = WebDisplayChannel(1920, 480)
        main.setActive(true); cluster.setActive(true)
        main.publish(byteArrayOf(1, 2)); cluster.publish(byteArrayOf(3, 4))
        val reports = mutableListOf<List<AirPlayContact>>()
        val server = WebDisplayServer("page".toByteArray(), mapOf(110 to main, 111 to cluster), "test-key", { reports.add(it); true }, 0)
        server.start(3000, true)
        val origin = "http://127.0.0.1:${server.listeningPort}"
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        fun request(path: String, method: String = "GET", referer: String? = null): HttpResponse<ByteArray> {
            val builder = HttpRequest.newBuilder(URI(origin + path)).timeout(Duration.ofSeconds(2))
                .method(method, HttpRequest.BodyPublishers.noBody())
            if (referer != null) builder.header("Origin", referer)
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        }
        try {
            request("/main?key=wrong").let { assertEquals(401, it.statusCode()) }
            request("/main/frame?key=test-key").let {
                assertEquals(200, it.statusCode()); assertEquals("1280", it.headers().firstValue("X-Display-Width").orElse(""))
                assertEquals("no-store", it.headers().firstValue("Cache-Control").orElse(""))
                assertArrayEquals(byteArrayOf(1, 2), it.body())
            }
            request("/cluster/frame?key=test-key").let {
                assertArrayEquals(byteArrayOf(3, 4), it.body())
            }
            request("/main/frame?key=test-key&after=1").let { assertEquals(204, it.statusCode()) }
            request("/main/touch?key=test-key&client=a&contacts=0,0.5,0.5", "POST", "http://other.test").let {
                assertEquals(403, it.statusCode())
            }
            request("/cluster/touch?key=test-key&client=a", "POST", origin).let { assertEquals(405, it.statusCode()) }
            request("/main/touch?key=test-key&client=a&contacts=0,0.5,0.5", "POST", origin).let {
                assertEquals(200, it.statusCode())
            }
            assertTrue(reports.last()[0].down)
            main.setActive(false)
            request("/main/frame?key=test-key").let { assertEquals(204, it.statusCode()); assertEquals("false", it.headers().firstValue("X-Stream-Active").orElse("")) }
        } finally { server.stop() }
        assertTrue(reports.last().isEmpty())
    }
}
