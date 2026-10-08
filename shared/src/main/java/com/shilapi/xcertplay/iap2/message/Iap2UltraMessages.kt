package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Endpoint audit §6.7. Unconfirmed parameter IDs are isolated here instead of spread through I/O. */
object Iap2UltraMessages {
    data class Layout(val authStartArrays: List<Int> = listOf(1, 2), val authCertRequestArray: Int = 0,
        val authChallengeArray: Int = 0) {
        init { require(authStartArrays.size == 2 && (authStartArrays + authCertRequestArray + authChallengeArray).all { it in 0..0xffff }) }
    }
    fun notificationResponse(notification: Int, response: Int) = Iap2Messages.buildRaw(0x0002) { u16(0, notification); u8(1, response) }
    fun requestAuthSetup(a: Int, b: Int, c: Int, algorithms: List<Int>, certificates: List<Int>) =
        Iap2Messages.buildRaw(0x5100) { u8(0, a); u8(1, b); u8(2, c); algorithms.forEach { u8(3, it) }; certificates.forEach { u8(4, it) } }
    fun requestAuthStart() = Iap2Messages.buildRaw(0x5110) {}
    fun authStart(version: Int, first: ByteArray, second: ByteArray, layout: Layout = Layout()) =
        Iap2Messages.buildRaw(0x5111) { u16(0, version); bytes(layout.authStartArrays[0], first); bytes(layout.authStartArrays[1], second) }
    fun requestAuthCert(data: ByteArray, layout: Layout = Layout()) = Iap2Messages.buildRaw(0x5112) { bytes(layout.authCertRequestArray, data) }
    fun authCert(kind: Int, certificate: ByteArray, metadata: ByteArray = byteArrayOf()) =
        Iap2Messages.buildRaw(0x5113) { u8(0, kind); bytes(1, certificate); bytes(2, metadata) }
    fun requestAuthChallengeResponse(challenge: ByteArray, layout: Layout = Layout()) =
        Iap2Messages.buildRaw(0x5115) { bytes(layout.authChallengeArray, challenge) }
    fun authenticationSucceeded() = Iap2Messages.buildRaw(0x51ff) {}
    fun requestRelayRead() = Iap2Messages.buildRaw(0x5163) {}
    fun relay(frame: Iap2Frame) = Iap2Messages.buildRaw(0x5164) { bytes(0, frame.encodedFrame()) }
    fun tunnel(frame: Iap2Frame, direction: Int, reply: Boolean = true) =
        Iap2Messages.buildRaw(if (reply) 0x5501 else 0x5500) { u8(0, direction); bytes(1, frame.encodedFrame()) }
    fun requestAccessoryInformation() = Iap2Messages.buildRaw(0x5502) {}
    fun accessoryInformation(fields: Map<Int, String>) = Iap2Messages.buildRaw(0x5503) {
        fields.forEach { (id, value) -> string(id, value) }
    }
    fun nestedFrame(data: ByteArray): Iap2Frame {
        val frames = Iap2CsmFramer().offer(data)
        require(frames.size == 1 && frames.single().encodedFrame().contentEquals(data)) { "Expected exactly one complete nested CSM frame" }
        return frames.single()
    }
}

/** Recursive relay dispatcher retaining all encapsulation on replies, bounded against malformed recursion. */
class Iap2UltraRelaySession(private val respond: (Iap2Frame) -> Unit,
    private val authenticate: (Iap2Frame) -> Iap2Frame?,
    private val accessoryInformation: Map<Int, String> = emptyMap(),
    private val trace: (String) -> Unit = {}, private val replyDirection: Int = 0) {
    fun handle(frame: Iap2Frame): Boolean {
        if (frame.messageId !in IDS) return false
        trace("Ultra relay RX id=0x${frame.messageId.toString(16)} frameHex=${com.shilapi.xcertplay.airplay.ProtocolTraceFormatter.hex(frame.encodedFrame())}")
        val reply = dispatch(frame, 0)
        reply?.let { trace("Ultra relay TX id=0x${it.messageId.toString(16)} frameHex=${com.shilapi.xcertplay.airplay.ProtocolTraceFormatter.hex(it.encodedFrame())}"); respond(it) }
        return true
    }
    private fun dispatch(frame: Iap2Frame, depth: Int): Iap2Frame? {
        require(depth < 4) { "Ultra relay nesting exceeds limit" }
        trace("Ultra relay depth=$depth id=0x${frame.messageId.toString(16)}")
        return when (frame.messageId) {
            0x5164 -> dispatch(Iap2UltraMessages.nestedFrame(Iap2Messages.reader(frame).bytes(0)), depth + 1)?.let(Iap2UltraMessages::relay)
            0x5500, 0x5501 -> {
                val reader = Iap2Messages.reader(frame)
                trace("Ultra tunnel incomingDirection=${reader.u8(0)} replyDirection=$replyDirection (configured assumption)")
                dispatch(Iap2UltraMessages.nestedFrame(reader.bytes(1)), depth + 1)?.let { Iap2UltraMessages.tunnel(it, replyDirection) }
            }
            0x5502 -> Iap2UltraMessages.accessoryInformation(accessoryInformation)
            0x5503 -> { trace("Ultra accessoryInfo body=${com.shilapi.xcertplay.airplay.ProtocolTraceFormatter.hex(frame.payload)}"); null }
            0x5163 -> { trace("Ultra relay read: synchronous replies already delivered"); null }
            0x51ff -> { authenticate(frame); null }
            in AUTH_IDS -> authenticate(frame)
            else -> { trace("Ultra relay unhandled nested id=${frame.messageId}"); null }
        }
    }
    companion object {
        val AUTH_IDS = setOf(0x5100, 0x5110, 0x5111, 0x5112, 0x5113, 0x5115, 0x51ff)
        val IDS = AUTH_IDS + setOf(0x5163, 0x5164, 0x5500, 0x5501, 0x5502, 0x5503)
    }
}
