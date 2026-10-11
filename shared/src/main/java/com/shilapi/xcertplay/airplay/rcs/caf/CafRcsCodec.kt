package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec

/** CarData uses OPACK for both blobs; explicit binary-plist peers remain supported. */
enum class CafPayloadEncoding { OPACK, BINARY_PLIST }

/** Direct comm bodies are observed on the wire; command wrappers remain supported. */
enum class CafPayloadFraming { DIRECT, COMMAND_PARAMS_DATA }

object CafRcsCodec {
    fun encode(
        envelope: CafEnvelope,
        encoding: CafPayloadEncoding = envelope.encoding,
        framing: CafPayloadFraming = envelope.framing,
    ): CafRcsFrame {
        envelope.message.validateWireShape()
        if (envelope.pluginId < 0) {
            throw CafProtocolException("pluginId must not be negative")
        }

        val inner = LinkedHashMap<String, Any?>()
        inner["command"] = envelope.message.command.wireName
        envelope.message.transactionId?.let { inner["transactionID"] = it }
        envelope.message.values?.let { inner["values"] = it }
        envelope.message.errors?.let { inner["errors"] = it }
        envelope.message.error?.let { inner["error"] = it }

        val outer = linkedMapOf<String, Any?>(
            "pluginID" to envelope.pluginId,
            "pluginData" to encodeBlob(inner, encoding),
        )
        val outerBytes = encodeBlob(outer, encoding)
        val body = when (framing) {
            CafPayloadFraming.DIRECT -> outerBytes
            CafPayloadFraming.COMMAND_PARAMS_DATA -> BplistCodec.encode(
                linkedMapOf(
                    "params" to linkedMapOf("data" to outerBytes),
                ),
            )
        }
        return CafRcsFrame(envelope.copy(encoding = encoding, framing = framing), body)
    }

    fun decode(rcsBody: ByteArray): CafEnvelope {
        val body = requireMap(decodeBlob(rcsBody), "RCS body")
        val framing = if (body.containsKey("params")) {
            if (body.containsKey("pluginID") || body.containsKey("pluginData")) {
                throw CafProtocolException("RCS body has conflicting CAF framings")
            }
            CafPayloadFraming.COMMAND_PARAMS_DATA
        } else {
            CafPayloadFraming.DIRECT
        }
        val outerBytes = when (framing) {
            CafPayloadFraming.DIRECT -> rcsBody
            CafPayloadFraming.COMMAND_PARAMS_DATA -> {
                val params = requireMap(body["params"], "RCS body params")
                params["data"] as? ByteArray
                    ?: throw CafProtocolException("RCS body params.data must be NSData")
            }
        }
        val outer = if (framing == CafPayloadFraming.DIRECT) body
            else requireMap(decodeBlob(outerBytes), "CAF outer blob")
        val pluginId = CafNumbers.toLong(outer["pluginID"], "CAF pluginID")
        if (pluginId < 0) throw CafProtocolException("pluginId must not be negative")
        val innerBytes = outer["pluginData"] as? ByteArray
            ?: throw CafProtocolException("CAF outer pluginData must be NSData")
        val inner = requireMap(decodeBlob(innerBytes), "CAF inner blob")
        val commandName = inner["command"] as? String
            ?: throw CafProtocolException("CAF inner command must be a string")
        val command = CafCommand.find(commandName)
            ?: throw CafProtocolException("Unknown CAF command '$commandName'")
        val transactionId = inner["transactionID"]?.let {
            CafNumbers.toLong(it, "CAF transactionID")
        }
        val errors = inner["errors"]?.let {
            CafNumbers.toLongMap(it, "CAF errors")
        }
        val error = inner["error"]?.let {
            CafNumbers.toLong(it, "CAF error")
        }
        val message = CafMessage(
            command = command,
            transactionId = transactionId,
            values = inner["values"],
            errors = errors,
            error = error,
        )
        message.validateWireShape()
        return CafEnvelope(
            pluginId,
            message,
            if (isPlist(outerBytes)) CafPayloadEncoding.BINARY_PLIST else CafPayloadEncoding.OPACK,
            framing,
        )
    }

    private fun isPlist(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals("bplist00".toByteArray(Charsets.US_ASCII))

    private fun encodeBlob(value: Any?, encoding: CafPayloadEncoding): ByteArray = when (encoding) {
        CafPayloadEncoding.BINARY_PLIST -> BplistCodec.encode(value)
        CafPayloadEncoding.OPACK -> OpackCodec.encode(value)
    }

    private fun decodeBlob(bytes: ByteArray): Any? =
        if (isPlist(bytes)) BplistCodec.decode(bytes) else OpackCodec.decode(bytes)

    @Suppress("UNCHECKED_CAST")
    private fun requireMap(value: Any?, label: String): Map<Any?, Any?> =
        value as? Map<Any?, Any?>
            ?: throw CafProtocolException("$label must be a dictionary")
}

/**
 * iAPChannel is an iAP2 tunnel, not a CAF channel. Its body remains byte-for-byte raw iAP2.
 */
object IapChannelPayload {
    /** Accessory-initiated: this iAP2 frame is wrapped for the phone, so it carries `cmnd`. */
    fun fromDevice(iap2Frame: ByteArray): RcsMessage =
        RcsMessage.uplink(iap2Frame.copyOf())

    fun toDevice(message: RcsMessage): ByteArray {
        if (message.messageType != com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec.MESSAGE_TYPE_COMM) {
            throw CafProtocolException("iAPChannel payload must use comm messageType")
        }
        return message.body.copyOf()
    }

    fun asRcsMessage(iap2Frame: ByteArray): RcsMessage = fromDevice(iap2Frame)

    fun from(message: RcsMessage): ByteArray = toDevice(message)
}
