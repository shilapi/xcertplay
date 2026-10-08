package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.iap2.message.Iap2AuthenticationMessages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Runs LIVI's minimal iAP2 CSM MFi exchange without taking ownership of [Iap2Session]. */
class Iap2MfiAuthenticationClient(
    private val authentication: MfiAuthenticator,
    private val maximumCertificateLength: Int = MfiAuthenticationClient.DEFAULT_MAXIMUM_CERTIFICATE_OUTPUT_LENGTH,
) {
    private val ultraSent = authentication.mfi4SentMessages.toSet()
    private val ultraReceived = authentication.mfi4ReceivedMessages.toSet()
    init {
        require((ultraSent + ultraReceived).all { it in 0..0xffff }) { "Authentication endpoint IDs must fit u16" }
        require(maximumCertificateLength in 1..MAX_CSM_CERTIFICATE_BYTES) {
            "maximumCertificateLength must be in 1..$MAX_CSM_CERTIFICATE_BYTES"
        }
    }

    // AA00–AA06 use the fixed pre-identification authentication whitelist (audit §2.7).
    // Capability lists declare negotiated service endpoints, not every frame this client sends.
    fun identification(config: com.shilapi.xcertplay.transport.Iap2IdentificationConfig) = config.copy(
        authenticationSentMessages = ultraSent,
        authenticationReceivedMessages = ultraReceived,
    )

    fun supportsUltra(messageId: Int) = messageId in ultraReceived

    fun validateUltraReply(frame: Iap2Frame) = validateUltraFrame(frame, ultraSent, "reply", 0)
    fun validateUltraIncoming(frame: Iap2Frame) = validateUltraFrame(frame, ultraReceived, "incoming", 0)

    private fun validateUltraFrame(frame: Iap2Frame, declared: Set<Int>, direction: String, depth: Int) {
        require(depth in 0..3) { "MFi4 $direction nesting exceeds limit" }
        require(frame.messageId in declared) { "MFi4 $direction endpoint not declared: 0x${frame.messageId.toString(16)}" }
        val reader = com.shilapi.xcertplay.iap2.message.Iap2Messages.reader(frame)
        when (frame.messageId) {
            0x5164 -> validateUltraFrame(com.shilapi.xcertplay.iap2.message.Iap2UltraMessages.nestedFrame(reader.bytes(0)), declared, direction, depth + 1)
            0x5500, 0x5501 -> validateUltraFrame(com.shilapi.xcertplay.iap2.message.Iap2UltraMessages.nestedFrame(reader.bytes(1)), declared, direction, depth + 1)
        }
    }

    /** Shared handler for direct and tunneled MFi4. Unknown response layouts fail explicitly. */
    fun handleUltra(frame: Iap2Frame, onProgress: (String) -> Unit = {}): Iap2Frame? {
        require(supportsUltra(frame.messageId)) { "MFi4 endpoint was not declared: ${frame.messageId}" }
        onProgress("MFi4 incoming id=0x${frame.messageId.toString(16)} bytes=${frame.payload.size}")
        return when (frame.messageId) {
            0x51ff -> { onProgress("MFi4 phone authentication-succeeded"); null }
            else -> authentication.mfi4Exchange(frame).also {
                onProgress("MFi4 provider response=${it?.messageId?.toString(16) ?: "pending"}")
            }
        }
    }

    /** Blocks until the phone confirms AA05, or throws a typed authentication failure. */
    fun run(
        session: Iap2Session,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onProgress: (String) -> Unit = {},
    ) {
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS"
        }
        val deadlineNanos = deadlineAfter(timeoutMillis)
        val certificate = authentication.readCertificate(maximumCertificateLength)
        val certificateFrame = when (authentication.certificateType) {
            MfiCertificateType.MFI -> Iap2AuthenticationMessages.accessoryCertificate(certificate)
            MfiCertificateType.BAA -> Iap2AuthenticationMessages.accessoryCertificateBody(certificate)
        }
        val certificatePayloadBytes = certificateFrame.payload.size
        onProgress(
            "mfi type=${authentication.certificateType} " +
                "certificate loaded bytes=${certificate.size} " +
                "payload bytes=$certificatePayloadBytes",
        )
        var ultraSucceeded = false
        val relay = com.shilapi.xcertplay.iap2.message.Iap2UltraRelaySession(
            respond = { validateUltraReply(it); send(session, it, deadlineNanos) },
            authenticate = { frame -> if (frame.messageId == 0x51ff) ultraSucceeded = true; handleUltra(frame, onProgress) },
            trace = { onProgress("TRACE $it") })
        while (true) {
            val remaining = remainingMillis(deadlineNanos)
            if (remaining == 0L) throw Iap2MfiAuthenticationException("Timed out waiting for iAP2 MFi authentication")
            val frame = session.recv(remaining)
                ?: throw Iap2MfiAuthenticationException("Timed out waiting for iAP2 MFi authentication")
            if (supportsUltra(frame.messageId)) {
                validateUltraIncoming(frame)
                if (relay.handle(frame)) { if (ultraSucceeded) return; continue }
            }
            when (frame.messageId) {
                REQUEST_CERTIFICATE -> {
                    onProgress("iap2 mfi rx=0xaa00 request-certificate")
                    send(session, certificateFrame, deadlineNanos)
                    onProgress("iap2 mfi tx=0xaa01 certificate bytes=$certificatePayloadBytes")
                }
                REQUEST_CHALLENGE -> {
                    val challenge = requireParameterZero(frame)
                    if (challenge.size !in 1..MAXIMUM_CHALLENGE_BYTES) {
                        throw Iap2MfiAuthenticationException("Invalid iAP2 MFi challenge length ${challenge.size}")
                    }
                    onProgress("iap2 mfi rx=0xaa02 challenge bytes=${challenge.size}")
                    val signature = authentication.signChallenge(challenge)
                    send(session, Iap2AuthenticationMessages.response(signature), deadlineNanos)
                    onProgress("iap2 mfi tx=0xaa03 signature bytes=${signature.size}")
                }
                AUTHENTICATION_SUCCEEDED -> {
                    onProgress("iap2 mfi rx=0xaa05 authentication-succeeded")
                    return
                }
                AUTHENTICATION_FAILED -> throw Iap2MfiAuthenticationException("iPhone sent AuthenticationFailed")
                else -> throw Iap2MfiAuthenticationException(
                    "Unexpected iAP2 MFi message 0x${frame.messageId.toString(16).padStart(4, '0')}",
                )
            }
        }
    }

    private fun send(
        session: Iap2Session,
        frame: Iap2Frame,
        deadlineNanos: Long,
    ) {
        val remaining = remainingMillis(deadlineNanos)
        if (remaining == 0L) throw Iap2MfiAuthenticationException("Timed out sending iAP2 MFi authentication reply")
        session.send(frame, remaining)
    }

    private fun requireParameterZero(frame: Iap2Frame): ByteArray {
        return try {
            Iap2AuthenticationMessages.challenge(frame)
        } catch (failure: Exception) {
            throw Iap2MfiAuthenticationException("iAP2 MFi challenge is missing parameter 0", failure)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) return 0
        return ((remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
            .coerceAtMost(MAX_TIMEOUT_MILLIS)
    }

    companion object {
        private const val REQUEST_CERTIFICATE = 0xaa00
        private const val CERTIFICATE = 0xaa01
        private const val REQUEST_CHALLENGE = 0xaa02
        private const val RESPONSE = 0xaa03
        private const val AUTHENTICATION_FAILED = 0xaa04
        private const val AUTHENTICATION_SUCCEEDED = 0xaa05
        private const val MAXIMUM_CHALLENGE_BYTES = 128
        private const val MAX_CSM_CERTIFICATE_BYTES = 65_525
        private const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        private const val MAX_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/** A malformed, rejected, unexpected, or timed-out iAP2 MFi authentication exchange. */
class Iap2MfiAuthenticationException(message: String, cause: Throwable? = null) : MfiException(message, cause)
