package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistration
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistry

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
 * CarKit requires every `pluginConfigs` element to be an NSDictionary containing an NSNumber
 * `pluginID`. CarAccessoryFramework reads `pluginMapping` as plugin-name -> NSNumber pluginID.
 * Other element and mapping fields remain OEM/protocol-specific and are preserved verbatim.
 */
data class AirPlayVehicleStateProtocolInfo(
    val protocolVersion: String = "1.0",
    val pluginConfigs: List<Map<String, Any?>>,
    val pluginMapping: Map<String, Long> = emptyMap(),
) {
    companion object {
        /**
         * Builds the `/info` sidecar from explicitly supplied CAF registrations.
         *
         * A registration supplies the plugin ID and may supply additional wire fields through
         * `CafPluginRegistration.pluginConfig`. No OEM values are synthesized. When no registration
         * is supplied this returns null and the feature stays disabled.
         */
        fun from(
            registrations: Collection<CafPluginRegistration>,
            pluginMapping: Map<String, Long> = emptyMap(),
        ): AirPlayVehicleStateProtocolInfo? {
            if (registrations.isEmpty()) return null
            val pluginConfigs = registrations.map { registration ->
                registration.wirePluginConfig()
            }
            val effectiveMapping = LinkedHashMap(pluginMapping)
            registrations.forEach { registration ->
                val name = registration.pluginName?.trim().orEmpty()
                if (name.isEmpty()) return@forEach
                val existing = effectiveMapping[name]
                if (existing != null && existing != registration.pluginId) {
                    throw AirPlayConfigurationException(
                        "vehicleStateProtocolInfo.pluginMapping '$name' maps to multiple plugin IDs",
                    )
                }
                effectiveMapping[name] = registration.pluginId
            }
            return AirPlayVehicleStateProtocolInfo(
                pluginConfigs = pluginConfigs,
                pluginMapping = effectiveMapping,
            )
        }

        fun fromRegistry(
            registry: CafPluginRegistry,
            pluginMapping: Map<String, Long> = emptyMap(),
        ): AirPlayVehicleStateProtocolInfo? = from(
            registrations = registry.allRegistrations(),
            pluginMapping = pluginMapping,
        )
    }
}

/**
 * `uiSyncInfo` is required when `uiSync` is enabled, but the firmware only checks that the
 * top-level key exists and is an NSDictionary. No child key is read by AirPlaySender. Extra fields
 * are retained for forward compatibility and OEM extensions.
 */
data class AirPlayUiSyncInfo(
    val values: Map<String, Any?> = emptyMap(),
) {
    fun toWireMap(): Map<String, Any?> = LinkedHashMap(values)
}

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
    val uiSyncInfo: AirPlayUiSyncInfo? = null,
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
