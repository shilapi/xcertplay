package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec

/**
 * CAF carrier codec.
 *
 * RCS body:
 * `bplist({"params":{"data": outerOpack}})`
 *
 * Outer OPACK:
 * `{"pluginID": NSNumber, "pluginData": NSData(innerOpack)}`
 */
object CafRcsCodec {
    fun encode(envelope: CafEnvelope): CafRcsFrame {
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
            "pluginData" to OpackCodec.encode(inner),
        )
        val body = BplistCodec.encode(
            linkedMapOf(
                "params" to linkedMapOf(
                    "data" to OpackCodec.encode(outer),
                ),
            ),
        )
        return CafRcsFrame(envelope, body)
    }

    fun decode(rcsBody: ByteArray): CafEnvelope {
        val wrapper = requireMap(BplistCodec.decode(rcsBody), "RCS body")
        val params = requireMap(wrapper["params"], "RCS body params")
        val outerBytes = params["data"] as? ByteArray
            ?: throw CafProtocolException("RCS body params.data must be NSData")
        val outer = requireMap(OpackCodec.decode(outerBytes), "CAF outer OPACK")
        val pluginId = CafNumbers.toLong(outer["pluginID"], "CAF pluginID")
        val innerBytes = outer["pluginData"] as? ByteArray
            ?: throw CafProtocolException("CAF outer pluginData must be NSData")
        val inner = requireMap(OpackCodec.decode(innerBytes), "CAF inner OPACK")
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
        return CafEnvelope(pluginId, message)
    }

    @Suppress("UNCHECKED_CAST")
    private fun requireMap(value: Any?, label: String): Map<Any?, Any?> =
        value as? Map<Any?, Any?>
            ?: throw CafProtocolException("$label must be a dictionary")
}

/**
 * iAPChannel is an iAP2 tunnel, not a CAF channel. Its body remains byte-for-byte raw iAP2.
 */
object IapChannelPayload {
    fun asRcsMessage(iap2Frame: ByteArray): RcsMessage =
        RcsMessage.comm(iap2Frame.copyOf())

    fun from(message: RcsMessage): ByteArray {
        if (message.messageType != com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec.MESSAGE_TYPE_COMM) {
            throw CafProtocolException("iAPChannel payload must use comm messageType")
        }
        return message.body.copyOf()
    }
}
