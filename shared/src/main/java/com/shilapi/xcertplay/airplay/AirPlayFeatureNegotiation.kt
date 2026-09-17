package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes

class AirPlayConfigurationException(message: String) : IllegalArgumentException(message)

/**
 * AirPlay feature wire names and their local `/info` sidecar contract.
 *
 * `infoResponseKey` is non-null only when firmware requires a top-level sidecar in the accessory
 * `/info` response. Fields observed only in the phone's `/info` request, such as `altScreenURLs`,
 * intentionally do not have an `infoResponseKey`.
 */
enum class AirPlayFeature(
    val wireName: String,
    val infoResponseKey: String? = null,
) {
    UI_CONTEXT("uiContext"),
    VIEW_AREAS("viewAreas"),
    CORNER_MASKS("cornerMasks"),
    FOCUS_TRANSFER("focusTransfer"),
    H264_LEVEL_5_1("h.264Level5.1"),
    MAIN_BUFFERED("mainBuffered", "mainBufferedInfo"),
    ALT_SCREEN("altScreen"),
    ENHANCED_SIRI("enhancedSiri"),
    HEVC("hevc", "hevcInfo"),
    FILE_TRANSFER("fileTransfer", "fileTransferInfo"),
    VEHICLE_STATE_PROTOCOL("vehicleStateProtocol", "vehicleStateProtocolInfo"),
    SESSION_MANAGEMENT("sessionManagement", "sessionManagementInfo"),
    VIDEO_PLAYBACK("videoPlayback", "videoPlaybackInfo"),
    LOG_TRANSFER("logTransfer", "logTransferInfo"),
    UI_SYNC("uiSync", "uiSyncInfo"),
    IAP_CHANNEL("iAPChannel"),
    ;

    val requiresInfoResponseSidecar: Boolean
        get() = infoResponseKey != null
}

/**
 * AirPlay feature names used by the control SETUP proposal and response.
 *
 * Only features whose complete local behavior is ready belong in
 * [AirPlayFeatureCapabilities.supportedFeatures]. Unknown strings from the phone are intentionally
 * ignored by [AirPlayFeatureNegotiation].
 */
object AirPlayFeatureCatalog {
    val ALL: List<AirPlayFeature> = AirPlayFeature.entries.toList()

    private val byName = ALL.associateBy(AirPlayFeature::wireName)

    fun find(wireName: String): AirPlayFeature? = byName[wireName]

    fun require(wireName: String): AirPlayFeature =
        requireNotNull(byName[wireName]) { "Unknown AirPlay feature '$wireName'" }
}

/** Fully implemented and currently ready features for one AirPlay control session. */
data class AirPlayFeatureCapabilities(
    val supportedFeatureSet: Set<AirPlayFeature>,
    val ultraDisplay: AirPlayDisplayConfig? = null,
) {
    val supportedFeatures: List<String>
        get() = supportedFeatureSet.map(AirPlayFeature::wireName)

    fun supports(feature: AirPlayFeature): Boolean = feature in supportedFeatureSet

    fun supports(feature: String): Boolean {
        val known = AirPlayFeatureCatalog.find(feature) ?: return false
        return supports(known)
    }
}

/**
 * The intersection of the phone's proposal and the accessory's complete capabilities.
 *
 * Requested order is retained by [enabledFeatures], matching the reference negotiation flow while
 * keeping the response a strict subset of `features`.
 */
data class AirPlayFeatureProposal(
    val requestedFeatures: List<String>,
    val capabilities: AirPlayFeatureCapabilities,
) {
    val enabledFeatureSet: Set<AirPlayFeature> = requestedFeatures
        .distinct()
        .mapNotNull { AirPlayFeatureCatalog.find(it) }
        .filterTo(linkedSetOf()) { capabilities.supports(it) }

    val supportedFeatures: List<String>
        get() = capabilities.supportedFeatures

    val enabledFeatures: List<String> = requestedFeatures
        .distinct()
        .filter { capabilities.supports(it) }
}

/**
 * Builds and applies the receiver-side AirPlay string feature negotiation.
 *
 * `/info` is emitted before SETUP and therefore advertises sidecar structures independently. This
 * factory only decides which proposed strings are safe to acknowledge in `enabledFeatures`.
 */
object AirPlayFeatureNegotiation {
    fun requestedFeatures(value: Any?): List<String> {
        val entries = value as? List<*> ?: return emptyList()
        return entries
            .asSequence()
            .filterIsInstance<String>()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
    }

    fun capabilities(
        config: AirPlayConfig,
        eventPortAvailable: Boolean,
    ): AirPlayFeatureCapabilities {
        val supportedFeatures = linkedSetOf<AirPlayFeature>()
        if (config.main.widthPixels > 0 && config.main.heightPixels > 0) {
            supportedFeatures += AirPlayFeature.VIEW_AREAS
        }
        if (config.hevc) {
            supportedFeatures += AirPlayFeature.HEVC
        }
        if (eventPortAvailable) {
            supportedFeatures += AirPlayFeature.IAP_CHANNEL
        }

        val ultra = config.ultra
        val ultraDisplay = ultra?.cluster
        ultra?.readyFeatures?.forEach { feature ->
            when (feature) {
                AirPlayFeature.VIEW_AREAS -> if (AirPlayFeature.VIEW_AREAS !in supportedFeatures) {
                    throw AirPlayConfigurationException(
                        "viewAreas is ready but the main display is invalid",
                    )
                }

                AirPlayFeature.HEVC -> if (!config.hevc) {
                    throw AirPlayConfigurationException(
                        "hevc is ready but AirPlayConfig.hevc is false",
                    )
                }

                AirPlayFeature.IAP_CHANNEL -> if (!eventPortAvailable) {
                    throw AirPlayConfigurationException(
                        "iAPChannel is ready but the event port is unavailable",
                    )
                }

                else -> Unit
            }
            validateReadyFeature(config, feature)
            supportedFeatures += feature
        }

        validateSidecars(config, supportedFeatures)
        return AirPlayFeatureCapabilities(
            supportedFeatureSet = supportedFeatures,
            ultraDisplay = ultraDisplay.takeIf {
                AirPlayFeature.ALT_SCREEN in supportedFeatures
            },
        )
    }

    fun resolveInfoSidecar(config: AirPlayConfig, feature: AirPlayFeature): Any? =
        config.ultra?.runtime?.resolveInfoSidecar(feature)
            ?: when (feature) {
                AirPlayFeature.HEVC -> emptyMap<String, Any?>()
                AirPlayFeature.VEHICLE_STATE_PROTOCOL ->
                    config.ultra?.vehicleStateProtocolInfo?.toWireMap()
                AirPlayFeature.UI_SYNC -> config.ultra?.uiSyncInfo
                AirPlayFeature.FILE_TRANSFER -> config.ultra?.fileTransferInfo
                AirPlayFeature.LOG_TRANSFER -> config.ultra?.logTransferInfo
                AirPlayFeature.MAIN_BUFFERED -> config.ultra?.mainBufferedInfo
                AirPlayFeature.VIDEO_PLAYBACK -> config.ultra?.videoPlaybackInfo
                AirPlayFeature.SESSION_MANAGEMENT -> config.ultra?.sessionManagementInfo
                else -> null
            }

    fun validateEnabledFeatures(config: AirPlayConfig, features: Set<AirPlayFeature>) {
        features.forEach { feature -> validateReadyFeature(config, feature) }
        validateSidecars(config, features)
    }

    fun propose(
        requestedFeatures: Collection<String>,
        capabilities: AirPlayFeatureCapabilities,
    ): AirPlayFeatureProposal = AirPlayFeatureProposal(
        requestedFeatures = requestedFeatures.toList(),
        capabilities = capabilities,
    )

    fun propose(
        config: AirPlayConfig,
        requestedFeatures: Any?,
        eventPortAvailable: Boolean,
    ): AirPlayFeatureProposal = propose(
        requestedFeatures = requestedFeatures(requestedFeatures),
        capabilities = capabilities(config, eventPortAvailable),
    )

    private fun validateSidecars(
        config: AirPlayConfig,
        supportedFeatures: Set<AirPlayFeature>,
    ) {
        supportedFeatures
            .filter { it.requiresInfoResponseSidecar }
            .forEach { feature ->
                if (resolveInfoSidecar(config, feature) == null) {
                    throw AirPlayConfigurationException(
                        "AirPlay feature '${feature.wireName}' is ready but " +
                            "'${feature.infoResponseKey}' is missing",
                    )
                }
            }

        config.ultra?.vehicleStateProtocolInfo?.let { protocolInfo ->
            if (AirPlayFeature.VEHICLE_STATE_PROTOCOL in supportedFeatures) {
                protocolInfo.validate()
            }
        }
    }

    private fun validateReadyFeature(
        config: AirPlayConfig,
        feature: AirPlayFeature,
    ) {
        val ultra = config.ultra
        when (feature) {
            AirPlayFeature.ALT_SCREEN -> {
                val cluster = ultra?.cluster
                if (cluster == null ||
                    cluster.widthPixels <= 0 ||
                    cluster.heightPixels <= 0
                ) {
                    throw AirPlayConfigurationException(
                        "altScreen is ready but the cluster display is invalid",
                    )
                }
            }

            AirPlayFeature.VIEW_AREAS,
            AirPlayFeature.HEVC,
            AirPlayFeature.IAP_CHANNEL -> Unit

            else -> Unit
        }

        if (feature.requiresInfoResponseSidecar && resolveInfoSidecar(config, feature) == null) {
            throw AirPlayConfigurationException(
                "AirPlay feature '${feature.wireName}' is ready but " +
                    "'${feature.infoResponseKey}' is missing",
            )
        }

        val requiredClientTypes = requiredRcsClientTypes(feature)
        if (requiredClientTypes.isNotEmpty()) {
            val runtime = ultra?.runtime
            requiredClientTypes.forEach { clientType ->
                if (runtime?.supportsRcsClientType(clientType) != true) {
                    throw AirPlayConfigurationException(
                        "AirPlay feature '${feature.wireName}' is ready but RCS handler " +
                            "'${clientType.name}' is missing",
                    )
                }
            }
            return
        }

        if (feature !in BUILT_IN_FEATURES && ultra?.runtime?.supportsFeature(feature) != true) {
            throw AirPlayConfigurationException(
                "AirPlay feature '${feature.wireName}' is ready but no runtime handler is registered",
            )
        }
    }
}

private fun requiredRcsClientTypes(feature: AirPlayFeature): Set<RcsClientType> = when (feature) {
    AirPlayFeature.VEHICLE_STATE_PROTOCOL -> setOf(
        RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
        RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
    )
    AirPlayFeature.UI_SYNC -> setOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)
    AirPlayFeature.FILE_TRANSFER -> setOf(RcsClientTypes.CAR_PLAY_UPDATE_DATA)
    AirPlayFeature.LOG_TRANSFER -> setOf(RcsClientTypes.CAR_PLAY_LOGGING_DATA)
    else -> emptySet()
}

private val BUILT_IN_FEATURES = setOf(
    AirPlayFeature.VIEW_AREAS,
    AirPlayFeature.ALT_SCREEN,
    AirPlayFeature.HEVC,
    AirPlayFeature.IAP_CHANNEL,
)

private fun AirPlayVehicleStateProtocolInfo.toWireMap(): Map<String, Any?> {
    validate()
    return linkedMapOf(
        "protocolVersion" to protocolVersion,
        "pluginCount" to pluginConfigs.size,
        "pluginConfigs" to pluginConfigs,
        "pluginMapping" to pluginMapping,
    )
}

private fun AirPlayVehicleStateProtocolInfo.validate() {
    if (protocolVersion != SUPPORTED_PROTOCOL_VERSION) {
        throw AirPlayConfigurationException(
            "vehicleStateProtocolInfo.protocolVersion must be '$SUPPORTED_PROTOCOL_VERSION'",
        )
    }
    if (pluginConfigs.isEmpty()) {
        throw AirPlayConfigurationException(
            "vehicleStateProtocolInfo.pluginConfigs must contain at least one plugin",
        )
    }
}

private const val SUPPORTED_PROTOCOL_VERSION = "1.0"
