package com.shilapi.xcertplay.transport

/** CH341 I2C clock selections documented by the project's CH341 interface baseline. */
enum class Ch341I2cSpeed(internal val command: Int, internal val bitsPerSecond: Int) {
    KHZ_20(0x60, 20_000),
    KHZ_100(0x61, 100_000),
    KHZ_400(0x62, 400_000),
    KHZ_750(0x63, 750_000),
}

/**
 * Encodes a complete CH341 I2C transaction.
 *
 * Every byte written to the bus is emitted as its own bare `0x80` command. A bare `0x80` is the
 * only form the controller answers with an ACK/NACK status byte, which is what lets
 * [Ch341I2cTransport] see a NACKed register select; a batched `0x80|n` write is answered with no
 * status bytes at all. The number of written bytes is unchanged, so the transport's
 * "one status byte per written byte" model stays exact.
 *
 * A transaction may contain several 32-byte CH341 stream segments. Intermediate segments end
 * with `00` and padding, but deliberately omit STOP so the I2C transaction continues in the next
 * segment. The final segment contains STOP followed by `00`.
 */
object Ch341I2cStreamEncoder {
    const val MAX_STREAM_PACKET_BYTES = 32
    /** Matches the bounded I2C message size accepted by [LinuxI2cTransport]. */
    const val MAX_TRANSACTION_DATA_BYTES = 0xffff
    const val MAX_READ_BYTES = MAX_TRANSACTION_DATA_BYTES

    fun configuration(speed: Ch341I2cSpeed): ByteArray = byteArrayOf(
        STREAM_START.toByte(),
        speed.command.toByte(),
        STREAM_END.toByte(),
    )

    fun transaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray {
        validateRequest(address7Bit, writeData.size, readLength)
        legacyTransaction(address7Bit, writeData, readLength)?.let { return it }

        val stream = SegmentedStream()
        if (writeData.isNotEmpty()) {
            stream.startAndWrite(addressByte(address7Bit, read = false), writeData)
        }
        if (readLength > 0) {
            stream.startAndWrite(addressByte(address7Bit, read = true), byteArrayOf())
            stream.read(readLength)
        }
        return stream.finish()
    }

    private fun legacyTransaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray? {
        if (readLength > MAX_READ_BLOCK_BYTES || writeData.size + 1 > MAX_WRITE_COMMAND_BYTES) return null

        val bytes = ArrayList<Byte>(MAX_STREAM_PACKET_BYTES)
        fun add(value: Int) {
            bytes += value.toByte()
        }
        fun write(address: Byte, data: ByteArray) {
            // One bare `0x80` per byte: the controller answers that form with ACK/NACK status.
            add(WRITE)
            add(address.toInt() and 0xff)
            data.forEach {
                add(WRITE)
                add(it.toInt() and 0xff)
            }
        }

        add(STREAM_START)
        if (writeData.isNotEmpty()) {
            add(START)
            write(addressByte(address7Bit, read = false), writeData)
        }
        if (readLength > 0) {
            add(START)
            write(addressByte(address7Bit, read = true), byteArrayOf())
            if (readLength > 1) add(READ + readLength - 1)
            add(READ)
        }
        add(STOP)
        add(STREAM_END)
        return bytes.takeIf { it.size <= MAX_STREAM_PACKET_BYTES }?.toByteArray()
    }

    private fun validateRequest(address7Bit: Int, writeLength: Int, readLength: Int) {
        if (address7Bit !in 0..0x7f) {
            throw I2cTransportException.InvalidRequest("I2C address must be a 7-bit value")
        }
        if (readLength < 0) {
            throw I2cTransportException.InvalidRequest("Read length must not be negative")
        }
        if (writeLength == 0 && readLength == 0) {
            throw I2cTransportException.InvalidRequest("I2C transaction must read or write data")
        }
        if (writeLength > MAX_TRANSACTION_DATA_BYTES || readLength > MAX_TRANSACTION_DATA_BYTES) {
            throw I2cTransportException.InvalidRequest(
                "CH341 transactions support at most $MAX_TRANSACTION_DATA_BYTES bytes per read or write",
            )
        }
    }

    private fun addressByte(address7Bit: Int, read: Boolean): Byte =
        ((address7Bit shl 1) or if (read) 1 else 0).toByte()

    private class SegmentedStream {
        private val result = ArrayList<Byte>()
        private var segment = ArrayList<Byte>(MAX_STREAM_PACKET_BYTES)

        init {
            segment += STREAM_START.toByte()
        }

        fun startAndWrite(address: Byte, data: ByteArray) {
            if (segment.size + START_AND_ADDRESS_BYTES + STREAM_END_BYTES > MAX_STREAM_PACKET_BYTES) {
                finishIntermediate()
            }
            segment += START.toByte()
            write(address, data)
        }

        fun write(address: Byte, data: ByteArray) {
            if (data.isEmpty()) {
                // Address-only write: the read-address setup of a segmented read. Emit it as ONE
                // batched `0x80|1` command, which suppresses the ACK/NACK status byte. This is the
                // form the hardware-proven reference implementation uses: mfi3.py's seg_read_packets
                // emits `[START, OUT | 1, address]` and then requires the accumulated reply to total
                // exactly the requested data length, with no status byte in front of it. Emitting a
                // bare `0x80` here instead adds a status byte and shifts the whole answer.
                command(byteArrayOf((WRITE + 1).toByte(), address))
                return
            }
            // Writes that carry data keep one bare `0x80` per byte so that
            // [Ch341I2cTransport] can still check each byte's ACK/NACK status.
            command(byteArrayOf(WRITE.toByte(), address))
            data.forEach { value ->
                command(byteArrayOf(WRITE.toByte(), value))
            }
        }

        fun read(length: Int) {
            var remaining = length
            while (remaining > MAX_READ_BLOCK_BYTES) {
                command(byteArrayOf((READ + MAX_READ_BLOCK_BYTES).toByte()))
                finishIntermediate()
                remaining -= MAX_READ_BLOCK_BYTES
            }
            if (remaining > 1) command(byteArrayOf((READ + remaining - 1).toByte()))
            command(byteArrayOf(READ.toByte()))
        }

        fun finish(): ByteArray {
            if (segment.size + STOP_AND_END_BYTES > MAX_STREAM_PACKET_BYTES) finishIntermediate()
            segment += STOP.toByte()
            segment += STREAM_END.toByte()
            result += segment
            return result.toByteArray()
        }

        private fun command(bytes: ByteArray) {
            if (segment.size + bytes.size + STREAM_END_BYTES > MAX_STREAM_PACKET_BYTES) finishIntermediate()
            segment += bytes.toList()
        }

        private fun finishIntermediate() {
            segment += STREAM_END.toByte()
            while (segment.size < MAX_STREAM_PACKET_BYTES) segment += 0
            result += segment
            segment = arrayListOf(STREAM_START.toByte())
        }
    }

    private const val STREAM_START = 0xaa
    private const val START = 0x74
    private const val WRITE = 0x80
    private const val READ = 0xc0
    private const val STOP = 0x75
    private const val STREAM_END = 0x00
    private const val STREAM_END_BYTES = 1
    private const val STOP_AND_END_BYTES = 2
    private const val START_AND_ADDRESS_BYTES = 3
    private const val MAX_WRITE_COMMAND_BYTES = 0x3f
    private const val MAX_READ_BLOCK_BYTES = 32
}
