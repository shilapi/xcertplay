package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.caf.CafMessageReader
import com.shilapi.xcertplay.airplay.rcs.caf.CafNumbers
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginHandler
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistration
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistry
import com.shilapi.xcertplay.airplay.rcs.caf.CafProtocolSession
import com.shilapi.xcertplay.airplay.rcs.caf.CafRcsFrame
import com.shilapi.xcertplay.airplay.rcs.caf.CarAccessoryMessages
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes

/**
 * Enables the mandatory Ultra channels with type-valid fallback payloads.
 *
 * The plugin ID and mapping are deliberately synthetic and are not claimed to represent an OEM
 * vehicle. Replace this provisioning with an OEM registration bundle when the real schema is known.
 */
object CarPlayUltraFallbackProvisioning {
    const val PLUGIN_ID = 1L
    const val PLUGIN_NAME = "xcertplay.fallback"

    fun create(): AirPlayUltraProvisioning {
        val registration = CafPluginRegistration(
            pluginId = PLUGIN_ID,
            protocolVersion = CafPluginRegistry.CAF_PROTOCOL_VERSION_1_0,
            clientTypes = setOf(
                RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
            ),
            handler = CafFallbackPluginHandler(),
            pluginName = PLUGIN_NAME,
        )
        return AirPlayUltraProvisioning(
            pluginRegistrations = listOf(registration),
            uiSyncInfo = AirPlayUiSyncInfo(),
            rcsFactories = mapOf(
                RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL to CarPlayClusterControlFallbackFactory,
            ),
            runtimeFeatures = setOf(
                AirPlayFeature.ALT_SCREEN,
                AirPlayFeature.UI_CONTEXT,
            ),
        )
    }
}

internal class CafFallbackPluginHandler(
    private val responseSink: ((CafRcsFrame) -> Unit)? = null,
) : CafPluginHandler {
    override fun onConfigRequest(session: CafProtocolSession, message: CafMessageReader) {
        send(
            session,
            fallbackConfigResponse(message.pluginId, message.requireTransactionId()),
        )
    }

    override fun onRegisterRequest(session: CafProtocolSession, message: CafMessageReader) {
        send(
            session,
            CarAccessoryMessages.registerResponse(
                pluginId = message.pluginId,
                transactionId = message.requireTransactionId(),
                values = emptyMap(),
                errors = emptyMap(),
            ),
        )
    }

    override fun onUnregisterRequest(session: CafProtocolSession, message: CafMessageReader) {
        send(
            session,
            CarAccessoryMessages.unregisterResponse(
                pluginId = message.pluginId,
                transactionId = message.requireTransactionId(),
            ),
        )
    }

    override fun onReadRequest(session: CafProtocolSession, message: CafMessageReader) {
        val identifiers = message.valuesList().map {
            CafNumbers.toLong(it, "readRequest IID")
        }
        send(
            session,
            CarAccessoryMessages.readResponse(
                pluginId = message.pluginId,
                transactionId = message.requireTransactionId(),
                values = emptyMap(),
                errors = identifiers.associateWith { 0L },
            ),
        )
    }

    override fun onWriteRequest(session: CafProtocolSession, message: CafMessageReader) {
        send(
            session,
            CarAccessoryMessages.writeResponse(
                pluginId = message.pluginId,
                transactionId = message.requireTransactionId(),
                errors = message.valuesMap().keys.associateWith { 0L },
            ),
        )
    }

    override fun onControlRequest(session: CafProtocolSession, message: CafMessageReader) {
        val identifiers = message.valuesMap().keys
        send(
            session,
            CarAccessoryMessages.controlResponse(
                pluginId = message.pluginId,
                transactionId = message.requireTransactionId(),
                values = identifiers.associateWith { null },
                errors = identifiers.associateWith { 0L },
            ),
        )
    }

    private fun send(session: CafProtocolSession, frame: CafRcsFrame) {
        responseSink?.invoke(frame) ?: session.send(frame)
    }
}

internal fun fallbackConfigResponse(pluginId: Long, transactionId: Long): CafRcsFrame =
    CarAccessoryMessages.configResponse(
        pluginId = pluginId,
        transactionId = transactionId,
        values = linkedMapOf("accessories" to emptyList<Any?>()),
    )

private object CarPlayClusterControlFallbackFactory : RcsDataStreamHandlerFactory {
    override fun supports(clientType: com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType): Boolean =
        clientType == RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL

    override fun create(stream: RcsDataStream): RcsDataStreamHandler? =
        if (supports(stream.clientType)) CarPlayClusterControlFallbackHandler else null
}

private object CarPlayClusterControlFallbackHandler : RcsDataStreamHandler {
    override fun onStreamOpened(stream: RcsDataStream) {
        stream.traceProtocol("CarPlayClusterControl opened")
    }

    override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
        stream.traceProtocol(
            "CarPlayClusterControl RX messageType=0x${message.messageType.toString(16)} " +
                "body=${message.body.size}B bodyHex=${ProtocolTraceFormatter.hex(message.body)}",
        )
    }

    override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
        stream.traceProtocol(
            "CarPlayClusterControl closed cause=" +
                (cause?.message ?: cause?.javaClass?.simpleName ?: "none"),
        )
    }
}
