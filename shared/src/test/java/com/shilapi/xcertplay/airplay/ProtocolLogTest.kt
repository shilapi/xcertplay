package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class ProtocolLogTest {
    @Test fun longUnicodeRecordIsReassembledWithoutLogcatTruncation() {
        val original = "字段🙂\n".repeat(4000)
        val chunks = ProtocolLog.chunks(original)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 3000 })
        assertEquals(original, chunks.joinToString("") { it.substringAfter("] ") })
    }
}
