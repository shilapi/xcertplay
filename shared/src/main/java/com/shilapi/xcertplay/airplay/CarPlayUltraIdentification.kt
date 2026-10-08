package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2CarPlayThemeContract

/** Freeze the same vehicle asset identity used by 0x4301 before either identification exchange. */
object CarPlayUltraIdentification {
    fun configure(base: Iap2IdentificationConfig, config: AirPlayConfig): Iap2IdentificationConfig {
        val uiReady = config.ultra?.readyFeatures?.contains(AirPlayFeature.UI_SYNC) == true
        if (uiReady) {
            AirPlayFeatureNegotiation.validateEnabledFeatures(config, setOf(AirPlayFeature.UI_SYNC))
        }
        val asset = config.ultra?.clusterAsset
        require(asset == null || uiReady) { "Cluster asset requires a ready UI Sync implementation" }
        val result = base.copy(supportsThemeAssets = uiReady && asset != null, themeAssetIdentifier = asset?.assetId)
        Iap2CarPlayThemeContract.validateIdentification(result, config.ultra?.sdkVersion, asset)
        return result
    }
}
