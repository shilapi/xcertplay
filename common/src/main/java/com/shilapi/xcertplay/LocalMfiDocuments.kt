package com.shilapi.xcertplay

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Where a picked document lives on disk. BYD's file picker ("下载", com.android.documentsui) runs as
 * the system user, and Android refuses URI grants from the system user to other apps, so the URI it
 * returns cannot be opened at all. The file behind it can still be read with storage permission.
 */
internal object LocalMfiDocumentPath {
    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    const val DOWNLOADS = "com.android.providers.downloads.documents"

    /** The file path of a picked document, or null when the provider gives no path (Recent, media). */
    fun of(scheme: String?, authority: String?, documentId: String?, path: String?, primaryRoot: String): String? =
        when {
            scheme == "file" -> path
            documentId == null -> null
            authority == EXTERNAL_STORAGE -> externalStorage(documentId, primaryRoot)
            authority == DOWNLOADS && documentId.startsWith(RAW_PREFIX) -> documentId.removePrefix(RAW_PREFIX)
            else -> null
        }

    /** ExternalStorageProvider ids are "<volume>:<relative path>": primary, home (Documents) or a USB volume. */
    private fun externalStorage(documentId: String, primaryRoot: String): String? {
        val split = documentId.indexOf(':')
        if (split <= 0) return null
        val volume = documentId.substring(0, split)
        val relative = documentId.substring(split + 1)
        val root = when (volume) {
            "primary" -> primaryRoot
            "home" -> "$primaryRoot/Documents"
            else -> "/storage/$volume"
        }
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    private const val RAW_PREFIX = "raw:"
}

/**
 * Private copies of local MFi documents whose source cannot stay readable, under files/mfi/<kind>/.
 * Only the app can read them, and the backup rules exclude the folder.
 */
internal class LocalMfiDocumentStore(private val root: File) {
    enum class Kind(val folder: String) { CERTIFICATE("certificate"), PRIVATE_KEY("private-key") }

    /** Replaces the copy for [kind]; returns the new file. */
    fun save(kind: Kind, name: String, bytes: ByteArray): File {
        require(bytes.isNotEmpty()) { "empty document" }
        val folder = File(root, kind.folder)
        folder.deleteRecursively()
        if (!folder.mkdirs() && !folder.isDirectory) throw IOException("cannot create $folder")
        val file = File(folder, safeName(name))
        val partial = File(folder, "${file.name}.partial")
        partial.writeBytes(bytes)
        if (!partial.renameTo(file)) throw IOException("cannot write $file")
        return file
    }

    fun clear(kind: Kind) {
        File(root, kind.folder).deleteRecursively()
    }

    /** Whether [file] is one of these private copies. */
    fun holds(file: File): Boolean = file.canonicalPath.startsWith(root.canonicalPath + File.separator)

    companion object {
        /** Larger than any MFi certificate (65,525 bytes) or private key. */
        const val MAX_BYTES = 64 * 1024

        fun readLimited(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (output.size() > MAX_BYTES) throw IOException("larger than ${MAX_BYTES / 1024} KB")
            }
            return output.toByteArray()
        }

        /** The file name without path separators or control characters; Chinese names are kept. */
        fun safeName(name: String): String {
            val cleaned = name.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_").trim().take(100)
            return cleaned.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "document"
        }
    }
}
