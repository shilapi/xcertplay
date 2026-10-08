package com.shilapi.xcertplay.iap2.session

import org.junit.Assert.*
import org.junit.Test

class Iap2ArtworkTransferContractTest {
    private fun setup(size: Int) = byteArrayOf(7, 4, 0, 0, 0, 0, 0, 0,
        (size shr 8).toByte(), size.toByte(), 0, 2)

    @Test fun artworkRetainsItsOwnSetupFirstLastAndSuccessDatagrams() {
        val handler = Iap2FileTransferHandler()
        assertArrayEquals(byteArrayOf(7, 1), handler.handle(setup(4)).response)
        assertNull(handler.handle(byteArrayOf(7, 0x80.toByte(), 1, 2)).response)
        val result = handler.handle(byteArrayOf(7, 0x40, 3, 4))
        assertArrayEquals(byteArrayOf(7, 5), result.response)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), result.artwork!!.bytes)
    }

    @Test fun truncatedArtworkAndCancelledTransfersCannotPublish() {
        val handler = Iap2FileTransferHandler()
        handler.handle(setup(4))
        assertArrayEquals(byteArrayOf(7, 6), handler.handle(byteArrayOf(7, 0xc0.toByte(), 1)).response)
        handler.handle(setup(4)); handler.handle(byteArrayOf(7, 2))
        assertNull(handler.handle(byteArrayOf(7, 0xc0.toByte(), 1, 2, 3, 4)).artwork)
    }

    @Test fun cachedArtworkAndUnknownLengthBehaviorRemainCompatible() {
        val handler = Iap2FileTransferHandler()
        assertArrayEquals(byteArrayOf(7, 5), handler.handle(setup(4)) { true }.response)
        handler.handle(setup(0))
        assertArrayEquals(byteArrayOf(1, 2), handler.handle(byteArrayOf(7, 0xc0.toByte(), 1, 2)).artwork!!.bytes)
    }
}
