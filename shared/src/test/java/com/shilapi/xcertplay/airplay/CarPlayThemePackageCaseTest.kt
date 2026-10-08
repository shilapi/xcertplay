package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.transfer.CarPlayThemeTransferReceiver
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

/** Optional offline integration with the separately acquired public package, never downloaded by tests. */
class CarPlayThemePackageCaseTest {
    @Test fun completePublicPackagePassesProductionTrustAndChunkedDiskStaging() {
        val path = System.getenv("ULTRA_THEME_ASSET_DATA")
        assumeNotNull(path)
        val data = File(path!!)
        val archive = File(data, "CarPlayAccessory.assets.zip")
        assertEquals(30_821_866L, archive.length())
        CarPlayThemeAssetStoreTest.withDirectory { dir ->
            val store = CarPlayThemeAssetStore(File(dir, "staged"))
            var staged: CarPlayStagedThemeAsset? = null
            val receiver = CarPlayThemeTransferReceiver(File(dir, "incoming"),
                onComplete = { file, header -> staged = store.stage(file, header, "44C9FA31EC", "19.0") },
                trace = ::println)
            receiver.use {
                val chunkSize = 128 * 1024
                val count = (archive.length() + chunkSize - 1) / chunkSize
                assertNull(receiver.receive(OpackCodec.encode(mapOf("messageType" to 1L,
                    "sessionID" to "offline-package-case", "payloadType" to 1L,
                    "version" to 985L, "chunkCount" to count,
                    "certificate" to File(data, "Certificate.chain.pem").readBytes(),
                    "signature" to File(data, "CarPlayAccessory.assets.sha256").readBytes(),
                    "additions" to File(data, "AccessoryAdditions").readBytes()))))
                archive.inputStream().use { input ->
                    val buffer = ByteArray(chunkSize)
                    for (index in 0 until count) {
                        var size = 0
                        while (size < buffer.size) {
                            val n = input.read(buffer, size, buffer.size - size)
                            if (n < 0) break
                            size += n
                        }
                        val last = index == count - 1
                        val fields = linkedMapOf<String, Any?>("messageType" to 2L,
                            "sessionID" to "offline-package-case", "chunkIndex" to index,
                            "data" to buffer.copyOf(size))
                        if (last) fields["checksum"] = "ddf1317ef051f8ac251c9d892b5ea0651a75e14dd59e0f5ac2ff13b37729d504"
                        val reply = receiver.receive(OpackCodec.encode(fields))
                        if (!last) assertNull(reply) else {
                            assertEquals(mapOf("messageType" to 5L, "accepted" to 0L), OpackCodec.decode(reply!!))
                        }
                    }
                }
            }
            assertNotNull(staged)
            assertEquals("44C9FA31EC", staged!!.assetId)
            assertEquals(985L, staged!!.version)
            assertEquals(archive.length(), staged!!.archive.length())
            assertTrue(File(dir, "incoming").listFiles()!!.isEmpty())
            val untrusted = CarPlayThemeSignatureVerifier(emptySet())
            assertThrows(IllegalArgumentException::class.java) {
                untrusted.verify(archive, CarPlayThemeAssetStoreTest.header().copy(
                    certificate = File(data, "Certificate.chain.pem").readBytes(),
                    signature = File(data, "CarPlayAccessory.assets.sha256").readBytes()))
            }
        }
    }
}
