package com.shilapi.xcertplay.mfi

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence

/**
 * File-backed MFi authenticator for deployment-provided certificate and private-key material.
 *
 * AuthV2 and AuthV3 both use the ordinary [MfiCertificateType.MFI] wire path; the BAA type is a
 * separate two-certificate protocol. The certificate is sent byte-for-byte to the phone. The
 * supported document format is a DER PKCS#7 certificate (`.p7b`) plus an unencrypted DER PKCS#8
 * private key (`.pk8`). Both documents are loaded once when the session opens, so replacing either
 * document takes effect on reconnect.
 */
class LocalMfiAuthenticationClient private constructor(
    certificateBytes: ByteArray,
    private val privateKey: PrivateKey,
    private val signatureAlgorithm: String,
    private val majorVersion: Int,
) : MfiAuthenticator {
    private val certificate = certificateBytes.copyOf()
    private val operationLock = Any()

    override val certificateType: MfiCertificateType = MfiCertificateType.MFI

    override fun protocolMajor(): Int = majorVersion

    override fun readCertificate(maximumOutputLength: Int): ByteArray {
        require(maximumOutputLength in 1..MAXIMUM_CERTIFICATE_BYTES) {
            "maximumOutputLength must be in 1..$MAXIMUM_CERTIFICATE_BYTES"
        }
        if (certificate.size > maximumOutputLength) {
            throw MfiInvalidDataException(
                "Local certificate length ${certificate.size} is outside 1..$maximumOutputLength",
            )
        }
        return certificate.copyOf()
    }

    override fun signChallenge(challenge: ByteArray): ByteArray = synchronized(operationLock) {
        if (majorVersion == 3 && challenge.size != AUTH_V3_CHALLENGE_BYTES) {
            throw MfiInvalidDataException(
                "MFi AuthV3 challenge must be $AUTH_V3_CHALLENGE_BYTES bytes",
            )
        }
        if (majorVersion != 3 && challenge.size !in MINIMUM_CHALLENGE_BYTES..MAXIMUM_CHALLENGE_BYTES) {
            throw MfiInvalidDataException(
                "challenge must be $MINIMUM_CHALLENGE_BYTES..$MAXIMUM_CHALLENGE_BYTES bytes",
            )
        }
        try {
            val signature = Signature.getInstance(signatureAlgorithm).run {
                initSign(privateKey)
                update(challenge.copyOf())
                sign()
            }
            if (majorVersion == 3) derEcdsaToRaw(signature) else signature
        } catch (failure: Exception) {
            throw MfiInvalidDataException("Could not sign with the local MFi private key", failure)
        }
    }

    companion object {
        /** Reads but does not close the document streams. */
        fun load(
            certificateInput: InputStream,
            privateKeyInput: InputStream,
        ): LocalMfiAuthenticationClient {
            return fromBytes(
                readLimited(certificateInput, "certificate", MAXIMUM_CERTIFICATE_BYTES),
                readLimited(privateKeyInput, "private key", MAXIMUM_PRIVATE_KEY_BYTES),
            )
        }

        internal fun fromBytes(
            certificateBytes: ByteArray,
            privateKeyBytes: ByteArray,
        ): LocalMfiAuthenticationClient {
            if (certificateBytes.isEmpty() || certificateBytes.size > MAXIMUM_CERTIFICATE_BYTES) {
                throw MfiInvalidDataException(
                    "Local certificate length ${certificateBytes.size} is outside " +
                        "1..$MAXIMUM_CERTIFICATE_BYTES",
                )
            }
            if (privateKeyBytes.isEmpty() || privateKeyBytes.size > MAXIMUM_PRIVATE_KEY_BYTES) {
                throw MfiInvalidDataException(
                    "Local private key length ${privateKeyBytes.size} is outside " +
                        "1..$MAXIMUM_PRIVATE_KEY_BYTES",
                )
            }
            try {
                val certificates = CertificateFactory.getInstance("X.509")
                    .generateCertificates(ByteArrayInputStream(certificateBytes))
                val leaf = certificates.firstOrNull()
                    ?: throw MfiInvalidDataException("Local PKCS#7 certificate contains no certificates")
                val keyAlgorithm = leaf.publicKey.algorithm.uppercase()
                val (signatureAlgorithm, protocolMajor) = when (keyAlgorithm) {
                    "EC", "ECDSA" -> "NONEwithECDSA" to 3
                    "RSA" -> "NONEwithRSA" to 2
                    else -> throw MfiInvalidDataException(
                        "Unsupported local MFi public key algorithm '${leaf.publicKey.algorithm}'",
                    )
                }
                val privateKey = KeyFactory.getInstance(
                    if (keyAlgorithm == "ECDSA") "EC" else keyAlgorithm,
                ).generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes.copyOf()))
                verifyKeyPair(privateKey, leaf, signatureAlgorithm)
                return LocalMfiAuthenticationClient(
                    certificateBytes = certificateBytes,
                    privateKey = privateKey,
                    signatureAlgorithm = signatureAlgorithm,
                    majorVersion = protocolMajor,
                )
            } catch (failure: MfiInvalidDataException) {
                throw failure
            } catch (failure: Exception) {
                throw MfiInvalidDataException(
                    "Could not parse the local MFi PKCS#7 certificate or PKCS#8 private key",
                    failure,
                )
            }
        }

        private fun verifyKeyPair(
            privateKey: PrivateKey,
            certificate: Certificate,
            signatureAlgorithm: String,
        ) {
            val probe = ByteArray(KEY_MATCH_PROBE_BYTES) { (it + 1).toByte() }
            val signature = Signature.getInstance(signatureAlgorithm).run {
                initSign(privateKey)
                update(probe)
                sign()
            }
            val matches = Signature.getInstance(signatureAlgorithm).run {
                initVerify(certificate.publicKey)
                update(probe)
                verify(signature)
            }
            if (!matches) {
                throw MfiInvalidDataException(
                    "Local MFi private key does not match the certificate public key",
                )
            }
        }

        private fun derEcdsaToRaw(derSignature: ByteArray): ByteArray {
            val sequence = ASN1Sequence.getInstance(derSignature)
            if (sequence.size() != 2) {
                throw MfiInvalidDataException("Local MFi ECDSA signature is malformed")
            }
            val rawSignature = ByteArray(AUTH_V3_SIGNATURE_BYTES)
            writeUnsignedInteger(
                ASN1Integer.getInstance(sequence.getObjectAt(0)),
                rawSignature,
                0,
            )
            writeUnsignedInteger(
                ASN1Integer.getInstance(sequence.getObjectAt(1)),
                rawSignature,
                AUTH_V3_INTEGER_BYTES,
            )
            return rawSignature
        }

        private fun writeUnsignedInteger(
            integer: ASN1Integer,
            output: ByteArray,
            offset: Int,
        ) {
            val value = integer.value
            if (value.signum() <= 0 || value.bitLength() > AUTH_V3_INTEGER_BITS) {
                throw MfiInvalidDataException("Local MFi ECDSA signature integer is out of range")
            }
            val encoded = value.toByteArray()
            val sourceOffset = if (encoded.size > AUTH_V3_INTEGER_BYTES) 1 else 0
            val length = encoded.size - sourceOffset
            if (length > AUTH_V3_INTEGER_BYTES) {
                throw MfiInvalidDataException("Local MFi ECDSA signature integer is out of range")
            }
            encoded.copyInto(
                destination = output,
                destinationOffset = offset + AUTH_V3_INTEGER_BYTES - length,
                startIndex = sourceOffset,
            )
        }

        private fun readLimited(input: InputStream, label: String, maximumBytes: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maximumBytes) {
                        throw MfiInvalidDataException(
                            "Local MFi $label exceeds $maximumBytes bytes",
                        )
                    }
                    output.write(buffer, 0, count)
                }
            } catch (failure: MfiInvalidDataException) {
                throw failure
            } catch (failure: Exception) {
                throw MfiInvalidDataException("Could not read local MFi $label document", failure)
            }
            return output.toByteArray().also { bytes ->
                if (bytes.isEmpty()) {
                    throw MfiInvalidDataException("Local MFi $label document is empty")
                }
            }
        }

        private const val MINIMUM_CHALLENGE_BYTES = 1
        private const val MAXIMUM_CHALLENGE_BYTES = 128
        private const val AUTH_V3_CHALLENGE_BYTES = 32
        private const val AUTH_V3_INTEGER_BYTES = 32
        private const val AUTH_V3_INTEGER_BITS = AUTH_V3_INTEGER_BYTES * 8
        private const val AUTH_V3_SIGNATURE_BYTES = AUTH_V3_INTEGER_BYTES * 2
        private const val MAXIMUM_CERTIFICATE_BYTES = 65_525
        private const val MAXIMUM_PRIVATE_KEY_BYTES = 16 * 1024
        private const val KEY_MATCH_PROBE_BYTES = 32
        private const val STREAM_BUFFER_BYTES = 8 * 1024
    }
}
