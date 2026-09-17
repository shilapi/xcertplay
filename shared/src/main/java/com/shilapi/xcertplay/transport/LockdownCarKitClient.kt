package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Blocking minimum Lockdown path that opens the `com.apple.carkit.service` byte stream.
 *
 * Connect, plist receive, and TLS engine budgets are five seconds; writes retain the underlying
 * transport's bounded timeout. The returned stream is owned by the caller. This client never
 * closes its caller-owned [Iap2UsbMuxHost], persists a record, sends iAP2, or performs UI work.
 */
class LockdownCarKitClient(
    private val host: Iap2UsbMuxHost,
    private val onTrace: (String) -> Unit = {},
) {
    @Throws(IphoneUsbException::class, GeneralSecurityException::class)
    fun open(pairedRecord: PairedRecord, label: String): BlockingDuplexByteStream =
        open(pairedRecord.pairRecord, label)

    @Throws(IphoneUsbException::class, GeneralSecurityException::class)
    fun open(pairRecord: LockdownPairRecord, label: String): BlockingDuplexByteStream {
        require(label.isNotBlank()) { "label must not be blank" }

        val lockdownConnection = host.connect(
            destinationPort = Iap2UsbMuxHost.LOCKDOWN_PORT,
            timeoutMillis = STEP_TIMEOUT_MILLIS,
        )
        val sessionTls = LockdownPlistChannel(
            lockdownConnection,
            onTrace = onTrace,
        ).use { plaintext ->
            val response = plaintext.request(
                LockdownPlistValue.Dictionary(
                    linkedMapOf(
                        "Label" to LockdownPlistValue.Text(label),
                        "Request" to LockdownPlistValue.Text("StartSession"),
                        "HostID" to LockdownPlistValue.Text(pairRecord.hostId),
                        "SystemBUID" to LockdownPlistValue.Text(pairRecord.systemBuid),
                    ),
                ),
                STEP_TIMEOUT_MILLIS,
            )
            rejectError(response, "StartSession")
            val enableSessionSsl = (response.entries["EnableSessionSSL"] as? LockdownPlistValue.Boolean)?.value
                ?: throw IphoneUsbException.Protocol("StartSession response omitted boolean EnableSessionSSL")
            if (!enableSessionSsl) {
                throw IphoneUsbException.Protocol("StartSession did not enable TLS")
            }
            TlsDuplexChannel.open(
                underlying = plaintext.detach(),
                pairRecord = pairRecord,
                handshakeTimeoutMillis = STEP_TIMEOUT_MILLIS,
            )
        }

        val secureLockdown = LockdownPlistChannel(sessionTls, onTrace = onTrace)
        var serviceStream: BlockingDuplexByteStream? = null
        try {
            val response = secureLockdown.request(
                LockdownPlistValue.Dictionary(
                    linkedMapOf(
                        "Request" to LockdownPlistValue.Text("StartService"),
                        "Service" to LockdownPlistValue.Text(CARKIT_SERVICE),
                    ),
                ),
                STEP_TIMEOUT_MILLIS,
            )
            rejectError(response, "StartService")
            val port = (response.entries["Port"] as? LockdownPlistValue.Integer)?.value
                ?: throw IphoneUsbException.Protocol("StartService response omitted integer Port")
            if (port !in 1..0xffff) {
                throw IphoneUsbException.Protocol("StartService returned an invalid Port")
            }
            val enableServiceSsl = (response.entries["EnableServiceSSL"] as? LockdownPlistValue.Boolean)?.value
                ?: false

            val serviceConnection = host.connect(
                destinationPort = port.toInt(),
                timeoutMillis = STEP_TIMEOUT_MILLIS,
            )
            val readyStream = if (enableServiceSsl) {
                TlsDuplexChannel.open(
                    underlying = serviceConnection,
                    pairRecord = pairRecord,
                    handshakeTimeoutMillis = STEP_TIMEOUT_MILLIS,
                )
            } else {
                serviceConnection
            }
            serviceStream = readyStream
            val ownedStream = CarkitServiceStream(readyStream, secureLockdown)
            serviceStream = null
            return ownedStream
        } finally {
            if (serviceStream != null) {
                try {
                    serviceStream.close()
                } finally {
                    secureLockdown.close()
                }
            }
        }
    }

    /** Keeps the Lockdown session that created carkit alive for the service stream's lifetime. */
    private class CarkitServiceStream(
        private val service: BlockingDuplexByteStream,
        private val lockdown: Closeable,
    ) : BlockingDuplexByteStream {
        private val closed = AtomicBoolean(false)

        override fun send(data: ByteArray) = service.send(data)

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
            service.recv(maxBytes, timeoutMillis)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                service.close()
            } finally {
                lockdown.close()
            }
        }
    }

    private fun rejectError(response: LockdownPlistValue.Dictionary, request: String) {
        val code = when (val error = response.entries["Error"] ?: return) {
            is LockdownPlistValue.Text -> error.value
            is LockdownPlistValue.Integer -> (response.entries["ErrorString"] as? LockdownPlistValue.Text)?.value
                ?: error.value.toString()
            else -> throw IphoneUsbException.Protocol("$request response Error was not text or integer")
        }
        throw IphoneUsbException.Protocol("Lockdown $request failed: $code")
    }

    private companion object {
        const val STEP_TIMEOUT_MILLIS = 5_000L
        const val CARKIT_SERVICE = "com.apple.carkit.service"
    }
}
