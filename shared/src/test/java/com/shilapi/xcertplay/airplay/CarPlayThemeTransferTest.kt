package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.transfer.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class CarPlayThemeTransferTest {
    private fun header(session: String = "session", chunks: Long = 2) = OpackCodec.encode(linkedMapOf(
        "messageType" to 1L, "sessionID" to session, "payloadType" to 1L,
        "version" to 985L, "chunkCount" to chunks, "certificate" to byteArrayOf(1),
        "signature" to byteArrayOf(2), "additions" to byteArrayOf(3)))

    private fun chunk(index: Long, data: ByteArray, checksum: String? = null, session: String = "session") =
        OpackCodec.encode(linkedMapOf<String, Any?>("messageType" to 2L, "sessionID" to session,
            "chunkIndex" to index, "data" to data).apply { checksum?.let { put("checksum", it) } })

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun outcome(response: ByteArray?) = (OpackCodec.decode(response!!) as Map<*, *>)["accepted"]

    private fun inDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("ultra-transfer-").toFile()
        try { test(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun independentFirmwareFieldsProduceOnlyOneAcceptAfterVerifiedFinalChunk() = inDirectory { dir ->
        var completed = 0
        val receiver = CarPlayThemeTransferReceiver(dir, onComplete = { file, metadata ->
            assertArrayEquals("abcdef".toByteArray(), file.readBytes())
            assertEquals(985L, metadata.version)
            assertArrayEquals(byteArrayOf(3), metadata.additions)
            completed++
        })
        assertNull(receiver.receive(header()))
        assertNull(receiver.receive(chunk(0, "abc".toByteArray())))
        val response = receiver.receive(chunk(1, "def".toByteArray(), sha("abcdef".toByteArray())))
        assertEquals(0L, outcome(response))
        assertEquals(mapOf("messageType" to 5L, "accepted" to 0L), OpackCodec.decode(response!!))
        assertEquals(1, completed)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun wrongChecksumNeverCallsInstallerAndRemovesPartial() = inDirectory { dir ->
        val receiver = CarPlayThemeTransferReceiver(dir, onComplete = { _, _ -> fail("corrupt package accepted") })
        receiver.receive(header(chunks = 1))
        assertEquals(5L, outcome(receiver.receive(chunk(0, byteArrayOf(4), "0".repeat(64)))))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun gapAndWrongSessionFailWithoutPublishing() = inDirectory { dir ->
        val receiver = CarPlayThemeTransferReceiver(dir, onComplete = { _, _ -> fail("out of order") })
        receiver.receive(header())
        assertEquals(5L, outcome(receiver.receive(chunk(1, byteArrayOf(4)))))
        receiver.receive(header())
        assertEquals(5L, outcome(receiver.receive(chunk(0, byteArrayOf(4), session = "other"))))
    }

    @Test fun diskLimitAndInstallFailureReturnFirmwareFailure() = inDirectory { dir ->
        val receiver = CarPlayThemeTransferReceiver(dir, maximumBytes = 2,
            onComplete = { _, _ -> throw IllegalArgumentException("signature invalid") })
        receiver.receive(header(chunks = 1))
        assertEquals(5L, outcome(receiver.receive(chunk(0, byteArrayOf(1, 2, 3), sha(byteArrayOf(1, 2, 3))))))
        receiver.receive(header(chunks = 1))
        assertEquals(5L, outcome(receiver.receive(chunk(0, byteArrayOf(1), sha(byteArrayOf(1))))))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun timeoutAndDisconnectCleanPartialFiles() = inDirectory { dir ->
        var time = 0L
        val receiver = CarPlayThemeTransferReceiver(dir, nowMillis = { time }, timeoutMillis = 100,
            onComplete = { _, _ -> fail("incomplete package") })
        receiver.receive(header())
        receiver.receive(chunk(0, byteArrayOf(4)))
        time = 100
        assertEquals(5L, outcome(receiver.expire()))
        assertTrue(dir.listFiles()!!.isEmpty())
        receiver.receive(header()); receiver.close()
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun numericTypesAndMetadataAreStrict() {
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayFileTransferCodec.decode(OpackCodec.encode(mapOf("messageType" to 1L,
                "sessionID" to "s", "payloadType" to 1L, "version" to 985.5, "chunkCount" to 1L)))
        }
        assertThrows(IllegalArgumentException::class.java) { CarPlayFileTransferCodec.decode(header(chunks = 0)) }
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayFileTransferCodec.decode(OpackCodec.encode(mapOf("messageType" to 1L,
                "sessionID" to "s", "payloadType" to 2L, "version" to 1L, "chunkCount" to 1L)))
        }
    }
}
