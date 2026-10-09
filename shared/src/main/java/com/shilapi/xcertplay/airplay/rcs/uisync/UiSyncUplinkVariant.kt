package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec

/**
 * APTransport header form used for the UI Sync uplink.
 *
 * The phone's receive gate is
 * `apEndpointRemoteControlSession_startMessageHandling` (airplayd, 0x2517092BC), whose first
 * statement drops the message unless the OSType is `cmnd` (0x636D6E64) or `died` (0x64696564):
 *
 * ```
 * if ( a1 != 1684628836 /* 'died' */ && a1 != 1668116068 /* 'cmnd' */ ) return 0;
 * ```
 *
 * That return is silent — no log, no counter, no error — and it sits in front of the increment of
 * `received messages` in `apEndpointRemoteControlSession_handleMessageInternal` (0x25161AD44).
 *
 * ultra41 settled which side of that gate an accessory is on. The phone's own downlink carries
 * `comm` at offset 16, but an upload carrying `comm` is dropped; switching only that one field to
 * `cmnd` moved the phone's `received messages` from 0 to 9 and let the handshake reach `ready`.
 * [DEFAULT] is therefore [ASYN_CMND]. The remaining entries are kept so the deployment profile can
 * re-run the sweep, and so the refuted forms stay named rather than being deleted.
 */
/** OSTypes accepted by `startMessageHandling`; see the class comment for the addresses. */
private const val MESSAGE_TYPE_CMND = 0x636D6E64
private const val MESSAGE_TYPE_DIED = 0x64696564

enum class UiSyncUplinkVariant(val wireName: String, private val packageType: Int, private val messageType: Int) {
    /**
     * The phone's own downlink form. An upload carrying it is dropped by the receive gate; this
     * was the shipped behaviour until ultra41 and is kept so the profile can reproduce that result.
     */
    ASYN_COMM("asyn-comm", ApTransportPackageCodec.PACKAGE_TYPE_ASYNC, ApTransportPackageCodec.MESSAGE_TYPE_COMM),

    /** Asynchronous package carrying the OSType the phone's receive gate accepts. **The default.** */
    ASYN_CMND("asyn-cmnd", ApTransportPackageCodec.PACKAGE_TYPE_ASYNC, MESSAGE_TYPE_CMND),

    /** Synchronous package, `comm` payload type. */
    SYNC_COMM("sync-comm", ApTransportPackageCodec.PACKAGE_TYPE_SYNC, ApTransportPackageCodec.MESSAGE_TYPE_COMM),

    /** Synchronous package carrying the accepted OSType. */
    SYNC_CMND("sync-cmnd", ApTransportPackageCodec.PACKAGE_TYPE_SYNC, MESSAGE_TYPE_CMND),

    /**
     * The other OSType the gate accepts. Kept last on purpose: `died` also notifies the peer's
     * session of a failure, so it can tear the peer's RCS entry down instead of delivering payload.
     */
    ASYN_DIED("asyn-died", ApTransportPackageCodec.PACKAGE_TYPE_ASYNC, MESSAGE_TYPE_DIED);

    fun frame(body: ByteArray): RcsMessage =
        if (packageType == ApTransportPackageCodec.PACKAGE_TYPE_ASYNC && transportHeaderIsImplicit()) {
            // Byte-identical to RcsMessage.comm, but keep the exact object shape of the current path.
            RcsMessage(messageType, body)
        } else {
            RcsMessage(messageType, body, ApTransportPackageCodec.headerTemplate(packageType))
        }

    private fun transportHeaderIsImplicit(): Boolean = messageType == ApTransportPackageCodec.MESSAGE_TYPE_COMM

    companion object {
        /**
         * `asyn-cmnd`: what the accessory must send. ultra41 measured `received messages` 0 -> 9
         * against `asyn-comm` with no other change; see [ApTransportPackageCodec.MESSAGE_TYPE_CMND].
         */
        val DEFAULT = ASYN_CMND

        fun fromProfile(value: String?): UiSyncUplinkVariant = when (value) {
            null -> DEFAULT
            else -> entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException(
                    "Unknown UI Sync uplink variant '$value'; expected one of ${entries.joinToString { it.wireName }}")
        }
    }
}
