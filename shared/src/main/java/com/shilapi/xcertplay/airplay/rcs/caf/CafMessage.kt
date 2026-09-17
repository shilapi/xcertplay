package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import java.math.BigInteger

class CafProtocolException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

data class CafMessage(
    val command: CafCommand,
    val transactionId: Long? = null,
    val values: Any? = null,
    val errors: Map<Long, Long>? = null,
    val error: Long? = null,
)

data class CafEnvelope(
    val pluginId: Long,
    val message: CafMessage,
)

data class CafRcsFrame(
    val envelope: CafEnvelope,
    val rcsBody: ByteArray,
) {
    fun asRcsMessage(): RcsMessage = RcsMessage.comm(rcsBody)
}

class CafMessageReader internal constructor(
    val envelope: CafEnvelope,
) {
    val pluginId: Long
        get() = envelope.pluginId

    val message: CafMessage
        get() = envelope.message

    val command: CafCommand
        get() = message.command

    val transactionId: Long?
        get() = message.transactionId

    fun requireTransactionId(): Long =
        transactionId ?: throw CafProtocolException("${command.wireName} has no transactionID")

    fun values(): Any? = message.values

    fun valuesMap(): Map<Long, Any?> =
        CafNumbers.toLongKeyedMap(values(), "${command.wireName}.values")

    fun valuesList(): List<Any?> =
        values() as? List<Any?>
            ?: throw CafProtocolException("${command.wireName}.values is not an array")

    fun errors(): Map<Long, Long> =
        message.errors ?: throw CafProtocolException("${command.wireName} has no errors map")

    fun requireError(): Long =
        message.error ?: throw CafProtocolException("${command.wireName} has no error")
}

internal object CafNumbers {
    fun toLong(value: Any?, label: String): Long = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        is BigInteger -> {
            val longValue = value.toLong()
            if (BigInteger.valueOf(longValue) != value) {
                throw CafProtocolException("$label is outside the signed 64-bit range")
            }
            longValue
        }
        else -> throw CafProtocolException("$label must be an integer, got $value")
    }

    fun toLongOrNull(value: Any?): Long? = try {
        toLong(value, "number")
    } catch (_: CafProtocolException) {
        null
    }

    fun toLongKeyedMap(value: Any?, label: String): Map<Long, Any?> {
        val source = value as? Map<*, *>
            ?: throw CafProtocolException("$label must be a dictionary")
        val result = LinkedHashMap<Long, Any?>(source.size)
        source.forEach { (key, item) ->
            result[toLong(key, "$label key")] = item
        }
        return result
    }

    fun toLongMap(value: Any?, label: String): Map<Long, Long> {
        val source = value as? Map<*, *>
            ?: throw CafProtocolException("$label must be a dictionary")
        val result = LinkedHashMap<Long, Long>(source.size)
        source.forEach { (key, item) ->
            result[toLong(key, "$label key")] = toLong(item, "$label value")
        }
        return result
    }
}

internal fun CafMessage.validateWireShape() {
    when (command.transactionRule) {
        CafTransactionRule.REQUIRED -> if (transactionId == null) {
            throw CafProtocolException("${command.wireName} requires transactionID")
        }

        CafTransactionRule.OPTIONAL -> Unit
        CafTransactionRule.FORBIDDEN -> if (transactionId != null) {
            throw CafProtocolException("${command.wireName} must not contain transactionID")
        }
    }

    when (command.valueRule) {
        CafValueRule.NONE -> if (values != null) {
            throw CafProtocolException("${command.wireName} must not contain values")
        }

        CafValueRule.WILDCARD_OR_IID_LIST -> {
            if (values != "*" && !isIidList(values)) {
                throw CafProtocolException(
                    "${command.wireName}.values must be \"*\" or an array of IIDs",
                )
            }
        }

        CafValueRule.IID_LIST -> if (!isIidList(values)) {
            throw CafProtocolException("${command.wireName}.values must be an array of IIDs")
        }

        CafValueRule.IID_VALUE_MAP -> CafNumbers.toLongKeyedMap(
            values,
            "${command.wireName}.values",
        )

        CafValueRule.CONFIG_TREE -> if (values !is Map<*, *>) {
            throw CafProtocolException("${command.wireName}.values must be a dictionary")
        }
    }

    when (command.errorRule) {
        CafErrorRule.NONE -> {
            if (errors != null) {
                throw CafProtocolException("${command.wireName} must not contain errors")
            }
            if (error != null) {
                throw CafProtocolException("${command.wireName} must not contain error")
            }
        }

        CafErrorRule.IID_ERROR_MAP -> {
            if (errors == null) {
                throw CafProtocolException("${command.wireName} requires errors")
            }
            if (error != null) {
                throw CafProtocolException("${command.wireName} must not contain error")
            }
        }

        CafErrorRule.OS_STATUS -> {
            if (error == null) {
                throw CafProtocolException("${command.wireName} requires error")
            }
            if (errors != null) {
                throw CafProtocolException("${command.wireName} must not contain errors")
            }
        }
    }
}

private fun isIidList(value: Any?): Boolean {
    val list = value as? List<*> ?: return false
    return list.all { CafNumbers.toLongOrNull(it) != null }
}
