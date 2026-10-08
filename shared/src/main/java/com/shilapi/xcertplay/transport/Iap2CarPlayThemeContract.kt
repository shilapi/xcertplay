package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2ClusterAsset

/** Keep theme identification and start-session information consistent before contacting the phone. */
internal object Iap2CarPlayThemeContract {
    fun validateAssetInformation(sdkVersion: String?, asset: Iap2ClusterAsset?) {
        sdkVersion?.let {
            require(it.isNotBlank() && '\u0000' !in it) { "sdkVersion must be a non-empty C string" }
        }
        require(asset == null || sdkVersion != null) {
            "A cluster asset requires SDK information in CarPlayStartSession"
        }
    }

    fun validateIdentification(
        identification: Iap2IdentificationConfig,
        sdkVersion: String?,
        asset: Iap2ClusterAsset?,
    ) {
        validateAssetInformation(sdkVersion, asset)
        if (identification.supportsThemeAssets) {
            // carkitd can persist an Ultra opt-out when a theme-capable HU starts without asset info.
            require(asset != null && sdkVersion != null) {
                "Theme support requires complete CarPlayStartSession asset and SDK information"
            }
            require(identification.themeAssetIdentifier == null || identification.themeAssetIdentifier == asset.assetId) {
                "Identification and CarPlayStartSession theme identities must match"
            }
        }
    }
}
