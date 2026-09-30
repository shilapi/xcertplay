package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoint
import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoints
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException

/** Reusable entry points for constructing and reading iAP2 CSM bodies. */
object Iap2Messages {
    inline fun build(
        endpoint: Iap2Endpoint,
        block: Iap2BodyBuilder.() -> Unit,
    ): Iap2Frame = Iap2BodyBuilder.of(endpoint).apply(block).build()

    inline fun buildRaw(
        messageId: Int,
        block: Iap2BodyBuilder.() -> Unit,
    ): Iap2Frame = Iap2BodyBuilder.opaque(messageId).apply(block).build()

    fun reader(frame: Iap2Frame): Iap2BodyReader = Iap2BodyReader.of(frame)
}

/** Parameters carried by the optional `wireless` group of 0x4301. */
data class Iap2WirelessSessionParameters(
    val ssid: String,
    val passphrase: String,
    val channel: Int,
    val ipAddresses: List<String>,
    val securityType: Int,
) {
    init {
        require(ssid.isNotBlank()) { "wireless SSID must not be blank" }
        require(passphrase.isNotBlank()) { "wireless passphrase must not be blank" }
        require(channel in 0..0xff) { "wireless channel must be in 0..255" }
        require(ipAddresses.isNotEmpty()) { "wireless IP address list must not be empty" }
        require(securityType in 0..0xff) { "wireless security type must be in 0..255" }
    }
}

data class Iap2ClusterAsset(
    val assetId: String,
    val assetVersion: Long,
) {
    init {
        require(assetId.isNotBlank()) { "cluster asset ID must not be blank" }
        require(assetVersion in 0..0xffff_ffffL) { "cluster asset version must fit in u32" }
    }
}

/** Typed codec for the high-value CarPlay endpoints. */
object Iap2CarPlayMessages {
    fun startSession(
        airPlayPort: Int,
        publicKey: String,
        sourceVersion: String,
        wiredIpv6Addresses: List<String> = emptyList(),
        wiredReserved: Long? = null,
        wireless: Iap2WirelessSessionParameters? = null,
        deviceIdentifier: String? = null,
        sdkVersion: String? = null,
        clusterAsset: Iap2ClusterAsset? = null,
        mutualAuth: Boolean? = null,
    ): Iap2Frame {
        require(airPlayPort in 1..0xffff) { "airPlayPort must be in 1..65535" }
        require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion must not be empty" }

        return Iap2Messages.build(Iap2Endpoints.CARPLAY_START_SESSION) {
            if (wiredIpv6Addresses.isNotEmpty() || wiredReserved != null) {
                group(0) {
                    wiredIpv6Addresses.forEach { string(0, it) }
                    optionalU32(1, wiredReserved)
                }
            }
            wireless?.let {
                group(1) {
                    string(0, it.ssid)
                    string(1, it.passphrase)
                    u8(2, it.channel)
                    it.ipAddresses.forEach { address -> string(3, address) }
                    u8(4, it.securityType)
                }
            }
            u32(2, airPlayPort)
            optionalString(3, deviceIdentifier)
            string(4, publicKey)
            string(5, sourceVersion)
            optionalString(6, sdkVersion)
            clusterAsset?.let {
                group(7) {
                    string(0, it.assetId)
                    u32(1, it.assetVersion)
                }
            }
            optionalU8(8, mutualAuth?.let { if (it) 1 else 0 })
        }
    }

    fun availability(frame: Iap2Frame): Iap2CarPlayAvailability {
        require(frame.messageId == Iap2Endpoints.CARPLAY_AVAILABILITY.id) {
            "Expected CarPlayAvailability, got 0x${frame.messageId.toString(16)}"
        }
        return availability(frame.payload)
    }

    /** Decodes an already extracted 0x4300 body without requiring the caller to rebuild a frame. */
    fun availability(payload: ByteArray): Iap2CarPlayAvailability {
        val body = Iap2BodyReader.of(Iap2ParameterList.parse(payload))
        val wired = body.optionalGroup(0)?.let {
            Iap2AvailabilityState(
                available = decodeAvailability(it.optionalU8(0), "wired"),
                identifier = it.optionalString(1),
            )
        }
        val wireless = body.optionalGroup(1)?.let {
            Iap2AvailabilityState(
                available = decodeAvailability(it.optionalU8(0), "wireless"),
                identifier = it.optionalString(1),
            )
        }
        val themeAssets = body.optionalGroup(2)?.let {
            Iap2AvailabilityState(
                available = decodeAvailability(it.optionalU8(0), "themeAssets"),
                identifier = null,
            )
        }
        return Iap2CarPlayAvailability(wired, wireless, themeAssets)
    }

    private fun decodeAvailability(value: Int?, label: String): Boolean? {
        if (value == null) return null
        if (value !in 0..1) {
            throw Iap2ProtocolException("$label availability must be 0 or 1, got $value")
        }
        return value == 1
    }
}

data class Iap2AvailabilityState(
    val available: Boolean?,
    val identifier: String?,
)

data class Iap2CarPlayAvailability(
    val wired: Iap2AvailabilityState?,
    val wireless: Iap2AvailabilityState?,
    val themeAssets: Iap2AvailabilityState?,
)

/** Typed codecs for Wi-Fi sharing and Wireless CarPlay notifications. */
object Iap2WirelessMessages {
    fun accessoryWiFiConfiguration(
        ssid: String,
        passphrase: String,
        channel: Int,
        securityType: Int,
        bssid: ByteArray? = null,
    ): Iap2Frame = Iap2Messages.build(Iap2Endpoints.ACCESSORY_WIFI_CONFIGURATION) {
        optionalBytes(0, bssid)
        string(1, ssid)
        string(2, passphrase)
        u8(3, securityType)
        u8(4, channel)
    }

    fun wifiInformation(frame: Iap2Frame): Iap2WifiInformation {
        require(frame.messageId == Iap2Endpoints.WIFI_INFORMATION.id) {
            "Expected WiFiInformation, got 0x${frame.messageId.toString(16)}"
        }
        val body = Iap2Messages.reader(frame)
        val status = body.u8(0)
        if (status != 0) return Iap2WifiInformation(status)
        return Iap2WifiInformation(
            status = status,
            securityType = body.optionalU8(1),
            ssid = body.optionalString(2),
            passphrase = body.optionalString(3),
        )
    }

    fun wirelessCarPlayAvailability(frame: Iap2Frame): Boolean {
        require(frame.messageId == Iap2Endpoints.WIRELESS_CARPLAY_UPDATE.id) {
            "Expected WirelessCarPlayUpdate, got 0x${frame.messageId.toString(16)}"
        }
        return when (val value = Iap2Messages.reader(frame).u8(0)) {
            0 -> false
            1 -> true
            else -> throw Iap2ProtocolException("WirelessCarPlayUpdate availability must be 0 or 1, got $value")
        }
    }

    fun deviceTransportIdentifier(frame: Iap2Frame): Iap2DeviceTransportIdentifier {
        require(frame.messageId == Iap2Endpoints.DEVICE_TRANSPORT_IDENTIFIER.id) {
            "Expected DeviceTransportIdentifierNotification, got 0x${frame.messageId.toString(16)}"
        }
        val body = Iap2Messages.reader(frame)
        return Iap2DeviceTransportIdentifier(
            bluetoothMac = body.optionalString(0),
            usbTransportIdentifier = body.optionalString(1),
        )
    }
}

/** Typed accessory-authentication bodies, including the BAA certificate package layout. */
object Iap2AuthenticationMessages {
    fun accessoryCertificate(certificate: ByteArray): Iap2Frame =
        Iap2Messages.build(Iap2Endpoints.AUTHENTICATION_CERTIFICATE) {
            bytes(0, certificate)
        }

    /**
     * Wraps an already encoded 0xAA01 body, as returned by the remote MFi/BAA transport.
     * The body is parsed before wrapping so malformed parameter framing cannot escape this layer.
     */
    fun accessoryCertificateBody(body: ByteArray): Iap2Frame {
        Iap2ParameterList.parse(body)
        return Iap2Frame(Iap2Endpoints.AUTHENTICATION_CERTIFICATE.id, body)
    }

    /**
     * The remote BAA service returns a package whose three fields are already the final 0xAA01
     * parameter sequence. It is intentionally built through the raw endpoint wrapper because this
     * package uses a schema variant that is not represented by the baseline MFi endpoint.
     */
    fun baaCertificatePackage(leaf: ByteArray, intermediate: ByteArray): Iap2Frame =
        Iap2Messages.buildRaw(Iap2Endpoints.AUTHENTICATION_CERTIFICATE.id) {
            bytes(0, leaf)
            u8(1, 1)
            bytes(2, intermediate)
        }

    fun response(signature: ByteArray): Iap2Frame =
        Iap2Messages.build(Iap2Endpoints.AUTHENTICATION_RESPONSE) {
            bytes(0, signature)
        }

    fun challenge(frame: Iap2Frame): ByteArray {
        require(frame.messageId == Iap2Endpoints.REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE.id) {
            "Expected RequestAuthenticationChallengeResponse, got 0x${frame.messageId.toString(16)}"
        }
        return Iap2Messages.reader(frame).bytes(0)
    }
}

data class Iap2WifiInformation(
    val status: Int,
    val securityType: Int? = null,
    val ssid: String? = null,
    val passphrase: String? = null,
) {
    val successful: Boolean get() = status == 0
}

data class Iap2DeviceTransportIdentifier(
    val bluetoothMac: String?,
    val usbTransportIdentifier: String?,
)

/** Typed codecs for power and subscription requests used during bring-up. */
object Iap2ControlMessages {
    fun powerSourceUpdate(
        availableCurrentMilliAmps: Int,
        charging: Boolean? = null,
        availableSiphoningCurrentMilliAmps: Int? = null,
        reserveCurrentMilliAmps: Int? = null,
        maxNonSiphoningCurrentExceeded: Boolean = false,
    ): Iap2Frame = Iap2Messages.build(Iap2Endpoints.POWER_SOURCE_UPDATE) {
        u16(0, availableCurrentMilliAmps)
        optionalU8(1, charging?.let { if (it) 1 else 0 })
        optionalU16(3, availableSiphoningCurrentMilliAmps)
        optionalU16(4, reserveCurrentMilliAmps)
        optionalVoid(5, maxNonSiphoningCurrentExceeded)
    }

    fun subscriptions(): List<Iap2Frame> = listOf(
        Iap2Messages.build(Iap2Endpoints.START_NOW_PLAYING_UPDATES) {
            group(0) {
                listOf(1, 4, 6, 12, 26).forEach(::void)
            }
            group(1) {
                listOf(0, 1, 2, 3, 7).forEach(::void)
            }
        },
        Iap2Messages.build(Iap2Endpoints.START_ROUTE_GUIDANCE_UPDATES) {},
        Iap2Messages.build(Iap2Endpoints.START_POWER_UPDATES) {
            void(4)
            void(5)
            void(6)
        },
        Iap2Messages.build(Iap2Endpoints.START_COMMUNICATIONS_UPDATES) {
            void(0)
            void(4)
            void(5)
        },
        Iap2Messages.build(Iap2Endpoints.START_CALL_STATE_UPDATES) {
            void(0)
            void(1)
            void(2)
            void(3)
            void(4)
            void(11)
        },
    )

    fun locationInformation(nmeaSentence: String): Iap2Frame {
        require(nmeaSentence.isNotEmpty()) { "NMEA sentence must not be empty" }
        return Iap2Messages.build(Iap2Endpoints.LOCATION_INFORMATION) {
            string(0, nmeaSentence)
        }
    }
}

enum class Iap2PlaybackStatus(val wireValue: Int) {
    STOPPED(0),
    PLAYING(1),
    PAUSED(2),
    SEEKING_FORWARD(3),
    SEEKING_BACKWARD(4),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromWire(value: Int): Iap2PlaybackStatus =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

/** Last complete NowPlaying state reconstructed from incremental 0x5001 updates. */
data class Iap2NowPlayingState(
    val title: String? = null,
    val durationMillis: Long? = null,
    val album: String? = null,
    val artist: String? = null,
    val status: Iap2PlaybackStatus = Iap2PlaybackStatus.STOPPED,
    val elapsedMillis: Long = 0,
    val queueIndex: Long? = null,
    val queueCount: Long? = null,
    val appName: String? = null,
    val positionUpdateRealtimeMillis: Long = 0,
    /** Artwork file-transfer identifier; fetching the artwork itself needs an iAP2 file transfer session. */
    val artworkFileTransferId: Int? = null,
)

/** Merges fields because iAP2 omits attributes whose value did not change. */
class Iap2NowPlayingAccumulator(
    private val realtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var current = Iap2NowPlayingState()

    @Synchronized
    fun update(frame: Iap2Frame): Iap2NowPlayingState {
        require(frame.messageId == NOW_PLAYING_UPDATE) {
            "Expected NowPlayingUpdate, got 0x${frame.messageId.toString(16)}"
        }
        val body = Iap2Messages.reader(frame)
        body.optionalGroup(MEDIA_ITEM_ATTRIBUTES)?.let { media ->
            current = current.copy(
                title = if (media.has(TITLE)) media.string(TITLE) else current.title,
                durationMillis = if (media.has(DURATION)) media.u32(DURATION) else current.durationMillis,
                album = if (media.has(ALBUM)) media.string(ALBUM) else current.album,
                artist = if (media.has(ARTIST)) media.string(ARTIST) else current.artist,
                artworkFileTransferId = if (media.has(ARTWORK_FILE_TRANSFER_ID)) {
                    media.u8(ARTWORK_FILE_TRANSFER_ID)
                } else {
                    current.artworkFileTransferId
                },
            )
        }
        body.optionalGroup(PLAYBACK_ATTRIBUTES)?.let { playback ->
            val statusChanged = playback.has(PLAYBACK_STATUS)
            val elapsedChanged = playback.has(ELAPSED_TIME)
            val updateTime = if (statusChanged || elapsedChanged) realtimeMillis() else null
            val elapsedAtUpdate = if (
                statusChanged &&
                !elapsedChanged &&
                current.status == Iap2PlaybackStatus.PLAYING &&
                current.positionUpdateRealtimeMillis > 0
            ) {
                current.elapsedMillis +
                    (checkNotNull(updateTime) - current.positionUpdateRealtimeMillis).coerceAtLeast(0)
            } else {
                current.elapsedMillis
            }
            current = current.copy(
                status = if (statusChanged) {
                    Iap2PlaybackStatus.fromWire(playback.u8(PLAYBACK_STATUS))
                } else {
                    current.status
                },
                elapsedMillis = if (elapsedChanged) playback.u32(ELAPSED_TIME) else elapsedAtUpdate,
                queueIndex = if (playback.has(QUEUE_INDEX)) playback.u32(QUEUE_INDEX) else current.queueIndex,
                queueCount = if (playback.has(QUEUE_COUNT)) playback.u32(QUEUE_COUNT) else current.queueCount,
                appName = if (playback.has(APP_NAME)) playback.string(APP_NAME) else current.appName,
                positionUpdateRealtimeMillis = updateTime ?: current.positionUpdateRealtimeMillis,
            )
        }
        return current
    }

    private companion object {
        const val NOW_PLAYING_UPDATE = 0x5001
        const val MEDIA_ITEM_ATTRIBUTES = 0
        const val PLAYBACK_ATTRIBUTES = 1
        const val TITLE = 1
        const val DURATION = 4
        const val ALBUM = 6
        const val ARTIST = 12
        const val ARTWORK_FILE_TRANSFER_ID = 26
        const val PLAYBACK_STATUS = 0
        const val ELAPSED_TIME = 1
        const val QUEUE_INDEX = 2
        const val QUEUE_COUNT = 3
        const val APP_NAME = 7
    }
}

enum class Iap2MediaRemoteCommand(val reportMask: Int) {
    PLAY(1),
    PAUSE(2),
    NEXT(4),
    PREVIOUS(8),
}

/** iAP2 HID Media Playback Remote messages (0x6800/0x6802/0x6803). */
object Iap2HidMessages {
    const val MEDIA_PLAYBACK_COMPONENT_ID = 0

    fun startMediaPlaybackRemote(
        vendorIdentifier: Int = 2,
        productIdentifier: Int = 1,
    ): Iap2Frame = Iap2Messages.buildRaw(START_HID) {
        u16(0, MEDIA_PLAYBACK_COMPONENT_ID)
        u16(1, vendorIdentifier)
        u16(2, productIdentifier)
        bytes(4, MEDIA_PLAYBACK_DESCRIPTOR)
    }

    fun mediaReport(command: Iap2MediaRemoteCommand): Iap2Frame =
        accessoryReport(byteArrayOf(command.reportMask.toByte()))

    fun releaseMediaButtons(): Iap2Frame = accessoryReport(byteArrayOf(0))

    fun stopMediaPlaybackRemote(): Iap2Frame = Iap2Messages.buildRaw(STOP_HID) {
        u16(0, MEDIA_PLAYBACK_COMPONENT_ID)
    }

    private fun accessoryReport(report: ByteArray): Iap2Frame =
        Iap2Messages.buildRaw(ACCESSORY_HID_REPORT) {
            u16(0, MEDIA_PLAYBACK_COMPONENT_ID)
            bytes(1, report)
        }

    private const val START_HID = 0x6800
    private const val ACCESSORY_HID_REPORT = 0x6802
    private const val STOP_HID = 0x6803
    private val MEDIA_PLAYBACK_DESCRIPTOR = byteArrayOf(
        0x05, 0x0c, // Usage Page (Consumer)
        0x09, 0x01, // Usage (Consumer Control)
        0xa1.toByte(), 0x01, // Collection (Application)
        0x15, 0x00, // Logical Minimum (0)
        0x25, 0x01, // Logical Maximum (1)
        0x75, 0x01, // Report Size (1)
        0x95.toByte(), 0x04, // Report Count (4)
        0x09, 0xb0.toByte(), // Play
        0x09, 0xb1.toByte(), // Pause
        0x09, 0xb5.toByte(), // Scan Next Track
        0x09, 0xb6.toByte(), // Scan Previous Track
        0x81.toByte(), 0x02, // Input (Data, Variable, Absolute)
        0x75, 0x04, // Report Size (4)
        0x95.toByte(), 0x01, // Report Count (1)
        0x81.toByte(), 0x03, // Input (Constant, Variable, Absolute)
        0xc0.toByte(), // End Collection
    )
}
