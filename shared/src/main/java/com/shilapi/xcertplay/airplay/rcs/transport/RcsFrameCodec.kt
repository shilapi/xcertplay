package com.shilapi.xcertplay.airplay.rcs.transport

import com.shilapi.xcertplay.airplay.ControlCipher

/**
 * Stateful ChaCha20-Poly1305 stream framing used by CarPlay RCS.
 *
 * Each encoded frame is `[u16-le ciphertext length][ciphertext][16-byte tag]`. The two-byte header
 * is the AEAD associated data and each direction uses its own 64-bit little-endian counter.
 */
class RcsFrameCodec private constructor(
    readKey: ByteArray,
    writeKey: ByteArray,
    writable: Boolean,
) {
    // Each ControlCipher instance owns one directional counter. The unused opposite key is set to
    // the same value so the key direction is explicit at this boundary.
    private val inbound = ControlCipher(readKey, readKey)
    private val outbound = if (writable) ControlCipher(writeKey, writeKey) else null

    fun decrypt(bytes: ByteArray): ControlCipher.Decrypted = inbound.decrypt(bytes)

    fun encrypt(plaintext: ByteArray): ByteArray =
        requireNotNull(outbound) { "This RCS frame codec is receive-only" }
            .encrypt(plaintext)

    companion object {
        fun duplex(readKey: ByteArray, writeKey: ByteArray): RcsFrameCodec =
            RcsFrameCodec(readKey, writeKey, writable = true)

        /** Reuses the same framing state and key path for the receive-only iAP tunnel. */
        fun reader(readKey: ByteArray): RcsFrameCodec =
            RcsFrameCodec(readKey, ByteArray(readKey.size), writable = false)
    }
}
