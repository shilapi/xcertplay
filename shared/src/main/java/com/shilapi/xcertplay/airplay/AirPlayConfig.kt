package com.shilapi.xcertplay.airplay

/** Display insets in pixels, used for CarPlay viewArea and safeArea declarations. */
data class AirPlayInsets(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

/** One display advertised to the phone in /info. */
data class AirPlayDisplayConfig(
    val widthPixels: Int,
    val heightPixels: Int,
    val widthPhysicalMm: Int? = null,
    val heightPhysicalMm: Int? = null,
    val fps: Int = 60,
    val primaryInputDevice: Int = 1,
    val viewArea: AirPlayInsets? = null,
    val safeArea: AirPlayInsets? = null,
    val safeAreaDrawOutside: Boolean? = null,
    val initialUrl: String? = null,
)

/** One OEM homescreen icon. */
data class AirPlayIcon(
    val widthPixels: Int,
    val heightPixels: Int,
    val data: ByteArray,
)

/**
 * Vehicle-state plugin metadata advertised in `/info`.
 *
 * The outer schema is firmware-confirmed:
 * - [pluginConfigs] is encoded as an NSArray on the AirPlay wire.
 * - [pluginMapping] is encoded as an NSDictionary on the AirPlay wire.
 *
 * Their element/value schemas remain OEM and protocol-version specific. The transport layer never
 * invents vehicle-specific IDs or characteristic definitions.
 */
data class AirPlayVehicleStateProtocolInfo(
    val protocolVersion: String = "1.0",
    val pluginConfigs: List<Any?>,
    val pluginMapping: Map<Long, Any?> = emptyMap(),
)

/**
 * CarPlay Ultra capabilities for one handshake.
 *
 * A null [AirPlayConfig.ultra] disables every Ultra feature. Sidecars whose wire schema is not yet
 * implemented have no default value: unless [readyFeatures] explicitly declares the implementation
 * ready and the corresponding info is present, the feature stays out of `enabledFeatures`.
 *
 * [readyFeatures] is deliberately an enum set rather than a set of wire strings. A sidecar-backed
 * feature listed there but missing its info is rejected while building the local response.
 *
 * [runtime] is the shared handler registry for RCS-backed features. A sidecar and a `readyFeatures`
 * entry are insufficient when the corresponding RCS client type cannot be dispatched.
 */
data class AirPlayUltraConfig(
    val cluster: AirPlayDisplayConfig,
    val vehicleStateProtocolInfo: AirPlayVehicleStateProtocolInfo? = null,
    val uiSyncInfo: Map<String, Any?>? = null,
    val fileTransferInfo: Map<String, Any?>? = null,
    val logTransferInfo: Map<String, Any?>? = null,
    val mainBufferedInfo: Map<String, Any?>? = null,
    val videoPlaybackInfo: Map<String, Any?>? = null,
    val sessionManagementInfo: Map<String, Any?>? = null,
    val readyFeatures: Set<AirPlayFeature> = emptySet(),
    val runtime: AirPlayUltraRuntime? = null,
)

/** Immutable accessory configuration consumed by the AirPlay session server. */
data class AirPlayConfig(
    val deviceName: String,
    val deviceId: String,
    val btMac: String,
    val sourceVersion: String,
    val main: AirPlayDisplayConfig,
    val cluster: AirPlayDisplayConfig? = null,
    val ultra: AirPlayUltraConfig? = null,
    val rightHandDrive: Boolean = false,
    val port: Int = 7000,
    val entertainmentSampleRate: Int = 48000,
    val hevc: Boolean = false,
    val disableAudioOutput: Boolean = false,
    val microphone: Boolean = false,
    val manufacturer: String = "xcertplay",
    val model: String = "xcertplay",
    val oemLabel: String = "xcertplay",
    val icons: List<AirPlayIcon> = emptyList(),
)
