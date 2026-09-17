package com.shilapi.xcertplay.airplay.rcs.caf

/**
 * Semantic CAF message factories.
 *
 * These methods return a complete RCS body, not a transport frame. The caller sends
 * [CafRcsFrame.asRcsMessage] through the negotiated CarPlayProtocolData channel.
 */
object CarAccessoryMessages {
    fun configRequest(pluginId: Long, transactionId: Long): CafRcsFrame =
        frame(
            pluginId,
            CafMessage(
                command = CafCommand.CONFIG_REQUEST,
                transactionId = transactionId,
            ),
        )

    fun configResponse(
        pluginId: Long,
        transactionId: Long,
        values: Map<*, *>,
    ): CafRcsFrame =
        frame(
            pluginId,
            CafMessage(
                command = CafCommand.CONFIG_RESPONSE,
                transactionId = transactionId,
                values = values,
            ),
        )

    fun configNotify(pluginId: Long, values: Map<*, *>): CafRcsFrame =
        frame(
            pluginId,
            CafMessage(
                command = CafCommand.CONFIG_NOTIFY,
                values = values,
            ),
        )

    fun registerRequest(
        pluginId: Long,
        transactionId: Long,
        registration: CafRegistration = CafRegistration.Wildcard,
    ): CafRcsFrame {
        val values = when (registration) {
            CafRegistration.Wildcard -> "*"
            is CafRegistration.Identifiers -> registration.values
        }
        return frame(
            pluginId,
            CafMessage(
                command = CafCommand.REGISTER_REQUEST,
                transactionId = transactionId,
                values = values,
            ),
        )
    }

    fun registerResponse(
        pluginId: Long,
        transactionId: Long,
        values: Map<Long, Any?>,
        errors: Map<Long, Long>,
    ): CafRcsFrame =
        frame(
            pluginId,
            CafMessage(
                command = CafCommand.REGISTER_RESPONSE,
                transactionId = transactionId,
                values = values,
                errors = errors,
            ),
        )

    fun unregisterRequest(
        pluginId: Long,
        transactionId: Long,
        identifiers: List<Long>,
    ): CafRcsFrame {
        require(identifiers.isNotEmpty()) { "identifiers must not be empty" }
        return frame(
            pluginId,
            CafMessage(
                command = CafCommand.UNREGISTER_REQUEST,
                transactionId = transactionId,
                values = identifiers,
            ),
        )
    }

    fun unregisterResponse(pluginId: Long, transactionId: Long): CafRcsFrame =
        frame(
            pluginId,
            CafMessage(
                command = CafCommand.UNREGISTER_RESPONSE,
                transactionId = transactionId,
            ),
        )

    fun readRequest(
        pluginId: Long,
        transactionId: Long,
        identifiers: List<Long>,
    ): CafRcsFrame {
        require(identifiers.isNotEmpty()) { "identifiers must not be empty" }
        return frame(
            pluginId,
            CafMessage(
                command = CafCommand.READ_REQUEST,
                transactionId = transactionId,
                values = identifiers,
            ),
        )
    }

    fun readResponse(
        pluginId: Long,
        transactionId: Long,
        values: Map<Long, Any?>,
        errors: Map<Long, Long>,
    ): CafRcsFrame =
        frame(
            pluginId,
            CafMessage(
                command = CafCommand.READ_RESPONSE,
                transactionId = transactionId,
                values = values,
                errors = errors,
            ),
        )

    fun writeRequest(
        pluginId: Long,
        transactionId: Long,
        values: Map<Long, Any?>,
    ): CafRcsFrame =
        frame(
            pluginId,
            CafMessage(
                command = CafCommand.WRITE_REQUEST,
                transactionId = transactionId,
                values = values,
            ),
        )

    fun writeResponse(
        pluginId: Long,
        transactionId: Long,
        errors: Map<Long, Long>,
    ): CafRcsFrame =
        frame(
            pluginId,
            CafMessage(
                command = CafCommand.WRITE_RESPONSE,
                transactionId = transactionId,
                errors = errors,
            ),
        )

    fun controlRequest(
        pluginId: Long,
        transactionId: Long,
        values: Map<Long, Any?>,
    ): CafRcsFrame =
        frame(
            pluginId,
            CafMessage(
                command = CafCommand.CONTROL_REQUEST,
                transactionId = transactionId,
                values = values,
            ),
        )

    fun controlResponse(
        pluginId: Long,
        transactionId: Long,
        values: Map<Long, Any?>,
        errors: Map<Long, Long>,
    ): CafRcsFrame =
        frame(
            pluginId,
            CafMessage(
                command = CafCommand.CONTROL_RESPONSE,
                transactionId = transactionId,
                values = values,
                errors = errors,
            ),
        )

    fun controlNotify(pluginId: Long, values: Map<Long, Any?>): CafRcsFrame =
        frame(
            pluginId,
            CafMessage(
                command = CafCommand.CONTROL_NOTIFY,
                values = values,
            ),
        )

    fun updateNotify(pluginId: Long, values: Map<Long, Any?>): CafRcsFrame =
        frame(
            pluginId,
            CafMessage(
                command = CafCommand.UPDATE_NOTIFY,
                values = values,
            ),
        )

    fun generalError(
        pluginId: Long,
        error: Long,
        transactionId: Long? = null,
    ): CafRcsFrame =
        frame(
            pluginId,
            CafMessage(
                command = CafCommand.GENERAL_ERROR,
                transactionId = transactionId,
                error = error,
            ),
        )

    fun reader(rcsBody: ByteArray): CafMessageReader =
        CafMessageReader(CafRcsCodec.decode(rcsBody))

    private fun frame(pluginId: Long, message: CafMessage): CafRcsFrame =
        CafRcsCodec.encode(CafEnvelope(pluginId, message))
}
