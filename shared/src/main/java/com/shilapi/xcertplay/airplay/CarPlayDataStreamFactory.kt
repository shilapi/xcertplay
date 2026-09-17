package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsPayloadStyle
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal data class CarPlayDataStreamHandlers(
    val iap: ((BlockingDuplexByteStream) -> Boolean)?,
    val rcs: RcsDataStreamHandlerFactory?,
    val onIapFallback: (ByteArray) -> Unit,
)

internal data class OpenedCarPlayDataStream(
    val port: Int,
    val closeable: Closeable,
    val attach: () -> Boolean,
)

internal enum class CarPlayDataStreamKind {
    IAP_TUNNEL,
    RCS,
}

/**
 * Creates a type-130 data stream according to the RCS client-type catalog.
 *
 * The iAP strategy reuses [IapTunnel] and [AirPlayIapTunnelStream]. All other confirmed RCS client
 * types share [RcsDataStream]. Application-level CAF, cluster, update, and logging schemas remain
 * behind a handler selected by the caller.
 */
internal class CarPlayDataStreamFactory {
    fun open(
        session: AirPlaySession,
        clientType: RcsClientType,
        seed: String,
        handlers: CarPlayDataStreamHandlers,
    ): OpenedCarPlayDataStream? {
        val shared = session.sharedSecret ?: return null
        val outputKey = deriveKey(shared, seed, DATASTREAM_OUTPUT_KEY)
        val inputKey = deriveKey(shared, seed, DATASTREAM_INPUT_KEY)
        return when (kindFor(clientType)) {
            CarPlayDataStreamKind.IAP_TUNNEL -> openIap(session, outputKey, handlers)
            CarPlayDataStreamKind.RCS ->
                openRcs(session, clientType, outputKey, inputKey, handlers.rcs)
        }
    }

    internal fun kindFor(clientType: RcsClientType): CarPlayDataStreamKind =
        when (clientType.payloadStyle) {
            RcsPayloadStyle.RAW_IAP2 -> CarPlayDataStreamKind.IAP_TUNNEL
            RcsPayloadStyle.CAF_BINARY_PLIST_OPACK,
            RcsPayloadStyle.UNCONFIRMED_OPAQUE -> CarPlayDataStreamKind.RCS
        }

    private fun openIap(
        session: AirPlaySession,
        outputKey: ByteArray,
        handlers: CarPlayDataStreamHandlers,
    ): OpenedCarPlayDataStream {
        val tunnel = IapTunnel(
            readKey = outputKey,
            bindAddress = bindAddress(session),
        )
        val applicationHandler = handlers.iap
        if (applicationHandler == null) {
            val port = tunnel.listen(
                object : IapTunnel.Listener {
                    override fun onIap(bytes: ByteArray) = handlers.onIapFallback(bytes)

                    override fun onClosed(cause: Throwable?) {
                        session.logDebug(
                            "AirPlay iAP tunnel ended reason=" +
                                "${cause?.message ?: "peer EOF"}",
                        )
                        if (cause != null) session.close()
                    }
                },
            )
            return OpenedCarPlayDataStream(port, tunnel) { true }
        }

        val bridge = AirPlayIapTunnelStream(session, tunnel)
        val port = bridge.listen()
        return OpenedCarPlayDataStream(port, bridge) {
            try {
                applicationHandler(bridge)
            } catch (error: Throwable) {
                session.logDebug("AirPlay iAP relay attachment failed: ${error.message}")
                false
            }
        }
    }

    private fun openRcs(
        session: AirPlaySession,
        clientType: RcsClientType,
        outputKey: ByteArray,
        inputKey: ByteArray,
        handlerFactory: RcsDataStreamHandlerFactory?,
    ): OpenedCarPlayDataStream? {
        if (handlerFactory == null) {
            session.logDebug(
                "AirPlay RCS SETUP rejected clientType=${clientType.name}: no payload handler",
            )
            return null
        }
        if (!handlerFactory.supports(clientType)) {
            session.logDebug(
                "AirPlay RCS SETUP rejected clientType=${clientType.name}: " +
                    "handler does not support this client type",
            )
            return null
        }
        val stream = RcsDataStream.open(
            clientType = clientType,
            readKey = outputKey,
            writeKey = inputKey,
            bindAddress = bindAddress(session),
            onTrace = session::logTrace,
        )
        val port = try {
            stream.listen()
        } catch (error: Throwable) {
            stream.close()
            throw error
        }
        val handler = try {
            handlerFactory.create(stream)
        } catch (error: Throwable) {
            stream.close()
            throw error
        }
        if (handler == null) {
            stream.close()
            session.logDebug(
                "AirPlay RCS SETUP rejected clientType=${clientType.name}: no payload decoder",
            )
            return null
        }
        return OpenedCarPlayDataStream(port, stream) {
            try {
                stream.attach(handler)
                true
            } catch (error: Throwable) {
                session.logDebug(
                    "AirPlay RCS handler attachment failed clientType=${clientType.name}: " +
                        "${error.message}",
                )
                false
            }
        }
    }

    private fun deriveKey(shared: ByteArray, seed: String, label: String): ByteArray =
        AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            label.toByteArray(Charsets.US_ASCII),
            32,
        )

    private fun bindAddress(session: AirPlaySession): InetAddress =
        session.localAddress
            ?: when (session.remoteAddress) {
                is Inet6Address -> InetAddress.getByName("::")
                is Inet4Address -> InetAddress.getByName("0.0.0.0")
                else -> InetAddress.getByName("0.0.0.0")
            }

    private companion object {
        const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
    }
}
