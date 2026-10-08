package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.airplay.ProtocolTraceFormatter

/** Logs wire chunks including link negotiation, partial reads, EOF and failed sends. */
internal class TracingDuplexByteStream(private val delegate: BlockingDuplexByteStream,
    private val context: String, private val onTrace: (String) -> Unit) : BlockingDuplexByteStream {
    override fun send(data: ByteArray) {
        onTrace("TRACE IAP2 WIRE [$context] TX attempt bytes=${data.size} wireHex=${ProtocolTraceFormatter.hex(data)}")
        try { delegate.send(data); onTrace("TRACE IAP2 WIRE [$context] TX sent bytes=${data.size}") }
        catch (error: Exception) { onTrace("TRACE IAP2 WIRE [$context] TX failed ${error.stackTraceToString()}"); throw error }
    }
    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = delegate.recv(maxBytes, timeoutMillis)?.also {
        onTrace("TRACE IAP2 WIRE [$context] RX bytes=${it.size} wireHex=${ProtocolTraceFormatter.hex(it)}")
    }
    override fun close() = delegate.close()
}
