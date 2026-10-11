package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import com.shilapi.xcertplay.airplay.CarPlayOemThemeDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarPlayClusterRenderingTest {
    private val root = File("../shared/src/main/assets/carplay-ultra")
    private val descriptor get() = CarPlayOemThemeDescriptor.decode(File(root, "manifest.bplist").readBytes())
    private val colors = ClusterColors.resolve(ClusterDialStyle.DIGI_RING) { null }
    private fun render(slot: OemLocalSlot): Bitmap {
        val bitmap = Bitmap.createBitmap(700, 700, Bitmap.Config.ARGB_8888)
        CarPlayClusterDashboardRenderer().draw(Canvas(bitmap), ClusterDialStyle.DIGI_RING, colors,
            CarPlayClusterDashboardRenderer.Zones(null, null, null, null, listOf(slot), true),
            ClusterReadings.from(mapOf("vehicleSpeed" to 135.0, "vehicleSpeedMax" to 260.0))) { false }
        return bitmap
    }

    @Test fun roundArcEndpointSurvivesPaintReset() {
        val cfg = mapOf("trackWidth" to 30, "trackInset" to 20, "trackLineCap" to 0,
            "angleConfiguration" to mapOf("minimumAngle" to Math.toRadians(135.0),
                "maximumAngle" to Math.toRadians(45.0), "clockwise" to true))
        val slot = OemLocalSlot(OemFrame(50f, 50f, 600f, 600f), mapOf("progressBar" to mapOf("type" to
            mapOf("digiBarProgress" to mapOf("configuration" to mapOf("style" to mapOf("radial" to
                mapOf("configuration" to cfg))), "dataSource" to mapOf("speedometer" to emptyMap<String, Any?>()))))), emptyList<Any>(), 0)
        val bitmap = render(slot)
        // 半进度在顶部，圆头应向终点右侧延伸；平头在此像素完全透明。
        assertTrue(bitmap.getPixel(359, 85).ushr(24) > 200)
        assertEquals(0, bitmap.getPixel(375, 85).ushr(24))
        bitmap.recycle()
    }

    @Test fun linearCapsAndCenterOffsetAreNotClippedToSlot() {
        val slot = OemLocalSlot(OemFrame(100f, 100f, 200f, 22f), mapOf("progressBar" to mapOf("type" to
            mapOf("digiBarProgress" to mapOf("configuration" to mapOf("style" to mapOf("linear" to
                mapOf("configuration" to mapOf("trackWidth" to 22, "centerOffset" to 40, "isRTL" to true)))),
                "dataSource" to mapOf("speedometer" to emptyMap<String, Any?>()))))), emptyList<Any>(), 0)
        val bitmap = render(slot)
        assertTrue(bitmap.getPixel(305, 151).ushr(24) > 200)
        assertTrue(bitmap.getPixel(195, 151).ushr(24) > 200)
        assertEquals(0, bitmap.getPixel(150, 151).ushr(24))
        assertEquals(0, bitmap.getPixel(250, 111).ushr(24))
        bitmap.recycle()
    }

    @Test fun conicalProgressFadesAroundWholeRing() {
        val cfg = mapOf("trackWidth" to 30, "trackInset" to 20,
            "angleConfiguration" to mapOf("minimumAngle" to Math.toRadians(135.0),
                "maximumAngle" to Math.toRadians(45.0), "clockwise" to true))
        val slot = OemLocalSlot(OemFrame(50f, 50f, 600f, 600f), mapOf("progressBar" to mapOf("type" to
            mapOf("digiBandGradientProgress" to mapOf("configuration" to mapOf("style" to cfg),
                "dataSource" to mapOf("speedometer" to emptyMap<String, Any?>()))))), emptyList<Any>(), 0)
        val bitmap = render(slot)
        val bright = bitmap.getPixel(347, 85).ushr(24)
        val fading = bitmap.getPixel(85, 350).ushr(24)
        assertTrue("进度端应最亮", bright > 200)
        assertTrue("尾部应渐隐而非实色弧", fading in 1 until bright)
        assertEquals("渐变结束后应透明", 0, bitmap.getPixel(615, 350).ushr(24))
        assertEquals(0, bitmap.getPixel(350, 350).ushr(24))
        bitmap.recycle()
    }

    @Test fun renderEveryBundledLayoutUsingItsSlotsAndFonts() {
        val d = descriptor
        val output = System.getenv("OEM_RENDER_DIR")?.let(::File)?.apply { mkdirs() }
        ZipFile(File(root, "CarPlayAccessory.assets.zip")).use { zip ->
            val fonts = CarPlayOemRasterFonts({ name, filename ->
                zip.getInputStream(zip.getEntry("${d.resourcePath(name)}/$filename")).use { it.readBytes() }
            }, File(root, "font-metrics.bplist").readBytes())
            d.layouts.forEach { layout ->
                val id = layout["id"] as String
                val vehicle = CarPlayClusterMockVehicle()
                repeat(59) { vehicle.advance(1.0) }
                val readings = ClusterReadings.from(vehicle.advance(1.0))
                val plan = OemScenePlan.build(layout, d.initialComponents(id), readings.values)
                val style = ClusterDialStyle.of(layout["layoutStyleID"])
                val renderer = CarPlayClusterDashboardRenderer().apply {
                    this.fonts = fonts
                    resolveColor = { color -> d.colorDefinition(id, d.palette(id), color)?.let { ClusterColors.argb(it) } }
                }
                val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val zones = ClusterZones.from(layout, plan)
                assertTrue("$style has asset slots", zones.assetDriven)
                renderer.draw(canvas, style, colors, zones.copy(localSlots = zones.localSlots.filterNot { it.persistent }), readings) { false }
                renderer.drawPersistent(canvas, colors, zones, readings)
                assertTrue("$style draws visible pixels", (0 until 720 step 8).any { y ->
                    (0 until 1920 step 8).any { x -> bitmap.getPixel(x, y).ushr(24) > 0 }
                })
                output?.let { File(it, "$style.png").outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) } }
                bitmap.recycle()
            }
            fonts.recycle()
        }
    }
}
