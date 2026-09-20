package com.shilapi.xcertplay.airplay

/**
 * Minimal Apple binary property list (bplist00) codec for the CarPlay control channel.
 *
 * It covers the subset the stack emits and reads: dictionaries, arrays, ASCII and UTF-16
 * strings, raw data, non-negative integers, 32/64-bit reals, and booleans. Dictionary keys are
 * serialized in insertion order, matching the reference implementation this stack targets.
 */
object BplistCodec {
    private val magic = "bplist00".toByteArray(Charsets.US_ASCII)

    /** Eight-byte magic plus the 32-byte trailer. */
    private const val MIN_FILE_BYTES = 40

    /** The trailer's offset/ref widths are used as read widths, so they must be sane. */
    private const val MAX_TRAILER_WIDTH = 8

    /** Bounds the object graph walk so a referencing cycle cannot exhaust the stack. */
    private const val MAX_NESTING_DEPTH = 64

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size >= MIN_FILE_BYTES) { "bplist: too short" }
        require(bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "bplist: bad magic" }

        val trailer = bytes.size - 32
        val offsetSize = bytes[trailer + 6].toInt() and 0xff
        val refSize = bytes[trailer + 7].toInt() and 0xff
        require(offsetSize in 1..MAX_TRAILER_WIDTH) {
            "bplist: offsetIntSize $offsetSize is not in 1..$MAX_TRAILER_WIDTH"
        }
        require(refSize in 1..MAX_TRAILER_WIDTH) {
            "bplist: objectRefSize $refSize is not in 1..$MAX_TRAILER_WIDTH"
        }

        val numObjects = readBigEndianLong(bytes, (trailer + 8).toLong(), 8)
        val topObject = readBigEndianLong(bytes, (trailer + 16).toLong(), 8)
        val offsetTable = readBigEndianLong(bytes, (trailer + 24).toLong(), 8)

        // The trailer is peer input. Without this bound a declared object count becomes a huge
        // LongArray allocation before a single object is read, which surfaces as an Error the
        // callers' `catch (Exception)` cannot see.
        require(numObjects in 1..(bytes.size.toLong() / offsetSize)) {
            "bplist: numObjects $numObjects does not fit the ${bytes.size}-byte file"
        }
        require(topObject in 0 until numObjects) {
            "bplist: topObject $topObject is outside 0..${numObjects - 1}"
        }

        val offsets = LongArray(numObjects.toInt())
        for (index in offsets.indices) {
            offsets[index] = readBigEndianLong(bytes, offsetTable + index.toLong() * offsetSize, offsetSize)
            require(offsets[index] in 0 until bytes.size.toLong()) {
                "bplist: object $index offset ${offsets[index]} is outside the ${bytes.size}-byte file"
            }
        }
        return readObject(bytes, offsets, refSize, topObject.toInt(), 0)
    }

    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<Node>()

        fun add(value: Any?): Int {
            val index = nodes.size
            nodes.add(Leaf(ByteArray(0)))
            when (value) {
                is Boolean -> nodes[index] = Leaf(byteArrayOf(if (value) 0x09 else 0x08))
                is java.math.BigInteger -> {
                    require(value.signum() >= 0) { "bplist: negative integers are not supported" }
                    require(value.bitLength() <= 64) { "bplist: integer exceeds 64 bits" }
                    nodes[index] = Leaf(encodeUnsignedInt(value))
                }
                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    nodes[index] = if (number >= 0) {
                        Leaf(encodeInt(number))
                    } else {
                        Leaf(byteArrayOf(0x23) + bigEndian(number.toDouble().toRawBits(), 8))
                    }
                }
                is Float, is Double -> {
                    val number = (value as Number).toDouble()
                    nodes[index] = Leaf(byteArrayOf(0x23) + bigEndian(number.toRawBits(), 8))
                }
                is String -> nodes[index] = Leaf(encodeString(value))
                is ByteArray -> nodes[index] = Leaf(marker(0x4, value.size) + value)
                is List<*> -> {
                    val refs = value.map { add(it) }.toIntArray()
                    nodes[index] = Container(marker(0xa, value.size), refs)
                }
                is Map<*, *> -> {
                    val entries = value.entries.toList()
                    val keyRefs = entries.map { add(it.key.toString()) }.toIntArray()
                    val valueRefs = entries.map { add(it.value) }.toIntArray()
                    nodes[index] = Container(marker(0xd, entries.size), keyRefs + valueRefs)
                }
                else -> throw IllegalArgumentException("bplist: unsupported value $value")
            }
            return index
        }

        val topIndex = add(root)
        val refSize = when {
            nodes.size > 0xffff -> 4
            nodes.size > 0xff -> 2
            else -> 1
        }

        val parts = ArrayList<ByteArray>(nodes.size + 2)
        parts.add(magic)
        var cursor = magic.size
        val offsets = ArrayList<Int>(nodes.size)
        for (node in nodes) {
            offsets.add(cursor)
            val serialized = when (node) {
                is Leaf -> node.body
                is Container -> node.head + refBytes(node.refs, refSize)
            }
            parts.add(serialized)
            cursor += serialized.size
        }

        val offsetTableOffset = cursor
        val offsetSize = when {
            cursor > 0xffff -> 4
            cursor > 0xff -> 2
            else -> 1
        }
        for (offset in offsets) parts.add(bigEndian(offset.toLong(), offsetSize))

        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        writeBigEndian(trailer, 8, nodes.size.toLong(), 8)
        writeBigEndian(trailer, 16, topIndex.toLong(), 8)
        writeBigEndian(trailer, 24, offsetTableOffset.toLong(), 8)
        parts.add(trailer)
        return concatBytes(*parts.toTypedArray())
    }

    private fun readObject(
        bytes: ByteArray,
        offsets: LongArray,
        refSize: Int,
        index: Int,
        depth: Int,
    ): Any? {
        // A self-referencing (or mutually referencing) object graph would otherwise recurse until
        // the stack is exhausted, which surfaces as a StackOverflowError rather than an Exception.
        require(depth <= MAX_NESTING_DEPTH) {
            "bplist: object graph nests deeper than $MAX_NESTING_DEPTH"
        }
        require(index in offsets.indices) {
            "bplist: object reference $index is outside 0..${offsets.size - 1}"
        }
        var position = offsets[index].toInt()
        require(position < bytes.size) { "bplist: object $index starts past the end of the file" }
        val markerByte = bytes[position].toInt() and 0xff
        val type = markerByte ushr 4
        val nibble = markerByte and 0x0f
        position++

        fun readCount(): Int {
            if (nibble != 0x0f) return nibble
            require(position < bytes.size) { "bplist: truncated count marker" }
            val sizeMarker = bytes[position].toInt() and 0xff
            position++
            val widthNibble = sizeMarker and 0x0f
            // The low nibble is the base-2 logarithm of the count width; Apple emits 0..3
            // (1, 2, 4 or 8 bytes). Anything larger is malformed and would read a huge field.
            require(widthNibble <= 3) { "bplist: count width nibble $widthNibble is not supported" }
            val intBytes = 1 shl widthNibble
            val count = readBigEndianLong(bytes, position.toLong(), intBytes)
            require(count in 0..Int.MAX_VALUE.toLong()) { "bplist: count $count is out of range" }
            position += intBytes
            return count.toInt()
        }

        /** Rejects a declared element count that cannot fit in the bytes that actually remain. */
        fun requireRoom(count: Int, elementBytes: Int, what: String) {
            val available = bytes.size - position
            require(count <= available / elementBytes) {
                "bplist: $what declares $count elements but only $available bytes remain"
            }
        }

        /** Confines a read of [count] bytes starting at [position] to the buffer. */
        fun requireAvailable(count: Int, what: String) {
            require(count >= 0 && position.toLong() + count <= bytes.size.toLong()) {
                "bplist: $what needs $count bytes at $position but the file is ${bytes.size} bytes"
            }
        }

        return when (type) {
            0x0 -> when (nibble) {
                0x08 -> false
                0x09 -> true
                else -> throw IllegalArgumentException("bplist: unsupported primitive 0x0$nibble")
            }
            0x1 -> {
                // 1, 2, 4, 8 or 16 bytes. Sixteen is not exotic: an encoder that treats the value
                // as signed has to widen anything above 2^63-1 to 16 bytes, and Python's plistlib
                // does exactly that. Only the low 64 bits fit a Long, which is what this decoder
                // has always returned for such an object, so read the trailing eight bytes rather
                // than refusing the whole plist — a refusal here fails SETUP with 400.
                require(nibble <= 4) { "bplist: integer width nibble $nibble is not supported" }
                val size = 1 shl nibble
                requireAvailable(size, "integer")
                readBigEndianLong(
                    bytes,
                    position.toLong() + (size - 8).coerceAtLeast(0),
                    minOf(size, 8),
                )
            }
            0x2 -> {
                val size = 1 shl nibble
                when (size) {
                    4 -> Float.fromBits(readBigEndianLong(bytes, position.toLong(), size).toInt())
                    8 -> Double.fromBits(readBigEndianLong(bytes, position.toLong(), size))
                    else -> throw IllegalArgumentException("bplist: unsupported real size $size")
                }
            }
            0x4 -> {
                val count = readCount()
                requireRoom(count, 1, "data")
                bytes.copyOfRange(position, position + count)
            }
            0x5 -> {
                val count = readCount()
                requireRoom(count, 1, "string")
                String(bytes, position, count, Charsets.US_ASCII)
            }
            0x6 -> {
                val count = readCount()
                requireRoom(count, 2, "UTF-16 string")
                String(bytes, position, count * 2, Charsets.UTF_16BE)
            }
            0xa -> {
                val count = readCount()
                requireRoom(count, refSize, "array")
                val array = ArrayList<Any?>(count)
                for (i in 0 until count) {
                    val reference = readBigEndianLong(bytes, position + i.toLong() * refSize, refSize).toInt()
                    array.add(readObject(bytes, offsets, refSize, reference, depth + 1))
                }
                array
            }
            0xd -> {
                val count = readCount()
                requireRoom(count, 2 * refSize, "dictionary")
                val dict = LinkedHashMap<String, Any?>(count)
                for (i in 0 until count) {
                    val keyReference = readBigEndianLong(bytes, position + i.toLong() * refSize, refSize).toInt()
                    val valueReference =
                        readBigEndianLong(bytes, position + (count + i).toLong() * refSize, refSize).toInt()
                    dict[readObject(bytes, offsets, refSize, keyReference, depth + 1).toString()] =
                        readObject(bytes, offsets, refSize, valueReference, depth + 1)
                }
                dict
            }
            else -> throw IllegalArgumentException("bplist: unsupported object type 0x${type.toString(16)}")
        }
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.all { it.code <= 0x7f }
        return if (ascii) {
            marker(0x5, value.length) + value.toByteArray(Charsets.US_ASCII)
        } else {
            marker(0x6, value.length) + value.toByteArray(Charsets.UTF_16BE)
        }
    }

    private fun encodeInt(number: Long): ByteArray {
        val size = when {
            number > 0xffffffffL -> 8
            number > 0xffffL -> 4
            number > 0xffL -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf((0x10 or log).toByte()) + bigEndian(number, size)
    }

    private fun encodeUnsignedInt(number: java.math.BigInteger): ByteArray {
        val size = when {
            number.bitLength() > 32 -> 8
            number.bitLength() > 16 -> 4
            number.bitLength() > 8 -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        // BigInteger.toByteArray() is a minimal two's-complement encoding, so a positive value
        // whose top bit is set carries an extra sign byte. It can therefore be shorter than `size`
        // (e.g. 2^32 encodes to five bytes, not eight), which made a plain slice start at a
        // negative index. Zero-extend to exactly `size` bytes instead.
        val raw = number.toByteArray()
        val low = raw.copyOfRange((raw.size - size).coerceAtLeast(0), raw.size)
        val output = ByteArray(size)
        low.copyInto(output, size - low.size)
        return byteArrayOf((0x10 or log).toByte()) + output
    }

    private fun marker(type: Int, count: Int): ByteArray {
        if (count < 0x0f) return byteArrayOf(((type shl 4) or count).toByte())
        val size = when {
            count > 0xffff -> 4
            count > 0xff -> 2
            else -> 1
        }
        val log = when (size) {
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf(((type shl 4) or 0x0f).toByte(), (0x10 or log).toByte()) +
            bigEndian(count.toLong(), size)
    }

    private fun refBytes(refs: IntArray, refSize: Int): ByteArray {
        val output = ByteArray(refs.size * refSize)
        for (index in refs.indices) writeBigEndian(output, index * refSize, refs[index].toLong(), refSize)
        return output
    }

    private fun bigEndian(value: Long, size: Int): ByteArray {
        val output = ByteArray(size)
        writeBigEndian(output, 0, value, size)
        return output
    }

    private fun writeBigEndian(target: ByteArray, offset: Int, value: Long, size: Int) {
        var current = value
        for (index in size - 1 downTo 0) {
            target[offset + index] = (current and 0xff).toByte()
            current = current ushr 8
        }
    }

    private fun readBigEndianLong(bytes: ByteArray, offset: Long, size: Int): Long {
        // `offset` arrives from the trailer and from object references, so it is range-checked as a
        // Long before any narrowing, and the read is confined to the buffer.
        require(size in 0..8) { "bplist: read width $size is not in 0..8" }
        require(offset >= 0 && offset + size <= bytes.size.toLong()) {
            "bplist: read of $size bytes at $offset is outside the ${bytes.size}-byte file"
        }
        var value = 0L
        val base = offset.toInt()
        for (index in 0 until size) {
            value = (value shl 8) or (bytes[base + index].toLong() and 0xffL)
        }
        return value
    }

    private sealed class Node
    private class Leaf(val body: ByteArray) : Node()
    private class Container(val head: ByteArray, val refs: IntArray) : Node()
}
