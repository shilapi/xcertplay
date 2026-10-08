package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoints
import com.shilapi.xcertplay.iap2.message.Iap2HidMessages
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.IOException
import kotlin.math.min

private fun List<Int>.messageIds(): String = joinToString(prefix = "[", postfix = "]") {
    "0x${it.toString(16).padStart(4, '0')}"
}

private fun ByteArray.toHex(): String = joinToString(separator = " ") { "%02x".format(it.toInt() and 0xff) }

/** The wireless transport identity advertised on the Bluetooth identification session. */
class Iap2WirelessIdentification(
    val bluetoothMac: String,
    val ssid: String,
) {
    private val macBytes: ByteArray

    init {
        require(MAC_ADDRESS.matches(bluetoothMac)) {
            "bluetoothMac must be six colon-separated hexadecimal bytes"
        }
        require(ssid.isNotBlank()) { "Wireless SSID must not be blank" }
        require('\u0000' !in ssid) { "Wireless SSID must not contain U+0000" }
        macBytes = ByteArray(6) { index ->
            bluetoothMac.substring(index * 3, index * 3 + 2).toInt(16).toByte()
        }
    }

    internal fun bluetoothMacBytes(): ByteArray = macBytes.copyOf()

    private companion object {
        private val MAC_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}

/** Required identity for the minimal wired or wireless iAP2 identification exchange. */
data class Iap2IdentificationConfig(
    val name: String,
    val modelIdentifier: String,
    val manufacturer: String,
    val serialNumber: String,
    val firmwareVersion: String,
    val hardwareVersion: String,
    /** The iPhone USB interface used by CarPlay, supplied explicitly by the deployment. */
    val carPlayUsbInterfaceNumber: Int,
    val language: String = "en",
    /** This app's own EA protocol identifier; it deliberately does not claim a CarPlay EA flag. */
    val externalAccessoryProtocol: String = "com.shilapi.xcertplay",
    /** Non-null selects the wireless Bluetooth and WirelessCarPlay transport components. */
    val wireless: Iap2WirelessIdentification? = null,
    /** Advertises and enables iAP2 LocationInformation from the accessory to the phone. */
    val locationInformationEnabled: Boolean = false,
    /** USB-IF identity used by StartHID; defaults match this app's existing AirPlay HID identity. */
    val hidVendorIdentifier: Int = 2,
    val hidProductIdentifier: Int = 1,
    /** Additional endpoints backed by the selected authentication provider, frozen before identification. */
    val authenticationSentMessages: Set<Int> = emptySet(),
    val authenticationReceivedMessages: Set<Int> = emptySet(),
    /** Wireless component child 5: a void capability, separate from the optional asset ID. */
    val supportsThemeAssets: Boolean = false,
    /** Wireless component child 6, consumed as CarPlay_ThemeAssetIdentifier by accessoryd. */
    val themeAssetIdentifier: String? = null,
) {
    constructor(
        name: String,
        modelIdentifier: String,
        manufacturer: String,
        serialNumber: String,
        firmwareVersion: String,
        hardwareVersion: String,
        wireless: Iap2WirelessIdentification,
        language: String = "en",
        externalAccessoryProtocol: String = "com.shilapi.xcertplay",
    ) : this(
        name = name,
        modelIdentifier = modelIdentifier,
        manufacturer = manufacturer,
        serialNumber = serialNumber,
        firmwareVersion = firmwareVersion,
        hardwareVersion = hardwareVersion,
        carPlayUsbInterfaceNumber = 0,
        language = language,
        externalAccessoryProtocol = externalAccessoryProtocol,
        wireless = wireless,
    )

    init {
        themeAssetIdentifier?.let {
            require(supportsThemeAssets) { "A theme asset identifier requires theme support" }
            require(it.isNotBlank() && '\u0000' !in it) { "Theme asset identifier must be a non-empty C string" }
        }
        listOf(name, modelIdentifier, manufacturer, serialNumber, firmwareVersion, hardwareVersion).forEach {
            require(it.isNotBlank()) { "Identification identity strings must not be blank" }
        }
        require(language.isNotBlank()) { "Identification language must not be blank" }
        require(externalAccessoryProtocol.isNotBlank()) { "External accessory protocol must not be blank" }
        listOf(
            name,
            modelIdentifier,
            manufacturer,
            serialNumber,
            firmwareVersion,
            hardwareVersion,
            language,
            externalAccessoryProtocol,
        ).forEach {
            require('\u0000' !in it) { "NUL-terminated identification strings must not contain U+0000" }
        }
        require(carPlayUsbInterfaceNumber in 0..0xff) {
            "carPlayUsbInterfaceNumber must be in 0..255"
        }
        require(hidVendorIdentifier in 0..0xffff) { "hidVendorIdentifier must fit in u16" }
        require(hidProductIdentifier in 0..0xffff) { "hidProductIdentifier must fit in u16" }
    }
}

/** Identification failures distinguished from the underlying iAP2 transport failure. */
sealed class Iap2IdentificationException(message: String) : IOException(message) {
    class Rejected(parameterIds: Set<Int>, rawBody: ByteArray) : Iap2IdentificationException(
        "iAP2 identification rejected parameters ${parameterIds.sorted().joinToString(prefix = "[", postfix = "]") { "0x${it.toString(16).padStart(4, '0')}" }}; " +
            "raw 0x1d03 body=${rawBody.toHex()}; " +
            "inspect preceding 0x1d01: param6=MessagesSentByAccessory, param7=MessagesReceivedFromDevice; rejection identifies parameters, not individual message IDs",
    ) {
        val parameterIds: Set<Int> = parameterIds.toSet()
    }

    class UnexpectedMessage(messageId: Int) : Iap2IdentificationException(
        "Unexpected iAP2 identification message 0x${messageId.toString(16).padStart(4, '0')}",
    )
}

/**
 * Synchronous accessory-side wired or wireless identification over an already owned CSM channel.
 *
 * This is intentionally only identification: it neither invokes MFi nor itself starts any
 * CarPlay, subscription, power, media, or UI service.
 */
class Iap2IdentificationClient(private val session: Iap2Session) {
    /** Waits for link negotiation, then completes the 1D00/1D01/1D02 exchange. */
    @Throws(IphoneUsbException::class, Iap2IdentificationException::class)
    fun identify(
        config: Iap2IdentificationConfig,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onTrace: (String) -> Unit = {},
    ) {
        require(timeoutMillis in 1..MAXIMUM_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAXIMUM_TIMEOUT_MILLIS"
        }
        val information = identificationInformation(config)
        val declared = Iap2BodyReader.of(information)
        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        if (!session.awaitReady(remainingMillis(deadlineNanos))) {
            throw IphoneUsbException.TimedOut("Timed out waiting for iAP2 control session readiness")
        }

        while (true) {
            val frame = session.recv(remainingMillis(deadlineNanos))
                ?: throw IphoneUsbException.TimedOut("Timed out waiting for iAP2 identification")
            when (frame.messageId) {
                START_IDENTIFICATION -> session.send(information, remainingMillis(deadlineNanos))
                IDENTIFICATION_ACCEPTED -> return
                IDENTIFICATION_REJECTED -> {
                    val rejected = Iap2BodyReader.of(frame).list().mapTo(LinkedHashSet()) { it.id }
                    runCatching { onTrace("TRACE identification rejected parameters=$rejected " +
                        "MessagesSentByAccessory=${declared.u16List(6).messageIds()} " +
                        "MessagesReceivedFromDevice=${declared.u16List(7).messageIds()}") }
                    throw Iap2IdentificationException.Rejected(rejected, frame.payload)
                }
                else -> throw Iap2IdentificationException.UnexpectedMessage(frame.messageId)
            }
        }
    }

    companion object {
        const val START_IDENTIFICATION = 0x1d00
        const val IDENTIFICATION_INFORMATION = 0x1d01
        const val IDENTIFICATION_ACCEPTED = 0x1d02
        const val IDENTIFICATION_REJECTED = 0x1d03

        private const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        private const val MAXIMUM_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Builds the smallest honest LIVI-compatible wired or wireless IdentificationInformation. */
        fun identificationInformation(config: Iap2IdentificationConfig): Iap2Frame {
            val wireless = config.wireless
            val sentMessages = if (config.locationInformationEnabled) {
                MESSAGES_SENT_BY_ACCESSORY + LOCATION_INFORMATION
            } else {
                MESSAGES_SENT_BY_ACCESSORY
            }
            val receivedMessages = if (config.locationInformationEnabled) {
                MESSAGES_RECEIVED_FROM_PHONE + START_LOCATION_INFORMATION + STOP_LOCATION_INFORMATION
            } else {
                MESSAGES_RECEIVED_FROM_PHONE
            }
            // accessoryd rejects AE03/AE06 on a wireless endpoint during identification,
            // even with zero current or a nonzero powerProvidingCapability. Power subscriptions
            // remain valid; only the USB supply/reset endpoints belong to the wired transport.
            val declaredSent = (sentMessages.asIterable() + config.authenticationSentMessages)
                .distinct()
                .filterNot { wireless != null && it in WIRED_POWER_MESSAGES }
            val declaredReceived = (receivedMessages.asIterable() + config.authenticationReceivedMessages).distinct()
            return Iap2Messages.build(Iap2Endpoints.IDENTIFICATION_INFORMATION) {
                string(0, config.name)
                string(1, config.modelIdentifier)
                string(2, config.manufacturer)
                string(3, config.serialNumber)
                string(4, config.firmwareVersion)
                string(5, config.hardwareVersion)
                u16List(
                    6,
                    if (wireless == null) {
                        declaredSent
                    } else {
                        declaredSent +
                            ACCESSORY_WIFI_CONFIGURATION_INFORMATION
                    },
                )
                u16List(
                    7,
                    if (wireless == null) {
                        declaredReceived
                    } else {
                        declaredReceived + WIRELESS_PHONE_MESSAGES.asIterable()
                    },
                )
                u8(8, if (wireless == null) 2 else 0)
                u16(9, 20)
                group(10) {
                    u8(0, 1)
                    string(1, config.externalAccessoryProtocol)
                    u8(2, 0)
                }
                string(12, config.language)
                strings(13, listOf(config.language))
                if (wireless == null) {
                    group(16) {
                        u16(0, 0)
                        string(1, "USBHostTransport")
                        void(2)
                        u8(3, config.carPlayUsbInterfaceNumber)
                        void(4)
                    }
                } else {
                    group(17) {
                        u16(0, 0)
                        string(1, "blue")
                        void(2)
                        bytes(3, wireless.bluetoothMacBytes())
                        string(4, "blue")
                        void(5)
                    }
                }
                group(18) {
                    u16(0, Iap2HidMessages.MEDIA_PLAYBACK_COMPONENT_ID)
                    string(1, "Media Playback Remote")
                    u8(2, 1)
                }
                if (config.locationInformationEnabled) {
                    group(22) {
                        u16(0, 0)
                        string(1, config.name)
                        void(17)
                        void(18)
                    }
                }
                if (wireless != null) {
                    group(24) {
                        u16(0, 1)
                        string(1, config.name)
                        void(2)
                        void(4)
                        if (config.supportsThemeAssets) void(5)
                        config.themeAssetIdentifier?.let { string(6, it) }
                    }
                }
            }
        }

        private fun remainingMillis(deadlineNanos: Long): Long {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) throw IphoneUsbException.TimedOut("iAP2 identification timed out")
            return min(MAXIMUM_TIMEOUT_MILLIS, (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
        }

        /* Base service endpoints. Authentication declarations come from the selected provider. */
        private val MESSAGES_SENT_BY_ACCESSORY = intArrayOf(
            0x5000, // StartNowPlayingUpdates
            0x5002, // StopNowPlayingUpdates
            0x5200, // StartRouteGuidanceUpdates
            0x5203, // StopRouteGuidanceUpdates
            0xae00, // StartPowerUpdates
            0xae02, // StopPowerUpdates
            0x4157, // StartCommunicationsUpdates
            0x4159, // StopCommunicationsUpdates
            0x4154, // StartCallStateUpdates
            0x4156, // StopCallStateUpdates
            0xae03, // PowerSourceUpdate
            0xae06, // AccessoryResetBaseCurrent
            0x4301, // CarPlayStartSession
            0x6800, // StartHID
            0x6802, // AccessoryHIDReport
            0x6803, // StopHID
        )
        private val MESSAGES_RECEIVED_FROM_PHONE = intArrayOf(
            0xea00, // StartExternalAccessoryProtocolSession
            0xea01, // StopExternalAccessoryProtocolSession
            0x5001, // NowPlayingUpdate
            0x5201, // RouteGuidanceUpdate
            0x5202, // RouteGuidanceManeuverUpdate
            0xae01, // PowerUpdate
            0x4158, // CommunicationsUpdate
            0x4155, // CallStateUpdate
            0x4300, // CarPlayAvailability
            0x6801, // DeviceHIDReport (phone→accessory reply required once StartHID is declared)
        )
        private val WIRED_POWER_MESSAGES = setOf(0xae03, 0xae06)
        private const val ACCESSORY_WIFI_CONFIGURATION_INFORMATION = 0x5703
        private const val LOCATION_INFORMATION = 0xfffb
        private const val START_LOCATION_INFORMATION = 0xfffa
        private const val STOP_LOCATION_INFORMATION = 0xfffc
        private val WIRELESS_PHONE_MESSAGES = intArrayOf(0x4e0d, 0x4e0e, 0x5702)
    }
}
