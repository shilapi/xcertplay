package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandPayload
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.caf.CafVehicleStatePlugin
import com.shilapi.xcertplay.airplay.rcs.caf.Ios27CafWireContract
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.net.InetSocketAddress
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CarPlayOemDeploymentTest {
    private val assets = File("src/main/assets/carplay-ultra")
    private fun deployed(test: (CarPlayOemThemeRuntime) -> Unit) {
        val root = Files.createTempDirectory("oem-deployment").toFile()
        try { test(CarPlayOemAssetDeployment.prepare(root, { File(assets, it).inputStream() })) }
        finally { root.deleteRecursively() }
    }

    @Test fun defaultProvisioningUsesVerifiedAssetIdentityAndSdk() = deployed { runtime ->
        val provisioning = CarPlayUltraVehicleProvisioning.create(themeRuntime = runtime)
        assertEquals("19.0", provisioning.sdkVersion)
        assertEquals("44C9FA31EC", provisioning.clusterAsset!!.assetId)
        assertEquals(0L, provisioning.clusterAsset!!.assetVersion)
        assertEquals(985L, runtime.descriptor.version)
        assertEquals(1920, runtime.descriptor.clusterWidth)
        assertEquals(720, runtime.descriptor.clusterHeight)
        Ios27CafWireContract.assertReferenceClimateLayoutKeys(
            (provisioning.pluginRegistrations.single().handler as CafVehicleStatePlugin).config)
    }

    @Test fun requestUsesZoneToPrimaryComponentDictionary() = deployed { runtime ->
        val request = runtime.initialCommands().single().toWireMap()
        assertEquals(5L, request["type"])
        val data = request["data"] as Map<*, *>
        assertEquals("6C45CC47-EC01-527A-B40D-1316AA9C936F", data["layoutID"])
        assertEquals(mapOf("D6E7BC4C-744C-5944-BA96-8F578A31ED6D" to
            "C1A48632-8A76-54EA-B1E9-D9D15A4EAF0E"), data["activeComponents"])
        assertEquals(1L, data["appearanceMode"])
        assertTrue(runtime.scene.uiConnected)
    }

    @Test fun mismatchedProfileCannotAdvertiseOtherAssetSdkOrAppliedVersion() = deployed { runtime ->
        listOf(mapOf("sdkVersion" to "1.0"), mapOf("layoutIdentifier" to "unknown"),
            mapOf("clusterAsset" to mapOf("assetID" to "other", "assetVer" to 0L)),
            mapOf("clusterAsset" to mapOf("assetID" to "44C9FA31EC", "assetVer" to 985L)))
            .forEach { invalid -> assertThrows(IllegalArgumentException::class.java) {
                runtime.descriptor.resolveProfile(invalid)
            } }
    }

    @Test fun missingTelemetryIsUnavailableAndDisconnectClearsUi() = deployed { runtime ->
        runtime.initialCommands()
        assertFalse(runtime.scene.vehicleValues.containsKey("vehicleSpeed"))
        runtime.updateVehicleValues(mapOf("vehicleSpeed" to 42.0))
        assertEquals(42.0, runtime.scene.vehicleValues["vehicleSpeed"])
        assertEquals(0L, runtime.appliedVersion)
        runtime.disconnect()
        assertFalse(runtime.scene.uiConnected)
    }

    @Test fun layoutRejectsWrongZoneBeforeChangingScene() = deployed { runtime ->
        val original = runtime.scene
        val valid = mapOf("D6E7BC4C-744C-5944-BA96-8F578A31ED6D" to "8D56ED3C-CA1D-5110-B846-EC79FF4A2A93")
        val fields = mapOf("layoutID" to runtime.scene.layoutId, "activeComponents" to mapOf("wrong-zone" to valid.values.single()))
        assertThrows(IllegalArgumentException::class.java) { runtime.handle(
            CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.LAYOUT_CHANGE, fields, assetWire = true)) }
        assertEquals(original, runtime.scene)
        runtime.handle(CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.METADATA_TRANSFER,
            mapOf("isTransitioning" to true), assetWire = true))
        runtime.handle(CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.LAYOUT_CHANGE,
            fields + ("activeComponents" to valid), assetWire = true))
        runtime.onVideoFrame(1_000_000)
        assertEquals(valid, runtime.scene.activeComponents)
        assertTrue(runtime.scene.transitioning)
        runtime.handle(CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.END_LAYOUT_CHANGE,
            mapOf("layoutID" to runtime.scene.layoutId), assetWire = true))
        runtime.onVideoFrame(1_033_333)
        assertFalse(runtime.scene.transitioning)
        assertTrue(runtime.scene.layoutConfirmed)
    }

    @Test fun layoutEndRequiresMatchingPhoneStart() = deployed { runtime ->
        runtime.initialCommands()
        assertThrows(IllegalArgumentException::class.java) { runtime.handle(CarPlayUiSyncCommandPayload(
            CarPlayUiSyncCommand.END_LAYOUT_CHANGE, mapOf("layoutID" to runtime.scene.layoutId), assetWire = true)) }
        runtime.handle(CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.LAYOUT_CHANGE,
            mapOf("layoutID" to runtime.scene.layoutId, "activeComponents" to runtime.descriptor.initialComponents(runtime.scene.layoutId)), assetWire = true))
        val before = runtime.scene
        assertThrows(IllegalArgumentException::class.java) { runtime.handle(CarPlayUiSyncCommandPayload(
            CarPlayUiSyncCommand.END_LAYOUT_CHANGE, mapOf("layoutID" to "wrong-layout"), assetWire = true)) }
        assertEquals(before, runtime.scene)
    }

    @Test fun disconnectInvalidatesActualVehicleReadings() = deployed { runtime ->
        runtime.updateVehicleValues(mapOf("engineRPM" to 2200.0))
        runtime.disconnect()
        assertTrue("Disconnected telemetry must not be reused", runtime.scene.vehicleValues.isEmpty())
    }

    @Test fun unknownPaletteMustNotBeAcknowledgedAsApplied() = deployed { runtime ->
        assertThrows(IllegalArgumentException::class.java) { runtime.handle(CarPlayUiSyncCommandPayload(
            CarPlayUiSyncCommand.PALETTE_CHANGE, mapOf("layoutIdentifier" to runtime.scene.layoutId,
                "paletteIdentifier" to "unknown"), assetWire = true)) }
    }

    @Test fun metadataMarkerCannotFabricateLayoutStartAndEndingMetadataCannotCancelIt() = deployed { runtime ->
        fun command(type: CarPlayUiSyncCommand, fields: Map<String, Any?>) =
            runtime.handle(CarPlayUiSyncCommandPayload(type, fields, assetWire = true))
        command(CarPlayUiSyncCommand.METADATA_TRANSFER, mapOf("isTransitioning" to true))
        runtime.onVideoFrame(1_000_000)
        assertFalse(runtime.scene.transitioning)
        assertThrows(IllegalArgumentException::class.java) { command(CarPlayUiSyncCommand.END_LAYOUT_CHANGE,
            mapOf("layoutID" to runtime.scene.layoutId)) }
        command(CarPlayUiSyncCommand.LAYOUT_CHANGE, mapOf("layoutID" to runtime.scene.layoutId))
        runtime.onVideoFrame(1_033_333)
        command(CarPlayUiSyncCommand.METADATA_TRANSFER, mapOf("isTransitioning" to false))
        runtime.onVideoFrame(1_066_666)
        assertTrue(runtime.scene.transitioning)
        command(CarPlayUiSyncCommand.END_LAYOUT_CHANGE, mapOf("layoutID" to runtime.scene.layoutId))
        runtime.onVideoFrame(1_099_999)
        assertTrue(runtime.scene.layoutConfirmed)
    }

    @Test fun futureMetadataWaitsForSenderVideoFrameAndResetDiscardsIt() = deployed { runtime ->
        fun command(type: CarPlayUiSyncCommand, fields: Map<String, Any?>) =
            runtime.handle(CarPlayUiSyncCommandPayload(type, fields, assetWire = true))
        command(CarPlayUiSyncCommand.METADATA_TRANSFER, mapOf("isTransitioning" to true))
        command(CarPlayUiSyncCommand.LAYOUT_CHANGE, mapOf("layoutID" to runtime.scene.layoutId))
        command(CarPlayUiSyncCommand.TRANSITION_DATA, mapOf("pts" to 10.5, "regions" to emptyMap<String, Any?>()))
        command(CarPlayUiSyncCommand.END_LAYOUT_CHANGE, mapOf("layoutID" to runtime.scene.layoutId))
        runtime.onVideoFrame(10_000_000)
        assertFalse(runtime.scene.layoutConfirmed)
        assertTrue(runtime.scene.transitionData.isEmpty())
        runtime.onVideoFrame(10_490_000)
        assertEquals(10.5, runtime.scene.transitionData["pts"])
        assertTrue(runtime.scene.layoutConfirmed)
        command(CarPlayUiSyncCommand.METADATA_TRANSFER, mapOf("isTransitioning" to true))
        command(CarPlayUiSyncCommand.TRANSITION_DATA, mapOf("pts" to 11.5, "regions" to emptyMap<String, Any?>()))
        runtime.disconnect()
        runtime.onVideoFrame(12_000_000)
        assertTrue(runtime.scene.transitionData.isEmpty())
        assertFalse(runtime.scene.layoutConfirmed)
    }

    @Test fun integerFocusTokenReturnsOwnershipAndRejectsWrongWireTypes() = deployed { runtime ->
        val initial: Any? = runtime.scene.focusTokenId
        val token = (initial as? Number)?.toLong() ?: 77L
        val give = runtime.handle(CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.REQUEST_FOCUS,
            mapOf("tokenID" to token), assetWire = true)).single()
        assertEquals(7L, give.toWireMap()["type"])
        assertEquals(token, give.fields["tokenID"])
        assertFalse(runtime.scene.hasFocus)
        runtime.handle(CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.GIVE_FOCUS,
            mapOf("tokenID" to token), assetWire = true))
        assertTrue(runtime.scene.hasFocus)
        assertEquals(token, runtime.scene.focusTokenId)
        listOf<Any>("77", 77.5, 0L).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { runtime.handle(CarPlayUiSyncCommandPayload(
                CarPlayUiSyncCommand.GIVE_FOCUS, mapOf("tokenID" to invalid), assetWire = true)) }
        }
    }

    @Test fun corruptedSignatureCannotActivateBundledRuntime() {
        val root = Files.createTempDirectory("oem-rejected").toFile()
        try {
            assertThrows(Exception::class.java) { CarPlayOemAssetDeployment.prepare(root, { name ->
                if (name == "CarPlayAccessory.assets.sha256") ByteArrayInputStream(ByteArray(70))
                else File(assets, name).inputStream()
            }) }
            assertFalse(root.walkTopDown().any { it.extension == "partial" })
        } finally { root.deleteRecursively() }
    }

    @Test fun appearancePreferenceUsesItsOwnThreeValueEnum() = deployed { runtime ->
        runtime.handle(CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.APPEARANCE_PREFERENCE_CHANGE,
            mapOf("preference" to 2L), assetWire = true))
        assertEquals(1, runtime.scene.appearance)
        runtime.handle(CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.APPEARANCE_PREFERENCE_CHANGE,
            mapOf("preference" to 1L), assetWire = true))
        assertEquals("OEM cluster requires dark appearance", 1, runtime.scene.appearance)
        assertThrows(IllegalArgumentException::class.java) { runtime.handle(CarPlayUiSyncCommandPayload(
            CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE, mapOf("appearanceMode" to 0x100000001L), assetWire = true)) }
    }

    @Test fun encryptedRcsExchangesRealOemRequestAndLayoutTransition() = deployed { runtime ->
        val disconnected = CountDownLatch(1)
        runtime.listener = { if (!it.uiConnected) disconnected.countDown() }
        val stream = RcsDataStream.open(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
            ByteArray(32) { 3 }, ByteArray(32) { 4 })
        try {
            val port = stream.listen()
            stream.attach(CarPlayUiSyncHandlerFactory("vehicle", themeRuntime = runtime).create(stream)!!)
            RcsChannel.connect(InetSocketAddress("127.0.0.1", port),
                ByteArray(32) { 4 }, ByteArray(32) { 3 }).use { phone ->
                fun receive(): Map<*, *> {
                    val carrier = OpackCodec.decode(phone.receiveNext(5_000)!!.body) as Map<*, *>
                    assertEquals("Cluster_Display", carrier["displayUUID"])
                    return BplistCodec.decode(carrier["data"] as ByteArray) as Map<*, *>
                }
                fun send(body: ByteArray) = phone.sendComm(OpackCodec.encode(mapOf(
                    "displayUUID" to "Cluster_Display", "data" to body)))
                assertNull("Wait for the phone display session before transmitting", phone.receiveNext(100))
                send(BplistCodec.encode(mapOf("type" to 0L, "ssn" to 1L, "psn" to 1L,
                    "asn" to 0L, "version" to 3L, "id" to "paired-phone")))
                val reset = receive()
                assertEquals(0L, reset["type"])
                assertEquals("paired-phone", reset["id"])
                send(BplistCodec.encode(mapOf("type" to 1L, "ssn" to 1L, "psn" to 1L,
                    "asn" to 1L, "version" to 2L, "id" to "paired-phone")))
                assertEquals(1L, receive()["type"])
                val envelope = receive()
                assertEquals(2L, envelope["type"])
                val inner = BplistCodec.decode(envelope["data"] as ByteArray) as Map<*, *>
                assertEquals(5L, inner["type"])
                val request = inner["data"] as Map<*, *>
                assertTrue(request["activeComponents"] is Map<*, *>)
                send(BplistCodec.encode(mapOf("type" to 2L, "ssn" to 1L, "psn" to 2L,
                    "asn" to 3L, "data" to BplistCodec.encode(mapOf("type" to 9L,
                        "data" to mapOf("isTransitioning" to true))))))
                assertEquals(3L, receive()["type"])
                send(BplistCodec.encode(mapOf("type" to 2L, "ssn" to 1L, "psn" to 3L,
                    "asn" to 4L, "data" to BplistCodec.encode(mapOf("type" to 3L,
                        "data" to mapOf("layoutID" to request["layoutID"], "activeComponents" to request["activeComponents"]))))))
                val ack = receive()
                assertEquals(3L, ack["type"])
                assertEquals(3L, ack["asn"])
                assertFalse(runtime.scene.transitioning)
                runtime.onVideoFrame(299521500000L)
                assertTrue(runtime.scene.transitioning)
                assertEquals(request["layoutID"], runtime.scene.layoutId)
                send(BplistCodec.encode(mapOf("type" to 2L, "ssn" to 1L, "psn" to 5L,
                    "asn" to 5L, "data" to BplistCodec.encode(mapOf("type" to 9L,
                        "data" to mapOf("isTransitioning" to true))))))
                assertEquals(0L, receive()["type"])
                assertFalse("A reset must hide previously ready OEM UI", runtime.scene.uiConnected)
            }
        } finally { stream.close() }
        assertTrue("RCS close callback must disconnect OEM UI", disconnected.await(5, TimeUnit.SECONDS))
        assertFalse(runtime.scene.uiConnected)
    }
}
