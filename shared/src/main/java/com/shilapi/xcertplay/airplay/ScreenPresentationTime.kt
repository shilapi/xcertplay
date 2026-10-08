package com.shilapi.xcertplay.airplay

/** AirPlaySender sbpd_setFrameHeaderTimestamps writes APSNetworkTime as LE NTP32.32 at +8.
 * Keep the sender's clock: decoder arrival time is not UI Sync transition PTS.
 */
internal object ScreenPresentationTime {
    fun microseconds(header: ByteArray): Long? {
        require(header.size >= 16) { "Truncated screen timestamp" }
        fun u32(offset: Int): Long = (0..3).fold(0L) { value, index ->
            value or ((header[offset + index].toLong() and 0xff) shl (index * 8))
        }
        val fraction = u32(8)
        val seconds = u32(12)
        if (seconds == 0L && fraction == 0L) return null
        return seconds * 1_000_000 + fraction * 1_000_000 / 0x1_0000_0000L
    }
}
