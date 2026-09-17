package com.shilapi.xcertplay.transport

import android.util.Log
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Blocking, plaintext Lockdown pairing over an already-open USBMUX host.
 *
 * Call [pair] from a worker thread, never Android's main thread. This client owns only the
 * connection it creates: it closes the plist channel (and therefore that connection) after every
 * outcome, but leaves [Iap2UsbMuxHost] open for its caller. It neither persists material nor
 * starts a session, negotiates TLS, starts a service, or establishes any CarPlay/iAP2 session.
 */
class LockdownPairingClient(
    private val host: Iap2UsbMuxHost,
    private val onTrace: (String) -> Unit = {},
) {
    /**
     * Fetches the two pairing inputs, generates the local record, and sends plaintext Pair.
     *
     * [totalTimeoutMillis] covers the entire operation, including a pending pairing dialog.
     * [isCancelled] is checked between blocking protocol operations and while waiting to retry a
     * pending dialog; it cannot interrupt an already-blocking plist read, which is bounded to
     * five seconds. It is intentionally a small synchronous callback rather than a coroutine.
     */
    @Throws(IphoneUsbException::class, LockdownPairingException::class, GeneralSecurityException::class)
    fun pair(
        label: String,
        hostId: String,
        systemBuid: String,
        totalTimeoutMillis: Long,
        isCancelled: () -> Boolean = { false },
    ): PairedRecord {
        require(label.isNotBlank()) { "label must not be blank" }
        require(hostId.isNotBlank()) { "hostId must not be blank" }
        require(systemBuid.isNotBlank()) { "systemBuid must not be blank" }
        require(totalTimeoutMillis in 1..MAXIMUM_TOTAL_TIMEOUT_MILLIS) {
            "totalTimeoutMillis must be between 1 and $MAXIMUM_TOTAL_TIMEOUT_MILLIS"
        }

        val deadline = Deadline(totalTimeoutMillis)
        var pairRecord: LockdownPairRecord? = null
        var attempt = 0
        while (true) {
            checkCancelled(isCancelled)
            attempt += 1
            val connection = host.connect(
                destinationPort = Iap2UsbMuxHost.LOCKDOWN_PORT,
                timeoutMillis = stepTimeoutMillis(deadline),
            )
            var pending = false
            LockdownPlistChannel(connection, onTrace = onTrace).use { channel ->
                setUntrustedHostBuid(channel, label, systemBuid, deadline, isCancelled)
                val record = pairRecord ?: generatePairRecord(
                    channel = channel,
                    label = label,
                    hostId = hostId,
                    systemBuid = systemBuid,
                    deadline = deadline,
                    isCancelled = isCancelled,
                ).also {
                    pairRecord = it
                    Log.i(
                        TAG,
                        "lockdown pair prepared hostId=uuid systemBuid=uuid " +
                            "deviceKey=${it.devicePublicKeyPem.size} deviceCert=${it.deviceCertificatePem.size} " +
                            "hostCert=${it.hostCertificatePem.size} rootCert=${it.rootCertificatePem.size}",
                    )
                }
                Log.i(TAG, "lockdown pair attempt=$attempt")
                val response = channel.request(pairRequest(label, record), stepTimeoutMillis(deadline))
                checkCancelled(isCancelled)
                when (val error = response.errorCodeOrNull()) {
                    null -> return PairedRecord(record, response.entries["EscrowBag"].asOptionalData())
                    "PairingDialogResponsePending" -> pending = true
                    "UserDeniedPairing" -> throw LockdownPairingException.UserDeniedPairing
                    "PasswordProtected" -> throw LockdownPairingException.PasswordProtected
                    else -> throw LockdownPairingException.RemoteError(error)
                }
            }
            if (pending) {
                Log.i(TAG, "lockdown trust pending; reconnecting before retry")
                waitForRetry(deadline, isCancelled)
            }
        }
    }

    private fun generatePairRecord(
        channel: LockdownPlistChannel,
        label: String,
        hostId: String,
        systemBuid: String,
        deadline: Deadline,
        isCancelled: () -> Boolean,
    ): LockdownPairRecord {
        val devicePublicKey = getValue(channel, label, "DevicePublicKey", deadline, isCancelled)
            as? LockdownPlistValue.Data
            ?: throw LockdownPairingException.InvalidResponse("DevicePublicKey was not data")
        val wifiAddress = getValue(channel, label, "WiFiAddress", deadline, isCancelled)
            as? LockdownPlistValue.Text
            ?: throw LockdownPairingException.InvalidResponse("WiFiAddress was not text")
        return LockdownPairRecordGenerator.generate(
            devicePublicKeyPkcs1Pem = devicePublicKey.bytes,
            wifiAddress = wifiAddress.value,
            hostId = hostId,
            systemBuid = systemBuid,
        )
    }

    private fun pairRequest(
        label: String,
        pairRecord: LockdownPairRecord,
    ): LockdownPlistValue.Dictionary = LockdownPlistValue.Dictionary(
        linkedMapOf(
            "Label" to LockdownPlistValue.Text(label),
            "PairRecord" to pairRecord.toPairRequestDictionary(),
            "Request" to LockdownPlistValue.Text("Pair"),
            "ProtocolVersion" to LockdownPlistValue.Text("2"),
            "PairingOptions" to LockdownPlistValue.Dictionary(
                linkedMapOf("ExtendedPairingErrors" to LockdownPlistValue.Boolean(true)),
            ),
        ),
    )

    private fun setUntrustedHostBuid(
        channel: LockdownPlistChannel,
        label: String,
        systemBuid: String,
        deadline: Deadline,
        isCancelled: () -> Boolean,
    ) {
        checkCancelled(isCancelled)
        val response = channel.request(
            LockdownPlistValue.Dictionary(
                linkedMapOf(
                    "Label" to LockdownPlistValue.Text(label),
                    "Request" to LockdownPlistValue.Text("SetValue"),
                    "Key" to LockdownPlistValue.Text("UntrustedHostBUID"),
                    "Value" to LockdownPlistValue.Text(systemBuid),
                ),
            ),
            stepTimeoutMillis(deadline),
        )
        checkCancelled(isCancelled)
        response.errorCodeOrNull()?.let { throw LockdownPairingException.RemoteError(it) }
        Log.i(TAG, "lockdown UntrustedHostBUID set")
    }

    private fun getValue(
        channel: LockdownPlistChannel,
        label: String,
        key: String,
        deadline: Deadline,
        isCancelled: () -> Boolean,
    ): LockdownPlistValue {
        checkCancelled(isCancelled)
        val response = channel.request(
            LockdownPlistValue.Dictionary(
                linkedMapOf(
                    "Label" to LockdownPlistValue.Text(label),
                    "Request" to LockdownPlistValue.Text("GetValue"),
                    "Key" to LockdownPlistValue.Text(key),
                ),
            ),
            stepTimeoutMillis(deadline),
        )
        checkCancelled(isCancelled)
        response.errorCodeOrNull()?.let { throw LockdownPairingException.RemoteError(it) }
        return response.entries["Value"]
            ?: throw LockdownPairingException.InvalidResponse("GetValue response omitted Value")
    }

    private fun LockdownPlistValue?.asOptionalData(): ByteArray? = when (this) {
        null -> null
        is LockdownPlistValue.Data -> bytes
        else -> throw LockdownPairingException.InvalidResponse("EscrowBag was not data")
    }

    private fun LockdownPlistValue.Dictionary.errorCodeOrNull(): String? {
        return when (val error = entries["Error"] ?: return null) {
            is LockdownPlistValue.Text -> error.value
            is LockdownPlistValue.Integer -> (entries["ErrorString"] as? LockdownPlistValue.Text)?.value
                ?: error.value.toString()
            else -> throw LockdownPairingException.InvalidResponse("Error was not text or integer")
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw LockdownPairingException.Cancelled
    }

    private fun stepTimeoutMillis(deadline: Deadline): Long =
        minOf(MAXIMUM_STEP_TIMEOUT_MILLIS, deadline.remainingMillis())

    private fun waitForRetry(deadline: Deadline, isCancelled: () -> Boolean) {
        var remaining = RETRY_INTERVAL_MILLIS
        while (remaining > 0) {
            checkCancelled(isCancelled)
            val sleepMillis = minOf(RETRY_CHECK_MILLIS, remaining, deadline.remainingMillis())
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LockdownPairingException.Cancelled
            }
            remaining -= sleepMillis
        }
    }

    private class Deadline(timeoutMillis: Long) {
        private val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND

        fun remainingMillis(): Long {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) throw LockdownPairingException.TimedOut
            return (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val RETRY_INTERVAL_MILLIS = 1_000L
        const val RETRY_CHECK_MILLIS = 100L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAXIMUM_STEP_TIMEOUT_MILLIS = 5_000L
        const val MAXIMUM_TOTAL_TIMEOUT_MILLIS = 5 * 60_000L
    }
}

/** Successful pairing material kept in memory only. Byte-array accessors return defensive copies. */
class PairedRecord internal constructor(
    val pairRecord: LockdownPairRecord,
    escrowBag: ByteArray?,
) {
    private val storedEscrowBag = escrowBag?.copyOf()

    val escrowBag: ByteArray?
        get() = storedEscrowBag?.copyOf()

    override fun toString(): String = "PairedRecord(redacted)"
}

/** Typed pairing failures; their messages never include pairing keys, certificates, or escrow data. */
sealed class LockdownPairingException(message: String) : IOException(message) {
    data object Cancelled : LockdownPairingException("Lockdown pairing was cancelled")
    data object TimedOut : LockdownPairingException("Lockdown pairing timed out")
    data object UserDeniedPairing : LockdownPairingException("The user denied Lockdown pairing")
    data object PasswordProtected : LockdownPairingException("The iPhone is password protected")
    class RemoteError(val code: String) : LockdownPairingException("Lockdown pairing failed: $code")
    class InvalidResponse(message: String) : LockdownPairingException("Invalid Lockdown pairing response: $message")
}
