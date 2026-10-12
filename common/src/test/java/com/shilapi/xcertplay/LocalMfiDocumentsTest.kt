package com.shilapi.xcertplay

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMfiDocumentsTest {
    private val primary = "/storage/emulated/0"

    private fun path(authority: String?, documentId: String?, scheme: String? = "content", filePath: String? = null) =
        LocalMfiDocumentPath.of(scheme, authority, documentId, filePath, primary)

    @Test
    fun externalStorageDocumentsMapToTheirFiles() {
        val storage = LocalMfiDocumentPath.EXTERNAL_STORAGE
        assertEquals("/storage/emulated/0/Download/mfi.p7b", path(storage, "primary:Download/mfi.p7b"))
        assertEquals("/storage/1A2B-3C4D/keys/identity.pk8", path(storage, "1A2B-3C4D:keys/identity.pk8"))
        assertEquals("/storage/emulated/0/Documents/mfi.p7b", path(storage, "home:mfi.p7b"))
        assertNull(path(storage, "no-separator"))
    }

    @Test
    fun onlyRawDownloadsAndFileUrisHaveAPath() {
        val downloads = LocalMfiDocumentPath.DOWNLOADS
        assertEquals("/storage/emulated/0/Download/mfi.p7b", path(downloads, "raw:/storage/emulated/0/Download/mfi.p7b"))
        assertNull("A download record has no path without its database", path(downloads, "42"))
        assertNull(path(downloads, "msf:17"))
        assertNull(path("com.android.providers.media.documents", "document:3"))
        assertNull(path(LocalMfiDocumentPath.EXTERNAL_STORAGE, null))
        assertEquals("/sdcard/mfi.p7b", path(null, null, scheme = "file", filePath = "/sdcard/mfi.p7b"))
    }

    @Test
    fun aCopyReplacesTheEarlierOneForTheSameKind() {
        val root = Files.createTempDirectory("mfi").toFile()
        val store = LocalMfiDocumentStore(root)
        val first = store.save(LocalMfiDocumentStore.Kind.CERTIFICATE, "旧证书.p7b", byteArrayOf(1))
        val second = store.save(LocalMfiDocumentStore.Kind.CERTIFICATE, "新证书.p7b", byteArrayOf(2, 3))
        assertFalse(first.exists())
        assertArrayEquals(byteArrayOf(2, 3), second.readBytes())
        assertEquals("新证书.p7b", second.name)
        assertTrue(store.holds(second))
        assertFalse(store.holds(File(root.parentFile, "elsewhere.p7b")))
        val key = store.save(LocalMfiDocumentStore.Kind.PRIVATE_KEY, "identity.pk8", byteArrayOf(4))
        assertTrue("The other kind is kept", second.exists() && key.exists())
        store.clear(LocalMfiDocumentStore.Kind.CERTIFICATE)
        assertFalse(second.exists())
        root.deleteRecursively()
    }

    @Test
    fun namesCannotLeaveTheFolder() {
        assertEquals("_.._etc_passwd", LocalMfiDocumentStore.safeName("/../etc/passwd"))
        assertEquals("document", LocalMfiDocumentStore.safeName(".."))
        assertEquals("document", LocalMfiDocumentStore.safeName("   "))
    }

    @Test(expected = IOException::class)
    fun documentsLargerThanTheLimitAreRefused() {
        LocalMfiDocumentStore.readLimited(ByteArrayInputStream(ByteArray(LocalMfiDocumentStore.MAX_BYTES + 1)))
    }

    @Test
    fun documentsUpToTheLimitAreRead() {
        val bytes = ByteArray(LocalMfiDocumentStore.MAX_BYTES) { it.toByte() }
        assertArrayEquals(bytes, LocalMfiDocumentStore.readLimited(ByteArrayInputStream(bytes)))
    }
}
