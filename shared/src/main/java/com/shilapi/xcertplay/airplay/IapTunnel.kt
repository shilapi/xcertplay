package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import java.io.Closeable
import java.net.InetAddress

/**
 * Receive-side iAP2-over-CarPlay type-130 tunnel.
 *
 * The common RCS listener/framing implementation lives in [RcsDataStream]. This adapter only
 * selects the iAP client type and exposes confirmed `comm` payloads as raw iAP2 bytes.
 */
class IapTunnel(
    readKey: ByteArray,
    bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
) : Closeable {
    interface Listener {
        fun onOpen(remoteAddress: String?) {}
        fun onIap(bytes: ByteArray) {}
        fun onDebug(message: String) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val stream = RcsDataStream.open(
        clientType = RcsClientTypes.IAP_CHANNEL,
        readKey = readKey,
        writeKey = readKey,
        bindAddress = bindAddress,
    )

    fun listen(listener: Listener): Int =
        stream.listen(
            object : RcsDataStreamHandler {
                override fun onStreamOpened(stream: RcsDataStream) {
                    listener.onOpen(null)
                }

                override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
                    if (message.messageType != ApTransportPackageCodec.MESSAGE_TYPE_COMM) return
                    listener.onDebug(
                        "AirPlay iAP tunnel RX package=comm body=${message.body.size}B " +
                            "bodyHex=${ProtocolTraceFormatter.hex(message.body)}",
                    )
                    listener.onIap(message.body)
                }

                override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
                    if (cause == null) {
                        listener.onDebug("AirPlay iAP tunnel peer EOF")
                    } else {
                        listener.onClosed(cause)
                    }
                }
            },
        ).also { port ->
            listener.onDebug("AirPlay iAP tunnel listening port=$port")
        }

    fun awaitPeerConnection(timeoutMillis: Long): Boolean =
        stream.awaitPeerConnection(timeoutMillis)

    override fun close() {
        stream.close()
    }
}
