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
 * The outer schema is firmware-confirmed. [pluginConfigs] and [pluginMapping] remain OEM data so
 * the transport layer never invents vehicle-specific IDs or characteristic definitions.
 */
data class AirPlayVehicleStateProtocolInfo(
    val protocolVersion: String = "1.0",
    val pluginConfigs: Map<Long, Any?>,
    val pluginMapping: Map<Long, Any?> = emptyMap(),
)

/**
 * CarPlay Ultra capabilities for one handshake.
 *
 * A null [AirPlayConfig.ultra] disables every Ultra feature. The optional vehicle/UI-sync entries
 * keep their corresponding features out of `enabledFeatures` until their implementation has the
 * required sidecar data.
 */
data class AirPlayUltraConfig(
    val cluster: AirPlayDisplayConfig,
    val vehicleStateProtocolInfo: AirPlayVehicleStateProtocolInfo? = null,
    val uiSyncInfo: Map<String, Any?>? = null,
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
