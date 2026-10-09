package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncUplinkVariant

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
        // AutomakerRequestContent (ASC). The phone only builds a request-content zone when the car
        // declares this accessory and holds a value for it; with no declaration every zone is
        // evaluated once as new=none and the lock-out screen never clears.
        // IDs are the firmware values (agent_docs/2026-10-10_caf-type-id-firmware-extract.zh-CN.md):
        // accessory AutomakerRequestContent 0x01200001, service RequestContent 0x17000004,
        // characteristics On 0x30000002 (CAFBoolCharacteristic -> bool), ContentURL 0x30000007
        // (CAFStringCharacteristic -> string), UserDismissible 0x36000063 (bool),
        // DisplayPanelIdentifier 0x37000007 (string), DisplayZoneIdentifier 0x3700000B (string).
        // Zone names are the literal `zone1`/`zone2` the phone's asset renderer keys on
        // (CarPlayAssetUI.Zone.ZoneRegion). No panel identifier can be read from a local reference
        // vehicle, so the run must supply one; the declaration stays an opt-in A/B rather than a
        // default, so an unarmed build is byte-for-byte the previous tree.
        val requestContent = deploymentProfile["ultraDeclareRequestContent"] as? Boolean ?: false
        val requestContentPanel = deploymentProfile["requestContentPanelIdentifier"] as? String ?: ""
        val requestContentURL = deploymentProfile["requestContentURL"] as? String ?: ""
        check(!requestContent || requestContentPanel.isNotBlank()) {
            "ultraDeclareRequestContent requires requestContentPanelIdentifier"
        }
        val requestContentZones = (deploymentProfile["requestContentZones"] as? List<*>)
            ?.map { it.toString() } ?: listOf("zone1", "zone2")
        val accessories = mutableListOf<Map<String, Any?>>(CafConfigTreeFactory.accessory(0x01000001, 1, listOf(
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
        )))
        if (requestContent) {
            accessories += CafConfigTreeFactory.accessory(0x01200001, 2,
                requestContentZones.mapIndexed { index, zone ->
                    CafConfigTreeFactory.service(0x17000004, 20L + index, listOf(
                        CafConfigTreeFactory.characteristic(0x30000002, 2000L + index * 10L,
                            CafCharacteristicFormat.BOOL, true, mutable = true),
                        CafConfigTreeFactory.characteristic(0x30000007, 2001L + index * 10L,
                            CafCharacteristicFormat.STRING, requestContentURL, mutable = true),
                        CafConfigTreeFactory.characteristic(0x36000063, 2002L + index * 10L,
                            CafCharacteristicFormat.BOOL, true, mutable = true),
                        CafConfigTreeFactory.characteristic(0x37000007, 2003L + index * 10L,
                            CafCharacteristicFormat.STRING, requestContentPanel, mutable = true),
                        CafConfigTreeFactory.characteristic(0x3700000B, 2004L + index * 10L,
                            CafCharacteristicFormat.STRING, zone, mutable = true),
                    ))
                })
            // Arming line: config-driven, so a run that never applied the profile is distinguishable
            // from a run where the declaration had no effect.
            trace("Ultra AutomakerRequestContent declared panel=$requestContentPanel " +
                "zones=$requestContentZones contentURL=${requestContentURL.ifEmpty { "<empty>" }}")
        }
        val config = CafConfigTreeFactory.tree(accessories)
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
        // ultra32 diagnostic profile. Disable deferred bootstrap to restore the previous
        // accessory-first handshake.
        val deferReset = deploymentProfile["uiSyncDeferInitialResetUntilPeer"] as? Boolean ?: (themeRuntime != null)
        // The timeout of the readiness probe. It is off by default: the phone's RCS layer reports
        // `received messages: 0` on every session, so nothing we send is being acknowledged, and
        // declaring ourselves ready without the peer's RESET_COMPLETE only hides that state and makes
        // the phone reject our commands with `DBUISyncSessionError Code=3`. Set
        // uiSyncPeerResetReadyTimeoutMillis to a positive number to restore the probe.
        val peerReadyTimeout = if (deploymentProfile.containsKey("uiSyncPeerResetReadyTimeoutMillis"))
            (deploymentProfile["uiSyncPeerResetReadyTimeoutMillis"] as? Number)?.toLong()
        else null
        require(peerReadyTimeout == null || peerReadyTimeout > 0) { "Invalid UI Sync peer readiness timeout" }
        // The APTransport header form is `asyn-cmnd` by default: the phone's RCS receive gate
        // accepts only `cmnd` on upload, and shipping `comm` gets every accessory upload silently
        // dropped. See UiSyncUplinkVariant. Both keys stay per-run overridable from the on-device
        // profile, which is how the header form was pinned down and how it can be re-tested.
        val uplinkVariant = UiSyncUplinkVariant.fromProfile(
            deploymentProfile["uiSyncUplinkVariant"] as? String)
        val uplinkProbeFrames = (deploymentProfile["uiSyncUplinkProbeFrames"] as? Number)?.toInt() ?: 0
        require(uplinkProbeFrames in 0..16) { "Invalid UI Sync uplink probe frame count" }
        // Deployment line: states the values actually in effect, so a run whose behaviour looks
        // wrong can be told apart from a run that never applied the settings it was supposed to.
        trace("Ultra UI Sync uplink variant=${uplinkVariant.wireName} probeFrames=$uplinkProbeFrames " +
            "peerReadyTimeoutMillis=${peerReadyTimeout ?: "off"} " +
            "overridden=${listOf("uiSyncUplinkVariant", "uiSyncUplinkProbeFrames")
                .filter(deploymentProfile::containsKey)}")
        val commandPayloadKey = deploymentProfile["uiSyncV3CommandPayloadKey"] as? String
            ?: if (themeRuntime != null) "payload" else "data"
        require(commandPayloadKey == "data" || commandPayloadKey == "payload") { "Invalid UI Sync v3 command payload key" }
        val payloadDictionary = deploymentProfile["uiSyncV3CommandPayloadDictionary"] as? Boolean ?: (themeRuntime != null)
        val omitFadeIn = deploymentProfile["uiSyncV3OmitFadeInNewLayout"] as? Boolean ?: (themeRuntime != null)
        val probeBytes = (deploymentProfile["uiSyncRcsBackpressureProbeBytes"] as? Number)?.toInt() ?: 0
        // Opens with one APTransport sync frame so a phone-side rply can prove the read path.
        val transportSyncProbe = deploymentProfile["uiSyncTransportSyncProbe"] as? Boolean ?: false
        val factories = linkedMapOf<com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType,
            com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory>(
            RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL to CarPlayUiSyncHandlerFactory(effectiveVehicleId,
                initialLayout = deploymentProfile["layoutIdentifier"] as? String ?: "default",
                initialAppearance = deploymentProfile["appearanceMode"] as? String ?: "dark", themeRuntime = themeRuntime,
                deferInitialResetUntilPeer = deferReset, peerResetReadyTimeoutMillis = peerReadyTimeout,
                commandPayloadKey = commandPayloadKey, commandPayloadDictionary = payloadDictionary,
                omitFadeInNewLayout = omitFadeIn, rcsBackpressureProbeBytes = probeBytes,
                transportSyncProbe = transportSyncProbe, uplinkVariant = uplinkVariant,
                uplinkProbeFrames = uplinkProbeFrames))
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
            centerDisplayPluginId = themeRuntime?.descriptor?.center?.get("id") as? String,
            // Diagnostic A/B: omits the cluster display from /info while ALT_SCREEN stays declared.
            // The phone rejects that combination outright (`carEndpoint_checkCarPlayFeatureAcceptance
            // failed: altScreen defined but display structures not found` -> -16720), so this is not
            // a usable configuration; it exists to keep that constraint measurable.
            declareClusterDisplay = deploymentProfile["ultraDeclareClusterDisplay"] as? Boolean ?: true,
        )
    }
}
