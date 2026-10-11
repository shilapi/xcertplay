package com.shilapi.xcertplay

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The web displays' self-signed TLS identity. Browsers only expose WebCodecs to secure contexts, so
 * HTTPS lets them use the faster, lower-latency decoder; each browser accepts the certificate once.
 * It is kept in app storage and only renewed near expiry or when it misses a display address.
 */
internal object WebDisplayCertificate {
    private const val ALIAS = "web-display"
    private const val LOOPBACK = "127.0.0.1"
    private const val MAX_ADDRESSES = 16
    private val PASSWORD = "xcertplay".toCharArray()
    /** Inside the 398 days browsers accept for a server certificate. */
    private val VALIDITY_MS = TimeUnit.DAYS.toMillis(397)
    private val RENEW_MS = TimeUnit.DAYS.toMillis(30)

    fun sslContext(file: File, addresses: List<String>, now: Long = System.currentTimeMillis()): SSLContext {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(identity(file, addresses, now), PASSWORD)
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
    }

    fun certificate(store: KeyStore): X509Certificate = store.getCertificate(ALIAS) as X509Certificate

    /** The stored identity when it still covers [addresses]; otherwise a new one that also covers the old ones. */
    fun identity(file: File, addresses: List<String>, now: Long): KeyStore {
        val wanted = (addresses + LOOPBACK).distinct()
        val stored = runCatching {
            KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD) } }
        }.getOrNull()?.takeIf { it.isKeyEntry(ALIAS) }
        val current = stored?.getCertificate(ALIAS) as? X509Certificate
        val covered = current?.subjectAlternativeNames.orEmpty().filter { it[0] == 7 }.map { it[1] as String }
        if (stored != null && current != null && covered.containsAll(wanted) && current.notAfter.time - now > RENEW_MS) {
            return stored
        }
        val store = create((covered + wanted).distinct().takeLast(MAX_ADDRESSES), now)
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.path + ".tmp")
            temporary.outputStream().use { store.store(it, PASSWORD) }
            check(temporary.renameTo(file))
        }
        return store
    }

    private fun create(addresses: List<String>, now: Long): KeyStore {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        // Positive and minimally encoded: the top bit clear and the next one set.
        val serial = ByteArray(16).also { SecureRandom().nextBytes(it); it[0] = (it[0].toInt() and 0x3f or 0x40).toByte() }
        val name = seq(set(seq(oid("2.5.4.3"), tlv(0x0c, "xcertplay".toByteArray()))))
        val ecdsaSha256 = seq(oid("1.2.840.10045.4.3.2"))
        val tbs = seq(
            tlv(0xa0, tlv(0x02, byteArrayOf(2))),
            tlv(0x02, serial),
            ecdsaSha256,
            name,
            seq(time(now - TimeUnit.DAYS.toMillis(1)), time(now + VALIDITY_MS)),
            name,
            keys.public.encoded,
            tlv(0xa3, seq(
                extension("2.5.29.19", true, seq()),
                extension("2.5.29.15", true, tlv(0x03, byteArrayOf(7, 0x80.toByte()))),
                extension("2.5.29.37", false, seq(oid("1.3.6.1.5.5.7.3.1"))),
                extension("2.5.29.17", false, seq(*addresses.map { tlv(0x87, InetAddress.getByName(it).address) }.toTypedArray())),
            )),
        )
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(tbs); sign() }
        val der = seq(tbs, ecdsaSha256, tlv(0x03, byteArrayOf(0) + signature))
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream())
        return KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, keys.private, PASSWORD, arrayOf(certificate))
        }
    }

    private fun extension(id: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) seq(oid(id), byteArrayOf(1, 1, -1), tlv(0x04, value)) else seq(oid(id), tlv(0x04, value))

    private fun time(millis: Long): ByteArray {
        val utc = millis < GENERALIZED_TIME_FROM
        val format = SimpleDateFormat(if (utc) "yyMMddHHmmss'Z'" else "yyyyMMddHHmmss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return tlv(if (utc) 0x17 else 0x18, format.format(Date(millis)).toByteArray())
    }

    private fun oid(value: String): ByteArray {
        val arcs = value.split('.').map(String::toLong)
        val out = ByteArrayOutputStream()
        for (arc in listOf(arcs[0] * 40 + arcs[1]) + arcs.drop(2)) {
            var shift = (63 - java.lang.Long.numberOfLeadingZeros(arc)) / 7 * 7
            while (shift > 0) { out.write(((arc ushr shift) and 0x7f or 0x80).toInt()); shift -= 7 }
            out.write((arc and 0x7f).toInt())
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0), ByteArray::plus))
    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0), ByteArray::plus))

    private fun tlv(tag: Int, body: ByteArray): ByteArray {
        val length = when {
            body.size < 0x80 -> byteArrayOf(body.size.toByte())
            body.size < 0x100 -> byteArrayOf(0x81.toByte(), body.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (body.size ushr 8).toByte(), body.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + body
    }

    /** 2050-01-01T00:00:00Z: X.509 switches from UTCTime to GeneralizedTime. */
    private const val GENERALIZED_TIME_FROM = 2524608000000L
}
