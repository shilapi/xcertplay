package com.shilapi.xcertplay.airplay.rcs.caf

/**
 * Directional CAF message models. These are intentionally separate types rather than a shared
 * parent with fixed `rx`/`tx` fields.
 */
sealed interface CafDirectionalMessage {
    val envelope: CafEnvelope

    fun asFrame(): CafRcsFrame = CafRcsCodec.encode(envelope)

    data class Request(
        override val envelope: CafEnvelope,
    ) : CafDirectionalMessage {
        init {
            require(envelope.message.command in REQUEST_COMMANDS) {
                "${envelope.message.command.wireName} is not a CAF request"
            }
        }
    }

    data class Response(
        override val envelope: CafEnvelope,
    ) : CafDirectionalMessage {
        init {
            require(envelope.message.command in RESPONSE_COMMANDS) {
                "${envelope.message.command.wireName} is not a CAF response"
            }
        }
    }

    data class Notification(
        override val envelope: CafEnvelope,
    ) : CafDirectionalMessage {
        init {
            require(envelope.message.command in NOTIFICATION_COMMANDS) {
                "${envelope.message.command.wireName} is not a CAF notification"
            }
        }
    }

    data class Error(
        override val envelope: CafEnvelope,
    ) : CafDirectionalMessage {
        init {
            require(envelope.message.command == CafCommand.GENERAL_ERROR) {
                "${envelope.message.command.wireName} is not a CAF general error"
            }
        }
    }

    companion object {
        fun from(frame: CafRcsFrame): CafDirectionalMessage = when {
            frame.envelope.message.command in REQUEST_COMMANDS -> Request(frame.envelope)
            frame.envelope.message.command in RESPONSE_COMMANDS -> Response(frame.envelope)
            frame.envelope.message.command in NOTIFICATION_COMMANDS ->
                Notification(frame.envelope)
            frame.envelope.message.command == CafCommand.GENERAL_ERROR ->
                Error(frame.envelope)
            else -> throw CafProtocolException(
                "CAF command ${frame.envelope.message.command.wireName} has no message category",
            )
        }

        val REQUEST_COMMANDS = setOf(
            CafCommand.CONFIG_REQUEST,
            CafCommand.REGISTER_REQUEST,
            CafCommand.UNREGISTER_REQUEST,
            CafCommand.READ_REQUEST,
            CafCommand.WRITE_REQUEST,
            CafCommand.CONTROL_REQUEST,
        )

        val RESPONSE_COMMANDS = setOf(
            CafCommand.CONFIG_RESPONSE,
            CafCommand.REGISTER_RESPONSE,
            CafCommand.UNREGISTER_RESPONSE,
            CafCommand.READ_RESPONSE,
            CafCommand.WRITE_RESPONSE,
            CafCommand.CONTROL_RESPONSE,
        )

        val NOTIFICATION_COMMANDS = setOf(
            CafCommand.CONFIG_NOTIFY,
            CafCommand.CONTROL_NOTIFY,
            CafCommand.UPDATE_NOTIFY,
        )
    }
}
