package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.BplistCodec

/**
 * DDP routes ClusterControl by the OEM display UID in an OPACK dictionary. Its NSData `data`
 * contains the session plist. The V3 experiment places structured command payload inside
 * that plist; dictionary packets from the phone remain supported through the same route.
 * Bare session plists remain supported for the local reference connection.
 */
object CarPlayUiSyncCarrier {
    data class Received(val message: CarPlayUiSyncMessage, val displayUuid: String?, val packet: Boolean = false)

    fun wireFields(message: CarPlayUiSyncMessage, commandPayloadKey: String = "data",
        commandPayloadDictionary: Boolean = false, omitFadeInNewLayout: Boolean = false): Map<String, Any?> {
        require(commandPayloadKey == "data" || commandPayloadKey == "payload") { "Invalid UI Sync command payload key" }
        val key = if (message.type == CarPlayUiSyncMessageType.COMMAND) commandPayloadKey else "data"
        return message.toWireMap().toMutableMap().apply {
            if (key != "data" && containsKey("data")) put(key, remove("data"))
            message.payload?.let { original ->
                val payload = if (omitFadeInNewLayout && message.commandPayload()?.command == CarPlayUiSyncCommand.REQUEST_LAYOUT) {
                    if (original.containsKey("type")) LinkedHashMap(original).apply {
                        val content = original["data"] as Map<*, *>
                        put("data", content.filterKeys { it != "fadeInNewLayout" })
                    } else original.filterKeys { it != "fadeInNewLayout" }
                } else original
                put(key, if (commandPayloadDictionary) payload else BplistCodec.encode(payload))
            }
        }
    }

    fun encode(message: CarPlayUiSyncMessage, displayUuid: String?, packet: Boolean = false,
        commandPayloadKey: String = "data", commandPayloadDictionary: Boolean = false,
        omitFadeInNewLayout: Boolean = false): ByteArray {
        val fields = wireFields(message, commandPayloadKey, commandPayloadDictionary || packet, omitFadeInNewLayout)
        if (displayUuid == null) {
            require(!packet) { "UI Sync dictionary packet requires a display route" }
            return BplistCodec.encode(fields)
        }
        require(displayUuid.isNotBlank()) { "UI Sync displayUUID must be non-empty" }
        val session = if (packet) fields else BplistCodec.encode(fields)
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
        return when (val data = carrier["data"]) {
            is ByteArray -> Received(CarPlayUiSyncMessageFactory.decode(data), display)
            is Map<*, *> -> Received(CarPlayUiSyncMessageFactory.decodeWireValue(data, packet = true), display, packet = true)
            else -> throw CarPlayUiSyncProtocolException("UI Sync display carrier data must be NSData or a dictionary packet")
        }
    }
}
