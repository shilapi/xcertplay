package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayInfoPlistTest {
    @Test
    fun defaultDisplayIncludesFullViewAndSafeAreas() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            ),
        )

        val display = (info["displays"] as List<*>).single() as Map<*, *>
        val view = (display["viewAreas"] as List<*>).single() as Map<*, *>
        val safe = view["safeArea"] as Map<*, *>
        assertEquals(0, display["initialViewArea"])
        assertEquals(1280, view["widthPixels"])
        assertEquals(720, view["heightPixels"])
        assertEquals(0, view["originXPixels"])
        assertEquals(0, view["originYPixels"])
        assertEquals(1, display["primaryInputDevice"])
        assertNotNull(safe)
        assertEquals(1280, safe["widthPixels"])
        assertEquals(720, safe["heightPixels"])
        assertEquals(true, safe["drawUIOutsideSafeArea"])
    }

    @Test
    fun hevcCapabilityIsAdvertisedOnlyWhenEnabled() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        )

        assertFalse(AirPlayInfoPlist.build(base).containsKey("hevcInfo"))
        assertTrue(AirPlayInfoPlist.build(base.copy(hevc = true)).containsKey("hevcInfo"))
    }

    @Test
    fun drivingSideAndDisplayValuesAreAdvertised() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(
                    widthPixels = 1280,
                    heightPixels = 720,
                    fps = 37,
                    widthPhysicalMm = 125,
                ),
                rightHandDrive = true,
                manufacturer = "Example",
                model = "HeadUnit",
            ),
        )

        val display = (info["displays"] as List<*>).single() as Map<*, *>
        assertEquals(true, info["rightHandDrive"])
        assertEquals("Example", info["manufacturer"])
        assertEquals("HeadUnit", info["model"])
        assertEquals(35, display["maxFPS"])
        assertEquals(125, display["widthPhysical"])
        assertEquals(70, display["heightPhysical"])
    }

    @Test
    fun squareOemIconIsAdvertisedWithItsOriginalBytes() {
        val iconBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                icons = listOf(AirPlayIcon(1, 1, iconBytes)),
                oemLabel = "xcertplay",
            ),
        )

        val icon = (info["oemIcons"] as List<*>).single() as Map<*, *>
        assertEquals(true, info["oemIconVisible"])
        assertEquals("xcertplay", info["oemIconLabel"])
        assertEquals(1, icon["widthPixels"])
        assertEquals(1, icon["heightPixels"])
        assertTrue((icon["imageData"] as ByteArray).contentEquals(iconBytes))
    }

    @Test
    fun safeAreaInsetsReachTheAirPlayViewArea() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(
                    widthPixels = 960,
                    heightPixels = 540,
                    safeArea = AirPlayInsets(top = 10, bottom = 15, left = 20, right = 25),
                    safeAreaDrawOutside = false,
                ),
            ),
        )

        val display = (info["displays"] as List<*>).single() as Map<*, *>
        val view = (display["viewAreas"] as List<*>).single() as Map<*, *>
        val safe = view["safeArea"] as Map<*, *>
        assertEquals(915, safe["widthPixels"])
        assertEquals(515, safe["heightPixels"])
        assertEquals(20, safe["originXPixels"])
        assertEquals(10, safe["originYPixels"])
        assertEquals(false, safe["drawUIOutsideSafeArea"])
    }

    @Test
    fun reportedSafeAreaUsesActivityMappingAndEvenAlignment() {
        val fullHdInsets = AirPlaySafeArea.toInsets(
            mapping = SafeAreaRect(left = 0, top = 0, right = 1920, bottom = 975),
            activityWidthPixels = 1920,
            activityHeightPixels = 1080,
            displayWidthPixels = 1920,
            displayHeightPixels = 1080,
        )
        val compactInsets = AirPlaySafeArea.toInsets(
            mapping = SafeAreaRect(left = 34, top = 75, right = 734, bottom = 725),
            activityWidthPixels = 768,
            activityHeightPixels = 800,
            displayWidthPixels = 768,
            displayHeightPixels = 800,
        )

        fun safeArea(
            widthPixels: Int,
            heightPixels: Int,
            insets: AirPlayInsets,
        ): Map<*, *> {
            val info = AirPlayInfoPlist.build(
                AirPlayConfig(
                    deviceName = "test",
                    deviceId = "02:00:00:00:00:02",
                    btMac = "02:00:00:00:00:02",
                    sourceVersion = "366.0",
                    main = AirPlayDisplayConfig(
                        widthPixels = widthPixels,
                        heightPixels = heightPixels,
                        safeArea = insets,
                    ),
                ),
            )
            val display = (info["displays"] as List<*>).single() as Map<*, *>
            val view = (display["viewAreas"] as List<*>).single() as Map<*, *>
            return view["safeArea"] as Map<*, *>
        }

        val fullHd = safeArea(1920, 1080, fullHdInsets)
        val compact = safeArea(768, 800, compactInsets)

        assertEquals(1920, fullHd["widthPixels"])
        assertEquals(976, fullHd["heightPixels"])
        assertEquals(700, compact["widthPixels"])
        assertEquals(650, compact["heightPixels"])
        assertEquals(34, compact["originXPixels"])
        assertEquals(75, compact["originYPixels"])
    }

    @Test
    fun microphoneInputsAreAdvertisedOnlyWhenEnabled() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        )

        fun telephony(info: Map<String, Any?>): Map<*, *> =
            (info["audioFormats"] as List<*>)
                .map { it as Map<*, *> }
                .single { it["audioType"] == "telephony" }

        fun defaultAudio(info: Map<String, Any?>): Map<*, *> =
            (info["audioFormats"] as List<*>)
                .map { it as Map<*, *> }
                .single { it["type"] == 100 && it["audioType"] == "default" }

        assertFalse(telephony(AirPlayInfoPlist.build(base)).containsKey("audioInputFormats"))
        assertTrue(
            telephony(AirPlayInfoPlist.build(base.copy(microphone = true)))
                .containsKey("audioInputFormats"),
        )
        assertEquals(
            0x70004154,
            defaultAudio(AirPlayInfoPlist.build(base.copy(microphone = true)))["audioInputFormats"],
        )
        assertEquals(
            0x70004154,
            telephony(AirPlayInfoPlist.build(base.copy(microphone = true)))["audioInputFormats"],
        )
    }

    @Test
    fun mainAltAndHighAudioStreamsAreDeclared() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            ),
        )

        val types = (info["audioFormats"] as List<*>)
            .map { (it as Map<*, *>)["type"] }
            .toSet()
        assertEquals(setOf(100, 101, 102), types)
    }

    @Test
    fun ultraDisabledDoesNotAdvertiseSecondDisplayOrUltraSidecars() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                cluster = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
        )

        assertEquals(1, (info["displays"] as List<*>).size)
        assertFalse(info.containsKey("vehicleStateProtocolInfo"))
        assertFalse(info.containsKey("uiSyncInfo"))
    }

    @Test
    fun infoRequestParsesUiContextUrlsAndNeverEchoesThemInResponse() {
        val request = AirPlayInfoRequestFactory.decode(
            BplistCodec.encode(
                linkedMapOf(
                    "altScreenURLs" to listOf(
                        "maps:/car/instrumentcluster/map",
                        "maps:/car/instrumentcluster",
                    ),
                    "uiContextURLs" to listOf("maps:/car/context"),
                    "uiContextLastOnDisplayURLs" to listOf("maps:/car/context/last"),
                    "uiContextNowOnDisplayURLs" to listOf("maps:/car/context/now"),
                    "futureField" to 7,
                ),
            ),
        )
        val response = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            ),
        )

        assertEquals(
            listOf("maps:/car/instrumentcluster/map", "maps:/car/instrumentcluster"),
            request!!.altScreenUrls,
        )
        assertEquals(listOf("maps:/car/context"), request.uiContextUrls)
        assertEquals(
            listOf("maps:/car/context/last"),
            request.uiContextLastOnDisplayUrls,
        )
        assertEquals(
            listOf("maps:/car/context/now"),
            request.uiContextNowOnDisplayUrls,
        )
        assertEquals(setOf("futureField"), request.unrecognized.keys)
        assertFalse(response.containsKey("altScreenURLs"))
        assertEquals(
            request,
            AirPlayInfoRequestFactory.decode(AirPlayInfoRequestFactory.encode(request)),
        )
    }

    @Test
    fun ultraAdvertisesAlternateDisplayAndProvidedSidecars() {
        val pluginConfigs = listOf(
            AirPlayVehicleStateProtocolPlugin.of(
                pluginId = 7L,
                "accessories" to listOf(
                    mapOf("iid" to 1, "type" to 0x0000000001000001L),
                ),
            ),
        )
        val pluginConfigsWire = pluginConfigs.map(AirPlayVehicleStateProtocolPlugin::toWireMap)
        val pluginMapping = mapOf("climate" to 7L)
        val uiSyncInfo = AirPlayUiSyncInfo(
            mapOf("schemaVersion" to 1, "supportsDashboard" to true),
        )
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                ultra = AirPlayUltraConfig(
                    cluster = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
                    vehicleStateProtocolInfo = AirPlayVehicleStateProtocolInfo(
                        protocolVersion = "1.0",
                        pluginConfigs = pluginConfigs,
                        pluginMapping = pluginMapping,
                    ),
                    uiSyncInfo = uiSyncInfo,
                    readyFeatures = setOf(
                        AirPlayFeature.ALT_SCREEN,
                        AirPlayFeature.VEHICLE_STATE_PROTOCOL,
                        AirPlayFeature.UI_SYNC,
                    ),
                    runtime = runtime(
                        sidecars = mapOf(
                            AirPlayFeature.VEHICLE_STATE_PROTOCOL to mapOf(
                                "protocolVersion" to "1.0",
                                "pluginCount" to pluginConfigs.size,
                                "pluginConfigs" to pluginConfigsWire,
                                "pluginMapping" to pluginMapping,
                            ),
                            AirPlayFeature.UI_SYNC to uiSyncInfo.toWireMap(),
                        ),
                        rcsClientTypes = setOf(
                            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
                            RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
                        ),
                    ),
                ),
            ),
        )

        val displays = info["displays"] as List<*>
        assertEquals(2, displays.size)
        assertEquals(111, (displays[1] as Map<*, *>)["type"])

        val vehicle = info["vehicleStateProtocolInfo"] as Map<*, *>
        assertEquals("1.0", vehicle["protocolVersion"])
        assertEquals(1, vehicle["pluginCount"])
        assertTrue(vehicle["pluginConfigs"] is List<*>)
        assertEquals(pluginConfigsWire, vehicle["pluginConfigs"])
        assertEquals(pluginMapping, vehicle["pluginMapping"])
        assertEquals(uiSyncInfo.toWireMap(), info["uiSyncInfo"])
    }

    @Test
    fun preparedSidecarFeaturesAreMaterializedInInfoResponse() {
        val sidecars = linkedMapOf<AirPlayFeature, Map<String, Any?>>(
            AirPlayFeature.FILE_TRANSFER to mapOf("schemaVersion" to 1),
            AirPlayFeature.LOG_TRANSFER to mapOf("schemaVersion" to 2),
            AirPlayFeature.MAIN_BUFFERED to mapOf("schemaVersion" to 3),
            AirPlayFeature.VIDEO_PLAYBACK to mapOf("schemaVersion" to 4),
            AirPlayFeature.SESSION_MANAGEMENT to mapOf("schemaVersion" to 5),
        )
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                ultra = AirPlayUltraConfig(
                    cluster = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
                    fileTransferInfo = sidecars.getValue(AirPlayFeature.FILE_TRANSFER),
                    logTransferInfo = sidecars.getValue(AirPlayFeature.LOG_TRANSFER),
                    mainBufferedInfo = sidecars.getValue(AirPlayFeature.MAIN_BUFFERED),
                    videoPlaybackInfo = sidecars.getValue(AirPlayFeature.VIDEO_PLAYBACK),
                    sessionManagementInfo = sidecars.getValue(AirPlayFeature.SESSION_MANAGEMENT),
                    readyFeatures = sidecars.keys,
                    runtime = runtime(
                        runtimeFeatures = setOf(
                            AirPlayFeature.MAIN_BUFFERED,
                            AirPlayFeature.VIDEO_PLAYBACK,
                            AirPlayFeature.SESSION_MANAGEMENT,
                        ),
                        rcsClientTypes = setOf(
                            RcsClientTypes.CAR_PLAY_UPDATE_DATA,
                            RcsClientTypes.CAR_PLAY_LOGGING_DATA,
                        ),
                    ),
                ),
            ),
        )

        sidecars.forEach { (feature, value) ->
            assertEquals(value, info[feature.infoResponseKey])
        }
    }

    @Test
    fun sidecarAndClusterAreNotAdvertisedWithoutReadyOptIn() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                ultra = AirPlayUltraConfig(
                    cluster = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
                    vehicleStateProtocolInfo = AirPlayVehicleStateProtocolInfo(
                        pluginConfigs = listOf(
                            AirPlayVehicleStateProtocolPlugin(pluginId = 7),
                        ),
                    ),
                    uiSyncInfo = AirPlayUiSyncInfo(mapOf("schemaVersion" to 1)),
                ),
            ),
        )

        assertEquals(1, (info["displays"] as List<*>).size)
        assertFalse(info.containsKey("vehicleStateProtocolInfo"))
        assertFalse(info.containsKey("uiSyncInfo"))
    }

    @Test
    fun bplistDictionaryPreservesSupportedNumericKeyKinds() {
        val encoded = BplistCodec.encode(
            linkedMapOf<Any, Any?>(
                "label" to "string",
                1.toByte() to "byte",
                2.toShort() to "short",
                3 to "int",
                4L to "long",
                BigInteger.valueOf(5) to "bigInteger",
            ),
        )

        val decoded = BplistCodec.decode(encoded) as Map<*, *>
        assertEquals("string", decoded["label"])
        assertEquals("byte", decoded[1L])
        assertEquals("short", decoded[2L])
        assertEquals("int", decoded[3L])
        assertEquals("long", decoded[4L])
        assertEquals("bigInteger", decoded[5L])
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), decoded.keys.filterIsInstance<Number>().map { it.toLong() }.toSet())
        assertTrue(decoded.keys.filterIsInstance<Number>().all { it is Long })
    }

    @Test
    fun vehicleStateProtocolArrayAndMappingSurviveBplistRoundTrip() {
        val pluginConfigs = listOf(
            AirPlayVehicleStateProtocolPlugin.of(
                pluginId = 7L,
                "pluginName" to "climate",
            ),
            AirPlayVehicleStateProtocolPlugin.of(
                pluginId = 42L,
                "pluginName" to "media",
            ),
        )
        val pluginConfigsWire = pluginConfigs.map(AirPlayVehicleStateProtocolPlugin::toWireMap)
        val pluginMapping = linkedMapOf<String, Long>(
            "climate" to 7L,
            "media" to 42L,
        )
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                ultra = AirPlayUltraConfig(
                    cluster = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
                    vehicleStateProtocolInfo = AirPlayVehicleStateProtocolInfo(
                        pluginConfigs = pluginConfigs,
                        pluginMapping = pluginMapping,
                    ),
                    readyFeatures = setOf(
                        AirPlayFeature.ALT_SCREEN,
                        AirPlayFeature.VEHICLE_STATE_PROTOCOL,
                    ),
                    runtime = runtime(
                        sidecars = mapOf(
                            AirPlayFeature.VEHICLE_STATE_PROTOCOL to mapOf(
                                "protocolVersion" to "1.0",
                                "pluginCount" to pluginConfigs.size,
                                "pluginConfigs" to pluginConfigsWire,
                                "pluginMapping" to pluginMapping,
                            ),
                        ),
                        rcsClientTypes = setOf(
                            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
                        ),
                    ),
                ),
            ),
        )

        val decoded = BplistCodec.decode(BplistCodec.encode(info)) as Map<*, *>
        val vehicle = decoded["vehicleStateProtocolInfo"] as Map<*, *>
        val decodedPluginConfigs = vehicle["pluginConfigs"] as List<*>
        val decodedPluginMapping = vehicle["pluginMapping"] as Map<*, *>

        assertEquals(2L, vehicle["pluginCount"])
        assertEquals("climate", (decodedPluginConfigs[0] as Map<*, *>)["pluginName"])
        assertEquals("media", (decodedPluginConfigs[1] as Map<*, *>)["pluginName"])
        assertEquals(setOf("climate", "media"), decodedPluginMapping.keys)
        assertEquals(7L, decodedPluginMapping["climate"])
        assertEquals(42L, decodedPluginMapping["media"])
    }

    private fun runtime(
        sidecars: Map<AirPlayFeature, Any?> = emptyMap(),
        runtimeFeatures: Set<AirPlayFeature> = emptySet(),
        rcsClientTypes: Set<RcsClientType> = emptySet(),
    ): AirPlayUltraRuntime {
        val builder = AirPlayUltraRuntime.builder()
        sidecars.forEach { (feature, value) -> builder.sidecar(feature, value) }
        runtimeFeatures.forEach { feature -> builder.runtimeFeature(feature) }
        if (rcsClientTypes.isNotEmpty()) {
            builder.rcs(rcsClientTypes, RcsDataStreamHandlerFactory { null })
        }
        return builder.build()
    }
}
