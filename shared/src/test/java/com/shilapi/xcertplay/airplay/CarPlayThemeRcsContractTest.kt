package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.transfer.*
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Collections

class CarPlayThemeRcsContractTest {
    @Test fun encryptedUpdateChannelStagesBeforeAcceptAndSuppressesPayloadHex() = CarPlayThemeAssetStoreTest.withDirectory { dir ->
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val stream = RcsDataStream.open(RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            ByteArray(32) { 1 }, ByteArray(32) { 2 }, onTrace = logs::add)
        try {
            val port = stream.listen()
            val store = CarPlayThemeAssetStore(File(dir, "staged")) { _, _ -> }
            val factory = CarPlayThemeTransferHandlerFactory(File(dir, "incoming"), store, "44C9FA31EC", "19.0")
            stream.attach(factory.create(stream)!!)
            RcsChannel.connect(InetSocketAddress("127.0.0.1", port),
                ByteArray(32) { 2 }, ByteArray(32) { 1 }).use { phone ->
                val archive = CarPlayThemeAssetStoreTest.themeArchive()
                phone.sendComm(OpackCodec.encode(mapOf("messageType" to 1L, "sessionID" to "test-session",
                    "payloadType" to 1L, "version" to 985L, "chunkCount" to 1L,
                    "certificate" to byteArrayOf(1), "signature" to byteArrayOf(2))))
                phone.sendComm(OpackCodec.encode(mapOf("messageType" to 2L, "sessionID" to "test-session",
                    "chunkIndex" to 0L, "data" to archive, "checksum" to MessageDigest.getInstance("SHA-256")
                        .digest(archive).joinToString("") { "%02x".format(it.toInt() and 0xff) })))
                val response = phone.receiveNext(5_000) ?: error("No file accept")
                val expected = "e24b6d657373616765547970650d48616363657074656408"
                    .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                assertArrayEquals(expected, response.body)
                val committed = File(dir, "staged").listFiles()!!.single()
                assertArrayEquals(archive, File(committed, "CarPlayAccessory.assets.zip").readBytes())
                assertTrue(logs.any { "applied=false" in it })
                assertFalse(logs.any { "wireHex=" in it || "bodyHex=" in it || "decryptedHex=" in it })
            }
        } finally { stream.close() }
    }

    @Test fun realSigned985ArchiveCompletes151EncryptedChunksAndLoadsBeforeAccept() = CarPlayThemeAssetStoreTest.withDirectory { dir ->
        val source = File("src/main/assets/carplay-ultra")
        val runtime = CarPlayOemAssetDeployment.prepare(File(dir, "apk"), { File(source, it).inputStream() })
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val stream = RcsDataStream.open(RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            ByteArray(32) { 1 }, ByteArray(32) { 2 }, onTrace = logs::add)
        try {
            val port = stream.listen()
            val factory = CarPlayThemeTransferHandlerFactory(File(dir, "incoming"),
                CarPlayThemeAssetStore(File(dir, "received")), "44C9FA31EC", "19.0", onStaged = {
                    runtime.load(it)
                    completed.set(true)
                })
            stream.attach(factory.create(stream)!!)
            RcsChannel.connect(InetSocketAddress("127.0.0.1", port),
                ByteArray(32) { 2 }, ByteArray(32) { 1 }).use { phone ->
                val archive = File(source, "CarPlayAccessory.assets.zip").readBytes()
                val checksum = MessageDigest.getInstance("SHA-256").digest(archive)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                fun sync(body: ByteArray, token: Long) {
                    val header = ByteArray(32)
                    java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.BIG_ENDIAN)
                        .putInt(4, 0x73796e63).putLong(20, token)
                    phone.send(RcsMessage.fromPackage(ApTransportPackageCodec.comm(body, header)))
                }
                fun reply(token: Long) {
                    val message = phone.receiveNext(30000) ?: error("Missing APTransport delivery reply")
                    val value = ApTransportPackageCodec.decode(message.encoded())
                    assertEquals(0x72706c79, value.packageType)
                    assertEquals(token, value.replyToken)
                    assertEquals(0, value.replyStatus)
                    assertTrue(value.body.isEmpty())
                }
                sync(OpackCodec.encode(mapOf("messageType" to 1L, "sessionID" to "real-985",
                    "payloadType" to 1L, "version" to 985L, "chunkCount" to 151L,
                    "certificate" to File(source, "Certificate.chain.pem").readBytes(),
                    "signature" to File(source, "CarPlayAccessory.assets.sha256").readBytes(),
                    "additions" to File(source, "AccessoryAdditions").readBytes())), 1L)
                reply(1L)
                assertNull("Header must not claim theme accepted", phone.receiveNext(100))
                for (index in 0 until 151) {
                    val start = archive.size.toLong() * index / 151
                    val end = archive.size.toLong() * (index + 1) / 151
                    val fields = linkedMapOf<String, Any?>("messageType" to 2L, "sessionID" to "real-985",
                        "chunkIndex" to index.toLong(), "data" to archive.copyOfRange(start.toInt(), end.toInt()))
                    if (index == 150) fields["checksum"] = checksum
                    sync(OpackCodec.encode(fields), index + 2L)
                    if (index != 150) reply(index + 2L)
                }
                val response = phone.receiveNext(30000) ?: error("No final theme accept")
                assertEquals(mapOf("messageType" to 5L, "accepted" to 0L), OpackCodec.decode(response.body))
                assertTrue("Runtime load must finish before accepted=0", completed.get())
                reply(152L)
                assertTrue(runtime.loaded)
                assertEquals(0L, runtime.appliedVersion)
                assertTrue(logs.any { "Theme transfer received version=985" in it })
                assertFalse(logs.any { "bodyHex=" in it || "decryptedHex=" in it })
                assertTrue(File(dir, "incoming").listFiles()!!.isEmpty())
            }
        } finally { stream.close() }
    }

    @Test fun fileTransferIsReadyOnlyWithStorageAndNegotiatesOnlyWhenProposed() = CarPlayThemeAssetStoreTest.withDirectory { dir ->
        val profile = mapOf("sdkVersion" to "19.0", "clusterAsset" to mapOf("assetID" to "44C9FA31EC", "assetVer" to 0L))
        fun config(storage: File?): AirPlayConfig {
            val provisioning = CarPlayUltraVehicleProvisioning.create(profile = profile, themeAssetDirectory = storage)
            val runtime = provisioning.createRuntime()
            return AirPlayConfig("vehicle", "device", "bt", "1.0", AirPlayDisplayConfig(1280, 720),
                ultra = provisioning.createConfig(AirPlayDisplayConfig(1920, 720), runtime))
        }
        assertFalse(AirPlayFeature.FILE_TRANSFER in config(null).ultra!!.readyFeatures)
        val active = config(dir)
        assertTrue(AirPlayFeature.FILE_TRANSFER in active.ultra!!.readyFeatures)
        assertTrue(active.ultra!!.runtime!!.supportsRcsClientType(RcsClientTypes.CAR_PLAY_UPDATE_DATA))
        assertEquals(emptyMap<String, Any?>(), active.ultra!!.fileTransferInfo)
        assertEquals(0L, active.ultra!!.clusterAsset!!.assetVersion)
        val proposed = AirPlayFeatureNegotiation.propose(active, listOf("fileTransfer"), true)
        assertTrue(AirPlayFeature.FILE_TRANSFER in proposed.enabledFeatureSet)
        assertFalse(AirPlayFeature.FILE_TRANSFER in AirPlayFeatureNegotiation.propose(active, emptyList<String>(), true).enabledFeatureSet)
    }
}
