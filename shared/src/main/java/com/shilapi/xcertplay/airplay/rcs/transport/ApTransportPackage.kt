package com.shilapi.xcertplay.airplay.rcs.transport

/**
 * APTransport package used by CarPlay type-130 RCS streams.
 *
 * The 32-byte header contains big-endian size (0), package type (4), group ID (8), message
 * type (16), reply token (20), and reply status (28). New messages use the asynchronous `asyn`
 * package type; explicit header templates preserve their transport metadata verbatim.
 */
class ApTransportPackage private constructor(
    private val header: ByteArray,
    rawBody: ByteArray,
) {
    val body: ByteArray = rawBody.copyOf()

    val messageType: Int
        get() = ApTransportPackageCodec.readMessageType(header)

    /** Immutable copy of the transport metadata, including sync reply correlation. */
    fun headerBytes(): ByteArray = header.copyOf()

    val packageType: Int get() = ApTransportPackageCodec.readPackageType(header)

    val groupId: Long get() = ApTransportPackageCodec.readU64Be(header, 8)
    val replyToken: Long get() = ApTransportPackageCodec.readU64Be(header, 20)
    val replyStatus: Int get() = ApTransportPackageCodec.readU32Be(header, 28).toInt()

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
    const val PACKAGE_TYPE_OFFSET = 4
    const val PACKAGE_TYPE_ASYNC = 0x6173796e
    const val PACKAGE_TYPE_SYNC = 0x73796e63
    const val PACKAGE_TYPE_REPLY = 0x72706c79
    const val MESSAGE_TYPE_OFFSET = 16
    const val BODY_OFFSET = 32
    const val MESSAGE_TYPE_COMM = 0x636f6d6d

    /**
     * OSType an accessory must use for RCS packages it sends to the phone.
     *
     * The phone's receive gate is `apEndpointRemoteControlSession_startMessageHandling`, whose
     * first statement drops any package whose OSType is neither this nor `died`. `comm` is what the
     * phone sends *down*; it never passes that gate, so an accessory that copies the phone's own
     * value has every upload silently discarded — no log, no counter, no error. Measured in
     * ultra41: the same nine frames moved the phone's `received messages` from 0 to 9 when only
     * this field changed from `comm` to `cmnd`.
     */
    const val MESSAGE_TYPE_CMND = 0x636d6e64
    const val DEFAULT_MAX_PACKAGE_BYTES = 4 * 1024 * 1024

    /**
     * Creates an asynchronous `comm` package, or uses an explicit transport header template.
     *
     * [uninterpretedHeader] must be either empty or a full 32-byte template. The size and message
     * type fields are overwritten. With the default empty template, package type is `asyn`
     * and group ID, reply token, and reply status are zero. An explicit template retains those
     * fields, including its original package type.
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

    /**
     * Creates an asynchronous accessory-to-phone package carrying [MESSAGE_TYPE_CMND], the only
     * OSType the phone's receive gate accepts for uploads. This is what every accessory-initiated
     * RCS send must use; see [MESSAGE_TYPE_CMND] for why `comm` does not work.
     */
    fun uplink(
        body: ByteArray,
        uninterpretedHeader: ByteArray = ByteArray(0),
        maxPackageBytes: Int = DEFAULT_MAX_PACKAGE_BYTES,
    ): ApTransportPackage = create(
        messageType = MESSAGE_TYPE_CMND,
        body = body,
        uninterpretedHeader = uninterpretedHeader,
        maxPackageBytes = maxPackageBytes,
    )

    /**
     * Creates a synchronous `comm` package: the peer's transport is expected to answer it with an
     * `rply`. Only the diagnostic UI Sync probe uses this; the shipped accessory always sends
     * asynchronous packages because the phone never answered a synchronous one in any capture.
     */
    fun sync(
        body: ByteArray,
        maxPackageBytes: Int = DEFAULT_MAX_PACKAGE_BYTES,
    ): ApTransportPackage = create(
        messageType = MESSAGE_TYPE_COMM,
        body = body,
        uninterpretedHeader = headerTemplate(PACKAGE_TYPE_SYNC),
        maxPackageBytes = maxPackageBytes,
    )

    /**
     * A zeroed 32-byte header carrying only [packageType]; group ID, reply token and reply status
     * stay zero, which is what our streams use. Pass it as `uninterpretedHeader` to pin the package
     * type of a frame that is not the default `asyn` one.
     */
    fun headerTemplate(packageType: Int): ByteArray =
        ByteArray(HEADER_SIZE).also { putU32Be(it, PACKAGE_TYPE_OFFSET, packageType.toLong() and UINT32_MAX) }

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
            ByteArray(HEADER_SIZE).also {
                putU32Be(it, PACKAGE_TYPE_OFFSET, PACKAGE_TYPE_ASYNC.toLong())
            }
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

    /** Reply metadata follows APTransport stream_sendReplyMessage, not a new asynchronous message. */
    fun reply(request: ApTransportPackage, status: Int = 0, body: ByteArray = ByteArray(0),
        groupId: Long = 0): ApTransportPackage {
        require(request.packageType == PACKAGE_TYPE_SYNC) { "Only synchronous requests have transport replies" }
        val header = request.headerBytes()
        putU32Be(header, PACKAGE_TYPE_OFFSET, PACKAGE_TYPE_REPLY.toLong())
        // The firmware writes the stream's group ID; our streams use the default group zero.
        putU32Be(header, 8, groupId ushr 32)
        putU32Be(header, 12, groupId and UINT32_MAX)
        putU32Be(header, 28, status.toLong() and UINT32_MAX)
        return create(request.messageType, body, header)
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

    internal fun readPackageType(header: ByteArray): Int =
        readU32Be(header, PACKAGE_TYPE_OFFSET).toInt()

    internal fun readMessageType(header: ByteArray): Int =
        readU32Be(header, MESSAGE_TYPE_OFFSET).toInt()

    internal fun readSize(header: ByteArray): Int =
        readU32Be(header, 0).toInt()

    private fun readSize(bytes: ByteArray, offset: Int): Int =
        readU32Be(bytes, offset).toInt()

    internal fun readU64Be(bytes: ByteArray, offset: Int): Long =
        (readU32Be(bytes, offset) shl 32) or readU32Be(bytes, offset + 4)

    internal fun readU32Be(bytes: ByteArray, offset: Int): Long {
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
