package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.transport.I2cTransport
import com.shilapi.xcertplay.transport.I2cTransportException

enum class MfiCertificateType {
    MFI,
    BAA,
}

class BaaCertificatePair(leaf: ByteArray, intermediate: ByteArray) {
    val leaf: ByteArray = leaf.copyOf()
    val intermediate: ByteArray = intermediate.copyOf()

    init {
        require(this.leaf.isNotEmpty()) { "BAA leaf certificate must not be empty" }
        require(this.intermediate.isNotEmpty()) { "BAA intermediate certificate must not be empty" }
    }
}

/** Common certificate/signing contract implemented by local coprocessors and remote services. */
interface MfiAuthenticator {
    val certificateType: MfiCertificateType
        get() = MfiCertificateType.MFI

    fun protocolMajor(): Int

    fun readCertificate(
        maximumOutputLength: Int = MfiAuthenticationClient.DEFAULT_MAXIMUM_CERTIFICATE_OUTPUT_LENGTH,
    ): ByteArray

    fun signChallenge(challenge: ByteArray): ByteArray

    fun baaCertificates(): BaaCertificatePair =
        throw MfiInvalidDataException("Authenticator does not provide BAA certificates")
}

/**
 * Blocking register client for one MFi authentication coprocessor.
 *
 * It follows the LIVI I2C sequence: a register read is a write-only register selection (with a
 * STOP), followed by a separate read-only transaction.  Call it from a worker thread.
 */
class MfiAuthenticationClient(
    private val transport: I2cTransport,
    val address7Bit: Int,
) : MfiAuthenticator {
    override val certificateType: MfiCertificateType = MfiCertificateType.MFI

    init {
        require(address7Bit in 0x00..0x7f) { "address7Bit must be in 0x00..0x7f" }
    }

    /** Returns the raw value advertised by register 0x02; no protocol-major policy is imposed. */
    override fun protocolMajor(): Int = synchronized(COPROCESSOR_LOCK) {
        readByte(PROTOCOL_MAJOR_REGISTER)
    }

    /** Reads the certificate length from 0x30 and its body through 128-byte register windows. */
    override fun readCertificate(
        maximumOutputLength: Int,
    ): ByteArray = synchronized(COPROCESSOR_LOCK) {
        require(maximumOutputLength in 1..MAX_REGISTER_READ_BYTES) {
            "maximumOutputLength must be in 1..$MAX_REGISTER_READ_BYTES"
        }
        val length = readUnsignedBigEndianShort(CERTIFICATE_LENGTH_REGISTER)
        if (length !in 1..maximumOutputLength) {
            throw MfiInvalidDataException(
                "Certificate length $length is outside 1..$maximumOutputLength",
            )
        }
        val certificate = ByteArray(length)
        var offset = 0
        var register = CERTIFICATE_DATA_REGISTER
        while (offset < certificate.size) {
            val count = minOf(CERTIFICATE_REGISTER_WINDOW_BYTES, certificate.size - offset)
            readRegister(register, count).copyInto(certificate, offset)
            offset += count
            register += 1
        }
        certificate
    }

    /**
     * Writes a 1..128 byte challenge and returns the dynamically sized signature response.
     *
     * The protocol-major register intentionally does not participate in this sequence: LIVI's
     * implementation uses the same registers for every observed major version.
     */
    override fun signChallenge(challenge: ByteArray): ByteArray = synchronized(COPROCESSOR_LOCK) {
        if (challenge.size !in MINIMUM_CHALLENGE_BYTES..MAXIMUM_CHALLENGE_BYTES) {
            throw MfiInvalidDataException(
                "challenge must be $MINIMUM_CHALLENGE_BYTES..$MAXIMUM_CHALLENGE_BYTES bytes",
            )
        }
        val input = challenge.copyOf()
        writeUnsignedBigEndianShort(CHALLENGE_LENGTH_REGISTER, input.size)
        writeRegister(CHALLENGE_DATA_REGISTER, input)
        writeRegister(AUTH_CONTROL_STATUS_REGISTER, byteArrayOf(AUTH_START_COMMAND.toByte()))

        sleepMillis(INITIAL_AUTH_DELAY_MILLIS)
        val deadlineNanos = deadlineAfter(AUTH_TIMEOUT_MILLIS)
        while (true) {
            try {
                if (readByte(AUTH_CONTROL_STATUS_REGISTER) == AUTH_SUCCESS_STATUS) break
            } catch (_: I2cTransportException) {
                // A polling read can be transiently unavailable while the chip is computing.
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw MfiAuthenticationFailedException(bestEffortErrorCode())
            }
            sleepMillis(AUTH_POLL_MILLIS)
        }

        val length = readUnsignedBigEndianShort(RESPONSE_LENGTH_REGISTER)
        if (length !in 1..MAX_REGISTER_READ_BYTES) {
            throw MfiInvalidDataException("Invalid signature length $length")
        }
        readRegister(RESPONSE_DATA_REGISTER, length)
    }

    private fun bestEffortErrorCode(): Int? = try {
        selectRegister(ERROR_REGISTER)
        sleepMicros(REGISTER_READ_SETTLE_MICROS)
        val result = transport.transaction(address7Bit, ByteArray(0), 1)
        if (result.size == 1) result[0].toInt() and BYTE_MASK else null
    } catch (_: I2cTransportException) {
        null
    }

    private fun readUnsignedBigEndianShort(register: Int): Int {
        val bytes = readRegister(register, 2)
        return ((bytes[0].toInt() and BYTE_MASK) shl 8) or (bytes[1].toInt() and BYTE_MASK)
    }

    private fun writeUnsignedBigEndianShort(register: Int, value: Int) {
        require(value in 0..0xffff) { "value must be in 0..65535" }
        writeRegister(register, byteArrayOf((value ushr 8).toByte(), value.toByte()))
    }

    private fun readByte(register: Int): Int = readRegister(register, 1)[0].toInt() and BYTE_MASK

    private fun readRegister(register: Int, length: Int): ByteArray {
        require(length in 1..MAX_REGISTER_READ_BYTES) { "read length must be in 1..$MAX_REGISTER_READ_BYTES" }
        return retryIo("read at 0x${register.toString(16).padStart(2, '0')}") {
            // The coprocessor can be asleep when the register-select write arrives. CH341 does
            // not surface that I2C NACK separately, so retry the complete select/read pair; a
            // retry of only the pure-read half can otherwise keep reading the old register.
            selectRegisterOnce(register)
            // The coprocessor needs a brief settle time after the register-select write before
            // the selected register can be read. A back-to-back read over raw Linux I2C (sub-ms
            // turnaround) NACKs the read (surfaces as errno 5 / I2C_RDWR failure); on CH341 the
            // USB round-trip latency hid this. Measured on an Apple Accessories coprocessor: 0ms
            // fails every time, >=2ms reads reliably. retryIo still backstops a marginal read.
            sleepMicros(REGISTER_READ_SETTLE_MICROS)
            val result = transport.transaction(address7Bit, ByteArray(0), length)
            if (result.size != length) {
                throw MfiInvalidDataException(
                    "Register 0x${register.toString(16)} returned ${result.size} bytes; expected $length",
                )
            }
            result.copyOf()
        }
    }

    private fun selectRegister(register: Int) {
        retryIo("register select 0x${register.toString(16).padStart(2, '0')}") {
            selectRegisterOnce(register)
        }
    }

    private fun selectRegisterOnce(register: Int) {
        val result = transport.transaction(address7Bit, byteArrayOf(register.toByte()), 0)
        if (result.isNotEmpty()) {
            throw MfiInvalidDataException("Register select 0x${register.toString(16)} returned data")
        }
    }

    private fun writeRegister(register: Int, data: ByteArray) {
        val request = ByteArray(data.size + 1)
        request[0] = register.toByte()
        data.copyInto(request, destinationOffset = 1)
        retryIo("write at 0x${register.toString(16).padStart(2, '0')}") {
            val result = transport.transaction(address7Bit, request, 0)
            if (result.isNotEmpty()) {
                throw MfiInvalidDataException("Write to register 0x${register.toString(16)} returned data")
            }
        }
    }

    private fun <T> retryIo(operation: String, action: () -> T): T {
        val deadlineNanos = deadlineAfter(IO_RETRY_TIMEOUT_MILLIS)
        var lastFailure: I2cTransportException? = null
        while (true) {
            try {
                return action()
            } catch (failure: I2cTransportException) {
                lastFailure = failure
            }
            if (System.nanoTime() >= deadlineNanos) throw checkNotNull(lastFailure) { operation }
            sleepMicros(IO_RETRY_DELAY_MICROS)
        }
    }

    private fun deadlineAfter(millis: Long): Long {
        val now = System.nanoTime()
        val delta = millis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun sleepMicros(micros: Long) {
        try {
            Thread.sleep(micros / MICROS_PER_MILLISECOND, ((micros % MICROS_PER_MILLISECOND) * NANOS_PER_MICROSECOND).toInt())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MfiAuthenticationFailedException(null, interrupted)
        }
    }

    private fun sleepMillis(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MfiAuthenticationFailedException(null, interrupted)
        }
    }

    companion object {
        private val COPROCESSOR_LOCK = Any()
        const val DEFAULT_MAXIMUM_CERTIFICATE_OUTPUT_LENGTH = 65_525
        private const val MAX_REGISTER_READ_BYTES = 65_525
        private const val BYTE_MASK = 0xff
        private const val PROTOCOL_MAJOR_REGISTER = 0x02
        private const val ERROR_REGISTER = 0x05
        private const val AUTH_CONTROL_STATUS_REGISTER = 0x10
        private const val RESPONSE_LENGTH_REGISTER = 0x11
        private const val RESPONSE_DATA_REGISTER = 0x12
        private const val CHALLENGE_LENGTH_REGISTER = 0x20
        private const val CHALLENGE_DATA_REGISTER = 0x21
        private const val CERTIFICATE_LENGTH_REGISTER = 0x30
        private const val CERTIFICATE_DATA_REGISTER = 0x31
        private const val CERTIFICATE_REGISTER_WINDOW_BYTES = 128
        private const val AUTH_START_COMMAND = 0x01
        private const val AUTH_SUCCESS_STATUS = 0x10
        private const val MINIMUM_CHALLENGE_BYTES = 1
        private const val MAXIMUM_CHALLENGE_BYTES = 128
        private const val IO_RETRY_TIMEOUT_MILLIS = 2_000L
        private const val IO_RETRY_DELAY_MICROS = 20_000L
        private const val REGISTER_READ_SETTLE_MICROS = 5_000L
        private const val INITIAL_AUTH_DELAY_MILLIS = 10L
        private const val AUTH_POLL_MILLIS = 10L
        private const val AUTH_TIMEOUT_MILLIS = 3_000L
        private const val MICROS_PER_MILLISECOND = 1_000L
        private const val NANOS_PER_MICROSECOND = 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/** MFi-layer failures that are distinct from [I2cTransportException]. */
sealed class MfiException(message: String, cause: Throwable? = null) : Exception(message, cause)

class MfiInvalidDataException(message: String, cause: Throwable? = null) : MfiException(message, cause)

/** The chip did not complete an authentication request; [errorCode] is best-effort only. */
class MfiAuthenticationFailedException(val errorCode: Int?, cause: Throwable? = null) :
    MfiException(
        if (errorCode == null) "MFi authentication failed (error code unavailable)"
        else "MFi authentication failed with error 0x${errorCode.toString(16)}",
        cause,
    )
