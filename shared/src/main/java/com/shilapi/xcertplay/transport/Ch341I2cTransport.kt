package com.shilapi.xcertplay.transport

import java.io.ByteArrayOutputStream

/**
 * Blocking CH341 implementation of [I2cTransport]. One transport owns one USB session and
 * serializes its configure/write/read sequence so stream packets cannot interleave.
 */
class Ch341I2cTransport(
    private val session: Ch341UsbSession,
    private val speed: Ch341I2cSpeed = Ch341I2cSpeed.KHZ_100,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : I2cTransport {
    private val lock = Any()

    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    override fun transaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray {
        val stream = Ch341I2cStreamEncoder.transaction(address7Bit, writeData, readLength)
        val transactionTimeoutMillis = maxOf(timeoutMillis, minimumTransferTimeoutMillis(writeData.size, readLength))
        return synchronized(lock) {
            configure()
            // The 2.0C revision NACKs the read address when a STOP is followed by a START any
            // sooner than about 3 ms, and the CH341 then answers the read request with 0xFF
            // padding. Hold the bus idle across the STOP/START boundary of a register read.
            if (writeData.isEmpty() && readLength > 0) {
                sleepBusIdle(SELECT_TO_READ_GAP_MILLIS)
            }
            val response = ByteArrayOutputStream(readLength)
            var offset = 0
            while (offset < stream.size) {
                val end = minOf(offset + Ch341I2cStreamEncoder.MAX_STREAM_PACKET_BYTES, stream.size)
                val segment = stream.copyOfRange(offset, end)
                session.bulkWrite(segment, transactionTimeoutMillis)
                val (writtenBytes, dataBytes) = instructionLengths(segment)
                if (writtenBytes + dataBytes > 0) {
                    // The worst case is one ACK/NACK status byte per byte written, but the controller
                    // may answer a multi-byte OUT with fewer or with none. Writes always precede the
                    // read commands, so the answer is split from its tail instead of trusting a fixed
                    // status count. A write-only packet is allowed to go unanswered entirely.
                    val received = session.bulkReadAtMost(
                        maxLength = writtenBytes + dataBytes,
                        firstPacketMillis = if (dataBytes > 0) {
                            transactionTimeoutMillis
                        } else {
                            minOf(RESPONSE_QUIET_MILLIS, transactionTimeoutMillis)
                        },
                        quietMillis = RESPONSE_QUIET_MILLIS,
                        allowEmpty = dataBytes == 0,
                    )
                    if (received.size < dataBytes) {
                        throw I2cTransportException.DeviceUnavailable(
                            "CH341 stream packet answered ${received.size} bytes; " +
                                "expected $dataBytes data bytes",
                        )
                    }
                    val dataStart = received.size - dataBytes
                    checkStatusBytes(received, dataStart)
                    if (dataBytes > 0) {
                        response.write(received, dataStart, dataBytes)
                    }
                }
                offset = end
            }
            val bytes = response.toByteArray()
            if (bytes.size != readLength) {
                throw I2cTransportException.Protocol(
                    "CH341 transaction returned ${bytes.size} bytes; expected $readLength",
                )
            }
            bytes
        }
    }

    /**
     * Pulses one D0..D5 output low, then leaves the pin actively driven instead of floating.
     *
     * The 2.0C reset notes require RST to be held low or actively driven high for the whole reset
     * window, so the release-to-input form left the address selection undefined on any board
     * without a pull-up on RST. The low period is timed on the host because the CH341 UIO delay
     * command cannot span the MFi reset window; the level itself sits in the CH341 output latch, so
     * USB scheduling cannot shorten it.
     *
     * [driveHigh] leaves RST driven high, which selects coprocessor address 0x11. Do not tie RST
     * straight to GND while driving high -- drive into the target's own pull-up, or pass
     * [driveHigh] = false to release the pin and let that pull-up set the idle level.
     */
    fun pulseActiveLowReset(
        gpio: Int,
        lowMillis: Long = MFI_RESET_LOW_MILLIS,
        driveHigh: Boolean = true,
    ) {
        require(gpio in 0..5) { "CH341 output GPIO must be D0..D5" }
        require(lowMillis > 0) { "reset pulse must be positive" }
        val pinMask = 1 shl gpio
        val driveLowStream = byteArrayOf(
            UIO_STREAM.toByte(),
            UIO_OUT.toByte(), // preload every output latch low while pins are inputs
            (UIO_DIR or pinMask).toByte(), // D<gpio> becomes an output driving low
            UIO_END.toByte(),
        )
        val releaseStream = byteArrayOf(
            UIO_STREAM.toByte(),
            if (driveHigh) (UIO_OUT or pinMask).toByte() else UIO_OUT.toByte(),
            if (driveHigh) (UIO_DIR or pinMask).toByte() else UIO_DIR.toByte(),
            UIO_END.toByte(),
        )
        synchronized(lock) {
            session.bulkWrite(driveLowStream, timeoutMillis)
            sleepBusIdle(lowMillis)
            session.bulkWrite(releaseStream, timeoutMillis)
            sleepBusIdle(MFI_RESET_STARTUP_MILLIS)
        }
    }

    /**
     * Splits one CH341 stream packet into the bytes it puts on the wire and the bytes it reads back.
     *
     * A reply of `0x80|n` writes `n` bytes to the bus -- address byte included -- and `0xc0|n`
     * requests `n` bytes, with a zero count meaning a single byte. How many ACK/NACK status bytes
     * the controller prepends is a firmware detail: a bare `0x80` reply is answered with one status
     * byte, while a multi-byte reply may be answered with one status byte per written byte or with
     * none. This count is therefore only used as an upper bound; [transaction] aligns the answer
     * from its tail, which keeps the read data correct either way.
     */
    private fun instructionLengths(segment: ByteArray): InstructionLengths {
        var index = 1 // 0xAA stream marker
        var writtenBytes = 0
        var dataBytes = 0
        while (index < segment.size) {
            val command = segment[index].toInt() and 0xff
            index += 1
            when {
                command == STREAM_END -> return InstructionLengths(writtenBytes, dataBytes)
                command == START || command == STOP -> Unit
                command in WRITE_MIN..WRITE_MAX -> {
                    val written = encodedLength(command)
                    // Only the bare `0x80` command is answered with one ACK/NACK status byte per
                    // written byte. A batched `0x80|n` (n >= 1) suppresses those status bytes, so it
                    // contributes none -- otherwise the answer is over-requested by exactly the
                    // number of status bytes and the last packet waits out its quiet timeout.
                    if (command and LENGTH_MASK == 0) writtenBytes += written
                    index += written
                }
                command in READ_MIN..READ_MAX -> dataBytes += encodedLength(command)
                command in SET_MIN..SET_MAX -> Unit
                else -> throw I2cTransportException.Protocol(
                    "Unexpected CH341 I2C stream command 0x${command.toString(16)}",
                )
            }
            if (index > segment.size) {
                throw I2cTransportException.Protocol("Truncated CH341 I2C stream command")
            }
        }
        return InstructionLengths(writtenBytes, dataBytes)
    }

    /** The count field of an OUT/IN command; the controller treats a zero count as one byte. */
    private fun encodedLength(command: Int): Int {
        val encoded = command and LENGTH_MASK
        return if (encoded == 0) 1 else encoded
    }

    /** Rejects a stream answer whose leading ACK/NACK status bytes report an I2C NACK. */
    private fun checkStatusBytes(received: ByteArray, statusCount: Int) {
        for (offset in 0 until minOf(statusCount, received.size)) {
            if (received[offset].toInt() and ACK_BIT != 0) {
                throw I2cTransportException.Nack(
                    "CH341 reported an I2C NACK on byte $offset of this stream packet",
                )
            }
        }
    }

    private fun sleepBusIdle(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw I2cTransportException.DeviceUnavailable(
                "Interrupted while holding the I2C bus idle",
                interrupted,
            )
        }
    }

    private data class InstructionLengths(val writtenBytes: Int, val dataBytes: Int)

    private fun configure() {
        session.bulkWrite(Ch341I2cStreamEncoder.configuration(speed), timeoutMillis)
    }

    /**
     * A simple lower bound for a complete I2C transaction at the selected clock, plus fixed USB
     * scheduling margin. The caller's configured timeout can still be longer. This is not a
     * hardware-performance claim; it only avoids rejecting a legal maximum-sized transfer under
     * the old one-second default before its nominal wire time has elapsed.
     */
    private fun minimumTransferTimeoutMillis(writeLength: Int, readLength: Int): Int {
        val i2cBytes = writeLength.toLong() + readLength + I2C_TRANSACTION_OVERHEAD_BYTES
        val wireMillis = (i2cBytes * BITS_PER_I2C_BYTE * MILLIS_PER_SECOND + speed.bitsPerSecond - 1) /
            speed.bitsPerSecond
        return (wireMillis + TRANSFER_MARGIN_MILLIS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 1_000
        const val I2C_TRANSACTION_OVERHEAD_BYTES = 8L
        const val BITS_PER_I2C_BYTE = 9L
        const val MILLIS_PER_SECOND = 1_000L
        const val TRANSFER_MARGIN_MILLIS = 1_000L
        const val STREAM_END = 0x00
        const val START = 0x74
        const val STOP = 0x75
        const val SET_MIN = 0x60
        const val SET_MAX = 0x63
        const val WRITE_MIN = 0x80
        const val WRITE_MAX = 0xbf
        const val READ_MIN = 0xc0
        const val READ_MAX = 0xff
        const val LENGTH_MASK = 0x3f
        const val ACK_BIT = 0x80
        const val SELECT_TO_READ_GAP_MILLIS = 5L
        const val RESPONSE_QUIET_MILLIS = 25
        const val UIO_STREAM = 0xab
        const val UIO_OUT = 0x80
        const val UIO_DIR = 0x40
        const val UIO_END = 0x20
        const val MFI_RESET_LOW_MILLIS = 10L
        const val MFI_RESET_STARTUP_MILLIS = 11L
    }
}
