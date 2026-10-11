package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.VideoCodec
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SessionLogFileTest {
    @Test fun restartingAfterPreviousWriterClosesDoesNotRestoreOldFileOffset() {
        val file = Files.createTempFile("xcert-log", ".log").toFile()
        try {
            SessionLog().use { log ->
                val previousActivityCallback = { log.append("late-background-record") }
                log.start(file, "old-session-" + "x".repeat(4096))
                repeat(64) { log.append("old-record-$it") }
                log.close()
                log.start(file, "new-session")
                previousActivityCallback()
            }
            assertEquals("new-session\nlate-background-record\n", file.readText())
        } finally { file.delete() }
    }

    @Test fun completeRecordsSurviveQueuePressureAndClose() {
        val file = Files.createTempFile("xcert-log", ".log").toFile()
        try {
            SessionLogFile(file) { throw AssertionError(it) }.use { log ->
                log.reset("header")
                repeat(4096) { log.append("record-$it") }
            }
            assertEquals(listOf("header") + (0 until 4096).map { "record-$it" }, file.readLines())
        } finally { file.delete() }
    }
    @Test fun nextSessionOverwritesPreviousSession() {
        val file = Files.createTempFile("xcert-log", ".log").toFile()
        try {
            SessionLogFile(file).use { it.reset("session-one"); it.append("old-record") }
            SessionLogFile(file).use { it.reset("session-two"); it.append("tail") }
            assertEquals("session-two\ntail\n", file.readText())
        } finally { file.delete() }
    }

    @Test fun resetDiscardsPreviouslyQueuedRecords() {
        val file = Files.createTempFile("xcert-log", ".log").toFile()
        try {
            SessionLogFile(file).use { log ->
                log.reset("session-one")
                repeat(64) { log.append("old-record-$it") }
                log.reset("session-two")
                log.append("tail")
            }
            assertEquals("session-two\ntail\n", file.readText())
        } finally { file.delete() }
    }

    @Test fun repeatedOverflowKeepsHandshakeAndLatestCompleteRecords() {
        val file = Files.createTempFile("xcert-log", ".log").toFile()
        val records = (0 until 80).map { "record-$it\n" + "x".repeat(200) + "\nend-$it" }
        try {
            SessionLogFile(file, maxBytes = 8192, headLimitBytes = 2048).use { log ->
                log.reset("handshake-header")
                records.forEach(log::append)
            }
            val retained = file.readText()
            assertTrue(retained.startsWith("handshake-header\n${records.first()}\n"))
            assertTrue(retained.endsWith("${records.last()}\n"))
            assertFalse(retained.contains("record-30\n"))
            assertTrue(retained.contains("LOG RETENTION"))
            assertTrue(file.length() <= 8192)
            // Every retained multi-line record must have both its start and end.
            for (i in records.indices) {
                assertEquals(retained.contains("record-$i\n"), retained.contains("\nend-$i\n"))
            }
        } finally { file.delete() }
    }

    @Test fun capacityCountsUtf8BytesAndRotatesOnlyAfterTheExactLimit() {
        for (overflow in listOf(false, true)) {
            val file = Files.createTempFile("xcert-log", ".log").toFile()
            try {
                SessionLogFile(file, maxBytes = 2048, headLimitBytes = 512).use { log ->
                    log.reset("H") // 2 bytes including newline.
                    repeat(15) { log.append("中".repeat(42) + "x") } // 128 UTF-8 bytes each.
                    log.append("x".repeat(125)) // Exactly 2048 bytes total.
                    if (overflow) log.append("latest")
                }
                val retained = file.readText()
                assertTrue(retained.startsWith("H\n"))
                assertFalse(retained.contains('\uFFFD'))
                if (overflow) {
                    assertTrue(retained.contains("LOG RETENTION"))
                    assertTrue(retained.endsWith("latest\n"))
                    assertTrue(file.length() <= 2048)
                } else {
                    assertEquals(2048L, file.length())
                    assertFalse(retained.contains("LOG RETENTION"))
                }
            } finally { file.delete() }
        }
    }

    @Test fun resetAfterRotationDiscardsBothHeadAndTail() {
        val file = Files.createTempFile("xcert-log", ".log").toFile()
        try {
            SessionLogFile(file, maxBytes = 2048, headLimitBytes = 512).use { log ->
                log.reset("old-handshake")
                repeat(40) { log.append("old-$it " + "x".repeat(200)) }
                log.reset("new-handshake")
                log.append("new-tail")
            }
            assertEquals("new-handshake\nnew-tail\n", file.readText())
        } finally { file.delete() }
    }

    @Test fun productionThirtyMiBLimitPreservesHeadAfterThirtyFiveMiBOfWrites() {
        val file = Files.createTempFile("xcert-log", ".log").toFile()
        val chunk = "x".repeat(1024 * 1024 - 16)
        try {
            SessionLogFile(file) { throw AssertionError(it) }.use { log ->
                log.reset("handshake-header")
                repeat(35) { log.append("record-$it|$chunk") }
            }
            val retained = file.readText()
            assertTrue(retained.startsWith("handshake-header\nrecord-0|"))
            assertTrue(retained.endsWith("record-34|$chunk\n"))
            assertFalse(retained.contains("\nrecord-11|"))
            assertTrue(retained.contains("LOG RETENTION maxBytes=31457280 headLimitBytes=10485760"))
            assertTrue(file.length() in (24L * 1024 * 1024)..(30L * 1024 * 1024))
            assertEquals(listOf(file.name), file.parentFile!!.listFiles()!!
                .filter { it.name.startsWith(file.name) }.map { it.name })
        } finally { file.delete() }
    }

    @Test fun oversizedRecordKeepsValidUtf8WithinSizeLimit() {
        val file = Files.createTempFile("xcert-log", ".log").toFile()
        val record = "原始数据".repeat(3 * 1024 * 1024)
        try {
            SessionLogFile(file).use { it.reset("header"); it.append(record) }
            val retained = file.readText()
            val limit = 30 * 1024 * 1024
            assertTrue(file.length() <= limit)
            assertTrue(file.length() > limit - 1024)
            assertTrue(retained.startsWith("header\nLOG TRUNCATED originalRecordBytes=37748736"))
            assertFalse(retained.contains('\uFFFD'))
            val suffix = retained.substringAfter("\n", "").substringAfter("\n", "").removeSuffix("\n")
            assertTrue(suffix.isNotEmpty())
            assertTrue(record.endsWith(suffix))
        } finally { file.delete() }
    }
    @Test fun rawVideoIsOptInAndCopiesExactWireAndPlainBytes() {
        val file = Files.createTempFile("xcert-log", ".txt").toFile()
        try {
            SessionLog().use { log ->
                log.start(file, "header")
                val header = byteArrayOf(0, 1, 2, 3, 0)
                val wire = byteArrayOf(0x10, 0x20, 0xff.toByte())
                val plain = byteArrayOf(0, 0, 1, 0x65)
                log.appendVideoPacket(110, VideoCodec.H264, 1, header, wire, plain)
                log.videoFramesEnabled = true
                log.appendVideoPacket(111, VideoCodec.H265, 2, header, wire, plain)
                wire.fill(0)
                plain.fill(0)
                log.videoFramesEnabled = false
                log.appendVideoPacket(110, VideoCodec.H264, 3, header, wire, plain)
            }
            val retained = file.readText()
            assertFalse(retained.contains("type=110"))
            assertTrue(retained.contains("type=111 codec=H265 sequence=2"))
            assertTrue(retained.contains("headerHex=0001020300"))
            assertTrue(retained.contains("wireHex=1020ff"))
            assertTrue(retained.contains("plainHex=00000165"))
        } finally { file.delete() }
    }

    @Test fun oversizedRawPacketIsDroppedWithMarkerAndProgressSurvives() {
        val file = Files.createTempFile("xcert-log", ".txt").toFile()
        try {
            SessionLogFile(file, maxBytes = 2048, headLimitBytes = 512).use { log ->
                log.reset("handshake-header")
                log.appendVideoPacket(110, "H264", 1, ByteArray(128), ByteArray(2048), null)
                log.append("connection-ready")
            }
            val retained = file.readText()
            assertTrue(retained.startsWith("handshake-header\n"))
            assertTrue(retained.contains("LOG VIDEO LOSS droppedPackets=1"))
            assertFalse(retained.contains("wireHex="))
            assertTrue(retained.endsWith("connection-ready\n"))
        } finally { file.delete() }
    }

    @Test fun activityReattachmentKeepsOneCaptureAndDoesNotTruncate() {
        val file = Files.createTempFile("xcert-log", ".txt").toFile()
        try {
            SessionLog().use { log ->
                log.start(file, "first-activity")
                log.append("connected")
                log.start(file, "second-activity")
                log.append("reattached")
            }
            assertEquals("first-activity\nconnected\nreattached\n", file.readText())
        } finally { file.delete() }
    }
}
