package com.shilapi.xcertplay.airplay.rcs.caf

import java.math.BigInteger

@JvmInline
value class CafTypeId(val value: Long) {
    init {
        require(value >= 0) { "CAF type ID must be a non-negative 64-bit integer" }
    }

    fun hex(): String = "0x${value.toString(16).padStart(16, '0')}"
}

@JvmInline
value class CafIid(val value: Long) {
    init {
        require(value >= 0) { "CAF IID must be a non-negative 64-bit integer" }
    }
}

enum class CafCharacteristicFormat(val wireValue: Long) {
    BOOL(0),
    UINT8(1),
    UINT16(2),
    UINT32(3),
    UINT64(4),
    INT8(5),
    INT16(6),
    INT32(7),
    INT64(8),
    FLOAT(9),
    STRING(10),
    DATA(11),
    DICTIONARY(12),
    ARRAY(13),
    ;

    companion object {
        fun fromWireValue(value: Long): CafCharacteristicFormat =
            entries.firstOrNull { it.wireValue == value }
                ?: throw CafProtocolException("Unknown CAF characteristic format $value")
    }
}

sealed interface CafFormatValue {
    data class Bool(val value: Boolean) : CafFormatValue
    data class Unsigned(val value: BigInteger, val bits: Int) : CafFormatValue
    data class Signed(val value: BigInteger, val bits: Int) : CafFormatValue
    data class Floating(val value: Double) : CafFormatValue
    data class Text(val value: String) : CafFormatValue
    data class Binary(val value: ByteArray) : CafFormatValue
    data class Dictionary(val value: Map<*, *>) : CafFormatValue
    data class ArrayValue(val value: List<*>) : CafFormatValue

    companion object {
        fun decode(
            value: Any?,
            format: CafCharacteristicFormat,
            label: String,
        ): CafFormatValue = when (format) {
            CafCharacteristicFormat.BOOL -> decodeBool(value, label)
            CafCharacteristicFormat.UINT8 -> decodeUnsigned(value, 8, label)
            CafCharacteristicFormat.UINT16 -> decodeUnsigned(value, 16, label)
            CafCharacteristicFormat.UINT32 -> decodeUnsigned(value, 32, label)
            CafCharacteristicFormat.UINT64 -> decodeUnsigned(value, 64, label)
            CafCharacteristicFormat.INT8 -> decodeSigned(value, 8, label)
            CafCharacteristicFormat.INT16 -> decodeSigned(value, 16, label)
            CafCharacteristicFormat.INT32 -> decodeSigned(value, 32, label)
            CafCharacteristicFormat.INT64 -> decodeSigned(value, 64, label)
            CafCharacteristicFormat.FLOAT ->
                Floating((value as? Number)?.toDouble()
                    ?: throw CafProtocolException("$label must be numeric"))
            CafCharacteristicFormat.STRING ->
                Text(value as? String ?: throw CafProtocolException("$label must be a string"))
            CafCharacteristicFormat.DATA ->
                Binary(value as? ByteArray ?: throw CafProtocolException("$label must be NSData"))
            CafCharacteristicFormat.DICTIONARY ->
                Dictionary(value as? Map<*, *>
                    ?: throw CafProtocolException("$label must be a dictionary"))
            CafCharacteristicFormat.ARRAY ->
                ArrayValue(value as? List<*>
                    ?: throw CafProtocolException("$label must be an array"))
        }

        private fun decodeBool(value: Any?, label: String): Bool = when (value) {
            is Boolean -> Bool(value)
            is Byte -> boolNumber(value.toLong(), label)
            is Short -> boolNumber(value.toLong(), label)
            is Int -> boolNumber(value.toLong(), label)
            is Long -> boolNumber(value, label)
            is BigInteger -> boolNumber(value, label)
            else -> throw CafProtocolException("$label must be a boolean")
        }

        private fun boolNumber(value: Long, label: String): Bool = when (value) {
            0L -> Bool(false)
            1L -> Bool(true)
            else -> throw CafProtocolException("$label must be 0 or 1")
        }

        private fun boolNumber(value: BigInteger, label: String): Bool =
            if (value == BigInteger.ZERO) Bool(false)
            else if (value == BigInteger.ONE) Bool(true)
            else throw CafProtocolException("$label must be 0 or 1")

        private fun decodeUnsigned(
            value: Any?,
            bits: Int,
            label: String,
        ): Unsigned {
            val integer = toInteger(value, label)
            val min = BigInteger.ZERO
            val max = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
            if (integer < min || integer > max) {
                throw CafProtocolException("$label is outside the unsigned $bits-bit range")
            }
            return Unsigned(integer, bits)
        }

        private fun decodeSigned(
            value: Any?,
            bits: Int,
            label: String,
        ): Signed {
            val integer = toInteger(value, label)
            val limit = BigInteger.ONE.shiftLeft(bits - 1)
            val min = limit.negate()
            val max = limit.subtract(BigInteger.ONE)
            if (integer < min || integer > max) {
                throw CafProtocolException("$label is outside the signed $bits-bit range")
            }
            return Signed(integer, bits)
        }

        private fun toInteger(value: Any?, label: String): BigInteger = when (value) {
            is Byte -> BigInteger.valueOf(value.toLong())
            is Short -> BigInteger.valueOf(value.toLong())
            is Int -> BigInteger.valueOf(value.toLong())
            is Long -> BigInteger.valueOf(value)
            is BigInteger -> value
            else -> throw CafProtocolException("$label must be an integer")
        }
    }
}

data class CafControlParameterMetadata(
    val name: String,
    val format: CafCharacteristicFormat,
    val supportsInvalid: Boolean,
    val extensions: Map<String, Any?>,
)

enum class CafControlSender(val wireName: String) {
    DEVICE("device"),
    ACCESSORY("accessory"),
    ;

    companion object {
        fun fromWireName(value: String): CafControlSender =
            entries.firstOrNull { it.wireName == value }
                ?: throw CafProtocolException("Unknown CAF control sender '$value'")
    }
}

data class CafControlMetadata(
    val type: CafTypeId,
    val iid: CafIid,
    val sender: CafControlSender,
    val hasResponse: Boolean,
    val priority: Long?,
    val requestParameters: List<CafControlParameterMetadata>,
    val responseParameters: List<CafControlParameterMetadata>,
    val iidError: CafIid?,
    val iidDisabled: CafIid?,
    val iidRestricted: CafIid?,
    val iidHidden: CafIid?,
    val extensions: Map<String, Any?>,
)

data class CafCharacteristicMetadata(
    val type: CafTypeId,
    val iid: CafIid,
    val format: CafCharacteristicFormat,
    val writable: Boolean?,
    val mutable: Boolean?,
    val initialValue: CafFormatValue?,
    val priority: Long?,
    val largePayload: Boolean?,
    val supportsInvalid: Boolean?,
    val iidError: CafIid?,
    val iidDisabled: CafIid?,
    val iidNotifier: CafIid?,
    val iidRestricted: CafIid?,
    val iidHidden: CafIid?,
    val minimumValue: CafFormatValue?,
    val maximumValue: CafFormatValue?,
    val maximumLength: Int?,
    val stepValue: CafFormatValue?,
    val validValues: List<CafFormatValue>,
    val units: Any?,
    val oemWritable: Boolean?,
    val extensions: Map<String, Any?>,
)

data class CafServiceMetadata(
    val type: CafTypeId,
    val iid: CafIid,
    val characteristics: List<CafCharacteristicMetadata>,
    val controls: List<CafControlMetadata>,
    val multipleInstances: Boolean?,
    val indexBy: Any?,
    val sortBy: Any?,
    val extensions: Map<String, Any?>,
)

data class CafAccessoryMetadata(
    val type: CafTypeId,
    val iid: CafIid,
    val version: String,
    val services: List<CafServiceMetadata>,
    val extensions: Map<String, Any?>,
)

/**
 * Firmware-confirmed CAF config tree.
 *
 * Unknown dictionary entries are retained as extension data rather than interpreted as known
 * fields. Missing required fields, malformed value formats, and unknown characteristic formats are
 * rejected.
 */
data class CafConfigTree(
    val root: Map<String, Any?>,
    val accessories: List<CafAccessoryMetadata>,
    val extensions: Map<String, Any?>,
) {
    companion object {
        fun decode(raw: Any?): CafConfigTree = CafFirmwareConfigTreeDecoder.decode(raw)
    }
}

fun interface CafConfigTreeDecoder {
    fun decode(raw: Any?): CafConfigTree
}

fun interface CafConfigTreeDecoderFactory {
    fun create(protocolVersion: String, pluginConfig: Any?): CafConfigTreeDecoder
}

/**
 * Selects a plugin-specific decoder first, then falls back to the decoder registered for the
 * negotiated Vehicle State protocol version. The default registry contains the firmware-confirmed
 * protocol 1.0 structural schema; OEM value interpretation remains a separate plugin extension.
 */
class ProtocolCafConfigTreeDecoderFactory(
    private val protocolDecoders: Map<String, CafConfigTreeDecoder> =
        mapOf("1.0" to CafFirmwareConfigTreeDecoder),
    private val pluginDecoderSelector: (String, Any?) -> CafConfigTreeDecoder? = { _, _ -> null },
) : CafConfigTreeDecoderFactory {
    override fun create(protocolVersion: String, pluginConfig: Any?): CafConfigTreeDecoder {
        pluginDecoderSelector(protocolVersion, pluginConfig)?.let { return it }
        return protocolDecoders[protocolVersion]
            ?: throw CafProtocolException(
                "No CAF config tree decoder for protocol version '$protocolVersion'",
            )
    }

    companion object {
        val FIRMWARE_V1: CafConfigTreeDecoderFactory =
            ProtocolCafConfigTreeDecoderFactory()
    }
}

object CafFirmwareConfigTreeDecoder : CafConfigTreeDecoder {
    override fun decode(raw: Any?): CafConfigTree {
        val root = requireStringMap(raw, "CAF config tree")
        val accessoryValues = root["accessories"]
            ?: throw CafProtocolException("CAF config tree is missing 'accessories'")
        val accessories = requireList(accessoryValues, "CAF config tree accessories")
            .mapIndexed { index, entry ->
                decodeAccessory(entry, "CAF accessory[$index]")
            }
        return CafConfigTree(
            root = root,
            accessories = accessories,
            extensions = root.unknownFields(ACCESSORY_TREE_KEYS),
        )
    }

    private fun decodeAccessory(raw: Any?, label: String): CafAccessoryMetadata {
        val map = requireStringMap(raw, label)
        val services = map.optionalList("services", "$label.services")
            .mapIndexed { index, entry ->
                decodeService(entry, "$label.services[$index]")
            }
        return CafAccessoryMetadata(
            type = map.requiredType("type", label),
            iid = map.requiredIid("iid", label),
            version = map.requiredString("version", label),
            services = services,
            extensions = map.unknownFields(ACCESSORY_KEYS),
        )
    }

    private fun decodeService(raw: Any?, label: String): CafServiceMetadata {
        val map = requireStringMap(raw, label)
        val characteristics = map.optionalList("characteristics", "$label.characteristics")
            .mapIndexed { index, entry ->
                decodeCharacteristic(entry, "$label.characteristics[$index]")
            }
        val controls = map.optionalList("controls", "$label.controls")
            .mapIndexed { index, entry ->
                decodeControl(entry, "$label.controls[$index]")
            }
        return CafServiceMetadata(
            type = map.requiredType("type", label),
            iid = map.requiredIid("iid", label),
            characteristics = characteristics,
            controls = controls,
            multipleInstances = map.optionalBoolean("multipleInstances"),
            indexBy = map["indexBy"],
            sortBy = map["sortBy"],
            extensions = map.unknownFields(SERVICE_KEYS),
        )
    }

    private fun decodeCharacteristic(
        raw: Any?,
        label: String,
    ): CafCharacteristicMetadata {
        val map = requireStringMap(raw, label)
        val format = CafCharacteristicFormat.fromWireValue(
            map.requiredLong("format", label),
        )
        val validValues = map.optionalList("validValues", "$label.validValues")
            .mapIndexed { index, value ->
                CafFormatValue.decode(value, format, "$label.validValues[$index]")
            }
        return CafCharacteristicMetadata(
            type = map.requiredType("type", label),
            iid = map.requiredIid("iid", label),
            format = format,
            writable = map.optionalBoolean("writable"),
            mutable = map.optionalBoolean("mutable"),
            initialValue = map.optionalFormatValue("initialValue", format, label),
            priority = map.optionalLong("priority", label),
            largePayload = map.optionalBoolean("largePayload"),
            supportsInvalid = map.optionalBoolean("supportsInvalid"),
            iidError = map.optionalIid("iidError"),
            iidDisabled = map.optionalIid("iidDisabled"),
            iidNotifier = map.optionalIid("iidNotifier"),
            iidRestricted = map.optionalIid("iidRestricted"),
            iidHidden = map.optionalIid("iidHidden"),
            minimumValue = map.optionalFormatValue("minimumValue", format, label),
            maximumValue = map.optionalFormatValue("maximumValue", format, label),
            maximumLength = map.optionalLong("maximumLength", label)?.toIntExact(
                "$label.maximumLength",
            ),
            stepValue = map.optionalFormatValue("stepValue", format, label),
            validValues = validValues,
            units = map["units"],
            oemWritable = map.optionalBoolean("oemWritable"),
            extensions = map.unknownFields(CHARACTERISTIC_KEYS),
        )
    }

    private fun decodeControl(raw: Any?, label: String): CafControlMetadata {
        val map = requireStringMap(raw, label)
        return CafControlMetadata(
            type = map.requiredType("type", label),
            iid = map.requiredIid("iid", label),
            sender = CafControlSender.fromWireName(map.requiredString("sender", label)),
            hasResponse = map.requiredBoolean("hasResponse", label),
            priority = map.optionalLong("priority", label),
            requestParameters = map.optionalList(
                "requestParameters",
                "$label.requestParameters",
            ).mapIndexed { index, parameter ->
                decodeControlParameter(parameter, "$label.requestParameters[$index]")
            },
            responseParameters = map.optionalList(
                "responseParameters",
                "$label.responseParameters",
            ).mapIndexed { index, parameter ->
                decodeControlParameter(parameter, "$label.responseParameters[$index]")
            },
            iidError = map.optionalIid("iidError"),
            iidDisabled = map.optionalIid("iidDisabled"),
            iidRestricted = map.optionalIid("iidRestricted"),
            iidHidden = map.optionalIid("iidHidden"),
            extensions = map.unknownFields(CONTROL_KEYS),
        )
    }

    private fun decodeControlParameter(
        raw: Any?,
        label: String,
    ): CafControlParameterMetadata {
        val map = requireStringMap(raw, label)
        return CafControlParameterMetadata(
            name = map.requiredString("name", label),
            format = CafCharacteristicFormat.fromWireValue(
                map.requiredLong("format", label),
            ),
            supportsInvalid = map.optionalBoolean("supportsInvalid") ?: false,
            extensions = map.unknownFields(CONTROL_PARAMETER_KEYS),
        )
    }

    private fun Map<String, Any?>.requiredType(key: String, label: String): CafTypeId =
        CafTypeId(requiredLong(key, label))

    private fun Map<String, Any?>.requiredIid(key: String, label: String): CafIid =
        CafIid(requiredLong(key, label))

    private fun Map<String, Any?>.optionalIid(key: String): CafIid? =
        optionalLong(key, key)?.let(::CafIid)

    private fun Map<String, Any?>.optionalFormatValue(
        key: String,
        format: CafCharacteristicFormat,
        label: String,
    ): CafFormatValue? =
        if (containsKey(key)) CafFormatValue.decode(this[key], format, "$label.$key") else null

    private fun Map<String, Any?>.requiredString(key: String, label: String): String =
        this[key] as? String
            ?: throw CafProtocolException("$label.$key must be a string")

    private fun Map<String, Any?>.requiredLong(key: String, label: String): Long =
        optionalLong(key, "$label.$key")
            ?: throw CafProtocolException("$label.$key is required")

    private fun Map<String, Any?>.requiredBoolean(key: String, label: String): Boolean =
        this[key] as? Boolean
            ?: throw CafProtocolException("$label.$key must be a boolean")

    private fun Map<String, Any?>.optionalLong(key: String, label: String): Long? {
        val value = this[key] ?: return null
        return CafNumbers.toLong(value, label)
    }

    private fun Map<String, Any?>.optionalBoolean(key: String): Boolean? {
        val value = this[key] ?: return null
        return value as? Boolean
            ?: throw CafProtocolException("$key must be a boolean")
    }

    private fun Map<String, Any?>.optionalList(
        key: String,
        label: String,
    ): List<Any?> =
        if (containsKey(key)) requireList(this[key], label) else emptyList()

    private fun Map<String, Any?>.unknownFields(known: Set<String>): Map<String, Any?> =
        filterKeys { it !in known }

    private fun requireStringMap(value: Any?, label: String): Map<String, Any?> {
        val map = value as? Map<*, *>
            ?: throw CafProtocolException("$label must be a dictionary")
        val result = LinkedHashMap<String, Any?>(map.size)
        map.forEach { (key, entry) ->
            val stringKey = key as? String
                ?: throw CafProtocolException("$label keys must be strings")
            result[stringKey] = entry
        }
        return result
    }

    private fun requireList(value: Any?, label: String): List<Any?> =
        (value as? List<*>)
            ?.map { it }
            ?: throw CafProtocolException("$label must be an array")

    private fun Long.toIntExact(label: String): Int {
        if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) {
            throw CafProtocolException("$label is outside the 32-bit range")
        }
        return toInt()
    }

    private val ACCESSORY_TREE_KEYS = setOf("accessories")

    private val ACCESSORY_KEYS = setOf("type", "iid", "version", "services")

    private val SERVICE_KEYS = setOf(
        "type",
        "iid",
        "characteristics",
        "controls",
        "multipleInstances",
        "indexBy",
        "sortBy",
    )

    private val CHARACTERISTIC_KEYS = setOf(
        "type",
        "iid",
        "format",
        "writable",
        "mutable",
        "initialValue",
        "priority",
        "largePayload",
        "supportsInvalid",
        "iidError",
        "iidDisabled",
        "iidNotifier",
        "iidRestricted",
        "iidHidden",
        "minimumValue",
        "maximumValue",
        "maximumLength",
        "stepValue",
        "validValues",
        "units",
        "oemWritable",
    )

    private val CONTROL_KEYS = setOf(
        "type",
        "iid",
        "sender",
        "hasResponse",
        "priority",
        "requestParameters",
        "responseParameters",
        "iidError",
        "iidDisabled",
        "iidRestricted",
        "iidHidden",
    )

    private val CONTROL_PARAMETER_KEYS = setOf("name", "format", "supportsInvalid")
}
