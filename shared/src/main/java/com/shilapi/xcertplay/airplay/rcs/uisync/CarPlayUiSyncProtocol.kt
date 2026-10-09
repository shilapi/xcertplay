package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.RcsMessage

class CarPlayUiSyncProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

enum class CarPlayUiSyncMessageType(val wireName: String, val wireValue: Long) {
    RESET("reset", 0),
    RESET_COMPLETE("resetComplete", 1),
    COMMAND("command", 2),
    COMMAND_ACK("commandAck", 3),
    ;

    companion object {
        fun fromWireName(value: String): CarPlayUiSyncMessageType =
            entries.firstOrNull { it.wireName == value }
                ?: throw CarPlayUiSyncProtocolException(
                    "Unknown UI Sync message type '$value'",
                )
    }
}

enum class CarPlayUiSyncProtocolVersion(val wireName: String, val wireValue: Long) {
    V1("v1", 1),
    V2("v2", 2),
    V3("v3", 3),
    ;

    companion object {
        fun fromWireName(value: String): CarPlayUiSyncProtocolVersion =
            entries.firstOrNull { it.wireName == value }
                ?: throw CarPlayUiSyncProtocolException(
                    "Unknown UI Sync protocol version '$value'",
                )
    }
}

enum class CarPlayUiSyncSessionState(val wireName: String) {
    INITIALIZED("initialized"),
    AWAITING_RESET_SELF_INITIATED("awaitingResetSelfInitiated"),
    AWAITING_RESET_COMPLETE_REMOTE_INITIATED("awaitingResetCompleteRemoteInitiated"),
    READY("ready"),
}

enum class CarPlayUiSyncCommand(val wireName: String) {
    TRANSITION_START("transitionStart"),
    TRANSITION_DATA("transitionData"),
    LAYOUT_CHANGE("layoutChange"),
    TRANSITION_END("transitionEnd"),
    REQUEST_LAYOUT("requestLayout"),
    END_LAYOUT_CHANGE("endLayoutChange"),
    GIVE_FOCUS("giveFocus"),
    REQUEST_FOCUS("requestFocus"),
    METADATA_TRANSFER("metadataTransfer"),
    PALETTE_CHANGE("paletteChange"),
    ALLOW_TRANSITIONS("allowTransitions"),
    TARGET_APPEARANCE_CHANGE("targetAppearanceChange"),
    APPEARANCE_PREFERENCE_CHANGE("appearancePreferenceChange"),
    DRIVE_MODE_CHANGE("driveModeChange"),
    APPEARANCE_CHANGE_START("appearanceChangeStart"),
    APPEARANCE_CHANGE_END("appearanceChangeEnd"),
    UI_CONFIGURATION_CHANGE("uiConfigurationChange"),
    DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE(
        "driveModeThemeConfigurationOverrideChange",
    ),
    DRIVE_MODE_DYNAMIC_ASSIGNMENT_CHANGE("driveModeDynamicAssignmentChange"),
    ;

    companion object {
        // LayoutController.lua in the signed 44C9FA31EC/985 asset. Values are hexadecimal.
        private val assetTypes = mapOf(
            0x01L to TRANSITION_START, 0x02L to TRANSITION_DATA, 0x03L to LAYOUT_CHANGE,
            0x04L to TRANSITION_END, 0x05L to REQUEST_LAYOUT, 0x06L to END_LAYOUT_CHANGE,
            0x07L to GIVE_FOCUS, 0x08L to REQUEST_FOCUS, 0x09L to METADATA_TRANSFER,
            0x10L to PALETTE_CHANGE, 0x11L to ALLOW_TRANSITIONS,
            0x12L to TARGET_APPEARANCE_CHANGE, 0x13L to APPEARANCE_PREFERENCE_CHANGE,
            0x14L to DRIVE_MODE_CHANGE,
        )
        fun fromAssetType(value: Long): CarPlayUiSyncCommand = assetTypes[value]
            ?: throw CarPlayUiSyncProtocolException("Unknown OEM UI command type $value")
        fun assetType(command: CarPlayUiSyncCommand): Long = assetTypes.entries.firstOrNull { it.value == command }?.key
            ?: throw CarPlayUiSyncProtocolException("Command has no confirmed OEM wire type: $command")
        fun fromWireName(value: String): CarPlayUiSyncCommand =
            entries.firstOrNull { it.wireName == value }
                ?: throw CarPlayUiSyncProtocolException(
                    "Unknown UI Sync command '$value'",
                )
    }
}

/**
 * Typed command dictionary, encoded as nested binary plist in the envelope's `data` field.
 *
 * OEM payloads use numeric type/data from LayoutController.lua. The string command encoding
 * remains available for the local reference backend; it is not the OEM wire format.
 */
data class CarPlayUiSyncCommandPayload(
    val command: CarPlayUiSyncCommand,
    val fields: Map<String, Any?> = emptyMap(),
    val assetWire: Boolean = false,
) {
    init {
        if (fields.containsKey("command")) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync command payload must not contain a nested 'command' key",
            )
        }
        if (!assetWire) validatePayload(command, fields)
        else CarPlayUiSyncCommand.assetType(command)
    }

    fun toWireMap(): Map<String, Any?> = if (assetWire) linkedMapOf(
        "type" to CarPlayUiSyncCommand.assetType(command), "data" to fields,
    ) else linkedMapOf<String, Any?>(
        "command" to command.wireName,
    ).apply {
        putAll(fields)
    }

    companion object {
        fun fromWireMap(values: Map<String, Any?>): CarPlayUiSyncCommandPayload {
            if (values.containsKey("type")) {
                require(!values.containsKey("command")) { "Conflicting OEM UI command/type" }
                val type = when (val raw = values["type"]) {
                    is Long -> raw
                    is Int -> raw.toLong()
                    else -> throw CarPlayUiSyncProtocolException("OEM command type must be an integer")
                }
                val rawData = values["data"] as? Map<*, *>
                    ?: throw CarPlayUiSyncProtocolException("OEM command data must be a dictionary")
                require(rawData.keys.all { it is String }) { "OEM command data keys must be strings" }
                @Suppress("UNCHECKED_CAST")
                return CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.fromAssetType(type),
                    LinkedHashMap(rawData as Map<String, Any?>), assetWire = true)
            }
            val command = values["command"] as? String
                ?: throw CarPlayUiSyncProtocolException(
                    "UI Sync command payload requires a string 'command'",
                )
            return CarPlayUiSyncCommandPayload(
                command = CarPlayUiSyncCommand.fromWireName(command),
                fields = LinkedHashMap(values).apply { remove("command") },
            )
        }
    }
}

/**
 * Accessory wire envelope carried over `CarPlayClusterControl`.
 * Numeric type/version and nested binary-plist data follow the acquired production asset's
 * APL/ClusterControlSession.lua. Swift/log enum names are not the serialized wire values.
 *
 * Optional Swift properties are omitted from [toWireMap] when null. The sequence fields are kept
 * as signed 64-bit values so binary-plist integers round-trip without narrowing.
 */
data class CarPlayUiSyncMessage(
    val type: CarPlayUiSyncMessageType,
    val sessionSequenceNumber: Long,
    val packetSequenceNumber: Long,
    val acknowledgementSequenceNumber: Long,
    val payload: Map<String, Any?>? = null,
    val noAck: Boolean = false,
    val vehicleId: String? = null,
    val version: CarPlayUiSyncProtocolVersion? = null,
    val remoteIdentifier: String? = null,
) {
    init {
        requireSequence("ssn", sessionSequenceNumber)
        requireSequence("psn", packetSequenceNumber)
        requireSequence("asn", acknowledgementSequenceNumber)
    }

    fun toWireMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "type" to type.wireValue,
        "ssn" to sessionSequenceNumber,
        "psn" to packetSequenceNumber,
        "asn" to acknowledgementSequenceNumber,
    ).apply {
        payload?.let { put("data", BplistCodec.encode(it)) }
        // The production asset reads noAck but never emits it. Keep it as an inbound/local flag.
        vehicleId?.let { put("vehicleID", it) }
        version?.let { put("version", it.wireValue) }
        remoteIdentifier?.let { put("id", it) }
    }

    fun toBplist(): ByteArray = BplistCodec.encode(toWireMap())

    fun toRcsMessage(): RcsMessage = RcsMessage.comm(toBplist())

    fun commandPayload(): CarPlayUiSyncCommandPayload? {
        if (type != CarPlayUiSyncMessageType.COMMAND || payload == null) return null
        return CarPlayUiSyncCommandPayload.fromWireMap(payload)
    }

    private fun requireSequence(name: String, value: Long) {
        if (value < 0) {
            throw CarPlayUiSyncProtocolException("UI Sync '$name' must be non-negative")
        }
    }
}

object CarPlayUiSyncMessageFactory {
    fun reset(
        sessionSequenceNumber: Long,
        packetSequenceNumber: Long,
        acknowledgementSequenceNumber: Long,
        noAck: Boolean = false,
        vehicleId: String? = null,
        version: CarPlayUiSyncProtocolVersion? = null,
    ): CarPlayUiSyncMessage = message(
        type = CarPlayUiSyncMessageType.RESET,
        sessionSequenceNumber = sessionSequenceNumber,
        packetSequenceNumber = packetSequenceNumber,
        acknowledgementSequenceNumber = acknowledgementSequenceNumber,
        noAck = noAck,
        vehicleId = vehicleId,
        version = version,
    )

    fun resetComplete(
        sessionSequenceNumber: Long,
        packetSequenceNumber: Long,
        acknowledgementSequenceNumber: Long,
        noAck: Boolean = false,
        vehicleId: String? = null,
        version: CarPlayUiSyncProtocolVersion? = null,
    ): CarPlayUiSyncMessage = message(
        type = CarPlayUiSyncMessageType.RESET_COMPLETE,
        sessionSequenceNumber = sessionSequenceNumber,
        packetSequenceNumber = packetSequenceNumber,
        acknowledgementSequenceNumber = acknowledgementSequenceNumber,
        noAck = noAck,
        vehicleId = vehicleId,
        version = version,
    )

    fun command(
        sessionSequenceNumber: Long,
        packetSequenceNumber: Long,
        acknowledgementSequenceNumber: Long,
        payload: CarPlayUiSyncCommandPayload,
        noAck: Boolean = false,
        vehicleId: String? = null,
        version: CarPlayUiSyncProtocolVersion? = null,
    ): CarPlayUiSyncMessage = message(
        type = CarPlayUiSyncMessageType.COMMAND,
        sessionSequenceNumber = sessionSequenceNumber,
        packetSequenceNumber = packetSequenceNumber,
        acknowledgementSequenceNumber = acknowledgementSequenceNumber,
        payload = payload.toWireMap(),
        noAck = noAck,
        vehicleId = vehicleId,
        version = version,
    )

    fun command(
        sessionSequenceNumber: Long,
        packetSequenceNumber: Long,
        acknowledgementSequenceNumber: Long,
        command: CarPlayUiSyncCommand,
        fields: Map<String, Any?> = emptyMap(),
        noAck: Boolean = false,
        vehicleId: String? = null,
        version: CarPlayUiSyncProtocolVersion? = null,
    ): CarPlayUiSyncMessage = command(
        sessionSequenceNumber = sessionSequenceNumber,
        packetSequenceNumber = packetSequenceNumber,
        acknowledgementSequenceNumber = acknowledgementSequenceNumber,
        payload = CarPlayUiSyncCommandPayload(command, fields),
        noAck = noAck,
        vehicleId = vehicleId,
        version = version,
    )

    fun commandAck(
        sessionSequenceNumber: Long,
        packetSequenceNumber: Long,
        acknowledgementSequenceNumber: Long,
        noAck: Boolean = false,
        vehicleId: String? = null,
        version: CarPlayUiSyncProtocolVersion? = null,
    ): CarPlayUiSyncMessage = message(
        type = CarPlayUiSyncMessageType.COMMAND_ACK,
        sessionSequenceNumber = sessionSequenceNumber,
        packetSequenceNumber = packetSequenceNumber,
        acknowledgementSequenceNumber = acknowledgementSequenceNumber,
        noAck = noAck,
        vehicleId = vehicleId,
        version = version,
    )

    fun decode(body: ByteArray): CarPlayUiSyncMessage {
        val value = try {
            BplistCodec.decode(body)
        } catch (error: Exception) {
            throw CarPlayUiSyncProtocolException("UI Sync body is not a binary plist", error)
        }
        return decodeWireValue(value)
    }

    fun decodeWireValue(value: Any?, packet: Boolean = false): CarPlayUiSyncMessage {
        val map = value as? Map<*, *>
            ?: throw CarPlayUiSyncProtocolException("UI Sync message must be a dictionary")
        val typed = LinkedHashMap<String, Any?>(map.size)
        map.forEach { (key, item) ->
            val name = key as? String
                ?: throw CarPlayUiSyncProtocolException(
                    "UI Sync message keys must be strings",
                )
            typed[name] = item
        }

        val type = when (val value = typed["type"]) {
            is String -> CarPlayUiSyncMessageType.fromWireName(value)
            is Byte, is Short, is Int, is Long -> CarPlayUiSyncMessageType.entries
                .firstOrNull { it.wireValue == (value as Number).toLong() }
                ?: throw CarPlayUiSyncProtocolException("Unknown numeric UI Sync type")
            else -> throw CarPlayUiSyncProtocolException(
                "UI Sync message 'type' must be an integer",
            )
        }
        require(!(typed.containsKey("data") && typed.containsKey("payload"))) { "Conflicting UI Sync data/payload" }
        val numericWire = typed["type"] !is String
        val rawPayload = if (numericWire) (typed["data"] ?: typed["payload"])?.let {
            when {
                it is ByteArray -> BplistCodec.decode(it)
                (packet || typed.containsKey("payload")) && it is Map<*, *> -> it
                else -> throw CarPlayUiSyncProtocolException("UI Sync data must be NSData or negotiated dictionary data")
            }
        } else typed["payload"]
        val payload = when (val value = rawPayload) {
            null -> null
            is Map<*, *> -> stringMap(value, "payload")
            else -> throw CarPlayUiSyncProtocolException(
                "UI Sync message 'payload' must be a dictionary",
            )
        }
        val noAck = if (numericWire && !typed.containsKey("noAck")) false else typed["noAck"] as? Boolean
            ?: throw CarPlayUiSyncProtocolException(
                "UI Sync message 'noAck' must be a boolean",
            )
        val vehicleId = typed["vehicleID"]?.let { value ->
            value as? String
                ?: throw CarPlayUiSyncProtocolException(
                    "UI Sync message 'vehicleID' must be a string",
                )
        }
        val version = typed["version"]?.let { value ->
            when (value) {
                is String -> CarPlayUiSyncProtocolVersion.fromWireName(value)
                is Byte, is Short, is Int, is Long -> when ((value as Number).toLong()) {
                    1L -> CarPlayUiSyncProtocolVersion.V1
                    2L -> CarPlayUiSyncProtocolVersion.V2
                    in 3..Long.MAX_VALUE -> CarPlayUiSyncProtocolVersion.V3
                    else -> throw CarPlayUiSyncProtocolException("Invalid numeric UI Sync version")
                }
                else -> throw CarPlayUiSyncProtocolException("UI Sync version must be an integer")
            }
        }
        val remoteIdentifier = typed["id"]?.let {
            it as? String ?: throw CarPlayUiSyncProtocolException("UI Sync id must be a string")
        }

        return CarPlayUiSyncMessage(
            type = type,
            sessionSequenceNumber = sequence(typed, "ssn"),
            packetSequenceNumber = sequence(typed, "psn"),
            acknowledgementSequenceNumber = sequence(typed, "asn"),
            payload = payload,
            noAck = noAck,
            vehicleId = vehicleId,
            version = version,
            remoteIdentifier = remoteIdentifier,
        )
    }

    fun encode(message: CarPlayUiSyncMessage): ByteArray = message.toBplist()

    private fun message(
        type: CarPlayUiSyncMessageType,
        sessionSequenceNumber: Long,
        packetSequenceNumber: Long,
        acknowledgementSequenceNumber: Long,
        payload: Map<String, Any?>? = null,
        noAck: Boolean = false,
        vehicleId: String? = null,
        version: CarPlayUiSyncProtocolVersion? = null,
    ): CarPlayUiSyncMessage = CarPlayUiSyncMessage(
        type = type,
        sessionSequenceNumber = sessionSequenceNumber,
        packetSequenceNumber = packetSequenceNumber,
        acknowledgementSequenceNumber = acknowledgementSequenceNumber,
        payload = payload,
        noAck = noAck,
        vehicleId = vehicleId,
        version = version,
    )
}

private fun validatePayload(
    command: CarPlayUiSyncCommand,
    fields: Map<String, Any?>,
) {
    fun requireField(name: String) {
        if (!fields.containsKey(name)) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync command '${command.wireName}' requires '$name'",
            )
        }
    }

    when (command) {
        CarPlayUiSyncCommand.GIVE_FOCUS,
        CarPlayUiSyncCommand.REQUEST_FOCUS,
        -> {
            requireField("focusToken")
            fields["focusToken"]?.let { value ->
                if (value !is Number && value !is String) {
                    throw CarPlayUiSyncProtocolException(
                        "UI Sync field 'focusToken' must be an integer or string",
                    )
                }
            }
        }

        CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE -> {
            requireField("appearanceMode")
            requireString(fields, "appearanceMode")
        }

        CarPlayUiSyncCommand.APPEARANCE_PREFERENCE_CHANGE -> {
            requireField("preference")
            requireField("override")
            requireString(fields, "preference")
            fields["override"]?.let { value ->
                if (value !is Boolean && value !is String) {
                    throw CarPlayUiSyncProtocolException(
                        "UI Sync command 'appearancePreferenceChange' field 'override' " +
                            "must be a boolean or string",
                    )
                }
            }
        }

        CarPlayUiSyncCommand.DRIVE_MODE_CHANGE -> {
            requireField("driveMode")
            requireString(fields, "driveMode")
        }

        CarPlayUiSyncCommand.UI_CONFIGURATION_CHANGE -> {
            requireField("uiConfiguration")
            requireString(fields, "uiConfiguration")
            fields["data"]?.let { value ->
                if (value !is Map<*, *> && value !is ByteArray) {
                    throw CarPlayUiSyncProtocolException(
                        "UI Sync command 'uiConfigurationChange' field 'data' " +
                            "must be a dictionary or data",
                    )
                }
            }
        }

        CarPlayUiSyncCommand.DRIVE_MODE_DYNAMIC_ASSIGNMENT_CHANGE -> {
            requireField("driveModeToLayoutIdAssignments")
            fields["driveModeToLayoutIdAssignments"]?.let { value ->
                if (value !is Map<*, *>) {
                    throw CarPlayUiSyncProtocolException(
                        "UI Sync command 'driveModeDynamicAssignmentChange' field " +
                            "'driveModeToLayoutIdAssignments' must be a dictionary",
                    )
                }
            }
        }

        CarPlayUiSyncCommand.REQUEST_LAYOUT -> {
            fields["layout"]?.let { value ->
                if (value !is String) {
                    throw CarPlayUiSyncProtocolException(
                        "UI Sync command 'requestLayout' field 'layout' must be a string",
                    )
                }
            }
            fields["fadeOutOldLayout"]?.let { value ->
                if (value !is Boolean) {
                    throw CarPlayUiSyncProtocolException(
                        "UI Sync command 'requestLayout' field 'fadeOutOldLayout' " +
                            "must be a boolean",
                    )
                }
            }
            fields["activeComponents"]?.let { value ->
                if (value !is Collection<*>) {
                    throw CarPlayUiSyncProtocolException(
                        "UI Sync command 'requestLayout' field 'activeComponents' " +
                            "must be an array or set",
                    )
                }
            }
        }

        else -> Unit
    }
}

private fun requireString(
    fields: Map<String, Any?>,
    name: String,
) {
    val value = fields[name] ?: return
    if (value !is String) {
        throw CarPlayUiSyncProtocolException(
            "UI Sync field '$name' must be a string",
        )
    }
}

private fun stringMap(value: Map<*, *>, label: String): Map<String, Any?> =
    linkedMapOf<String, Any?>().apply {
        value.forEach { (key, item) ->
            val name = key as? String
                ?: throw CarPlayUiSyncProtocolException("$label keys must be strings")
            put(name, item)
        }
    }

private fun sequence(values: Map<String, Any?>, name: String): Long {
    val value = values[name]
        ?: throw CarPlayUiSyncProtocolException("UI Sync message '$name' is required")
    return when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> throw CarPlayUiSyncProtocolException(
            "UI Sync message '$name' must be an integer",
        )
    }
}
