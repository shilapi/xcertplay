package com.shilapi.xcertplay.airplay

/**
 * AirPlay feature names used by the control SETUP proposal and response.
 *
 * Only features whose complete local behavior is ready belong in
 * [AirPlayFeatureCapabilities.supportedFeatures]. Unknown strings from the phone are intentionally
 * ignored by [AirPlayFeatureNegotiation].
 */
object AirPlayFeatureCatalog {
    const val VIEW_AREAS = "viewAreas"
    const val HEVC = "hevc"
    const val IAP_CHANNEL = "iAPChannel"
    const val ALT_SCREEN = "altScreen"
    const val VEHICLE_STATE_PROTOCOL = "vehicleStateProtocol"
    const val UI_SYNC = "uiSync"
}

/** Fully implemented and currently ready features for one AirPlay control session. */
data class AirPlayFeatureCapabilities(
    val supportedFeatures: List<String>,
    val ultraDisplay: AirPlayDisplayConfig? = null,
) {
    fun supports(feature: String): Boolean = feature in supportedFeatures
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
    val supportedFeatures: List<String>
        get() = capabilities.supportedFeatures

    val enabledFeatures: List<String> = requestedFeatures
        .distinct()
        .filter(capabilities::supports)
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
        val supportedFeatures = linkedSetOf<String>()
        if (config.main.widthPixels > 0 && config.main.heightPixels > 0) {
            supportedFeatures += AirPlayFeatureCatalog.VIEW_AREAS
        }
        if (config.hevc) {
            supportedFeatures += AirPlayFeatureCatalog.HEVC
        }
        if (eventPortAvailable) {
            supportedFeatures += AirPlayFeatureCatalog.IAP_CHANNEL
        }

        val ultra = config.ultra
        val ultraDisplay = ultra?.cluster
        if (ultra != null && ultraDisplay != null) {
            supportedFeatures += AirPlayFeatureCatalog.ALT_SCREEN
            if (ultra.vehicleStateProtocolInfo != null) {
                supportedFeatures += AirPlayFeatureCatalog.VEHICLE_STATE_PROTOCOL
            }
            if (ultra.uiSyncInfo != null) {
                supportedFeatures += AirPlayFeatureCatalog.UI_SYNC
            }
        }

        return AirPlayFeatureCapabilities(
            supportedFeatures = supportedFeatures.toList(),
            ultraDisplay = ultraDisplay,
        )
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
}
