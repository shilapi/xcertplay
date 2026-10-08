package com.shilapi.xcertplay.airplay.rcs.transfer

import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec

/** carkitd CRFileReceiver/CRFileSenderSession, iOS 27 / 24A437. Single OPACK dictionary. */
sealed interface CarPlayFileTransferMessage

data class CarPlayThemeFileHeader(
    val sessionId: String,
    val version: Long,
    val chunkCount: Long,
    val certificate: ByteArray,
    val signature: ByteArray,
    val additions: ByteArray? = null,
) : CarPlayFileTransferMessage

data class CarPlayFileChunk(
    val sessionId: String,
    val index: Long,
    val data: ByteArray,
    val checksum: String?,
) : CarPlayFileTransferMessage

object CarPlayFileTransferCodec {
    const val MAX_CHUNK_BYTES = 2 * 1024 * 1024
    const val MAX_METADATA_BYTES = 64 * 1024
    const val MAX_CHUNKS = 10_000L

    fun decode(bytes: ByteArray): CarPlayFileTransferMessage {
        require(bytes.size <= MAX_CHUNK_BYTES + 4 * MAX_METADATA_BYTES) { "File message too large" }
        val fields = OpackCodec.decode(bytes) as? Map<*, *>
            ?: throw IllegalArgumentException("File message must be an OPACK dictionary")
        val session = fields["sessionID"] as? String
            ?: throw IllegalArgumentException("File sessionID must be a string")
        require(session.isNotBlank() && session.length <= 256 && '\u0000' !in session) { "Invalid file sessionID" }
        return when (integer(fields, "messageType")) {
            1L -> {
                require(integer(fields, "payloadType") == 1L) { "Only theme payloadType=1 is supported" }
                val version = integer(fields, "version")
                val count = integer(fields, "chunkCount")
                require(version in 0..0xffff_ffffL && count in 1..MAX_CHUNKS) { "Invalid theme version/chunkCount" }
                CarPlayThemeFileHeader(session, version, count,
                    data(fields, "certificate", MAX_METADATA_BYTES),
                    data(fields, "signature", MAX_METADATA_BYTES),
                    if (fields.containsKey("additions")) data(fields, "additions", MAX_METADATA_BYTES) else null)
            }
            2L -> {
                val index = integer(fields, "chunkIndex")
                require(index in 0 until MAX_CHUNKS) { "Invalid chunkIndex" }
                val checksum = if (fields.containsKey("checksum")) fields["checksum"] as? String
                    ?: throw IllegalArgumentException("File checksum must be a string") else null
                require(checksum == null || checksum.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid SHA256 checksum" }
                CarPlayFileChunk(session, index, data(fields, "data", MAX_CHUNK_BYTES), checksum)
            }
            else -> throw IllegalArgumentException("Unsupported file messageType")
        }
    }

    /** No sessionID or boolean: carkitd uses NSNumber status 0=success / 5=write failure. */
    fun accept(success: Boolean): ByteArray = OpackCodec.encode(linkedMapOf(
        "messageType" to 5L, "accepted" to if (success) 0L else 5L))

    private fun integer(fields: Map<*, *>, key: String): Long = when (val value = fields[key]) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("File $key must be an integer")
    }

    private fun data(fields: Map<*, *>, key: String, limit: Int): ByteArray {
        val bytes = fields[key] as? ByteArray ?: throw IllegalArgumentException("File $key must be NSData")
        require(bytes.size in 1..limit) { "Invalid file $key length" }
        return bytes
    }
}
