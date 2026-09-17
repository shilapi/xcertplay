package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.SafeAreaCodec
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.File

/** SharedPreferences persistence for the accessory identity and paired controllers. */
object AirPlayPersistence {
    private const val PREFS = "xcertplay_airplay"
    private const val KEY_IDENT_PRIVATE = "identity_private"
    private const val KEY_IDENT_PUBLIC = "identity_public"
    private const val KEY_PAIRING_ID = "pairing_id"
    private const val KEY_PAIRING_IDS = "pairing_ids"
    private const val KEY_LOCKDOWN_HOST_ID = "lockdown_host_id"
    private const val KEY_LOCKDOWN_SYSTEM_BUID = "lockdown_system_buid"
    private const val KEY_LOCKDOWN_WIFI_MAC = "lockdown_wifi_mac"
    private const val KEY_LOCKDOWN_DEVICE_PUBLIC = "lockdown_device_public"
    private const val KEY_LOCKDOWN_DEVICE_CERT = "lockdown_device_cert"
    private const val KEY_LOCKDOWN_HOST_PRIVATE = "lockdown_host_private"
    private const val KEY_LOCKDOWN_HOST_CERT = "lockdown_host_cert"
    private const val KEY_LOCKDOWN_ROOT_PRIVATE = "lockdown_root_private"
    private const val KEY_LOCKDOWN_ROOT_CERT = "lockdown_root_cert"
    private const val KEY_DISPLAY_SCALE_TENTHS = "display_scale_tenths"
    private const val KEY_HEVC_ENABLED = "hevc_enabled"
    private const val KEY_HEVC_SOFTWARE_DECODER = "hevc_software_decoder"
    private const val KEY_CARPLAY_ULTRA_ENABLED = "carplay_ultra_enabled"
    private const val KEY_ADVANCED_AUDIO_CHANNEL_MAPPING = "advanced_audio_channel_mapping"
    private const val KEY_WIRELESS_ENABLED = "wireless_enabled"
    private const val KEY_WIRELESS_HOTSPOT_MODE = "wireless_hotspot_mode"
    private const val KEY_MANUAL_HOTSPOT_SSID = "manual_hotspot_ssid"
    private const val KEY_MANUAL_HOTSPOT_PASSPHRASE = "manual_hotspot_passphrase"
    private const val KEY_MANUAL_HOTSPOT_BAND = "manual_hotspot_band"
    private const val KEY_MANUAL_HOTSPOT_CHANNEL = "manual_hotspot_channel"
    private const val KEY_MANUAL_HOTSPOT_SECURITY = "manual_hotspot_security"
    private const val KEY_DEBUG_LOGS_ENABLED = "debug_logs_enabled"
    private const val KEY_MANUFACTURER = "manufacturer"
    private const val KEY_MODEL = "model"
    private const val KEY_OEM_LABEL = "oem_label"
    private const val KEY_FPS = "display_fps"
    private const val KEY_WIDTH_PHYSICAL_MM = "display_width_physical_mm"
    private const val KEY_PHYSICAL_SIZE_BASIS = "display_physical_size_basis"
    private const val KEY_MAX_DETECTED_WIDTH = "display_max_detected_width"
    private const val KEY_MAX_DETECTED_HEIGHT = "display_max_detected_height"
    private const val KEY_RIGHT_HAND_DRIVE = "right_hand_drive"
    private const val KEY_HIDE_TOP_BAR = "hide_top_bar"
    private const val KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar"
    private const val KEY_SAFE_AREA_DRAW_OUTSIDE = "safe_area_draw_outside"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_LOCATION_REPORTING_ENABLED = "location_reporting_enabled"
    private const val KEY_MFI_TARGET = "mfi_target"
    private const val KEY_MFI_I2C_PATH = "mfi_i2c_path"
    private const val KEY_REMOTE_MFI_SERVER = "remote_mfi_server"
    private const val KEY_REMOTE_MFI_TOKEN = "remote_mfi_token"
    private const val SAFE_AREA_KEY_PREFIX = "safe_area_"
    private const val CUSTOM_ICON_FILE = "airplay-icon.png"

    const val DEFAULT_MANUFACTURER = "xcertplay"
    const val DEFAULT_MODEL = "xcertplay"
    const val DEFAULT_OEM_LABEL = ""
    const val DEFAULT_MFI_I2C_PATH = "/dev/i2c-1"

    fun loadDisplayScaleTenths(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CarPlayDisplayScale.sanitize(
            prefs.getInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.DEFAULT_TENTHS),
        )
    }

    fun saveDisplayScaleTenths(context: Context, tenths: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.sanitize(tenths))
            .apply()
    }

    fun loadHevcEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_ENABLED, true)

    fun saveHevcEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_ENABLED, enabled)
            .apply()
    }

    fun loadHevcSoftwareDecoderEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_SOFTWARE_DECODER, false)

    fun saveHevcSoftwareDecoderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_SOFTWARE_DECODER, enabled)
            .apply()
    }

    fun loadCarPlayUltraEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CARPLAY_ULTRA_ENABLED, false)

    fun saveCarPlayUltraEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CARPLAY_ULTRA_ENABLED, enabled)
            .apply()
    }

    fun loadAdvancedAudioChannelMapping(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, false)

    fun saveAdvancedAudioChannelMapping(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, enabled)
            .apply()
    }

    fun loadWirelessEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIRELESS_ENABLED, true)

    fun saveWirelessEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIRELESS_ENABLED, enabled)
            .apply()
    }

    fun loadMfiTarget(context: Context): MfiTarget {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_TARGET, null)
        return MfiTarget.entries.firstOrNull { it.name == stored } ?: MfiTarget.USB_CH341
    }

    fun saveMfiTarget(context: Context, target: MfiTarget) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_TARGET, target.name)
            .apply()
    }

    fun loadMfiI2cPath(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_I2C_PATH, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MFI_I2C_PATH

    fun saveMfiI2cPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_I2C_PATH, path.trim())
            .apply()
    }

    fun loadRemoteMfiServer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_SERVER, null)
            .orEmpty()

    fun saveRemoteMfiServer(context: Context, server: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_SERVER, server)
            .apply()
    }

    fun loadRemoteMfiToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_TOKEN, null)
            .orEmpty()

    fun saveRemoteMfiToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_TOKEN, token)
            .apply()
    }

    fun loadWirelessHotspotMode(context: Context): WirelessHotspotMode {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WIRELESS_HOTSPOT_MODE, null)
        val mode = WirelessHotspotMode.entries.firstOrNull { it.name == stored }
            ?: WirelessHotspotMode.WIFI_P2P
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            mode == WirelessHotspotMode.WIFI_P2P
        ) {
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT
        } else {
            mode
        }
    }

    fun saveWirelessHotspotMode(context: Context, mode: WirelessHotspotMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WIRELESS_HOTSPOT_MODE, mode.name)
            .apply()
    }

    fun loadManualHotspotSsid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_SSID, null)
            .orEmpty()

    fun saveManualHotspotSsid(context: Context, ssid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SSID, ssid)
            .apply()
    }

    fun loadManualHotspotPassphrase(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_PASSPHRASE, null)
            .orEmpty()

    fun saveManualHotspotPassphrase(context: Context, passphrase: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_PASSPHRASE, passphrase)
            .apply()
    }

    fun loadManualHotspotBand(context: Context): ManualHotspotBand {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_BAND, null)
        return ManualHotspotBand.entries.firstOrNull { it.name == stored }
            ?: ManualHotspotBand.AUTO
    }

    fun saveManualHotspotBand(context: Context, band: ManualHotspotBand) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_BAND, band.name)
            .apply()
    }

    fun loadManualHotspotChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MANUAL_HOTSPOT_CHANNEL, 0)
            .coerceIn(0, 196)

    fun saveManualHotspotChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MANUAL_HOTSPOT_CHANNEL, channel.coerceIn(0, 196))
            .apply()
    }

    fun loadManualHotspotSecurity(context: Context): ManualHotspotSecurity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MANUAL_HOTSPOT_SECURITY, null)
        return ManualHotspotSecurity.entries.firstOrNull { it.name == stored }
            ?: if (loadManualHotspotPassphrase(context).isEmpty()) {
                ManualHotspotSecurity.OPEN
            } else {
                ManualHotspotSecurity.WPA2
            }
    }

    fun saveManualHotspotSecurity(context: Context, security: ManualHotspotSecurity) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SECURITY, security.name)
            .apply()
    }

    fun loadDebugLogsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_LOGS_ENABLED, false)

    fun saveDebugLogsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DEBUG_LOGS_ENABLED, enabled)
            .apply()
    }

    fun loadAutoStartOnBoot(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START_ON_BOOT, false)

    fun saveAutoStartOnBoot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_START_ON_BOOT, enabled)
            .apply()
    }

    fun loadLocationReportingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCATION_REPORTING_ENABLED, false)

    fun saveLocationReportingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOCATION_REPORTING_ENABLED, enabled)
            .apply()
    }

    fun loadManufacturer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUFACTURER, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MANUFACTURER

    fun saveManufacturer(context: Context, manufacturer: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUFACTURER, manufacturer)
            .apply()
    }

    fun loadModel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL

    fun saveModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODEL, model)
            .apply()
    }

    fun loadOemLabel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OEM_LABEL, DEFAULT_OEM_LABEL)
            .orEmpty()

    fun saveOemLabel(context: Context, oemLabel: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_OEM_LABEL, oemLabel)
            .apply()
    }

    fun loadFps(context: Context): Int = AirPlayDisplaySettings.sanitizeFps(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, AirPlayDisplaySettings.DEFAULT_FPS),
    )

    fun saveFps(context: Context, fps: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FPS, AirPlayDisplaySettings.sanitizeFps(fps))
            .apply()
    }

    fun loadWidthPhysicalMm(context: Context): Int =
        AirPlayDisplaySettings.sanitizeWidthPhysicalMm(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(
                KEY_WIDTH_PHYSICAL_MM,
                AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM,
            ),
        )

    fun saveWidthPhysicalMm(context: Context, widthPhysicalMm: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_WIDTH_PHYSICAL_MM,
                AirPlayDisplaySettings.sanitizeWidthPhysicalMm(widthPhysicalMm),
            )
            .apply()
    }

    fun loadPhysicalSizeBasis(context: Context): AirPlayPhysicalSizeBasis {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PHYSICAL_SIZE_BASIS, null)
        return AirPlayPhysicalSizeBasis.entries.firstOrNull { it.name == stored }
            ?: AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    }

    fun savePhysicalSizeBasis(context: Context, basis: AirPlayPhysicalSizeBasis) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHYSICAL_SIZE_BASIS, basis.name)
            .apply()
    }

    fun loadMaximumDetectedDisplay(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MAX_DETECTED_WIDTH, 0) to
            prefs.getInt(KEY_MAX_DETECTED_HEIGHT, 0)
    }

    fun saveMaximumDetectedDisplay(
        context: Context,
        widthPixels: Int,
        heightPixels: Int,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MAX_DETECTED_WIDTH, widthPixels.coerceAtLeast(0))
            .putInt(KEY_MAX_DETECTED_HEIGHT, heightPixels.coerceAtLeast(0))
            .apply()
    }

    fun loadRightHandDrive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RIGHT_HAND_DRIVE, false)

    fun saveRightHandDrive(context: Context, rightHandDrive: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RIGHT_HAND_DRIVE, rightHandDrive)
            .apply()
    }

    fun loadHideTopBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_TOP_BAR, true)

    fun saveHideTopBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_TOP_BAR, hide)
            .apply()
    }

    fun loadHideBottomBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_BOTTOM_BAR, true)

    fun saveHideBottomBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_BOTTOM_BAR, hide)
            .apply()
    }

    fun loadSafeAreaDrawOutside(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, true)

    fun saveSafeAreaDrawOutside(context: Context, drawOutside: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, drawOutside)
            .apply()
    }

    fun loadSafeAreaRect(context: Context, widthPixels: Int, heightPixels: Int): SafeAreaRect? {
        require(widthPixels > 0 && heightPixels > 0) { "Activity dimensions must be positive" }
        return SafeAreaCodec.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(safeAreaKey(widthPixels, heightPixels), null),
        )
    }

    fun saveSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        rect: SafeAreaRect,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                safeAreaKey(activityWidthPixels, activityHeightPixels),
                SafeAreaCodec.encode(rect.clampTo(activityWidthPixels, activityHeightPixels)),
            )
        if (commit) editor.commit() else editor.apply()
    }

    fun clearSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(safeAreaKey(activityWidthPixels, activityHeightPixels))
        if (commit) editor.commit() else editor.apply()
    }

    fun loadCustomAirPlayIconFile(context: Context): File? =
        File(context.filesDir, CUSTOM_ICON_FILE).takeIf { it.isFile }

    fun saveCustomAirPlayIcon(context: Context, encodedImage: ByteArray) {
        require(encodedImage.isNotEmpty()) { "AirPlay icon data must not be empty" }
        File(context.filesDir, CUSTOM_ICON_FILE).outputStream().use { output ->
            output.write(encodedImage)
        }
    }

    fun clearCustomAirPlayIcon(context: Context) {
        File(context.filesDir, CUSTOM_ICON_FILE).delete()
    }

    fun loadIdentity(context: Context): AirPlayIdentity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val privateKey = prefs.getString(KEY_IDENT_PRIVATE, null)
        val publicKey = prefs.getString(KEY_IDENT_PUBLIC, null)
        val pairingId = prefs.getString(KEY_PAIRING_ID, null)
        if (privateKey != null && publicKey != null && pairingId != null) {
            return AirPlayIdentity(privateKey.decodeHex(), publicKey.decodeHex(), pairingId)
        }
        return AirPlayIdentity.generate().also { identity ->
            prefs.edit()
                .putString(KEY_IDENT_PRIVATE, identity.privateKey.toHex())
                .putString(KEY_IDENT_PUBLIC, identity.publicKey.toHex())
                .putString(KEY_PAIRING_ID, identity.pairingId)
                .apply()
        }
    }

    fun loadPairings(context: Context, onSave: (String, ByteArray) -> Unit): PairingStore {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val store = PairingStore(onSave)
        for (identifier in prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty()) {
            prefs.getString("pairing.$identifier", null)?.let { store.save(identifier, it.decodeHex()) }
        }
        return store
    }

    fun savePairing(context: Context, identifier: String, longTermPublicKey: ByteArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val identifiers = prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty().toMutableSet()
        identifiers.add(identifier)
        prefs.edit()
            .putString("pairing.$identifier", longTermPublicKey.toHex())
            .putStringSet(KEY_PAIRING_IDS, identifiers)
            .apply()
    }

    fun loadLockdownRecord(context: Context): LockdownPairRecord? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hostId = prefs.getString(KEY_LOCKDOWN_HOST_ID, null) ?: return null
        val systemBuid = prefs.getString(KEY_LOCKDOWN_SYSTEM_BUID, null) ?: return null
        val wifiMac = prefs.getString(KEY_LOCKDOWN_WIFI_MAC, null) ?: return null
        val devicePublic = prefs.getString(KEY_LOCKDOWN_DEVICE_PUBLIC, null) ?: return null
        val deviceCert = prefs.getString(KEY_LOCKDOWN_DEVICE_CERT, null) ?: return null
        val hostPrivate = prefs.getString(KEY_LOCKDOWN_HOST_PRIVATE, null) ?: return null
        val hostCert = prefs.getString(KEY_LOCKDOWN_HOST_CERT, null) ?: return null
        val rootPrivate = prefs.getString(KEY_LOCKDOWN_ROOT_PRIVATE, null) ?: return null
        val rootCert = prefs.getString(KEY_LOCKDOWN_ROOT_CERT, null) ?: return null
        return try {
            LockdownPairRecord.restore(
                hostId = hostId,
                systemBuid = systemBuid,
                wifiMacAddress = wifiMac,
                devicePublicKeyPem = devicePublic.decodeHex(),
                deviceCertificatePem = deviceCert.decodeHex(),
                hostPrivateKeyPem = hostPrivate.decodeHex(),
                hostCertificatePem = hostCert.decodeHex(),
                rootPrivateKeyPem = rootPrivate.decodeHex(),
                rootCertificatePem = rootCert.decodeHex(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveLockdownRecord(context: Context, record: LockdownPairRecord) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOCKDOWN_HOST_ID, record.hostId)
            .putString(KEY_LOCKDOWN_SYSTEM_BUID, record.systemBuid)
            .putString(KEY_LOCKDOWN_WIFI_MAC, record.wifiMacAddress)
            .putString(KEY_LOCKDOWN_DEVICE_PUBLIC, record.devicePublicKeyPem.toHex())
            .putString(KEY_LOCKDOWN_DEVICE_CERT, record.deviceCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_HOST_PRIVATE, record.hostPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_HOST_CERT, record.hostCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_PRIVATE, record.rootPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_CERT, record.rootCertificatePem.toHex())
            .apply()
    }

    fun clearLockdownRecord(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LOCKDOWN_HOST_ID)
            .remove(KEY_LOCKDOWN_SYSTEM_BUID)
            .remove(KEY_LOCKDOWN_WIFI_MAC)
            .remove(KEY_LOCKDOWN_DEVICE_PUBLIC)
            .remove(KEY_LOCKDOWN_DEVICE_CERT)
            .remove(KEY_LOCKDOWN_HOST_PRIVATE)
            .remove(KEY_LOCKDOWN_HOST_CERT)
            .remove(KEY_LOCKDOWN_ROOT_PRIVATE)
            .remove(KEY_LOCKDOWN_ROOT_CERT)
            .apply()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun safeAreaKey(widthPixels: Int, heightPixels: Int): String =
        "$SAFE_AREA_KEY_PREFIX${widthPixels}x$heightPixels"
}
