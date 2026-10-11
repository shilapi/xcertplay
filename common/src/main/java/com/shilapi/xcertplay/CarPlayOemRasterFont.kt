package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import com.shilapi.xcertplay.airplay.BplistCodec

/** OEM 字体指标只来自预提取资源，PNG 通过 runtime 的目录资源接口延迟加载。 */
internal class CarPlayOemRasterFonts(
    private val loader: (String, String) -> ByteArray,
    metricsBytes: ByteArray,
) {
    private val metrics: Map<*, *>
    private val cache = mutableMapOf<String, CarPlayOemRasterFont>()
    private val failed = mutableSetOf<String>()
    private var bitmapBytes = 0L
    private var recycled = false

    init {
        require(metricsBytes.size in 40..MAX_METRICS_BYTES) { "OEM font metrics size limit" }
        // 公共解码器分配对象索引前，先限制 bplist trailer 中的对象数。
        val trailer = java.nio.ByteBuffer.wrap(metricsBytes, metricsBytes.size - 32, 32)
        require(trailer.getLong(metricsBytes.size - 24) in 1..200_000) { "OEM font metrics object limit" }
        val root = BplistCodec.decode(metricsBytes) as? Map<*, *>
            ?: error("Invalid OEM font metrics")
        require((root["version"] as? Number)?.toInt() == 1)
        metrics = root["fonts"] as? Map<*, *> ?: error("Missing OEM font table")
        require(metrics.size <= 256)
    }

    /** 缺少资源或非法指标返回 null；同名字体只解码一次。调用方在绘制线程使用。 */
    fun get(name: String): CarPlayOemRasterFont? {
        if (recycled || name in failed) return null
        cache[name]?.let { return it }
        val data = metrics[name] as? Map<*, *> ?: return null
        var bitmap: Bitmap? = null
        return try {
            require(name.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            val layout = RasterFontMetrics(data)
            val bytes = loader(name, "$name.png")
            require(bytes.size in 1..MAX_ATLAS_BYTES)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outMimeType == "image/png")
            require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
            val allocation = bounds.outWidth.toLong() * bounds.outHeight * 4
            require(allocation <= MAX_BITMAP_BYTES && bitmapBytes + allocation <= MAX_TOTAL_BITMAP_BYTES)
            layout.glyphs.values.forEach {
                require(it.source.right <= bounds.outWidth && it.source.bottom <= bounds.outHeight)
            }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: error("Invalid OEM font atlas")
            require(bitmap.allocationByteCount.toLong() + bitmapBytes <= MAX_TOTAL_BITMAP_BYTES)
            val font = CarPlayOemRasterFont(bitmap, layout)
            bitmapBytes += bitmap.allocationByteCount
            cache[name] = font
            font
        } catch (_: Exception) {
            bitmap?.recycle()
            failed += name
            null
        }
    }

    /** 释放全部图集；已经交给调用方的字体对象之后也不会继续绘制。 */
    fun recycle() {
        cache.values.forEach { it.recycle() }
        cache.clear()
        failed.clear()
        bitmapBytes = 0
        recycled = true
    }

    private companion object {
        const val MAX_METRICS_BYTES = 4 * 1024 * 1024
        const val MAX_ATLAS_BYTES = 8 * 1024 * 1024
        const val MAX_BITMAP_BYTES = 16L * 1024 * 1024
        const val MAX_TOTAL_BITMAP_BYTES = 32L * 1024 * 1024
    }
}

/** y 是字体 layer 顶部，不是 Android drawText 的基线。 */
internal class CarPlayOemRasterFont internal constructor(
    private val atlas: Bitmap,
    private val metrics: RasterFontMetrics,
) {
    val height: Float get() = metrics.height
    val baselineOffset: Float get() = height - metrics.lineOriginY
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var tint: Int? = null

    fun width(text: String, tracking: Float = 0f): Float {
        val points = codepoints(text, tracking)
        var width = 0f
        points.forEachIndexed { index, point ->
            val glyph = metrics.glyphs[point] ?: return@forEachIndexed
            width += glyph.advance + tracking + kerning(points, index)
        }
        points.lastOrNull()?.let { point ->
            metrics.glyphs[point]?.let { glyph ->
                // APL 末字符非等宽时去掉 advance 和 tracking，使用墨迹右边界。
                val mono = if (metrics.monoDigits) point in '0'.code..'9'.code else metrics.monoSpace
                if (!mono) width += glyph.width - glyph.advance - tracking
            }
        }
        return width
    }

    fun draw(canvas: Canvas, text: String, x: Float, y: Float, color: Int, tracking: Float = 0f) {
        if (atlas.isRecycled || !x.isFinite() || !y.isFinite()) return
        val points = codepoints(text, tracking)
        if (tint != color) {
            paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            tint = color
        }
        var originX = x
        points.forEachIndexed { index, point ->
            val glyph = metrics.glyphs[point] ?: return@forEachIndexed
            if (!glyph.whitespace && !glyph.source.isEmpty) {
                // APL RasterFontFramesetter 的 -1 补偿图集中一像素边框。
                // textureBoundingBox 已含整行垂直布局，不能再加 boundingBox.y 或基线。
                val left = originX + glyph.offsetX - 1f
                val top = y - 1f
                destination.set(left, top, left + glyph.source.width(), top + glyph.source.height())
                canvas.drawBitmap(atlas, glyph.source, destination, paint)
            }
            originX += glyph.advance + tracking + kerning(points, index)
        }
    }

    private fun kerning(points: IntArray, index: Int): Float {
        if (index + 1 == points.size || (metrics.stable && points.size > 2 && index == 0)) return 0f
        return metrics.kerning[points[index]]?.get(points[index + 1]) ?: 0f
    }

    private fun codepoints(text: String, tracking: Float): IntArray {
        require(text.length <= 8192 && tracking.isFinite() && kotlin.math.abs(tracking) <= 8192)
        val points = text.codePoints().toArray()
        require(points.size <= 4096)
        // 原资产只有有限字符；缺字不使用系统字体，宽度与绘制都跳过该字符。
        return points
    }

    internal fun recycle() {
        if (!atlas.isRecycled) atlas.recycle()
    }
}

internal data class RasterGlyph(
    val advance: Float,
    val offsetX: Float,
    val width: Float,
    val whitespace: Boolean,
    val source: Rect,
)

/** 指标仍按原 LayoutData 组织，校验字符表、数值和图集坐标后才创建位图。 */
internal class RasterFontMetrics(data: Map<*, *>) {
    val height = number(data["height"]).also { require(it in 1f..4096f) }
    val lineOriginY = number(data["lineOriginY"]).also { require(it in 0f..height) }
    val stable = boolean(data["stable"])
    val monoSpace = boolean(data["hasMonoSpaceFlag"])
    val monoDigits = boolean(data["hasMonoSpacedDigits"])
    val glyphs: Map<Int, RasterGlyph>
    val kerning: Map<Int, Map<Int, Float>>

    init {
        val characters = data["characters"] as? Map<*, *> ?: error("Invalid OEM glyph table")
        require(characters.size in 1..4096)
        glyphs = characters.entries.associate { (key, value) ->
            val codepoint = character(key)
            val glyph = value as? Map<*, *> ?: error("Invalid OEM glyph")
            val advance = pair(glyph["advance"])[0]
            val bb = rectangle(glyph["boundingBox"])
            val texture = rectangle(glyph["textureBoundingBox"])
            val coords = texture.flatMap { it.asList() }
            require(coords.all { it >= 0 && it == it.toInt().toFloat() })
            val source = Rect(texture[0][0].toInt(), texture[0][1].toInt(),
                (texture[0][0] + texture[1][0]).toInt(), (texture[0][1] + texture[1][1]).toInt())
            val whitespace = boolean(glyph["isWhitespace"])
            codepoint to RasterGlyph(advance, bb[0][0], if (whitespace) advance else bb[0][0] + bb[1][0], whitespace, source)
        }
        val rows = data["kerningTable"] as? Map<*, *> ?: emptyMap<Any, Any>()
        require(rows.size <= 4096)
        var pairs = 0
        kerning = rows.entries.associate { (key, value) ->
            val left = character(key)
            require(left in glyphs)
            val row = value as? Map<*, *> ?: error("Invalid OEM kerning row")
            pairs += row.size
            require(pairs <= 100000)
            left to row.entries.associate { (right, amount) ->
                val point = character(right)
                require(point in glyphs)
                point to number(amount)
            }
        }
    }

    private fun character(value: Any?): Int {
        val text = value as? String ?: error("Invalid OEM character")
        require(text.codePointCount(0, text.length) == 1)
        return text.codePointAt(0).also { require(it !in 0xD800..0xDFFF) }
    }

    private fun boolean(value: Any?) = value as? Boolean ?: error("Invalid OEM flag")
    private fun number(value: Any?): Float = (value as? Number)?.toFloat()
        ?.also { require(it.isFinite() && kotlin.math.abs(it) <= 8192) }
        ?: error("Invalid OEM metric")
    private fun pair(value: Any?): FloatArray {
        val list = value as? List<*> ?: error("Invalid OEM metric pair")
        require(list.size == 2)
        return floatArrayOf(number(list[0]), number(list[1]))
    }
    private fun rectangle(value: Any?): Array<FloatArray> {
        val list = value as? List<*> ?: error("Invalid OEM metric rectangle")
        require(list.size == 2)
        return arrayOf(pair(list[0]), pair(list[1])).also { require(it[1].all { n -> n >= 0 }) }
    }
}
