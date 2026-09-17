package com.shilapi.xcertplay.airplay.rcs.transport

/**
 * APTransport package used by CarPlay type-130 RCS streams.
 *
 * Only the package size (offset 0, big-endian), message type (offset 16, big-endian), and body
 * offset (32) are confirmed by the Ultra references. The remaining header bytes are transport
 * metadata whose individual fields are not confirmed and are preserved verbatim.
 */
class ApTransportPackage private constructor(
    private val header: ByteArray,
    rawBody: ByteArray,
) {
    val body: ByteArray = rawBody.copyOf()

    val messageType: Int
        get() = ApTransportPackageCodec.readMessageType(header)

    val encodedSize: Int
        get() = ApTransportPackageCodec.readSize(header)

    fun encoded(): ByteArray {
        val output = ByteArray(encodedSize)
        header.copyInto(output)
        body.copyInto(output, ApTransportPackageCodec.BODY_OFFSET)
        return output
    }

    companion object {
        internal fun fromEncodedHeader(header: ByteArray, body: ByteArray): ApTransportPackage {
            require(header.size == ApTransportPackageCodec.HEADER_SIZE) {
                "APTransport header must be ${ApTransportPackageCodec.HEADER_SIZE} bytes"
            }
            return ApTransportPackage(header.copyOf(), body)
        }
    }
}

data class ApTransportDecodeResult(
    val packages: List<ApTransportPackage>,
    val remainder: ByteArray,
)

class ApTransportProtocolException(message: String) : IllegalArgumentException(message)

/**
 * Public codec shared by the type-130 RCS tunnel and iAP2 bridge.
 */
object ApTransportPackageCodec {
    const val HEADER_SIZE = 32
    const val MESSAGE_TYPE_OFFSET = 16
    const val BODY_OFFSET = 32
    const val MESSAGE_TYPE_COMM = 0x636f6d6d
    const val DEFAULT_MAX_PACKAGE_BYTES = 4 * 1024 * 1024

    /**
     * Creates a `comm` package.
     *
     * [uninterpretedHeader] must be either empty or a full 32-byte template. The size and message
     * type fields are overwritten. Callers that only have confirmed CAF/RCS data should use the
     * default empty template; offsets 4/8/20/28 have no public interpretation yet.
     */
    fun comm(
        body: ByteArray,
        uninterpretedHeader: ByteArray = ByteArray(0),
        maxPackageBytes: Int = DEFAULT_MAX_PACKAGE_BYTES,
    ): ApTransportPackage = create(
        messageType = MESSAGE_TYPE_COMM,
        body = body,
        uninterpretedHeader = uninterpretedHeader,
        maxPackageBytes = maxPackageBytes,
    )

    fun create(
        messageType: Int,
        body: ByteArray,
        uninterpretedHeader: ByteArray = ByteArray(0),
        maxPackageBytes: Int = DEFAULT_MAX_PACKAGE_BYTES,
    ): ApTransportPackage {
        require(maxPackageBytes >= HEADER_SIZE) {
            "maxPackageBytes must be at least $HEADER_SIZE"
        }
        val encodedSize = HEADER_SIZE.toLong() + body.size
        require(encodedSize <= maxPackageBytes) {
            "APTransport package size $encodedSize exceeds $maxPackageBytes"
        }
        require(encodedSize <= UINT32_MAX) {
            "APTransport package size must fit in u32"
        }

        val header = if (uninterpretedHeader.isEmpty()) {
            ByteArray(HEADER_SIZE)
        } else {
            require(uninterpretedHeader.size == HEADER_SIZE) {
                "APTransport header template must be empty or $HEADER_SIZE bytes"
            }
            uninterpretedHeader.copyOf()
        }
        putU32Be(header, 0, encodedSize)
        putU32Be(header, MESSAGE_TYPE_OFFSET, messageType.toLong() and UINT32_MAX)
        return ApTransportPackage.fromEncodedHeader(header, body)
    }

    /**
     * Decodes every complete package in [bytes].
     *
     * Fragmented packages are retained in [ApTransportDecodeResult.remainder]. Invalid declared
     * sizes fail immediately instead of being mistaken for an incomplete package.
     */
    fun decodeAvailable(
        bytes: ByteArray,
        maxPackageBytes: Int = DEFAULT_MAX_PACKAGE_BYTES,
    ): ApTransportDecodeResult {
        require(maxPackageBytes >= HEADER_SIZE) {
            "maxPackageBytes must be at least $HEADER_SIZE"
        }
        val packages = ArrayList<ApTransportPackage>()
        var offset = 0
        while (bytes.size - offset >= HEADER_SIZE) {
            val declaredSize = readSize(bytes, offset).toLong()
            if (declaredSize < HEADER_SIZE) {
                throw ApTransportProtocolException(
                    "APTransport package size $declaredSize is smaller than header $HEADER_SIZE",
                )
            }
            if (declaredSize > maxPackageBytes) {
                throw ApTransportProtocolException(
                    "APTransport package size $declaredSize exceeds $maxPackageBytes",
                )
            }

            val size = declaredSize.toInt()
            if (bytes.size - offset < size) break
            val header = bytes.copyOfRange(offset, offset + HEADER_SIZE)
            val body = bytes.copyOfRange(offset + BODY_OFFSET, offset + size)
            packages += ApTransportPackage.fromEncodedHeader(header, body)
            offset += size
        }
        return ApTransportDecodeResult(
            packages = packages,
            remainder = bytes.copyOfRange(offset, bytes.size),
        )
    }

    fun decode(
        bytes: ByteArray,
        maxPackageBytes: Int = DEFAULT_MAX_PACKAGE_BYTES,
    ): ApTransportPackage {
        val decoded = decodeAvailable(bytes, maxPackageBytes)
        if (decoded.remainder.isNotEmpty() || decoded.packages.size != 1) {
            throw ApTransportProtocolException(
                "Expected one complete APTransport package, got ${decoded.packages.size}" +
                    " with ${decoded.remainder.size} trailing bytes",
            )
        }
        return decoded.packages.single()
    }

    internal fun readMessageType(header: ByteArray): Int =
        readU32Be(header, MESSAGE_TYPE_OFFSET).toInt()

    internal fun readSize(header: ByteArray): Int =
        readU32Be(header, 0).toInt()

    private fun readSize(bytes: ByteArray, offset: Int): Int =
        readU32Be(bytes, offset).toInt()

    private fun readU32Be(bytes: ByteArray, offset: Int): Long {
        requireRange(bytes, offset, 4)
        return ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
    }

    private fun putU32Be(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun requireRange(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "Byte range $offset..${offset + length} is outside ${bytes.size} bytes"
        }
    }

    private const val UINT32_MAX = 0xffff_ffffL
}
