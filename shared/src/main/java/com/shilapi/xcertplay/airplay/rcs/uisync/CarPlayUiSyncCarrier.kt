package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec

/**
 * DDP routes ClusterControl by the OEM display UID in an OPACK dictionary. Its NSData `data`
 * contains the accessory session plist (whose command data may itself be another plist).
 * Bare session plists remain supported for the local reference connection.
 */
object CarPlayUiSyncCarrier {
    data class Received(val message: CarPlayUiSyncMessage, val displayUuid: String?)

    fun encode(message: CarPlayUiSyncMessage, displayUuid: String?): ByteArray {
        val session = message.toBplist()
        if (displayUuid == null) return session
        require(displayUuid.isNotBlank()) { "UI Sync displayUUID must be non-empty" }
        return OpackCodec.encode(linkedMapOf("data" to session, "displayUUID" to displayUuid))
    }

    fun decode(body: ByteArray): Received {
        if (body.size >= 8 && body.copyOfRange(0, 8).contentEquals("bplist00".toByteArray(Charsets.US_ASCII))) {
            return Received(CarPlayUiSyncMessageFactory.decode(body), null)
        }
        val carrier = OpackCodec.decode(body) as? Map<*, *>
            ?: throw CarPlayUiSyncProtocolException("UI Sync display carrier must be an OPACK dictionary")
        val display = carrier["displayUUID"] as? String
            ?: throw CarPlayUiSyncProtocolException("UI Sync displayUUID must be a string")
        require(display.isNotBlank()) { "UI Sync displayUUID must be non-empty" }
        val data = carrier["data"] as? ByteArray
            ?: throw CarPlayUiSyncProtocolException("UI Sync display carrier data must be NSData")
        return Received(CarPlayUiSyncMessageFactory.decode(data), display)
    }
}
