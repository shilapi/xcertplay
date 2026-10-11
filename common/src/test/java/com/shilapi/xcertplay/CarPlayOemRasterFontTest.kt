package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.shilapi.xcertplay.airplay.BplistCodec
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarPlayOemRasterFontTest {
    // 使用仓库中实际部署的指标和压缩包，无需外部下载目录。
    private val assets = File("../shared/src/main/assets/carplay-ultra")
    private val asset = File(assets, "font-metrics.bplist")
    private fun loadAtlas(name: String, filename: String): ByteArray =
        ZipFile(File(assets, "CarPlayAccessory.assets.zip")).use { zip ->
            val entry = zip.getEntry("r/Cluster_Display/Fonts/$name/$filename")
                ?: error("Missing bundled font $name")
            zip.getInputStream(entry).use { it.readBytes() }
        }

    @Test fun realAtlasesWidthsDrawingAndRelease() {
        val bytes = asset.readBytes()
        val fonts = CarPlayOemRasterFonts(::loadAtlas, bytes)
        val layouts = (BplistCodec.decode(bytes) as Map<*, *>)["fonts"] as Map<*, *>
        layouts.forEach { (name, value) ->
            val data = value as Map<*, *>
            val font = fonts.get(name as String)!!
            assertSame(font, fonts.get(name))
            assertEquals((data["height"] as Number).toFloat(), font.height, 0f)
            assertEquals(font.height - (data["lineOriginY"] as Number).toFloat(), font.baselineOffset, 0f)
            val chars = data["characters"] as Map<*, *>
            val kern = data["kerningTable"] as Map<*, *>
            for (text in listOf("AV", "AVA", "123", "12°", " ", "􀮷")) {
                val points = text.codePoints().toArray()
                var expected = 0.0
                points.forEachIndexed { index, point ->
                    val ch = String(Character.toChars(point))
                    val g = chars[ch] as Map<*, *>
                    expected += ((g["advance"] as List<*>)[0] as Number).toDouble() + 2
                    if (index + 1 < points.size && !(data["stable"] == true && points.size > 2 && index == 0)) {
                        val row = kern[ch] as? Map<*, *>
                        val next = String(Character.toChars(points[index + 1]))
                        expected += (row?.get(next) as? Number)?.toDouble() ?: 0.0
                    }
                }
                val last = chars[String(Character.toChars(points.last()))] as Map<*, *>
                val advance = ((last["advance"] as List<*>)[0] as Number).toDouble()
                val bb = last["boundingBox"] as List<*>
                val lastWidth = if (last["isWhitespace"] == true) advance else
                    (((bb[0] as List<*>)[0] as Number).toDouble() + ((bb[1] as List<*>)[0] as Number).toDouble())
                expected += lastWidth - advance - 2
                assertEquals("$name $text", expected.toFloat(), font.width(text, 2f), 0.0001f)
            }
        }
        assertEquals(180f, fonts.get("Stable-SoftNumeric-Medium-150")!!.height, 0f)
        assertEquals(143f, fonts.get("Stable-SoftNumeric-Medium-150")!!.baselineOffset, 0f)
        val font = fonts.get("System-Medium-30")!!
        val image = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        font.draw(Canvas(image), "AV", 10f, 12f, Color.RED)
        val pixels = IntArray(128 * 96)
        image.getPixels(pixels, 0, 128, 0, 0, 128, 96)
        assertTrue(pixels.any { Color.alpha(it) > 0 })
        assertTrue(pixels.filter { Color.alpha(it) > 0 }.all { Color.red(it) == 255 && Color.green(it) == 0 && Color.blue(it) == 0 })
        assertNull(fonts.get("missing"))
        fonts.recycle()
        assertNull(fonts.get("System-Medium-30"))
        val clear = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        font.draw(Canvas(clear), "AV", 10f, 12f, Color.RED)
        clear.getPixels(pixels, 0, 128, 0, 0, 128, 96)
        assertTrue(pixels.all { it == 0 })
        image.recycle()
        clear.recycle()
    }

    @Test fun invalidAtlasAndGlyphLimitsFailClosed() {
        val bytes = asset.readBytes()
        val fonts = CarPlayOemRasterFonts({ _, _ -> byteArrayOf(1, 2, 3) }, bytes)
        assertNull(fonts.get("System-Medium-30"))
        val real = CarPlayOemRasterFonts(::loadAtlas, bytes)
        val font = real.get("System-Medium-30")!!
        assertEquals(0f, font.width("中"), 0f)
        assertThrows(IllegalArgumentException::class.java) { font.width("a".repeat(8193)) }
        assertThrows(IllegalArgumentException::class.java) { font.width("A", Float.NaN) }
        real.recycle()
    }
}
