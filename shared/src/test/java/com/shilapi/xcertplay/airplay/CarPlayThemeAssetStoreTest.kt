package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.transfer.CarPlayThemeFileHeader
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CarPlayThemeAssetStoreTest {
    @Test fun authenticECDSADerSignatureChecksExactArchiveBytes() = withDirectory { dir ->
        val file = File(dir, "archive.zip").apply { writeBytes(themeArchive()) }
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(pair.private); update(file.readBytes())
        }
        val signature = signer.sign()
        CarPlayThemeSignatureVerifier.verifySignature(file, signature, pair.public)
        file.appendBytes(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayThemeSignatureVerifier.verifySignature(file, signature, pair.public)
        }
    }

    @Test fun stagingPreservesIdentityAndDoesNotClaimInstalledVersion() = withDirectory { dir ->
        var verified = 0
        val file = File(dir, "archive.zip").apply { writeBytes(themeArchive()) }
        val store = CarPlayThemeAssetStore(File(dir, "store")) { _, _ -> verified++ }
        val staged = store.stage(file, header(), "44C9FA31EC", "19.0")
        assertEquals("44C9FA31EC", staged.assetId)
        assertEquals(985L, staged.version)
        assertArrayEquals(file.readBytes(), staged.archive.readBytes())
        assertEquals("staged", (BplistCodec.decode(File(staged.directory, "state.bplist").readBytes()) as Map<*, *>)["state"])
        assertEquals(staged, store.stage(file, header(), "44C9FA31EC", "19"))
        assertEquals(2, verified)
        assertEquals(1, File(dir, "store").listFiles()!!.size)
    }

    @Test fun wrongIdentityVersionAndSDKCannotPublishPackage() = withDirectory { dir ->
        val file = File(dir, "archive.zip").apply { writeBytes(themeArchive()) }
        val root = File(dir, "store")
        val store = CarPlayThemeAssetStore(root) { _, _ -> }
        assertThrows(IllegalArgumentException::class.java) { store.stage(file, header(), "other", "19.0") }
        assertThrows(IllegalArgumentException::class.java) { store.stage(file, header().copy(version = 984), "44C9FA31EC", "19.0") }
        assertThrows(IllegalArgumentException::class.java) { store.stage(file, header(), "44C9FA31EC", "1.0") }
        assertFalse(root.exists())
    }

    @Test fun signatureRejectionPrecedesParsingAndUnsafeArchivePathsAreRejected() = withDirectory { dir ->
        val file = File(dir, "archive.zip").apply { writeText("not a zip") }
        var verified = false
        val store = CarPlayThemeAssetStore(File(dir, "store")) { _, _ ->
            verified = true; throw IllegalArgumentException("untrusted signer")
        }
        val failure = assertThrows(IllegalArgumentException::class.java) { store.stage(file, header(), null, "19.0") }
        assertEquals("untrusted signer", failure.message); assertTrue(verified)
        file.writeBytes(themeArchive("../escape.lua"))
        val permissiveTestStore = CarPlayThemeAssetStore(File(dir, "store")) { _, _ -> }
        assertThrows(IllegalArgumentException::class.java) { permissiveTestStore.stage(file, header(), null, "19.0") }
        assertFalse(File(dir, "escape.lua").exists())
    }

    @Test fun SDKComparisonIsNumericAndTypeStrict() {
        assertTrue(CarPlayThemeAssetStore.compareSDKVersions("19.0", "1.0") > 0)
        assertTrue(CarPlayThemeAssetStore.compareSDKVersions("9.0", "19.0") < 0)
        assertEquals(0, CarPlayThemeAssetStore.compareSDKVersions("19.0.0", "19"))
        for (value in listOf("", "19-beta", "1\u0000", "-1", "999999999999")) {
            assertThrows(IllegalArgumentException::class.java) { CarPlayThemeAssetStore.compareSDKVersions(value, "19") }
        }
    }

    companion object {
        fun themeArchive(resourcePath: String = "Workspace.lua"): ByteArray {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("Info.plist"))
                zip.write(BplistCodec.encode(mapOf("CFBundleIdentifier" to "44C9FA31EC",
                    "AccessoryContentVersion" to 985L, "MinimumSDKVersion" to "19.0")))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(resourcePath)); zip.write("return {}".toByteArray()); zip.closeEntry()
            }
            return bytes.toByteArray()
        }

        fun header() = CarPlayThemeFileHeader("test-session", 985, 1, byteArrayOf(1), byteArrayOf(2))

        fun withDirectory(test: (File) -> Unit) {
            val root = Files.createTempDirectory("ultra-store-test-").toFile()
            try { test(root) } finally { root.deleteRecursively() }
        }
    }
}
