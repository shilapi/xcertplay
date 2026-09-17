package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes

/**
 * Application-level CAF plugin.
 *
 * Requests, responses, notifications, and errors have separate callbacks so a plugin does not need
 * to infer direction from a generic message handler.
 */
interface CafPluginHandler {
    fun onTransactionCompleted(
        session: CafProtocolSession,
        transaction: CafPendingTransaction,
        message: CafMessageReader,
    ) {
        // Default no-op; the command-specific callback still runs.
    }

    fun onConfigRequest(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    fun onConfigResponse(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    fun onConfigNotify(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    fun onRegisterRequest(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    fun onRegisterResponse(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    fun onUnregisterRequest(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    fun onUnregisterResponse(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    fun onReadRequest(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    fun onReadResponse(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    fun onWriteRequest(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    fun onWriteResponse(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    fun onControlRequest(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    fun onControlResponse(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    fun onControlNotify(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    fun onUpdateNotify(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    fun onGeneralError(session: CafProtocolSession, message: CafMessageReader) =
        unsupported(session, message)

    private fun unsupported(session: CafProtocolSession, message: CafMessageReader) {
        throw CafProtocolException(
            "CAF plugin ${message.pluginId} does not implement ${message.command.wireName} " +
                "on ${session.clientType.name}",
        )
    }
}

/** One CAF plugin's registration and channel routing metadata. */
data class CafPluginRegistration(
    val pluginId: Long,
    val protocolVersion: String,
    val clientTypes: Set<RcsClientType>,
    val handler: CafPluginHandler,
    val pluginName: String? = null,
    val pluginConfig: Any? = null,
    val configTreeDecoder: CafConfigTreeDecoder? = null,
) {
    init {
        require(pluginId >= 0) { "CAF plugin ID must be non-negative" }
        require(protocolVersion.isNotBlank()) { "CAF protocol version must not be blank" }
        require(clientTypes.isNotEmpty()) { "CAF plugin must be assigned to an RCS client type" }
    }

    /**
     * Returns the element used in AirPlay `vehicleStateProtocolInfo.pluginConfigs`.
     *
     * `CAFCarConfiguration` requires this array element to be an NSDictionary with an NSNumber
     * `pluginID`. Caller-supplied fields are retained, but cannot overwrite or contradict the ID
     * registered for the handler.
     */
    fun wirePluginConfig(): Map<String, Any?> {
        val supplied = when (val value = pluginConfig) {
            null -> emptyMap()
            is Map<*, *> -> value.entries.associate { (key, item) ->
                (key as? String)
                    ?: throw CafProtocolException("pluginConfig keys must be strings")
                key to item
            }
            else -> throw CafProtocolException(
                "CAF plugin $pluginId pluginConfig must be a dictionary",
            )
        }
        val suppliedId = supplied["pluginID"]
        if (suppliedId != null && toPluginId(suppliedId) != pluginId) {
            throw CafProtocolException(
                "CAF plugin $pluginId pluginConfig.pluginID must match the registration",
            )
        }
        return linkedMapOf<String, Any?>("pluginID" to pluginId).apply {
            supplied.forEach { (key, value) ->
                if (key != "pluginID") put(key, value)
            }
        }
    }

    private fun toPluginId(value: Any): Long = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> throw CafProtocolException("pluginConfig.pluginID must be an integer")
    }
}

/** Supplies CAF registrations without hard-coding OEM plugin IDs into the transport layer. */
fun interface CafPluginRegistrationProvider {
    fun registrations(): Collection<CafPluginRegistration>
}

/**
 * Runtime plugin registry. A client type is routable only when at least one registered plugin
 * explicitly claims it.
 */
class CafPluginRegistry private constructor(
    handlers: Map<Long, CafPluginHandler>,
    private val registrations: Map<Long, CafPluginRegistration>,
) {
    constructor(handlers: Map<Long, CafPluginHandler>) : this(
        handlers = handlers,
        registrations = handlers.mapValues { (pluginId, handler) ->
            CafPluginRegistration(
                pluginId = pluginId,
                protocolVersion = CAF_PROTOCOL_VERSION_1_0,
                clientTypes = DEFAULT_CLIENT_TYPES,
                handler = handler,
            )
        },
    )

    private val handlers = LinkedHashMap(handlers)

    fun require(pluginId: Long): CafPluginHandler =
        handlers[pluginId]
            ?: throw CafProtocolException("No CAF handler registered for pluginID $pluginId")

    fun requireRegistration(pluginId: Long): CafPluginRegistration =
        registrations[pluginId]
            ?: throw CafProtocolException("No CAF registration for pluginID $pluginId")

    fun supports(clientType: RcsClientType): Boolean =
        registrations.values.any { clientType in it.clientTypes }

    fun registrationsFor(clientType: RcsClientType): List<CafPluginRegistration> =
        registrations.values.filter { clientType in it.clientTypes }

    fun allRegistrations(): List<CafPluginRegistration> = registrations.values.toList()

    companion object {
        const val CAF_PROTOCOL_VERSION_1_0 = "1.0"

        val DEFAULT_CLIENT_TYPES: Set<RcsClientType> = setOf(
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
        )

        fun from(registrations: Collection<CafPluginRegistration>): CafPluginRegistry {
            val byId = LinkedHashMap<Long, CafPluginRegistration>(registrations.size)
            registrations.forEach { registration ->
                require(byId.put(registration.pluginId, registration) == null) {
                    "Duplicate CAF registration for pluginID ${registration.pluginId}"
                }
            }
            return CafPluginRegistry(
                handlers = byId.mapValues { it.value.handler },
                registrations = byId,
            )
        }

        fun from(provider: CafPluginRegistrationProvider): CafPluginRegistry =
            from(provider.registrations())
    }
}
