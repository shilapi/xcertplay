package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistration
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistrationProvider
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistry
import com.shilapi.xcertplay.airplay.rcs.caf.CafProtocolSession
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsPayloadStyle

/**
 * Explicit local implementation set for CarPlay Ultra.
 *
 * This object is the opt-in boundary: registrations supply OEM plugin IDs and handlers, while
 * sidecars and factories supply the corresponding protocol implementations. An empty provisioning
 * value keeps every Ultra feature except the built-in alternate display path disabled.
 */
data class AirPlayUltraProvisioning(
    val pluginRegistrations: List<CafPluginRegistration> = emptyList(),
    val pluginMapping: Map<String, Long> = emptyMap(),
    val uiSyncInfo: AirPlayUiSyncInfo? = null,
    val sidecars: Map<AirPlayFeature, Any?> = emptyMap(),
    val rcsFactories: Map<RcsClientType, RcsDataStreamHandlerFactory> = emptyMap(),
    val runtimeFeatures: Set<AirPlayFeature> = emptySet(),
) {
    init {
        sidecars.keys.forEach { feature ->
            require(feature.requiresInfoResponseSidecar) {
                "AirPlay feature '${feature.wireName}' does not use an /info sidecar"
            }
        }
        require(AirPlayFeature.VEHICLE_STATE_PROTOCOL !in sidecars) {
            "Use pluginRegistrations to supply vehicleStateProtocolInfo"
        }
        require(AirPlayFeature.UI_SYNC !in sidecars) {
            "Use uiSyncInfo to supply uiSyncInfo"
        }
    }

    fun createRuntime(): AirPlayUltraRuntime {
        val registry = CafPluginRegistry.from(pluginRegistrations)
        val builder = AirPlayUltraRuntime.builder()
        val cafClientTypes = registry.allRegistrations()
            .flatMapTo(linkedSetOf()) { registration ->
                registration.clientTypes.filterTo(linkedSetOf()) {
                    it.payloadStyle == RcsPayloadStyle.CAF_BINARY_PLIST_OPACK
                }
            }
        if (cafClientTypes.isNotEmpty()) {
            builder.rcs(
                clientTypes = cafClientTypes,
                factory = CafProtocolSession.handlerFactory(registry),
            )
        }
        rcsFactories.forEach { (clientType, factory) ->
            builder.rcs(listOf(clientType), factory)
        }
        runtimeFeatures.forEach { feature -> builder.runtimeFeature(feature) }
        resolveSidecars().forEach { (feature, value) -> builder.sidecar(feature, value) }
        return builder.build()
    }

    fun createConfig(
        cluster: AirPlayDisplayConfig,
        runtime: AirPlayUltraRuntime,
    ): AirPlayUltraConfig {
        val vehicleStateProtocolInfo = AirPlayVehicleStateProtocolInfo.from(
            registrations = pluginRegistrations,
            pluginMapping = pluginMapping,
        )
        val allSidecars = resolveSidecars()

        val readyFeatures = linkedSetOf<AirPlayFeature>()
        allSidecars.keys.forEach { feature ->
            if (runtimeReadyFor(feature, runtime)) {
                readyFeatures += feature
            }
        }
        runtimeFeatures.forEach { feature ->
            if (runtimeReadyFor(feature, runtime)) {
                readyFeatures += feature
            }
        }
        return AirPlayUltraConfig(
            cluster = cluster,
            vehicleStateProtocolInfo = vehicleStateProtocolInfo,
            uiSyncInfo = uiSyncInfo,
            fileTransferInfo = sidecars.wireMap(AirPlayFeature.FILE_TRANSFER),
            logTransferInfo = sidecars.wireMap(AirPlayFeature.LOG_TRANSFER),
            mainBufferedInfo = sidecars.wireMap(AirPlayFeature.MAIN_BUFFERED),
            videoPlaybackInfo = sidecars.wireMap(AirPlayFeature.VIDEO_PLAYBACK),
            sessionManagementInfo = sidecars.wireMap(AirPlayFeature.SESSION_MANAGEMENT),
            readyFeatures = readyFeatures,
            runtime = runtime,
        )
    }

    private fun runtimeReadyFor(
        feature: AirPlayFeature,
        runtime: AirPlayUltraRuntime,
    ): Boolean {
        val requiredClientTypes = AirPlayFeatureCatalog.requiredRcsClientTypes(feature)
        return if (requiredClientTypes.isNotEmpty()) {
            requiredClientTypes.all(runtime::supportsRcsClientType)
        } else {
            runtime.supportsFeature(feature)
        }
    }

    private fun resolveSidecars(): Map<AirPlayFeature, Any?> {
        val result = linkedMapOf<AirPlayFeature, Any?>()
        AirPlayVehicleStateProtocolInfo.from(
            registrations = pluginRegistrations,
            pluginMapping = pluginMapping,
        )?.let {
            result[AirPlayFeature.VEHICLE_STATE_PROTOCOL] = it.toInfoResponseMap()
        }
        uiSyncInfo?.let {
            result[AirPlayFeature.UI_SYNC] = it.toWireMap()
        }
        sidecars.forEach { (feature, value) ->
            require(!result.containsKey(feature)) {
                "AirPlay sidecar '${feature.infoResponseKey}' is supplied more than once"
            }
            result[feature] = value
        }
        return result
    }

    private fun Map<AirPlayFeature, Any?>.wireMap(
        feature: AirPlayFeature,
    ): Map<String, Any?>? {
        val value = this[feature] ?: return null
        val raw = value as? Map<*, *>
            ?: throw AirPlayConfigurationException(
                "AirPlay sidecar '${feature.infoResponseKey}' must be a dictionary",
            )
        val result = LinkedHashMap<String, Any?>(raw.size)
        raw.forEach { (key, item) ->
            val name = key as? String
                ?: throw AirPlayConfigurationException(
                    "AirPlay sidecar '${feature.infoResponseKey}' keys must be strings",
                )
            result[name] = item
        }
        return result
    }

    companion object {
        fun from(
            provider: CafPluginRegistrationProvider,
            pluginMapping: Map<String, Long> = emptyMap(),
            uiSyncInfo: AirPlayUiSyncInfo? = null,
            sidecars: Map<AirPlayFeature, Any?> = emptyMap(),
            rcsFactories: Map<RcsClientType, RcsDataStreamHandlerFactory> = emptyMap(),
            runtimeFeatures: Set<AirPlayFeature> = emptySet(),
        ): AirPlayUltraProvisioning = AirPlayUltraProvisioning(
            pluginRegistrations = provider.registrations().toList(),
            pluginMapping = pluginMapping,
            uiSyncInfo = uiSyncInfo,
            sidecars = sidecars,
            rcsFactories = rcsFactories,
            runtimeFeatures = runtimeFeatures,
        )
    }
}
