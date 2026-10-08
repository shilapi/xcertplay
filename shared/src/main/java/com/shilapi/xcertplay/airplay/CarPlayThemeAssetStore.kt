package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.transfer.CarPlayThemeFileHeader
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAsset
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipFile

data class CarPlayStagedThemeAsset(val assetId: String, val version: Long,
    val minimumSDKVersion: String, val directory: File) {
    val archive: File get() = File(directory, "CarPlayAccessory.assets.zip")
}

/** Pinned production signing key observed in the verified public CarDDP package.
 * This is an explicit accessory trust policy, not a reproduction of iOS PKIX/expiry policy.
 */
class CarPlayThemeSignatureVerifier(
    private val trustedKeySHA256: Set<String> = setOf(PRODUCTION_SIGNING_KEY_SHA256),
) {
    fun verify(file: File, header: CarPlayThemeFileHeader) {
        val certificates = CertificateFactory.getInstance("X.509")
            .generateCertificates(header.certificate.inputStream()).filterIsInstance<X509Certificate>()
        val certificate = certificates.firstOrNull()
            ?: throw IllegalArgumentException("Theme signing certificate missing")
        val key = certificate.publicKey
        require(sha256(key.encoded) in trustedKeySHA256) { "Untrusted theme signing key" }
        verifySignature(file, header.signature, key)
    }

    companion object {
        const val PRODUCTION_SIGNING_KEY_SHA256 = "00aa85d7aa6c9cb76ef17e88d185122dadf596a4192e5e2df190b98ff36da04d"

        internal fun verifySignature(file: File, signature: ByteArray, key: PublicKey) {
            require(key.algorithm == "EC") { "Theme signer must use ECDSA" }
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    verifier.update(buffer, 0, read)
                }
            }
            require(verifier.verify(signature)) { "Theme ECDSA signature mismatch" }
        }
    }
}

/** Verified packages are staged durably; they are never advertised as installed by this store. */
class CarPlayThemeAssetStore(
    private val root: File,
    private val verifySignature: (File, CarPlayThemeFileHeader) -> Unit = CarPlayThemeSignatureVerifier()::verify,
) {
    @Synchronized fun stage(file: File, header: CarPlayThemeFileHeader,
        expectedAssetId: String?, sdkVersion: String): CarPlayStagedThemeAsset {
        require(file.length() in 1..MAX_ARCHIVE_BYTES) { "Invalid theme archive size" }
        verifySignature(file, header)
        val info = validateArchive(file)
        val id = info["CFBundleIdentifier"] as? String
            ?: throw IllegalArgumentException("Theme CFBundleIdentifier missing")
        val version = when (val value = info["AccessoryContentVersion"]) {
            is Int -> value.toLong()
            is Long -> value
            else -> throw IllegalArgumentException("Theme AccessoryContentVersion must be an integer")
        }
        Iap2ClusterAsset(id, version)
        require(expectedAssetId == null || id == expectedAssetId) { "Theme asset identity mismatch" }
        require(version == header.version) { "Theme header/archive version mismatch" }
        val minimumSDK = info["MinimumSDKVersion"] as? String
            ?: throw IllegalArgumentException("Theme MinimumSDKVersion missing")
        require(compareSDKVersions(minimumSDK, sdkVersion) <= 0) { "Theme requires SDK $minimumSDK; configured SDK $sdkVersion" }
        require(root.isDirectory || root.mkdirs()) { "Cannot create theme store" }
        val directory = File(root, "${sha256(id.toByteArray(Charsets.UTF_8))}-$version")
        val result = CarPlayStagedThemeAsset(id, version, minimumSDK, directory)
        if (directory.exists()) {
            require(directory.isDirectory && result.archive.isFile && fileSHA256(result.archive) == fileSHA256(file)) {
                "Conflicting staged theme version"
            }
            return result
        }
        val temporary = File.createTempFile("stage-", ".tmp", root)
        check(temporary.delete() && temporary.mkdir()) { "Cannot create theme staging directory" }
        try {
            file.inputStream().use { source -> java.io.FileOutputStream(File(temporary, "CarPlayAccessory.assets.zip")).use { target ->
                source.copyTo(target); target.fd.sync()
            } }
            writeDurable(File(temporary, "Certificate.chain.pem"), header.certificate)
            writeDurable(File(temporary, "CarPlayAccessory.assets.sha256"), header.signature)
            header.additions?.let { writeDurable(File(temporary, "AccessoryAdditions"), it) }
            writeDurable(File(temporary, "Info.bplist"), BplistCodec.encode(info))
            writeDurable(File(temporary, "state.bplist"), BplistCodec.encode(mapOf(
                "state" to "staged", "assetID" to id, "assetVer" to version,
                "minimumSDKVersion" to minimumSDK, "archiveSHA256" to fileSHA256(file))))
            check(temporary.renameTo(directory)) { "Cannot commit staged theme" }
            return result
        } finally { temporary.deleteRecursively() }
    }

    private fun writeDurable(file: File, bytes: ByteArray) {
        java.io.FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
    }

    private fun validateArchive(file: File): Map<String, Any?> {
        ZipFile(file).use { zip ->
            val names = HashSet<String>()
            var count = 0
            var total = 0L
            var info: Map<String, Any?>? = null
            val buffer = ByteArray(64 * 1024)
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(++count <= 5000 && names.add(entry.name)) { "Too many or duplicate theme entries" }
                require(!entry.name.startsWith('/') && '\\' !in entry.name && '\u0000' !in entry.name &&
                    entry.name.split('/').none { it == ".." || it == "." }) { "Invalid theme archive path" }
                if (entry.isDirectory) continue
                require(entry.size in 0..MAX_EXPANDED_BYTES) { "Invalid theme entry size" }
                val crc = CRC32()
                var size = 0L
                val plist = if (entry.name == "Info.plist") java.io.ByteArrayOutputStream() else null
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read; total += read
                        require(total <= MAX_EXPANDED_BYTES) { "Expanded theme exceeds limit" }
                        crc.update(buffer, 0, read)
                        if (plist != null) {
                            require(size <= 64 * 1024) { "Theme Info.plist exceeds limit" }
                            plist.write(buffer, 0, read)
                        }
                    }
                }
                require(size == entry.size && crc.value == entry.crc) { "Theme ZIP CRC/size mismatch" }
                if (plist != null) {
                    val raw = BplistCodec.decode(plist.toByteArray()) as? Map<*, *>
                        ?: throw IllegalArgumentException("Theme Info.plist must be a binary plist dictionary")
                    require(raw.keys.all { it is String }) { "Invalid theme metadata keys" }
                    @Suppress("UNCHECKED_CAST")
                    info = raw as Map<String, Any?>
                }
            }
            return info ?: throw IllegalArgumentException("Theme Info.plist missing")
        }
    }

    companion object {
        const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 128L * 1024 * 1024

        internal fun compareSDKVersions(left: String, right: String): Int {
            fun parts(value: String): List<Int> {
                require(value.length <= 64 && value.matches(Regex("[0-9]+(\\.[0-9]+)*"))) { "Invalid theme SDK version" }
                return value.split('.').map { it.toIntOrNull() ?: throw IllegalArgumentException("SDK component overflow") }
            }
            val a = parts(left); val b = parts(right)
            for (i in 0 until maxOf(a.size, b.size)) {
                val result = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
                if (result != 0) return result
            }
            return 0
        }
    }
}

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun fileSHA256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
