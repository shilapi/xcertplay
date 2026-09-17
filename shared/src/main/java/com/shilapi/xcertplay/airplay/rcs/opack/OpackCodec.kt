package com.shilapi.xcertplay.airplay.rcs.opack

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.UUID

class OpackException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Apple CoreUtils OPACK codec used by CAF.
 *
 * This is not a property list. The implementation covers the scalar and collection tags needed by
 * CAF: null, booleans, signed integers, float32/float64, strings, data, arrays, dictionaries, and
 * object-reference tags emitted by Apple encoders. Unknown or unsupported tags fail explicitly.
 */
object OpackCodec {
    data class DecodeResult(
        val value: Any?,
        val remaining: ByteArray,
    )

    fun encode(value: Any?): ByteArray = encodeValue(value)

    fun decode(bytes: ByteArray): Any? {
        val decoded = decodePrefix(bytes)
        if (decoded.remaining.isNotEmpty()) {
            throw OpackException("OPACK has ${decoded.remaining.size} trailing bytes")
        }
        return decoded.value
    }

    fun decodePrefix(bytes: ByteArray): DecodeResult {
        if (bytes.isEmpty()) throw OpackException("OPACK payload is empty")
        val cursor = Cursor(bytes)
        val objectTable = ArrayList<Any?>()
        val value = readValue(cursor, objectTable)
        return DecodeResult(value, bytes.copyOfRange(cursor.position, bytes.size))
    }

    private fun encodeValue(value: Any?): ByteArray = when (value) {
        null -> byteArrayOf(0x04)
        is Boolean -> byteArrayOf(if (value) 0x01 else 0x02)
        is Byte -> encodeInteger(value.toLong())
        is Short -> encodeInteger(value.toLong())
        is Int -> encodeInteger(value.toLong())
        is Long -> encodeInteger(value)
        is BigInteger -> encodeInteger(value)
        is Float -> byteArrayOf(0x35) + littleEndian(value.toRawBits().toLong(), 4)
        is Double -> byteArrayOf(0x36) + littleEndian(value.toRawBits(), 8)
        is String -> encodeString(value)
        is ByteArray -> encodeData(value)
        is UUID -> byteArrayOf(0x05) + uuidBytes(value)
        is List<*> -> encodeArray(value)
        is Array<*> -> encodeArray(value.asList())
        is Map<*, *> -> encodeDictionary(value)
        else -> throw OpackException("Unsupported OPACK value type ${value::class.java.name}")
    }

    private fun encodeInteger(value: BigInteger): ByteArray {
        val longValue = value.toLong()
        if (BigInteger.valueOf(longValue) != value) {
            throw OpackException("OPACK integer is outside the signed 64-bit range")
        }
        return encodeInteger(longValue)
    }

    private fun encodeInteger(value: Long): ByteArray {
        if (value in 0..0x27) {
            return byteArrayOf((0x08 + value).toByte())
        }
        val width = when (value) {
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> 1
            in Short.MIN_VALUE..Short.MAX_VALUE -> 2
            in Int.MIN_VALUE..Int.MAX_VALUE -> 4
            else -> 8
        }
        val tag = when (width) {
            1 -> 0x30
            2 -> 0x31
            4 -> 0x32
            else -> 0x33
        }
        return byteArrayOf(tag.toByte()) + littleEndian(value, width)
    }

    private fun encodeString(value: String): ByteArray {
        val utf8 = value.toByteArray(StandardCharsets.UTF_8)
        if (utf8.size <= 0x20) {
            return byteArrayOf((0x40 + utf8.size).toByte()) + utf8
        }
        val lengthBytes = lengthBytes(utf8.size)
        return byteArrayOf((0x60 + lengthBytes.size).toByte()) +
            littleEndian(utf8.size.toLong(), lengthBytes.size) +
            utf8
    }

    private fun encodeData(value: ByteArray): ByteArray {
        if (value.size <= 0x20) {
            return byteArrayOf((0x70 + value.size).toByte()) + value
        }
        val lengthBytes = dataLengthBytes(value.size)
        return byteArrayOf((0x90 + lengthBytes.size).toByte()) +
            littleEndian(value.size.toLong(), lengthBytes.size) +
            value
    }

    private fun encodeArray(values: List<*>): ByteArray {
        val output = ArrayList<Byte>(values.size + 2)
        output.addAll(marker(0xd0, values.size).toList())
        values.forEach { output.addAll(encodeValue(it).toList()) }
        if (values.size >= 0x0f) output.add(0x03.toByte())
        return output.toByteArray()
    }

    private fun encodeDictionary(values: Map<*, *>): ByteArray {
        val output = ArrayList<Byte>(values.size * 2 + 2)
        output.addAll(marker(0xe0, values.size).toList())
        values.forEach { (key, value) ->
            output.addAll(encodeValue(key).toList())
            output.addAll(encodeValue(value).toList())
        }
        if (values.size >= 0x0f) output.add(0x03.toByte())
        return output.toByteArray()
    }

    private fun marker(high: Int, count: Int): ByteArray {
        require(count >= 0)
        return if (count < 0x0f) {
            byteArrayOf((high or count).toByte())
        } else {
            byteArrayOf((high or 0x0f).toByte())
        }
    }

    private fun lengthBytes(size: Int): ByteArray =
        ByteArray(
            when {
                size <= 0xff -> 1
                size <= 0xffff -> 2
                size <= 0xff_ffff -> 3
                else -> 4
            },
        )

    private fun dataLengthBytes(size: Int): ByteArray =
        ByteArray(
            when {
                size <= 0xff -> 1
                size <= 0xffff -> 2
                else -> 4
            },
        )

    private fun readValue(cursor: Cursor, objectTable: MutableList<Any?>): Any? {
        val tag = cursor.readU8()
        return when (tag) {
            0x01 -> true
            0x02 -> false
            0x04 -> null
            0x05 -> {
                val bytes = cursor.readBytes(16)
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                UUID(buffer.long, buffer.long).also(objectTable::add)
            }
            0x06 -> throw OpackException(
                "Unsupported OPACK absolute-time tag 0x06; use a typed CAF value instead",
            )
            in 0x08..0x2f -> (tag - 0x08).toLong()
            0x30, 0x31, 0x32, 0x33 -> {
                val width = 1 shl (tag and 0x0f)
                readSignedInteger(cursor, width).also(objectTable::add)
            }
            0x35 -> {
                val bits = readUnsignedInteger(cursor, 4).toInt()
                Float.fromBits(bits).also(objectTable::add)
            }
            0x36 -> {
                val bits = readUnsignedInteger(cursor, 8)
                Double.fromBits(bits).also(objectTable::add)
            }
            in 0x40..0x60 -> {
                readString(cursor, tag - 0x40).also(objectTable::add)
            }
            0x61, 0x62, 0x63, 0x64 -> {
                val width = tag - 0x60
                readString(cursor, cursor.readLength(width)).also(objectTable::add)
            }
            in 0x70..0x90 -> {
                cursor.readBytes(tag - 0x70).also(objectTable::add)
            }
            0x91, 0x92, 0x93, 0x94 -> {
                val width = 1 shl (tag - 0x91)
                cursor.readBytes(cursor.readLength(width)).also(objectTable::add)
            }
            in 0xa0..0xc0 -> {
                val index = tag - 0xa0
                objectTable.getOrNull(index)
                    ?: throw OpackException("OPACK object reference $index is out of range")
            }
            0xc1, 0xc2, 0xc3, 0xc4 -> {
                val index = cursor.readLength(tag - 0xc0)
                objectTable.getOrNull(index)
                    ?: throw OpackException("OPACK object reference $index is out of range")
            }
            in 0xd0..0xdf -> readArray(cursor, tag and 0x0f, objectTable)
            in 0xe0..0xef -> readDictionary(cursor, tag and 0x0f, objectTable)
            else -> throw OpackException("Unknown OPACK tag 0x${tag.toString(16).padStart(2, '0')}")
        }
    }

    private fun readArray(
        cursor: Cursor,
        count: Int,
        objectTable: MutableList<Any?>,
    ): List<Any?> {
        val values = ArrayList<Any?>(if (count == 0x0f) 16 else count)
        if (count == 0x0f) {
            while (true) {
                if (cursor.peekU8() == 0x03) {
                    cursor.readU8()
                    break
                }
                values += readValue(cursor, objectTable)
            }
        } else {
            repeat(count) { values += readValue(cursor, objectTable) }
        }
        return values
    }

    private fun readDictionary(
        cursor: Cursor,
        count: Int,
        objectTable: MutableList<Any?>,
    ): Map<Any?, Any?> {
        val values = LinkedHashMap<Any?, Any?>(if (count == 0x0f) 16 else count)
        if (count == 0x0f) {
            while (true) {
                if (cursor.peekU8() == 0x03) {
                    cursor.readU8()
                    break
                }
                val key = readValue(cursor, objectTable)
                val value = readValue(cursor, objectTable)
                values[key] = value
            }
        } else {
            repeat(count) {
                val key = readValue(cursor, objectTable)
                val value = readValue(cursor, objectTable)
                values[key] = value
            }
        }
        return values
    }

    private fun readString(cursor: Cursor, length: Int): String =
        String(cursor.readBytes(length), StandardCharsets.UTF_8)

    private fun readSignedInteger(cursor: Cursor, width: Int): Long {
        var value = readUnsignedInteger(cursor, width)
        if (width < 8 && (cursor.bytes[cursor.position - 1].toInt() and 0x80) != 0) {
            value = value or (-1L shl (width * 8))
        }
        return value
    }

    private fun readUnsignedInteger(cursor: Cursor, width: Int): Long {
        if (width !in 1..8) throw OpackException("Invalid OPACK integer width $width")
        var value = 0L
        repeat(width) { index ->
            value = value or ((cursor.readU8().toLong() and 0xff) shl (index * 8))
        }
        return value
    }

    private fun littleEndian(value: Long, width: Int): ByteArray {
        val output = ByteArray(width)
        var remaining = value
        for (index in 0 until width) {
            output[index] = remaining.toByte()
            remaining = remaining ushr 8
        }
        return output
    }

    private fun uuidBytes(value: UUID): ByteArray =
        ByteBuffer.allocate(16)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(value.mostSignificantBits)
            .putLong(value.leastSignificantBits)
            .array()

    private class Cursor(val bytes: ByteArray) {
        var position: Int = 0
            private set

        fun readU8(): Int {
            if (position >= bytes.size) throw OpackException("Unexpected end of OPACK at byte $position")
            return bytes[position++].toInt() and 0xff
        }

        fun peekU8(): Int {
            if (position >= bytes.size) throw OpackException("Unexpected end of OPACK at byte $position")
            return bytes[position].toInt() and 0xff
        }

        fun readBytes(length: Int): ByteArray {
            if (length < 0 || position > bytes.size - length) {
                throw OpackException(
                    "OPACK expects $length bytes at $position but only ${bytes.size - position} remain",
                )
            }
            return bytes.copyOfRange(position, position + length).also {
                position += length
            }
        }

        fun readLength(width: Int): Int {
            val value = readUnsignedInteger(this, width)
            if (value < 0 || value > Int.MAX_VALUE) {
                throw OpackException("OPACK length $value exceeds the JVM byte-array limit")
            }
            return value.toInt()
        }
    }
}
