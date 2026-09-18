package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.RcsMessage

class CarPlayUiSyncProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

enum class CarPlayUiSyncMessageType(val wireName: String) {
    RESET("reset"),
    RESET_COMPLETE("resetComplete"),
    COMMAND("command"),
    COMMAND_ACK("commandAck"),
    ;

    companion object {
        fun fromWireName(value: String): CarPlayUiSyncMessageType =
            entries.firstOrNull { it.wireName == value }
                ?: throw CarPlayUiSyncProtocolException(
                    "Unknown UI Sync message type '$value'",
                )
    }
}

enum class CarPlayUiSyncProtocolVersion(val wireName: String) {
    V1("v1"),
    V2("v2"),
    V3("v3"),
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
        fun fromWireName(value: String): CarPlayUiSyncCommand =
            entries.firstOrNull { it.wireName == value }
                ?: throw CarPlayUiSyncProtocolException(
                    "Unknown UI Sync command '$value'",
                )
    }
}

/**
 * Typed `payload` dictionary for [CarPlayUiSyncMessageType.COMMAND].
 *
 * The command name is always emitted. Firmware-confirmed required fields are checked when this
 * type is constructed; callers can preserve additional OEM fields through [fields].
 */
data class CarPlayUiSyncCommandPayload(
    val command: CarPlayUiSyncCommand,
    val fields: Map<String, Any?> = emptyMap(),
) {
    init {
        if (fields.containsKey("command")) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync command payload must not contain a nested 'command' key",
            )
        }
        validatePayload(command, fields)
    }

    fun toWireMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "command" to command.wireName,
    ).apply {
        putAll(fields)
    }

    companion object {
        fun fromWireMap(values: Map<String, Any?>): CarPlayUiSyncCommandPayload {
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
 * `DashBoard.DBUISyncSessionMessage` envelope carried over `CarPlayClusterControl`.
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
) {
    init {
        requireSequence("ssn", sessionSequenceNumber)
        requireSequence("psn", packetSequenceNumber)
        requireSequence("asn", acknowledgementSequenceNumber)
    }

    fun toWireMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "type" to type.wireName,
        "ssn" to sessionSequenceNumber,
        "psn" to packetSequenceNumber,
        "asn" to acknowledgementSequenceNumber,
    ).apply {
        payload?.let { put("payload", LinkedHashMap(it)) }
        put("noAck", noAck)
        vehicleId?.let { put("vehicleID", it) }
        version?.let { put("version", it.wireName) }
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

    fun decodeWireValue(value: Any?): CarPlayUiSyncMessage {
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
            else -> throw CarPlayUiSyncProtocolException(
                "UI Sync message 'type' must be a string",
            )
        }
        val payload = when (val value = typed["payload"]) {
            null -> null
            is Map<*, *> -> stringMap(value, "payload")
            else -> throw CarPlayUiSyncProtocolException(
                "UI Sync message 'payload' must be a dictionary",
            )
        }
        val noAck = typed["noAck"] as? Boolean
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
            val wireName = value as? String
                ?: throw CarPlayUiSyncProtocolException(
                    "UI Sync message 'version' must be a string",
                )
            CarPlayUiSyncProtocolVersion.fromWireName(wireName)
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
