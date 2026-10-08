package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncHandlerFactory

/** A local reference vehicle. Replace its state callbacks with an OEM adapter for real telemetry. */
object CarPlayUltraVehicleProvisioning {
    // ultra3's single-plugin session requests ID 0. Advertise and register the same ID;
    // explicit OEM profiles retain their own IDs, with no implicit 0 -> 1 alias.
    const val PLUGIN_ID = 0L
    fun create(vehicleId: String = "xcertplay.reference.vehicle", trace: (String) -> Unit = {},
        profile: Map<String, Any?> = emptyMap(), themeAssetDirectory: java.io.File? = null,
        themeRuntime: CarPlayOemThemeRuntime? = null): AirPlayUltraProvisioning {
        val deploymentProfile = themeRuntime?.let {
            check(it.loaded) { "OEM runtime must load before identification" }
            it.descriptor.resolveProfile(profile)
        } ?: profile
        val config = CafConfigTreeFactory.tree(listOf(CafConfigTreeFactory.accessory(0x01000001, 1, listOf(
            CafConfigTreeFactory.service(0x11000002, 10, listOf(
                CafConfigTreeFactory.characteristic(0x31000017, 100, CafCharacteristicFormat.FLOAT, 22.0,
                    writable = true, mutable = true,
                    fields = mapOf("minimumValue" to 16.0, "maximumValue" to 32.0, "stepValue" to 0.5)),
                // Numeric metadata requires all three constraints even for read-only state.
                // These temperature bounds are local reference choices, not OEM specifications.
                CafConfigTreeFactory.characteristic(0x31000019, 101, CafCharacteristicFormat.FLOAT, 22.0,
                    mutable = true, fields = mapOf("minimumValue" to -40.0, "maximumValue" to 100.0, "stepValue" to 0.1)),
                CafConfigTreeFactory.characteristic(0x46000005, 102, CafCharacteristicFormat.UINT8, 0L,
                    fields = mapOf("minimumValue" to 0L, "maximumValue" to 1L, "stepValue" to 1L)),
                // Climate.app requires a tracked region before constructing its UI model.
                // Temperature and fan share one AM370-supported reference region.
                CafConfigTreeFactory.characteristic(0x36000065, 103, CafCharacteristicFormat.STRING, "seat_front_left")),
            ),
            CafConfigTreeFactory.service(0x11000007, 11, listOf(
                CafConfigTreeFactory.characteristic(0x31000012, 110, CafCharacteristicFormat.UINT8, 0L,
                    writable = true, mutable = true,
                    fields = mapOf("minimumValue" to 0L, "maximumValue" to 7L, "stepValue" to 1L)),
                CafConfigTreeFactory.characteristic(0x36000065, 111, CafCharacteristicFormat.STRING, "seat_front_left")),
            ),
        ))))
        val pluginId = (deploymentProfile["pluginID"] as? Number)?.toLong() ?: PLUGIN_ID
        val pluginName = deploymentProfile["pluginName"] as? String ?: "Communication Plug-in"
        val effectiveVehicleId = deploymentProfile["vehicleID"] as? String ?: vehicleId
        require(effectiveVehicleId.isNotBlank() && pluginName.isNotBlank()) { "Ultra vehicleID/pluginName must be non-empty" }
        @Suppress("UNCHECKED_CAST")
        val vehicleConfig = deploymentProfile["config"] as? Map<String, Any?> ?: config
        val asset = deploymentProfile["clusterAsset"]?.let { raw ->
            val fields = raw as? Map<*, *>
                ?: throw IllegalArgumentException("clusterAsset must be a dictionary")
            val version = when (val value = fields["assetVer"]) {
                is Byte -> value.toLong()
                is Short -> value.toLong()
                is Int -> value.toLong()
                is Long -> value
                else -> throw IllegalArgumentException("clusterAsset.assetVer must be a uint32 integer")
            }
            com.shilapi.xcertplay.iap2.message.Iap2ClusterAsset(fields["assetID"] as? String
                ?: throw IllegalArgumentException("clusterAsset.assetID missing"), version)
        }
        val sdkVersion = (deploymentProfile["sdkVersion"] ?: "1.0") as? String
            ?: throw IllegalArgumentException("Ultra sdkVersion must be a string")
        CarPlayThemeAssetStore.compareSDKVersions(sdkVersion, sdkVersion)
        val factories = linkedMapOf<com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType,
            com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory>(
            RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL to CarPlayUiSyncHandlerFactory(effectiveVehicleId,
                initialLayout = deploymentProfile["layoutIdentifier"] as? String ?: "default",
                initialAppearance = deploymentProfile["appearanceMode"] as? String ?: "dark", themeRuntime = themeRuntime))
        themeAssetDirectory?.let { directory ->
            factories[RcsClientTypes.CAR_PLAY_UPDATE_DATA] =
                com.shilapi.xcertplay.airplay.rcs.transfer.CarPlayThemeTransferHandlerFactory(
                    java.io.File(directory, "incoming"), CarPlayThemeAssetStore(java.io.File(directory, "staged")),
                    asset?.assetId, sdkVersion, onStaged = { staged -> themeRuntime?.load(staged) })
        }
        trace("Ultra reference profile vehicleID=$effectiveVehicleId pluginID=$pluginId pluginName=$pluginName " +
            "assumptions=local-climate-state,current-temperature-range=-40..100/0.1,fixed-temperature-unit=0," +
            "fan-range-0..7,climate-region=seat_front_left,ui-layout-default; no vehicle hardware attached")
        val handler = CafVehicleStatePlugin(pluginId, vehicleConfig,
            writeToVehicle = { id, value -> trace("TRACE Ultra local vehicle write instanceID=$id value=$value") })
        return AirPlayUltraProvisioning(
            pluginRegistrations = listOf(CafPluginRegistration(pluginId,
                CafPluginRegistry.CAF_PROTOCOL_VERSION_1_0,
                setOf(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2),
                handler, pluginName = pluginName)),
            uiSyncInfo = AirPlayUiSyncInfo.empty(),
            rcsFactories = factories,
            sidecars = if (themeAssetDirectory != null) mapOf(AirPlayFeature.FILE_TRANSFER to emptyMap<String, Any?>()) else emptyMap(),
            runtimeFeatures = setOf(AirPlayFeature.ALT_SCREEN, AirPlayFeature.UI_CONTEXT),
            sdkVersion = sdkVersion,
            clusterAsset = asset,
            clusterDisplayPluginId = themeRuntime?.descriptor?.cluster?.get("id") as? String,
        )
    }
}
