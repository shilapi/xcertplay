package com.shilapi.xcertplay

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.MainMediaAudioBuffer
import com.shilapi.xcertplay.media.MediaMetricsMonitor
import com.shilapi.xcertplay.media.MicrophoneGain
import com.shilapi.xcertplay.media.MicrophoneLevelMonitor
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.isManualHotspotChannelCompatible
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen CarPlay host. It renders decoded video through a [TextureView], forwards touch to
 * the active AirPlay session, and drives the complete wired or wireless bring-up through
 * [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private data class SettingsBaseline(
        val safeAreaSize: DisplaySize?,
        val safeAreaRect: SafeAreaRect?,
        val customIconBytes: ByteArray?,
    )

    private lateinit var airPlayIdentity: AirPlayIdentity

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        localMfiCertificateUri = localMfiCertificateUri.takeIf { it.isNotEmpty() },
        localMfiPrivateKeyUri = localMfiPrivateKeyUri.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = "xcertplay",
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "xcertplay",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
        ),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        locationReportingEnabled = locationReportingEnabled,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingVpnConsent = false
            if (result.resultCode == RESULT_OK) {
                vpnReady = true
                maybeStartCarPlay()
            } else {
                setStatus("VPN consent was denied")
            }
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            val startGainTest = granted && microphoneGainTestAfterPermission && menuOpen
            microphoneGainTestAfterPermission = false
            requestStartupPrerequisites()
            if (startGainTest) startMicrophoneGainTest()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                if (!menuOpen) {
                    AirPlayPersistence.saveLocationReportingEnabled(
                        this@CarPlayHostActivity,
                        false,
                    )
                }
                locationReportingSwitch?.isChecked = false
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            updateResolutionMenu()
            if (!menuOpen) requestStartupPrerequisites()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                externalActivityInProgress = false
                return@registerForActivityResult
            }
            imageCrop.launch(
                Intent(this, ImageCropActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    private val imageCrop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            externalActivityInProgress = false
            if (result.resultCode == RESULT_OK) {
                updateAirPlayIconPreview()
                appendLog("Custom AirPlay icon updated")
            }
        }
    private val localMfiCertificatePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            externalActivityInProgress = false
            if (uri != null && retainDocumentReadPermission(uri, "certificate")) {
                localMfiCertificateUri = uri.toString()
                updateLocalMfiDocumentViews()
                mfiErrorView?.visibility = View.GONE
            }
        }
    private val localMfiPrivateKeyPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            externalActivityInProgress = false
            if (uri != null && retainDocumentReadPermission(uri, "private key")) {
                localMfiPrivateKeyUri = uri.toString()
                updateLocalMfiDocumentViews()
                mfiErrorView?.visibility = View.GONE
            }
        }

    private var mainWebDisplay = CarPlayWebDisplayConfig()
    private var clusterWebDisplay = CarPlayWebDisplayConfig(width = 1920)
    private val webDisplays get() = CarPlayWebDisplayFactory.find(sink)
    private var clusterPreview: TextureView? = null
    private var clusterPreviewSurface: Surface? = null
    private val webDisplayInputs = mutableMapOf<Int, Pair<EditText, EditText>>()
    private val webDisplayRefresh = mutableListOf<() -> Unit>()
    private val webDisplayAddressViews = mutableMapOf<Int, TextView>()
    private var videoView: TextureView? = null
    private var contentRoot: FrameLayout? = null
    private var gestureOverlay: View? = null
    private var disconnectedSettingsButton: View? = null
    private var settingsMenu: View? = null
    private var mfiTargetGroup: RadioGroup? = null
    private var mfiI2cFields: View? = null
    private var mfiRemoteFields: View? = null
    private var mfiLocalFields: View? = null
    private var mfiErrorView: TextView? = null
    private var mfiI2cPathInput: EditText? = null
    private var remoteMfiServerInput: EditText? = null
    private var remoteMfiTokenInput: EditText? = null
    private var localMfiCertificateDocumentView: TextView? = null
    private var localMfiPrivateKeyDocumentView: TextView? = null
    private var settingsBaseline: SettingsBaseline? = null
    private var locationReportingSwitch: Switch? = null
    private var mainMediaAudioBufferSeekBar: SeekBar? = null
    private var mainMediaAudioBufferValueView: TextView? = null
    private var microphoneGainSeekBar: SeekBar? = null
    private var microphoneGainValueView: TextView? = null
    private var microphoneTestButton: Button? = null
    private var microphoneLevelBar: ProgressBar? = null
    private var microphoneLevelValueView: TextView? = null
    private var microphoneLevelMonitor: MicrophoneLevelMonitor? = null
    private var microphoneGainTestAfterPermission = false
    private var statusView: TextView? = null
    private var statusScrollView: ScrollView? = null
    private var handshakePanel: HandshakePanelView? = null
    private var handshakeTimeline: HandshakeTimeline? = null
    private var lastAttemptFailure: String? = null
    private var resolutionValueView: TextView? = null
    private var resolutionPreviewView: TextView? = null
    private var hotspotStatusView: TextView? = null
    private var manualHotspotFields: View? = null
    private var manualHotspotErrorView: TextView? = null
    private var iconPreviewView: ImageView? = null
    private var iconStatusView: TextView? = null
    private var safeAreaSummaryView: TextView? = null
    private var safeAreaEditor: View? = null
    private var safeAreaEditorView: SafeAreaEditorView? = null
    private var safeAreaEditSize: DisplaySize? = null
    private var safeAreaEditorActive = false
    private var mediaMetricsOverlay: MediaMetricsOverlayView? = null
    private var mediaMetricsMonitor: MediaMetricsMonitor? = null
    private var externalActivityInProgress = false
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private var currentSurface: Surface? = null
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var hevcEnabled = true
    private var hevcSoftwareDecoderEnabled = false
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    private var mainMediaAudioBufferDurationMs = MainMediaAudioBuffer.DEFAULT_DURATION_MS
    @Volatile private var debugLogsEnabled = false
    private var videoFrameLogsEnabled = false
    private var videoFrameLogSwitch: Switch? = null
    private var mediaMetricsEnabled = false
    private var audioPacketCaptureEnabled = false
    private var moreGesturesToSettings = false
    private var autoStartOnBoot = false
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var oemLabel = AirPlayPersistence.DEFAULT_OEM_LABEL
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var hideTopBar = true
    private var hideBottomBar = true
    private var safeAreaDrawOutside = true
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    @Volatile private var microphoneGainPercent = MicrophoneGain.DEFAULT_PERCENT
    private var wirelessEnabled = false
    private var mfiTarget = MfiTarget.USB_CH341
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var localMfiCertificateUri = ""
    private var localMfiPrivateKeyUri = ""
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var manualHotspotSsid = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var awaitingVpnConsent = false
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var vpnReady = false
    private var hotspotStatus = HotspotStatus(state = "off")
    private var menuOpen = false
    private var darkMode = false
    private var activeAirPlaySession: AirPlaySession? = null
    private var activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var startAfterHandshakeReset = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private val sessionLog get() = CarPlayBackgroundSession.log
    private var gestureSequenceActive = false
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var edgeSettingsGestureCaptured = false
    private var edgeSettingsGestureEligible = false
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logLines = ScreenLogBuffer(MAX_SCREEN_LOG_LINES)
    private val pendingScreenLogs = ArrayDeque<PendingLog>()
    private val pendingScreenLogsLock = Any()
    private var screenDrainPosted = false
    private val drainScreenLogs = Runnable {
        val pending = synchronized(pendingScreenLogsLock) {
            screenDrainPosted = false
            pendingScreenLogs.toList().also { pendingScreenLogs.clear() }
        }
        if (debugLogsEnabled && !menuOpen) {
            pending.forEach { entry ->
                if (entry.generation == restartGeneration) {
                    appendScreenLog(entry.timestampMillis, entry.message)
                }
            }
        }
    }
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    private var logRenderScheduled = false
    private val renderLogLines = Runnable {
        logRenderScheduled = false
        refreshLogView(System.currentTimeMillis())
    }
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = currentSurface
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    existing?.release()
                    currentSurface = it
                    currentSurfaceTexture = texture
                }
            }
            appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
            attachSurface(surface)
            updateWebDisplayPreviews()
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            updateWebDisplayPreviews()
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) return true
            currentSurface?.let { surface ->
                detachSurface(SCREEN_TYPE_MAIN, surface)
                surface.release()
            }
            currentSurface = null
            currentSurfaceTexture = null
            appendLog("Texture surface destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeSessionLog()
        darkMode = isDarkMode(resources.configuration.uiMode)
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        setContentView(buildContentView())
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (menuOpen) {
                        if (safeAreaEditorActive) closeSafeAreaEditor() else cancelSettingsEdits()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadPersistedSettings() {
        mainWebDisplay = AirPlayPersistence.loadWebDisplay(this, SCREEN_TYPE_MAIN)
        clusterWebDisplay = AirPlayPersistence.loadWebDisplay(this, SCREEN_TYPE_ALT)
        displayScaleTenths = AirPlayPersistence.loadDisplayScaleTenths(this)
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        mainMediaAudioBufferDurationMs =
            AirPlayPersistence.loadMainMediaAudioBufferDurationMs(this)
        microphoneGainPercent = AirPlayPersistence.loadMicrophoneGainPercent(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        videoFrameLogsEnabled = AirPlayPersistence.loadVideoFrameLogsEnabled(this)
        sessionLog.videoFramesEnabled = videoFrameLogsEnabled
        mediaMetricsEnabled = AirPlayPersistence.loadMediaMetricsEnabled(this)
        audioPacketCaptureEnabled = AirPlayPersistence.loadAudioPacketCaptureEnabled(this)
        moreGesturesToSettings = AirPlayPersistence.loadMoreGesturesToSettings(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        locationPermissionAvailable = hasFineLocationPermission()
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        localMfiCertificateUri = AirPlayPersistence.loadLocalMfiCertificateUri(this)
        localMfiPrivateKeyUri = AirPlayPersistence.loadLocalMfiPrivateKeyUri(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    private fun requestStartupPrerequisites() {
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            requestVpnConsent()
        }
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestVpnConsent() {
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            vpnReady = true
            maybeStartCarPlay()
        } else {
            awaitingVpnConsent = true
            vpnConsent.launch(consent)
        }
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    override fun onResume() {
        super.onResume()
        CarPlayBackgroundSession.audioFocus(this).apply {
            retain(this@CarPlayHostActivity)
            requestFromHost()
        }
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable && !menuOpen) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        maybeStartCarPlay()
        applyFullscreenMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreenMode()
    }

    override fun onStop() {
        stopMicrophoneGainTest()
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nextDarkMode = isDarkMode(newConfig.uiMode)
        if (nextDarkMode != darkMode) {
            darkMode = nextDarkMode
            syncAirPlayDarkMode()
        }
        applyFullscreenMode()
        scrollLogsToBottom()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onDestroy() {
        CarPlayBackgroundSession.audioFocus(this).release(this)
        stopMicrophoneGainTest()
        mainHandler.removeCallbacks(applyDisplaySize)
        clusterPreviewSurface?.let { detachSurface(SCREEN_TYPE_ALT, it); it.release() }
        clusterPreviewSurface = null
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(renderLogLines)
        mainHandler.removeCallbacks(drainScreenLogs)
        currentSurface?.let { surface ->
            detachSurface(SCREEN_TYPE_MAIN, surface)
            surface.release()
        }
        currentSurface = null
        currentSurfaceTexture = null
        sessionLog.append("Activity destroyed; background session and log remain active")
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(NO_VIDEO_BACKGROUND)
        }
        contentRoot = root
        val video = TextureView(this).apply {
            isOpaque = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            surfaceTextureListener = textureListener
        }
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        val log = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            text = ""
        }
        val logScroll = object : ScrollView(this) {
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false
            override fun onTouchEvent(event: MotionEvent): Boolean = false
        }.apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            isFillViewport = false
            isFocusable = false
            addView(
                log,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scrollLogsToBottom() }
        }
        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(MENU_ACCENT)
            background = menuRipple(
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(MENU_BACKGROUND)
                    setStroke(maxOf(1, dp(1)), MENU_SEPARATOR)
                },
            )
            setPadding(dp(14), dp(14), dp(14), dp(14))
            contentDescription = "Open settings"
            setOnClickListener { openSettingsMenu() }
        }
        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        )
        statusParams.setMargins(dp(12), 0, dp(12), dp(12))
        val settingsButtonParams = FrameLayout.LayoutParams(
            dp(56),
            dp(56),
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(dp(16), 0, dp(16), dp(16)) }

        val settings = buildSettingsMenu().apply { visibility = View.GONE }
        val editor = buildSafeAreaEditor().apply { visibility = View.GONE }

        val panel = HandshakePanelView(this).apply { visibility = View.GONE }
        val mainFrame = FrameLayout(this).apply { addView(video) }
        mainFrame.addView(
            gestureLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // Keep the connection UI inside the main display, leaving room for settings below.
        mainFrame.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply { setMargins(dp(12), dp(12), dp(12), dp(84)) },
        )
        val cluster = TextureView(this).apply {
            isOpaque = false
            visibility = View.GONE
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    texture.setDefaultBufferSize(clusterWebDisplay.width, clusterWebDisplay.height)
                    clusterPreviewSurface = Surface(texture).also { attachDisplaySurface(SCREEN_TYPE_ALT, it) }
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    clusterPreviewSurface?.let { detachSurface(SCREEN_TYPE_ALT, it); it.release() }
                    clusterPreviewSurface = null
                    return true
                }
            }
        }
        clusterPreview = cluster
        val displays = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(cluster, LinearLayout.LayoutParams(1, 1).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(mainFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(displays, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(logScroll, statusParams)
        root.addView(settingsButton, settingsButtonParams)
        root.addView(
            settings,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updateMediaMetricsOverlayLayout(right - left, bottom - top)
                updateWebDisplayPreviews()
            }
        }
        videoView = video
        gestureOverlay = gestureLayer
        disconnectedSettingsButton = settingsButton
        settingsMenu = settings
        safeAreaEditor = editor
        statusView = log
        statusScrollView = logScroll
        handshakeTimeline = HandshakeTimeline(wirelessEnabled, System.currentTimeMillis())
        handshakePanel = panel.also { it.bind(handshakeTimeline) }
        updateWebDisplayPreviews()
        updateDebugOverlays()
        return root
    }

    private fun buildSettingsMenu(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val panel = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(MENU_CORNER_DP).toFloat()
                setColor(MENU_BACKGROUND)
            }
            clipToOutline = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(36), dp(48), dp(36))
        }
        content.addView(
            menuText("CarPlay settings", 32f, MENU_LABEL, bold = true).apply {
                setPadding(dp(56), 0, 0, 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            settingsCategoryHeader("Connection"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(32) },
        )

        content.addView(
            buildMfiTargetSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        val wirelessRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        wirelessRow.addView(
            menuText("Wireless CarPlay", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val wirelessSwitch = Switch(this).apply {
            isChecked = wirelessEnabled
            contentDescription = "Wireless CarPlay transport"
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
            )
            setOnCheckedChangeListener { _, checked ->
                if (wirelessEnabled == checked) return@setOnCheckedChangeListener
                wirelessEnabled = checked
                hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
                updateHotspotStatusBlock()
                appendLog(
                    "Wireless CarPlay ${if (wirelessEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                requestStartupPrerequisites()
            }
        }
        wirelessRow.addView(
            wirelessSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            wirelessRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            buildHotspotModeSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            menuText("Hotspot status", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) },
        )
        val hotspotStatusView = menuText("", 16f, MENU_ACCENT)
        content.addView(
            hotspotStatusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        content.addView(
            settingsCategoryHeader("Location"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            buildLocationReportingSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(
            settingsCategoryHeader("Startup"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            settingsSwitchRow(
                label = "Auto-start on boot",
                checked = autoStartOnBoot,
                description = "Start CarPlay automatically after device boot",
            ) { checked ->
                autoStartOnBoot = checked
                appendLog("Boot auto-start ${if (checked) "enabled" else "disabled"}")
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(
            settingsCategoryHeader("Audio"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            buildStepSliderSection(
                title = "Main media audio buffer",
                values = (
                    MainMediaAudioBuffer.MIN_DURATION_MS..MainMediaAudioBuffer.MAX_DURATION_MS
                    step MainMediaAudioBuffer.STEP_DURATION_MS
                    ).toList(),
                selectedValue = mainMediaAudioBufferDurationMs,
                label = ::mainMediaAudioBufferLabel,
                onControlsCreated = { valueView, seekBar ->
                    mainMediaAudioBufferValueView = valueView
                    mainMediaAudioBufferSeekBar = seekBar
                },
                onValueChanged = { value ->
                    mainMediaAudioBufferDurationMs = value
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            buildMicrophoneGainSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) },
        )
        if (advancedAudioChannelMappingSupported) {
            content.addView(
                settingsSwitchRow(
                    label = "Advanced audio channel mapping",
                    checked = advancedAudioChannelMapping,
                    description = "Route AAOS audio buses by CarPlay audio type",
                ) { checked ->
                    advancedAudioChannelMapping = checked
                    appendLog(
                        "Advanced audio channel mapping ${if (checked) "enabled" else "disabled"}; " +
                            "applies when settings close",
                    )
                    updateResolutionMenu()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(20) },
            )
        }

        content.addView(
            settingsCategoryHeader("Identity & appearance"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            buildIdentitySettingsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            buildAirPlayIconSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content.addView(
            buildDrivingSideSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content.addView(
            settingsCategoryHeader("Display & video"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )

        val resolutionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        resolutionHeader.addView(
            menuText("Resolution", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val resolutionValue = menuText(
            CarPlayDisplayScale.label(displayScaleTenths),
            28f,
            MENU_ACCENT,
            bold = true,
        )
        resolutionHeader.addView(
            resolutionValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            resolutionHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        val seekBar = SeekBar(this).apply {
            max = CarPlayDisplayScale.MAX_TENTHS - CarPlayDisplayScale.MIN_TENTHS
            progress = displayScaleTenths - CarPlayDisplayScale.MIN_TENTHS
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        displayScaleTenths = CarPlayDisplayScale.sanitize(
                            CarPlayDisplayScale.MIN_TENTHS + progress,
                        )
                        updateResolutionMenu()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        content.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        val range = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        range.addView(
            menuText("0.3x", 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        range.addView(
            menuText("1.0x", 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            range,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        content.addView(
            buildStepSliderSection(
                title = "Frame rate",
                values = (
                    AirPlayDisplaySettings.MIN_FPS..AirPlayDisplaySettings.MAX_FPS
                    step AirPlayDisplaySettings.FPS_STEP
                    ).toList(),
                selectedValue = fps,
                label = { "$it fps" },
                onValueChanged = { value ->
                    fps = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        content.addView(
            settingsChoiceRow(
                label = "Physical size basis",
                options = listOf(
                    AirPlayPhysicalSizeBasis.WIDTH to "Widest width",
                    AirPlayPhysicalSizeBasis.HEIGHT to "Longest height",
                ),
                selected = physicalSizeBasis,
            ) { value ->
                physicalSizeBasis = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        content.addView(
            buildStepSliderSection(
                title = "Physical length",
                values = (
                    AirPlayDisplaySettings.MIN_WIDTH_PHYSICAL_MM..
                        AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM
                    step AirPlayDisplaySettings.WIDTH_PHYSICAL_MM_STEP
                    ).toList(),
                selectedValue = widthPhysicalMm,
                label = { "$it mm" },
                onValueChanged = { value ->
                    widthPhysicalMm = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

        content.addView(
            settingsCategoryHeader("Web displays"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(32) },
        )
        for (type in listOf(SCREEN_TYPE_MAIN, SCREEN_TYPE_ALT)) {
            content.addView(buildWebDisplaySection(type),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(16) })
        }
        content.addView(
            menuText(
                "Web displays use HTTPS with a self-signed certificate so browsers can decode with WebCodecs " +
                    "(H.265 included). Each browser warns once: choose to continue.",
                13f,
                MENU_SECONDARY,
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16) })

        val hevcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hevcRow.addView(
            menuText("HEVC (H.265)", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val hevcSwitch = Switch(this).apply {
            isChecked = hevcEnabled
            contentDescription = "HEVC H.265 video transport"
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
            )
            setOnCheckedChangeListener { _, checked ->
                if (hevcEnabled == checked) return@setOnCheckedChangeListener
                hevcEnabled = checked
                appendLog(
                    "HEVC (H.265) ${if (hevcEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                updateResolutionMenu()
            }
        }
        hevcRow.addView(
            hevcSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            hevcRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        val softwareHevcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        softwareHevcRow.addView(
            menuText("HEVC software decoder", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val softwareHevcSwitch = Switch(this).apply {
            isChecked = hevcSoftwareDecoderEnabled
            contentDescription = "Use software HEVC decoder"
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
            )
            setOnCheckedChangeListener { _, checked ->
                if (hevcSoftwareDecoderEnabled == checked) return@setOnCheckedChangeListener
                hevcSoftwareDecoderEnabled = checked
                appendLog(
                    "HEVC software decoder ${if (hevcSoftwareDecoderEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                updateResolutionMenu()
            }
        }
        softwareHevcRow.addView(
            softwareHevcSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            content.addView(
                softwareHevcRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(16) },
            )
        }

        content.addView(
            buildSafeAreaSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            settingsCategoryHeader("Window"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )
        content.addView(
            buildFullscreenSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            settingsSwitchRow(
                label = "More gestures to Settings page",
                checked = moreGesturesToSettings,
                description = "Enable a one-finger swipe down along the left edge to open settings",
            ) { checked -> moreGesturesToSettings = checked },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(
            settingsCategoryHeader("Diagnostics"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )
        content.addView(
            buildMediaMetricsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            buildAudioPacketCaptureSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) },
        )
        content.addView(
            buildDebugLogsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) },
        )
        content.addView(
            buildVideoFrameLogsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            Button(this).apply {
                text = "Open system Bluetooth settings"
                applyMenuButtonStyle(MENU_BUTTON_PRIMARY)
                textSize = 17f
                minHeight = dp(52)
                setOnClickListener { openSystemBluetoothSettings() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            content.addView(
                settingsCategoryHeader("Android 9 compatibility"),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(40) },
            )
            content.addView(
                menuText(
                    "The following settings are unavailable and hidden on Android 9 " +
                        "(API 28):\n" +
                        "• Wi-Fi P2P (5 GHz) — LocalOnlyHotspot is used instead.\n" +
                        "• HEVC software decoder — hardware decoding is used instead.",
                    16f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }

        val preview = menuText("", 17f, MENU_SECONDARY)
        content.addView(
            preview,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        val save = Button(this).apply {
            text = "Save and reconnect"
            applyMenuButtonStyle(MENU_BUTTON_PRIMARY)
            textSize = 17f
            minHeight = dp(52)
            setOnClickListener { saveSettingsAndReconnect() }
        }
        content.addView(
            save,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(46) },
        )

        val exitApplicationButton = Button(this).apply {
            text = "EXIT APPLICATION"
            applyMenuButtonStyle(MENU_DANGER_FILL, MENU_DANGER)
            textSize = 17f
            minHeight = dp(52)
            setOnClickListener { exitApplication() }
        }
        content.addView(
            exitApplicationButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        panel.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        panel.addView(
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_hs_close)
                imageTintList = ColorStateList.valueOf(MENU_LABEL)
                background = menuRipple(
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(MENU_BUTTON)
                    },
                )
                contentDescription = "Discard changes and exit settings"
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { cancelSettingsEdits() }
            },
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(16)
                topMargin = dp(16)
            },
        )
        val panelInset = dp(12)
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                minOf(resources.displayMetrics.widthPixels - 2 * panelInset, MAX_SETTINGS_MENU_WIDTH_PX),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ).apply { setMargins(0, panelInset, 0, panelInset) },
        )
        overlay.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val desiredWidth = minOf(view.width - 2 * panelInset, MAX_SETTINGS_MENU_WIDTH_PX)
            val params = panel.layoutParams
            if (params.width != desiredWidth) {
                params.width = desiredWidth
                panel.layoutParams = params
            }
        }

        resolutionValueView = resolutionValue
        resolutionPreviewView = preview
        this.hotspotStatusView = hotspotStatusView
        updateHotspotStatusBlock()
        updateResolutionMenu()
        return overlay
    }

    private fun persistMenuSettings() {
        AirPlayPersistence.saveWebDisplay(this, SCREEN_TYPE_MAIN, mainWebDisplay)
        AirPlayPersistence.saveWebDisplay(this, SCREEN_TYPE_ALT, clusterWebDisplay)
        AirPlayPersistence.saveWirelessEnabled(this, wirelessEnabled)
        AirPlayPersistence.saveMfiTarget(this, mfiTarget)
        AirPlayPersistence.saveMfiI2cPath(this, mfiI2cPath)
        AirPlayPersistence.saveRemoteMfiServer(this, remoteMfiServer)
        AirPlayPersistence.saveRemoteMfiToken(this, remoteMfiToken)
        AirPlayPersistence.saveLocalMfiCertificateUri(this, localMfiCertificateUri)
        AirPlayPersistence.saveLocalMfiPrivateKeyUri(this, localMfiPrivateKeyUri)
        AirPlayPersistence.saveWirelessHotspotMode(this, wirelessHotspotMode)
        AirPlayPersistence.saveManualHotspotSsid(this, manualHotspotSsid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, manualHotspotPassphrase)
        AirPlayPersistence.saveManualHotspotBand(this, manualHotspotBand)
        AirPlayPersistence.saveManualHotspotChannel(this, manualHotspotChannel)
        AirPlayPersistence.saveManualHotspotSecurity(this, manualHotspotSecurity)
        AirPlayPersistence.saveLocationReportingEnabled(this, locationReportingEnabled)
        AirPlayPersistence.saveAutoStartOnBoot(this, autoStartOnBoot)
        AirPlayPersistence.saveAdvancedAudioChannelMapping(this, advancedAudioChannelMapping)
        AirPlayPersistence.saveMainMediaAudioBufferDurationMs(
            this,
            mainMediaAudioBufferDurationMs,
        )
        AirPlayPersistence.saveMicrophoneGainPercent(this, microphoneGainPercent)
        AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
        AirPlayPersistence.saveFps(this, fps)
        AirPlayPersistence.saveWidthPhysicalMm(this, widthPhysicalMm)
        AirPlayPersistence.savePhysicalSizeBasis(this, physicalSizeBasis)
        AirPlayPersistence.saveHevcEnabled(this, hevcEnabled)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(this, hevcSoftwareDecoderEnabled)
        AirPlayPersistence.saveManufacturer(this, manufacturer)
        AirPlayPersistence.saveModel(this, model)
        AirPlayPersistence.saveOemLabel(this, oemLabel)
        AirPlayPersistence.saveDebugLogsEnabled(this, debugLogsEnabled)
        AirPlayPersistence.saveVideoFrameLogsEnabled(this, videoFrameLogsEnabled)
        AirPlayPersistence.saveMediaMetricsEnabled(this, mediaMetricsEnabled)
        AirPlayPersistence.saveAudioPacketCaptureEnabled(this, audioPacketCaptureEnabled)
        AirPlayPersistence.saveMoreGesturesToSettings(this, moreGesturesToSettings)
        AirPlayPersistence.saveRightHandDrive(this, rightHandDrive)
        AirPlayPersistence.saveHideTopBar(this, hideTopBar)
        AirPlayPersistence.saveHideBottomBar(this, hideBottomBar)
        AirPlayPersistence.saveSafeAreaDrawOutside(this, safeAreaDrawOutside)
    }

    private fun captureSettingsBaseline(): SettingsBaseline {
        val safeAreaSize = currentActivitySize()
        val customIconBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the current AirPlay icon for settings rollback", error)
            null
        }
        return SettingsBaseline(
            safeAreaSize = safeAreaSize,
            safeAreaRect = safeAreaSize?.let {
                AirPlayPersistence.loadSafeAreaRect(this, it.width, it.height)
            },
            customIconBytes = customIconBytes,
        )
    }

    private fun restoreSettingsBaseline() {
        val baseline = settingsBaseline ?: return
        loadPersistedSettings()
        webDisplayRefresh.forEach { it() }
        videoFrameLogSwitch?.isChecked = videoFrameLogsEnabled
        updateWebDisplayPreviews()
        baseline.safeAreaSize?.let { size ->
            baseline.safeAreaRect?.let { rect ->
                AirPlayPersistence.saveSafeAreaRect(
                    this,
                    size.width,
                    size.height,
                    rect,
                    commit = true,
                )
            } ?: AirPlayPersistence.clearSafeAreaRect(
                this,
                size.width,
                size.height,
                commit = true,
            )
        }
        try {
            baseline.customIconBytes?.let { bytes ->
                AirPlayPersistence.saveCustomAirPlayIcon(this, bytes)
            } ?: AirPlayPersistence.clearCustomAirPlayIcon(this)
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore the previous AirPlay icon", error)
        }
        settingsBaseline = null
        locationPermissionAvailable = hasFineLocationPermission()
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
        syncMfiSettingsControls()
        syncMainMediaAudioBufferControls()
        syncMicrophoneGainControls()
        updateManualHotspotFields()
        updateAirPlayIconPreview()
        updateSafeAreaSummary()
        updateHotspotStatusBlock()
        updateResolutionMenu()
        updateDebugOverlays()
        applyFullscreenMode()
        refreshDisplaySizeAfterLayout()
    }

    private fun webDisplayConfig(type: Int) = if (type == SCREEN_TYPE_MAIN) mainWebDisplay else clusterWebDisplay

    private fun setWebDisplayConfig(type: Int, config: CarPlayWebDisplayConfig) {
        if (type == SCREEN_TYPE_MAIN) mainWebDisplay = config else clusterWebDisplay = config
        updateWebDisplayPreviews()
        updateResolutionMenu()
    }

    private fun buildWebDisplaySection(type: Int): View {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val enabled = settingsSwitchRow(
            label = if (type == SCREEN_TYPE_MAIN) "Main display" else "Instrument display",
            checked = webDisplayConfig(type).enabled,
            description = "Open this display in a browser on the same network",
        ) { checked ->
            setWebDisplayConfig(type, webDisplayConfig(type).copy(enabled = checked))
            details.visibility = if (checked) View.VISIBLE else View.GONE
        }
        section.addView(enabled)
        val resolution = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun dimension(value: Int, hint: String) = EditText(this).apply {
            setText(value.toString()); this.hint = hint; contentDescription = hint
            inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(); setTextColor(MENU_LABEL)
            setHintTextColor(MENU_SECONDARY); backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
        }
        val width = dimension(webDisplayConfig(type).width, "Width")
        val height = dimension(webDisplayConfig(type).height, "Height")
        webDisplayInputs[type] = width to height
        resolution.addView(width, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        resolution.addView(menuText(" × ", 18f, MENU_SECONDARY))
        resolution.addView(height, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        details.addView(menuText("Resolution", 15f, MENU_SECONDARY))
        details.addView(resolution)
        val preview = settingsSwitchRow("Local preview", webDisplayConfig(type).preview,
            "Fit the preview to the configured resolution") { checked ->
            setWebDisplayConfig(type, webDisplayConfig(type).copy(preview = checked))
        }
        details.addView(preview)
        val address = menuText("", 13f, MENU_ACCENT).apply { setTextIsSelectable(true) }
        webDisplayAddressViews[type] = address
        details.addView(address)
        details.addView(menuText("Apply with Save & Reconnect", 13f, MENU_SECONDARY))
        section.addView(details)
        webDisplayRefresh += {
            val config = webDisplayConfig(type)
            ((enabled as ViewGroup).getChildAt(1) as Switch).isChecked = config.enabled
            ((preview as ViewGroup).getChildAt(1) as Switch).isChecked = config.preview
            width.setText(config.width.toString()); height.setText(config.height.toString())
            width.error = null; height.error = null
            details.visibility = if (config.enabled) View.VISIBLE else View.GONE
            updateWebDisplayAddresses()
        }
        webDisplayRefresh.last().invoke()
        return section
    }

    private fun updateWebDisplayAddresses() {
        for ((type, view) in webDisplayAddressViews) {
            view.text = CarPlayWebDisplayFactory.urls(type, AirPlayPersistence.webDisplayKey(this))
                .joinToString("\n").ifEmpty { "Connect to a local network to get the display address" }
        }
    }

    private fun validateWebDisplaySettings(): Boolean {
        for ((type, inputs) in webDisplayInputs) {
            val config = webDisplayConfig(type)
            if (!config.enabled) continue
            val updated = runCatching { config.copy(width = inputs.first.text.toString().toInt(), height = inputs.second.text.toString().toInt()) }
                .getOrElse {
                    inputs.first.error = "Use even dimensions: 320–3840 × 240–2160"
                    inputs.second.error = inputs.first.error
                    inputs.first.requestFocus()
                    return false
                }
            setWebDisplayConfig(type, updated)
        }
        return true
    }

    private fun buildMfiTargetSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val targetChoice = settingsChoiceRow(
            label = "MFI certificate & signing target",
            options = listOf(
                MfiTarget.USB_CH341 to "USB/CH341",
                MfiTarget.I2C to "I2C",
                MfiTarget.REMOTE to "Remote",
                MfiTarget.LOCAL_FILES to "Local files",
            ),
            selected = mfiTarget,
        ) { target ->
            if (mfiTarget == target) return@settingsChoiceRow
            mfiTarget = target
            updateMfiTargetFields()
            appendLog("MFI target: ${mfiTargetLabel(target)}; applies when settings close")
        }
        mfiTargetGroup = (targetChoice as ViewGroup).getChildAt(1) as RadioGroup
        section.addView(
            targetChoice,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val i2cFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    "I2C device",
                    mfiI2cPath,
                    onInputCreated = { mfiI2cPathInput = it },
                ) { value ->
                    mfiI2cPath = value
                    mfiErrorView?.visibility = View.GONE
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText("Linux device path, for example /dev/i2c-1.", 14f, MENU_SECONDARY),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            i2cFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiI2cFields = i2cFields

        val remoteFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    "Server address",
                    remoteMfiServer,
                    onInputCreated = { remoteMfiServerInput = it },
                ) { value ->
                    remoteMfiServer = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                settingsInputRow(
                    "Token (optional)",
                    remoteMfiToken,
                    password = true,
                    onInputCreated = { remoteMfiTokenInput = it },
                ) { value ->
                    remoteMfiToken = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            addView(
                menuText(
                    "Address must start with http:// or https://. Token is optional and sent " +
                        "as an Authorization bearer token.",
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            remoteFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiRemoteFields = remoteFields

        val localFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                localMfiDocumentRow(
                    "Certificate (.p7b)",
                    localMfiCertificateUri,
                    onDocumentViewCreated = { localMfiCertificateDocumentView = it },
                ) {
                    externalActivityInProgress = true
                    localMfiCertificatePicker.launch(arrayOf("*/*"))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                localMfiDocumentRow(
                    "Private key (.pk8)",
                    localMfiPrivateKeyUri,
                    onDocumentViewCreated = { localMfiPrivateKeyDocumentView = it },
                ) {
                    externalActivityInProgress = true
                    localMfiPrivateKeyPicker.launch(arrayOf("*/*"))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            addView(
                menuText(
                    "Select a DER PKCS#7 certificate and its matching unencrypted DER PKCS#8 " +
                        "private key. Documents are reloaded when MFI reconnects.",
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            localFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiLocalFields = localFields
        val error = menuText("", 14f, MENU_DANGER).apply {
            visibility = View.GONE
        }
        section.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        mfiErrorView = error
        updateMfiTargetFields()
        return section
    }

    private fun updateMfiTargetFields() {
        mfiI2cFields?.visibility = if (mfiTarget == MfiTarget.I2C) View.VISIBLE else View.GONE
        mfiRemoteFields?.visibility = if (mfiTarget == MfiTarget.REMOTE) View.VISIBLE else View.GONE
        mfiLocalFields?.visibility = if (mfiTarget == MfiTarget.LOCAL_FILES) View.VISIBLE else View.GONE
        mfiErrorView?.visibility = View.GONE
    }

    private fun localMfiDocumentRow(
        label: String,
        uri: String,
        onDocumentViewCreated: (TextView) -> Unit,
        onChoose: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            menuText(label, 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(this@CarPlayHostActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val documentView = menuText(localMfiDocumentLabel(uri), 14f, MENU_SECONDARY)
        onDocumentViewCreated(documentView)
        row.addView(
            documentView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            Button(this@CarPlayHostActivity).apply {
                text = "Choose"
                applyMenuButtonStyle()
                contentDescription = "Choose $label"
                setOnClickListener { onChoose() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(12) },
        )
        addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) },
        )
    }

    private fun updateLocalMfiDocumentViews() {
        localMfiCertificateDocumentView?.text = localMfiDocumentLabel(localMfiCertificateUri)
        localMfiPrivateKeyDocumentView?.text = localMfiDocumentLabel(localMfiPrivateKeyUri)
    }

    private fun localMfiDocumentLabel(value: String): String {
        if (value.isEmpty()) return "Not selected"
        val uri = try {
            Uri.parse(value)
        } catch (_: Exception) {
            return "Selection unavailable"
        }
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column) else null
            } ?: uri.lastPathSegment ?: "Selected document"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "Selected document"
        }
    }

    private fun retainDocumentReadPermission(uri: Uri, label: String): Boolean = try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (failure: SecurityException) {
        Log.e(TAG, "Could not retain local MFi $label document permission", failure)
        Toast.makeText(
            this,
            "Could not keep access to the selected $label",
            Toast.LENGTH_LONG,
        ).show()
        false
    }

    private fun syncMfiSettingsControls() {
        mfiTargetGroup?.let { group ->
            val button = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filterIsInstance<RadioButton>()
                .firstOrNull { it.tag == mfiTarget }
            button?.let { group.check(it.id) }
        }
        if (mfiI2cPathInput?.text?.toString() != mfiI2cPath) {
            mfiI2cPathInput?.setText(mfiI2cPath)
        }
        if (remoteMfiServerInput?.text?.toString() != remoteMfiServer) {
            remoteMfiServerInput?.setText(remoteMfiServer)
        }
        if (remoteMfiTokenInput?.text?.toString() != remoteMfiToken) {
            remoteMfiTokenInput?.setText(remoteMfiToken)
        }
        updateLocalMfiDocumentViews()
        updateMfiTargetFields()
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.USB_CH341 -> "USB/CH341"
        MfiTarget.I2C -> "I2C"
        MfiTarget.REMOTE -> "Remote"
        MfiTarget.LOCAL_FILES -> "Local files"
    }

    private fun buildIdentitySettingsSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            settingsInputRow("Manufacturer", manufacturer) { value ->
                manufacturer = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            settingsInputRow("Model", model) { value ->
                model = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsInputRow("OEM label", oemLabel) { value ->
                oemLabel = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    private fun settingsCategoryHeader(title: String): TextView =
        menuText(title, 16f, MENU_ACCENT, bold = true)

    private fun buildLocationReportingSection(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val row = LinearLayout(this@CarPlayHostActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                menuText("Report location to iPhone", 20f, MENU_SECONDARY),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val switch = Switch(this@CarPlayHostActivity).apply {
                isChecked = locationReportingEnabled
                contentDescription = "Report Android location to the iPhone"
                showText = false
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
                )
                setOnCheckedChangeListener { _, checked ->
                    onLocationReportingChanged(checked)
                }
            }
            locationReportingSwitch = switch
            row.addView(
                switch,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(
                    "Sends precise Android location as CarPlay GPS data when the iPhone requests it.",
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
        }

    private fun onLocationReportingChanged(checked: Boolean) {
        if (locationReportingEnabled == checked) return
        locationReportingEnabled = checked
        appendLog(
            "Location reporting ${if (locationReportingEnabled) "enabled" else "disabled"}; " +
                "applies when settings close",
        )
        updateResolutionMenu()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
    }

    private fun buildDebugLogsSection(): View =
        settingsSwitchRow(
            label = "Debug logs",
            checked = debugLogsEnabled,
            description = "Show on-screen debug logs",
        ) { checked ->
            debugLogsEnabled = checked
            if (!checked) clearScreenLogs()
            appendLog("Debug logs ${if (debugLogsEnabled) "enabled" else "disabled"}")
            updateDebugOverlays()
        }

    private fun buildVideoFrameLogsSection(): View =
        settingsSwitchRow(
            label = "Raw video in saved log",
            checked = videoFrameLogsEnabled,
            description = "Include original video packets in the saved log",
            onSwitchCreated = { videoFrameLogSwitch = it },
        ) { checked ->
            videoFrameLogsEnabled = checked
            sessionLog.videoFramesEnabled = checked
            appendLog("Raw video file logging ${if (checked) "enabled" else "disabled"}")
        }

    private fun buildMediaMetricsSection(): View =
        settingsSwitchRow(
            label = "Media latency monitor",
            checked = mediaMetricsEnabled,
            description = "Show live video latency, frame rate, and audio latency",
        ) { checked ->
            mediaMetricsEnabled = checked
            appendLog("Media latency monitor ${if (checked) "enabled" else "disabled"}")
            updateMediaMetricsOverlay()
        }

    private fun buildAudioPacketCaptureSection(): View =
        settingsSwitchRow(
            label = "Audio diagnostic capture",
            checked = audioPacketCaptureEnabled,
            description = "Capture encrypted UDP and decrypted RTP audio packets for diagnostics",
        ) { checked ->
            audioPacketCaptureEnabled = checked
            appendLog(
                "Audio diagnostic capture ${if (checked) "enabled" else "disabled"}; " +
                    "applies when settings close",
            )
        }

    private fun buildMicrophoneGainSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText("Microphone gain (0.8x–2.0x)", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val gainValue = menuText(
            microphoneGainLabel(microphoneGainPercent),
            22f,
            MENU_ACCENT,
            bold = true,
        )
        microphoneGainValueView = gainValue
        header.addView(
            gainValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val gainSlider = SeekBar(this).apply {
            max = (MicrophoneGain.MAX_PERCENT - MicrophoneGain.MIN_PERCENT) /
                MicrophoneGain.STEP_PERCENT
            progress = (microphoneGainPercent - MicrophoneGain.MIN_PERCENT) /
                MicrophoneGain.STEP_PERCENT
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
            contentDescription = "Microphone gain"
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        val percent = MicrophoneGain.sanitize(
                            MicrophoneGain.MIN_PERCENT +
                                progress * MicrophoneGain.STEP_PERCENT,
                        )
                        microphoneGainPercent = percent
                        microphoneGainValueView?.text = microphoneGainLabel(percent)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        microphoneGainSeekBar = gainSlider
        controls.addView(
            gainSlider,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val testButton = Button(this).apply {
            text = "Test"
            applyMenuButtonStyle(MENU_BUTTON_PRIMARY)
            textSize = 16f
            minWidth = dp(78)
            contentDescription = "Test microphone level"
            setOnClickListener {
                if (microphoneLevelMonitor?.isRunning == true) {
                    stopMicrophoneGainTest()
                } else {
                    startMicrophoneGainTest()
                }
            }
        }
        microphoneTestButton = testButton
        controls.addView(
            testButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(12) },
        )
        section.addView(
            controls,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        val levelHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        levelHeader.addView(
            menuText("Peak level", 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val levelValue = menuText("0%", 15f, MENU_SECONDARY)
        microphoneLevelValueView = levelValue
        levelHeader.addView(
            levelValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            levelHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        val levelBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(MENU_TRACK_OFF)
            contentDescription = "Microphone peak level"
        }
        microphoneLevelBar = levelBar
        section.addView(
            levelBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(14),
            ).apply { topMargin = dp(4) },
        )
        return section
    }

    private fun mainMediaAudioBufferLabel(durationMs: Int): String =
        String.format(
            Locale.US,
            "%d ms",
            MainMediaAudioBuffer.sanitizeDurationMs(durationMs),
        )

    private fun microphoneGainLabel(percent: Int): String =
        String.format(Locale.US, "%.1fx", MicrophoneGain.sanitize(percent) / 100.0)

    private fun syncMainMediaAudioBufferControls() {
        val durationMs = MainMediaAudioBuffer.sanitizeDurationMs(mainMediaAudioBufferDurationMs)
        mainMediaAudioBufferDurationMs = durationMs
        mainMediaAudioBufferValueView?.text = mainMediaAudioBufferLabel(durationMs)
        mainMediaAudioBufferSeekBar?.progress =
            (durationMs - MainMediaAudioBuffer.MIN_DURATION_MS) /
                MainMediaAudioBuffer.STEP_DURATION_MS
    }

    private fun syncMicrophoneGainControls() {
        microphoneGainValueView?.text = microphoneGainLabel(microphoneGainPercent)
        microphoneGainSeekBar?.progress =
            (microphoneGainPercent - MicrophoneGain.MIN_PERCENT) /
                MicrophoneGain.STEP_PERCENT
    }

    private fun startMicrophoneGainTest() {
        if (!menuOpen || handshakeResetInProgress || microphoneLevelMonitor != null) return
        if (!microphoneAvailable) {
            microphoneGainTestAfterPermission = true
            microphoneLevelValueView?.text = "Permission required"
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        lateinit var monitor: MicrophoneLevelMonitor
        monitor = MicrophoneLevelMonitor(
            context = applicationContext,
            gainPercent = { microphoneGainPercent },
            onPeakPercent = { peak ->
                runOnUiThread {
                    if (microphoneLevelMonitor !== monitor || !menuOpen || isDestroyed) {
                        return@runOnUiThread
                    }
                    microphoneLevelBar?.progress = peak
                    microphoneLevelValueView?.text = "$peak%"
                }
            },
            onStopped = { error ->
                runOnUiThread {
                    if (microphoneLevelMonitor !== monitor) return@runOnUiThread
                    microphoneLevelMonitor = null
                    microphoneTestButton?.text = "Test"
                    microphoneTestButton?.isEnabled = menuOpen && !handshakeResetInProgress
                    if (error != null) {
                        Log.e(TAG, "microphone gain test failed", error)
                        microphoneLevelBar?.progress = 0
                        microphoneLevelValueView?.text =
                            error.message ?: "Test failed"
                    }
                }
            },
        )
        microphoneLevelMonitor = monitor
        microphoneTestButton?.text = "Stop"
        microphoneLevelBar?.progress = 0
        microphoneLevelValueView?.text = "Listening…"
        if (!monitor.start()) {
            microphoneLevelMonitor = null
            microphoneTestButton?.text = "Test"
        }
    }

    private fun stopMicrophoneGainTest() {
        microphoneGainTestAfterPermission = false
        val monitor = microphoneLevelMonitor
        microphoneLevelMonitor = null
        monitor?.close()
        microphoneTestButton?.text = "Test"
        microphoneLevelBar?.progress = 0
        microphoneLevelValueView?.text = "0%"
    }

    private fun openSystemBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (error: ActivityNotFoundException) {
            appendLog("System Bluetooth settings are unavailable: ${error.message}")
            Toast.makeText(this, "System Bluetooth settings are unavailable", Toast.LENGTH_LONG).show()
        } catch (error: SecurityException) {
            appendLog("Cannot open system Bluetooth settings: ${error.message}")
            Toast.makeText(this, "Cannot open system Bluetooth settings", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildStepSliderSection(
        title: String,
        values: List<Int>,
        selectedValue: Int,
        label: (Int) -> String,
        onControlsCreated: ((TextView, SeekBar) -> Unit)? = null,
        onValueChanged: (Int) -> Unit,
    ): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText(title, 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val selectedIndex = values.indexOf(selectedValue)
            .takeIf { it >= 0 }
            ?: 0
        val valueView = menuText(label(values[selectedIndex]), 22f, MENU_ACCENT, bold = true)
        header.addView(
            valueView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val seekBar = SeekBar(this).apply {
            max = (values.size - 1).coerceAtLeast(0)
            progress = selectedIndex
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = values.getOrNull(progress) ?: return
                        valueView.text = label(value)
                        if (fromUser) onValueChanged(value)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        section.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        onControlsCreated?.invoke(valueView, seekBar)
        return section
    }

    private fun buildAirPlayIconSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText("AirPlay icon", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(MENU_TRACK_OFF)
            }
        }
        row.addView(
            preview,
            LinearLayout.LayoutParams(dp(72), dp(72)),
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        actions.addView(
            Button(this).apply {
                text = "Choose image"
                applyMenuButtonStyle()
                setOnClickListener {
                    externalActivityInProgress = true
                    imagePicker.launch("image/*")
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        actions.addView(
            Button(this).apply {
                text = "Default icon"
                applyMenuButtonStyle()
                setOnClickListener {
                    AirPlayPersistence.clearCustomAirPlayIcon(this@CarPlayHostActivity)
                    updateAirPlayIconPreview()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        row.addView(
            actions,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(16) },
        )
        section.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        val status = menuText("", 14f, MENU_SECONDARY)
        section.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        iconPreviewView = preview
        iconStatusView = status
        updateAirPlayIconPreview()
        return section
    }

    private fun buildDrivingSideSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText("Driving side", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val left = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Left-hand drive"
            setTextColor(MENU_SECONDARY)
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            isChecked = !rightHandDrive
        }
        val right = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Right-hand drive"
            setTextColor(MENU_SECONDARY)
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            isChecked = rightHandDrive
        }
        group.addView(left)
        group.addView(right)
        group.setOnCheckedChangeListener { _, checkedId ->
            rightHandDrive = checkedId == right.id
            updateResolutionMenu()
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildFullscreenSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText("Fullscreen", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            settingsSwitchRow(
                label = "Hide top bar",
                checked = hideTopBar,
                description = "Hide the status bar",
            ) { checked ->
                hideTopBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = "Hide bottom bar",
                checked = hideBottomBar,
                description = "Hide the navigation bar",
            ) { checked ->
                hideBottomBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    private fun buildSafeAreaSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText("Safe area", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val summary = menuText("", 15f, MENU_ACCENT)
        section.addView(
            summary,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(
            Button(this).apply {
                text = "Set"
                applyMenuButtonStyle()
                setOnClickListener { openSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        buttons.addView(
            Button(this).apply {
                text = "Reset"
                applyMenuButtonStyle()
                setOnClickListener { resetSafeAreaForCurrentSize() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        section.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = "Draw outside safe area",
                checked = safeAreaDrawOutside,
                description = "Allow CarPlay UI outside the safe area",
            ) { checked ->
                safeAreaDrawOutside = checked
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        safeAreaSummaryView = summary
        updateSafeAreaSummary()
        return section
    }

    private fun buildSafeAreaEditor(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val editor = SafeAreaEditorView(this)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.addView(
            menuText("Safe area", 24f, MENU_LABEL, bold = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(8))
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        controls.addView(
            Button(this).apply {
                text = "Cancel"
                applyMenuButtonStyle()
                setOnClickListener { closeSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(
            Button(this).apply {
                text = "Save"
                applyMenuButtonStyle(MENU_BUTTON_PRIMARY)
                setOnClickListener { saveSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        overlay.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        safeAreaEditorView = editor
        return overlay
    }

    private fun settingsInputRow(
        label: String,
        value: String,
        password: Boolean = false,
        numeric: Boolean = false,
        onInputCreated: ((EditText) -> Unit)? = null,
        onChanged: (String) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            EditText(this@CarPlayHostActivity).apply {
                setText(value)
                textSize = 18f
                setTextColor(MENU_LABEL)
                setHintTextColor(MENU_SECONDARY)
                backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
                minHeight = dp(48)
                isSingleLine = true
                inputType = when {
                    numeric -> InputType.TYPE_CLASS_NUMBER
                    password -> InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                addTextChangedListener(afterTextChanged(onChanged))
                onInputCreated?.invoke(this)
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(12) },
        )
    }

    private fun settingsSwitchRow(
        label: String,
        checked: Boolean,
        description: String,
        onSwitchCreated: (Switch) -> Unit = {},
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            Switch(this@CarPlayHostActivity).apply {
                isChecked = checked
                contentDescription = description
                showText = false
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
                )
                onSwitchCreated(this)
                setOnCheckedChangeListener { _, value -> onChanged(value) }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun afterTextChanged(onChanged: (String) -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(text: Editable?) {
                onChanged(text?.toString().orEmpty())
            }
        }

    private fun buildHotspotModeSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText("Wi-Fi session", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val group = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val modes = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(WirelessHotspotMode.WIFI_P2P to "Wi-Fi P2P (5 GHz)")
            }
            add(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT to "LocalOnlyHotspot")
            add(WirelessHotspotMode.MANUAL to "Manual hotspot")
        }
        var selectedId = View.NO_ID
        for ((mode, label) in modes) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 18f
                setTextColor(MENU_SECONDARY)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                tag = mode
                isChecked = wirelessHotspotMode == mode
            }
            if (wirelessHotspotMode == mode) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val selected = radioGroup.findViewById<RadioButton>(checkedId)
                ?.tag as? WirelessHotspotMode
                ?: return@setOnCheckedChangeListener
            if (wirelessHotspotMode == selected) return@setOnCheckedChangeListener
            wirelessHotspotMode = selected
            hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
            updateHotspotStatusBlock()
            updateManualHotspotFields()
            appendLog(
                "Wi-Fi session mode: ${hotspotModeLabel(wirelessHotspotMode)}; " +
                    "applies when settings close",
            )
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val manualFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        manualFields.addView(
            settingsInputRow("Hotspot SSID", manualHotspotSsid) { value ->
                manualHotspotSsid = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        manualFields.addView(
            settingsChoiceRow(
                label = "Band",
                options = listOf(
                    ManualHotspotBand.AUTO to "Auto",
                    ManualHotspotBand.GHZ_2_4 to "2.4 GHz",
                    ManualHotspotBand.GHZ_5 to "5 GHz",
                ),
                selected = manualHotspotBand,
            ) { value ->
                manualHotspotBand = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = "Channel (0 = auto)",
                value = manualHotspotChannel.toString(),
                numeric = true,
            ) { value ->
                manualHotspotChannel = value.toIntOrNull() ?: -1
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = "Hotspot password",
                value = manualHotspotPassphrase,
                password = true,
            ) { value ->
                manualHotspotPassphrase = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsChoiceRow(
                label = "Security",
                options = listOf(
                    ManualHotspotSecurity.OPEN to "Open",
                    ManualHotspotSecurity.WPA2 to "WPA2",
                    ManualHotspotSecurity.WPA3_TRANSITION to "WPA3 transition",
                    ManualHotspotSecurity.WPA3 to "WPA3",
                ),
                selected = manualHotspotSecurity,
            ) { value ->
                manualHotspotSecurity = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        val error = menuText("", 14f, MENU_DANGER).apply {
            visibility = View.GONE
        }
        manualFields.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        section.addView(
            manualFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        manualHotspotFields = manualFields
        manualHotspotErrorView = error
        updateManualHotspotFields()
        return section
    }

    private fun updateManualHotspotFields() {
        val visible = wirelessHotspotMode == WirelessHotspotMode.MANUAL
        manualHotspotFields?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) manualHotspotErrorView?.visibility = View.GONE
    }

    private fun validateMfiSettings(): Boolean {
        val error = when {
            mfiTarget == MfiTarget.I2C && mfiI2cPath.isBlank() ->
                "I2C device path is required"
            mfiTarget == MfiTarget.REMOTE && remoteMfiServer.isBlank() ->
                "Remote server address is required"
            mfiTarget == MfiTarget.REMOTE &&
                !remoteMfiServer.trim().startsWith("http://") &&
                !remoteMfiServer.trim().startsWith("https://") ->
                "Remote server address must start with http:// or https://"
            mfiTarget == MfiTarget.LOCAL_FILES && localMfiCertificateUri.isBlank() ->
                "Select a local certificate"
            mfiTarget == MfiTarget.LOCAL_FILES && localMfiPrivateKeyUri.isBlank() ->
                "Select a local private key"
            '\u0000' in mfiI2cPath -> "I2C device path contains U+0000"
            '\u0000' in remoteMfiServer -> "Remote server address contains U+0000"
            '\u0000' in remoteMfiToken -> "Remote token contains U+0000"
            '\u0000' in localMfiCertificateUri -> "Local certificate URI contains U+0000"
            '\u0000' in localMfiPrivateKeyUri -> "Local private key URI contains U+0000"
            else -> null
        }
        mfiErrorView?.text = error.orEmpty()
        mfiErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun validateManualHotspotSettings(): Boolean {
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) return true
        val error = when {
            manualHotspotSsid.isBlank() -> "Hotspot SSID is required"
            manualHotspotSsid.encodeToByteArray().size > 32 ->
                "Hotspot SSID must be at most 32 UTF-8 bytes"
            '\u0000' in manualHotspotSsid -> "Hotspot SSID contains U+0000"
            manualHotspotChannel !in 0..196 -> "Channel must be 0 or 1-196"
            manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel) ->
                "Channel is not valid for the selected band"
            '\u0000' in manualHotspotPassphrase -> "Hotspot password contains U+0000"
            manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.isNotEmpty() ->
                "Password must be empty when security is Open"
            manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.length !in 8..63 ->
                "WPA2/WPA3 password must be 8-63 characters"
            else -> null
        }
        manualHotspotErrorView?.text = error.orEmpty()
        manualHotspotErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> "Wi-Fi P2P (5 GHz)"
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "LocalOnlyHotspot"
        WirelessHotspotMode.MANUAL -> "Manual hotspot"
    }

    private fun menuText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) HandshakePanelView.MEDIUM else Typeface.DEFAULT
        includeFontPadding = false
    }

    private fun menuRipple(shape: GradientDrawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(MENU_RIPPLE), shape, null)

    /** Flat rounded button in the connection panel's palette. */
    private fun Button.applyMenuButtonStyle(fill: Int = MENU_BUTTON, textColor: Int = MENU_LABEL) {
        isAllCaps = false
        typeface = HandshakePanelView.MEDIUM
        setTextColor(textColor)
        stateListAnimator = null
        backgroundTintList = null
        background = menuRipple(
            GradientDrawable().apply {
                cornerRadius = dp(MENU_BUTTON_CORNER_DP).toFloat()
                setColor(fill)
            },
        )
        minHeight = dp(48)
        setPadding(dp(16), 0, dp(16), 0)
    }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = "Starting")
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = "Ready",
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(state = "Waiting for paired iPhone")
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(state = "Connecting Bluetooth")
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(state = "Running")
            CarPlayStatus.WirelessActive ->
                hotspotStatus.copy(state = "Active")
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(state = "Starting AirPlay service")
            is CarPlayStatus.Failed -> hotspotStatus.copy(state = "Error")
            else -> return
        }
        updateHotspotStatusBlock()
    }

    private fun updateHotspotStatusBlock() {
        if (!wirelessEnabled) {
            hotspotStatusView?.text = "Wireless hotspot: off"
            return
        }
        val status = hotspotStatus
        hotspotStatusView?.text = buildString {
            append("Wireless hotspot: ").append(status.state)
            status.ssid?.let { append("\nSSID: ").append(it) }
            status.backend?.let { append("\nBackend: ").append(it) }
            status.band?.let { append("\nBand: ").append(it) }
            status.channel?.let {
                append("\nChannel: ").append(if (it == 0) "Auto" else it.toString())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> settingsChoiceRow(
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        onSelected: (T) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this@CarPlayHostActivity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        var selectedId = View.NO_ID
        for ((value, text) in options) {
            val button = RadioButton(this@CarPlayHostActivity).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 17f
                setTextColor(MENU_SECONDARY)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                tag = value
                isChecked = value == selected
            }
            if (value == selected) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val value = radioGroup.findViewById<RadioButton>(checkedId)?.tag as? T ?: return@setOnCheckedChangeListener
            onSelected(value)
        }
        addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun updateResolutionMenu() {
        resolutionValueView?.text = CarPlayDisplayScale.label(displayScaleTenths)
        val native = activeDisplaySize ?: currentActivitySize()
        val resolution = if (native == null) {
            "Handshake resolution: waiting for display"
        } else {
            val negotiated = mainWebDisplay.override(CarPlayDisplayScale.apply(
                AirPlayDisplayConfig(
                    widthPixels = native.width,
                    heightPixels = native.height,
                    widthPhysicalMm = widthPhysicalMm,
                    fps = fps,
                ),
                displayScaleTenths,
            ))
            "Handshake resolution: ${native.width} x ${native.height} -> " +
                "${negotiated.widthPixels} x ${negotiated.heightPixels}"
        }
        val transport = if (!hevcEnabled) {
            "H.264"
        } else {
            "HEVC (H.265, ${if (hevcSoftwareDecoderEnabled) "software" else "hardware"})"
        }
        val fullscreen = buildString {
            append(if (hideTopBar) "top hidden" else "top shown")
            append(", ")
            append(if (hideBottomBar) "bottom hidden" else "bottom shown")
        }
        resolutionPreviewView?.text = buildString {
            append(resolution).append('\n')
            append("Identity: ").append(normalizedManufacturer()).append(" / ")
                .append(normalizedModel()).append('\n')
            append("OEM label: ").append(oemLabel.ifBlank { "(empty)" }).append('\n')
            append("Frame rate: ").append(fps).append(" fps\n")
            append("Detected maximum: ")
                .append(maximumDetectedWidthPixels).append(" x ")
                .append(maximumDetectedHeightPixels).append(" px\n")
            append("Physical reference: ")
                .append(
                    when (physicalSizeBasis) {
                        AirPlayPhysicalSizeBasis.WIDTH -> "widest width"
                        AirPlayPhysicalSizeBasis.HEIGHT -> "longest height"
                    },
                )
                .append(" = ").append(widthPhysicalMm).append(" mm\n")
            native?.let { size ->
                val physical = resolvePhysicalSize(size)
                append("CarPlay physical size: ")
                    .append(physical.widthMm).append(" x ")
                    .append(physical.heightMm).append(" mm\n")
            }
            append("Driving side: ").append(if (rightHandDrive) "right" else "left").append('\n')
            append("Fullscreen: ").append(fullscreen).append('\n')
            append("Video transport: ").append(transport).append('\n')
            append("Location reporting: ")
                .append(if (locationReportingEnabled) "enabled" else "disabled")
                .append('\n')
            if (advancedAudioChannelMappingSupported) {
                append("Audio channel mapping: ")
                    .append(if (advancedAudioChannelMapping) "AAOS buses" else "Mobile compatible")
                    .append('\n')
            }
            append(safeAreaSummary())
        }
    }

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        val physical = resolvePhysicalSize(size)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = size.width,
            heightPixels = size.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
        )
        val scaledDisplay = CarPlayDisplayScale.apply(
            baseDisplay,
            displayScaleTenths,
        )
        val display = mainWebDisplay.override(scaledDisplay.copy(
            safeArea = AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = safeAreaDrawOutside,
        ))
        val cluster = clusterWebDisplay.takeIf { it.enabled }?.let {
            it.override(AirPlayDisplayConfig(it.width, it.height, widthPhysicalMm = widthPhysicalMm, fps = fps,
                primaryInputDevice = 0))
        }
        return AirPlayConfig(
            deviceName = "xcertplay",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "950.7.1",
            main = display,
            cluster = cluster,
            rightHandDrive = rightHandDrive,
            hevc = hevcEnabled,
            microphone = microphoneAvailable,
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon()),
        )
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        resources.openRawResource(R.raw.placeholder_icon).use { it.readBytes() }

    private fun updateAirPlayIconPreview() {
        val preview = iconPreviewView ?: return
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)
        var customBitmap: Bitmap? = null
        if (custom != null) {
            customBitmap = BitmapFactory.decodeFile(custom.absolutePath)
            if (customBitmap == null) {
                AirPlayPersistence.clearCustomAirPlayIcon(this)
            }
        }
        val bitmap = customBitmap ?: BitmapFactory.decodeResource(resources, R.raw.placeholder_icon)
        preview.setImageBitmap(bitmap)
        iconStatusView?.text =
            if (customBitmap != null) "Custom 1:1 icon" else "Default placeholder icon"
    }

    private fun currentActivitySize(): DisplaySize? {
        val view = videoView
        if (view != null && view.width > 0 && view.height > 0) {
            return DisplaySize(view.width, view.height)
        }
        return activeDisplaySize
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun safeAreaSummary(): String {
        val size = currentActivitySize() ?: return "Safe area: waiting for activity size"
        val mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        return if (mapping == null) {
            "Safe area: full screen at ${size.width} x ${size.height}"
        } else {
            "Safe area: ${mapping.width} x ${mapping.height} at " +
                "(${mapping.left}, ${mapping.top}) in ${size.width} x ${size.height}"
        }
    }

    private fun updateSafeAreaSummary() {
        safeAreaSummaryView?.text = safeAreaSummary()
    }

    private fun openSafeAreaEditor() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area editor is unavailable before display layout")
            return
        }
        val editorView = safeAreaEditorView ?: return
        val initial = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
            ?: AirPlaySafeArea.default(size.width, size.height)
        safeAreaEditSize = size
        safeAreaEditorActive = true
        // Keep the current activity size; changing system bars here would remap the safe area.
        settingsMenu?.visibility = View.GONE
        safeAreaEditor?.visibility = View.VISIBLE
        editorView.setRect(initial, size.width, size.height)
        appendLog(
            "Safe area editor opened for ${size.width}x${size.height}; " +
                "drag the four boundaries",
        )
    }

    private fun closeSafeAreaEditor() {
        if (!safeAreaEditorActive) return
        safeAreaEditorActive = false
        safeAreaEditSize = null
        safeAreaEditor?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area editor closed")
    }

    private fun saveSafeAreaEditor() {
        val size = safeAreaEditSize ?: currentActivitySize() ?: return
        val rect = safeAreaEditorView?.currentRectForSource() ?: return
        AirPlayPersistence.saveSafeAreaRect(this, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        closeSafeAreaEditor()
    }

    private fun resetSafeAreaForCurrentSize() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area reset is unavailable before display layout")
            return
        }
        AirPlayPersistence.clearSafeAreaRect(this, size.width, size.height)
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area reset to full screen for ${size.width}x${size.height}")
    }

    private fun refreshDisplaySizeAfterLayout() {
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun createMediaSink(
        airPlayConfig: AirPlayConfig,
        controllerGeneration: Int,
    ): AndroidMediaSink {
        val outputs = CarPlayWebDisplayFactory.create(
            context = applicationContext,
            configs = mapOf(SCREEN_TYPE_MAIN to mainWebDisplay, SCREEN_TYPE_ALT to clusterWebDisplay),
            key = AirPlayPersistence.webDisplayKey(this),
            sendTouch = { CarPlayBackgroundSession.snapshot()?.controller?.sendTouch(it) ?: false },
        )
        return try {
            AndroidMediaSink(
                context = applicationContext,
                videoWidth = airPlayConfig.main.widthPixels,
                videoHeight = airPlayConfig.main.heightPixels,
                videoSizes = buildMap {
                    put(SCREEN_TYPE_MAIN, airPlayConfig.main.widthPixels to airPlayConfig.main.heightPixels)
                    airPlayConfig.cluster?.let { put(SCREEN_TYPE_ALT, it.widthPixels to it.heightPixels) }
                },
                preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
                advancedAudioChannelMapping = advancedAudioChannelMapping,
                mainMediaAudioBufferDurationMs = mainMediaAudioBufferDurationMs,
                microphoneGainPercent = microphoneGainPercent,
                mediaMetricsMonitor = mediaMetricsMonitor,
                onClosed = { outputs?.close() },
                mediaTap = outputs,
                audioFocus = CarPlayBackgroundSession.audioFocus(this),
                onScreenStreamActiveChanged = { type, active ->
                    onScreenStreamStateChanged(controllerGeneration, type, active)
                },
            ).also { outputs?.bind(it) }
        } catch (error: Exception) { outputs?.close(); throw error }
    }

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
            videoPacketLog = CarPlayBackgroundSession.log::appendVideoPacket,
        )

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeAirPlaySession = session
                    syncAirPlayDarkMode()
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    if (activeAirPlaySession === session) activeAirPlaySession = null
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    failHandshake("CarPlay session ended", "iPhone closed the AirPlay session")
                    setConnectionStage("CarPlay session ended; reconnecting")
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    failHandshake("Transport error", message)
                    setConnectionStage("Transport error; reconnecting")
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onDebugLog(message: String) {
                val now = System.currentTimeMillis()
                appendFileLog(message, now)
                if (HandshakeTimeline.isRelevant(message)) {
                    mainHandler.post { recordHandshake(controllerGeneration, message, now) }
                }
                if (!debugLogsEnabled) return
                val summary = ScreenLogPolicy.summary(message) ?: return
                synchronized(pendingScreenLogsLock) {
                    if (!debugLogsEnabled) return
                    pendingScreenLogs.addLast(PendingLog(controllerGeneration, now, summary))
                    if (pendingScreenLogs.size > MAX_SCREEN_LOG_LINES) pendingScreenLogs.removeFirst()
                    if (!screenDrainPosted) {
                        screenDrainPosted = true
                        mainHandler.post(drainScreenLogs)
                    }
                }
            }
        }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = { status ->
        if (!menuOpen && controllerGeneration == restartGeneration) {
            updateHotspotStatus(status)
            val description = status.describe()
            setConnectionStage(description)
            when (status) {
                is CarPlayStatus.Failed -> {
                    failHandshake("CarPlay failed", description)
                    reconnectAfterLoss(description)
                }
                else -> Unit
            }
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        controller = snapshot.controller
        sink = snapshot.sink
        handshakeTimeline = snapshot.handshakeTimeline
        activeScreenStreamTypes = snapshot.activeScreenStreamTypes
        handshakePanel?.bind(handshakeTimeline)
        snapshot.sink.setMediaMetricsMonitor(mediaMetricsMonitor)
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
        }
        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(generation, type, active)
        }
        currentSurface?.let(::attachSurface)
        clusterPreviewSurface?.let { attachDisplaySurface(SCREEN_TYPE_ALT, it) }
        updateWebDisplayPreviews()
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                "CarPlay service already running"
            } else {
                "CarPlay session already running"
            },
        )
        updateDebugOverlays()
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || controller != null) return
        val controllerGeneration = restartGeneration
        handshakeTimeline = HandshakeTimeline(wirelessEnabled, System.currentTimeMillis(), lastAttemptFailure)
        lastAttemptFailure = null
        handshakePanel?.bind(handshakeTimeline)
        updateDebugOverlays()
        val config = createRuntimeConfig()
        val airPlayConfig = createAirPlayConfig(size)
        val locationProvider: Iap2LocationProvider? =
            if (config.locationReportingEnabled) {
                AndroidCarPlayLocationProvider(this)
            } else {
                null
            }
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${CarPlayDisplayScale.label(displayScaleTenths)}) " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${CarPlayDisplayScale.label(displayScaleTenths)} " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val renderer = try {
            createMediaSink(airPlayConfig, controllerGeneration)
        } catch (error: Exception) {
            appendLog("Could not create web displays: ${error.message}")
            failHandshake("Could not start web displays", error.message ?: "Check resolution and port 8080")
            setConnectionStage("Could not start web displays; check resolution and port 8080")
            return
        }
        sink = renderer
        currentSurface?.let(::attachSurface)
        clusterPreviewSurface?.let { attachDisplaySurface(SCREEN_TYPE_ALT, it) }
        updateWebDisplayPreviews()
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
            locationProvider = locationProvider,
        )
        controller = next
        CarPlayBackgroundSession.store(
            next, renderer, size.width, size.height,
            checkNotNull(handshakeTimeline), activeScreenStreamTypes,
        )
        next.start()
    }

    private fun syncAirPlayDarkMode() {
        val session = activeAirPlaySession ?: return
        val night = darkMode
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        if (!audioPacketCaptureEnabled) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == activeDisplaySize || size == pendingDisplaySize) return
        pendingDisplaySize = size
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun applyDisplaySize(size: DisplaySize) {
        if (shuttingDown.get() || size == activeDisplaySize) return
        val previous = activeDisplaySize
        activeDisplaySize = size
        recordDetectedMaximum(size)
        updateResolutionMenu()
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else if (!mainWebDisplay.enabled) {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (controller == null && adoptBackgroundSession()) return
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            menuOpen ||
            handshakeResetInProgress ||
            controller != null
        ) {
            return
        }
        startCarPlay(size)
    }

    private fun reconnectAfterLoss(reason: String) {
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        if (reconnectScheduled) return
        reconnectScheduled = true
        val generation = restartGeneration
        val delayMillis = if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            RECONNECT_DELAY_MILLIS
        }
        appendLog("$reason; retrying in ${delayMillis}ms")
        mainHandler.postDelayed(
            {
                reconnectScheduled = false
                if (
                    shuttingDown.get() ||
                    menuOpen ||
                    handshakeResetInProgress ||
                    generation != restartGeneration
                ) {
                    return@postDelayed
                }
                restartCarPlay("Reconnecting after $reason")
            },
            delayMillis,
        )
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) {
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        appendLog(reason)
        activeScreenStreamTypes.clear()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        teardownExecutor.execute {
            oldController?.close()
            oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            oldSink?.close()
            runOnUiThread {
                if (!shuttingDown.get() && generation == restartGeneration) startCarPlay(size)
            }
        }
    }

    private fun openSettingsMenu() {
        if (menuOpen || shuttingDown.get()) return
        stopMicrophoneGainTest()
        settingsBaseline = captureSettingsBaseline()
        menuOpen = true
        handshakeResetInProgress = true
        startAfterHandshakeReset = false
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
        updateHotspotStatusBlock()
        val generation = ++restartGeneration
        controller?.sendTouch(emptyList())
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        activeScreenStreamTypes.clear()
        setConnectionStage("Reconnecting after settings")
        gestureOverlay?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        webDisplayRefresh.forEach { it() }
        syncMainMediaAudioBufferControls()
        syncMicrophoneGainControls()
        microphoneTestButton?.isEnabled = false
        updateDebugOverlays()
        clearScreenLogs()
        appendLog("Settings opened; CarPlay handshake reset")
        updateResolutionMenu()
        teardownExecutor.execute {
            try {
                oldController?.close()
                oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            } finally {
                try {
                    oldSink?.close()
                } finally {
                    runOnUiThread {
                        if (shuttingDown.get() || generation != restartGeneration) {
                            return@runOnUiThread
                        }
                        handshakeResetInProgress = false
                        microphoneTestButton?.isEnabled = menuOpen
                        if (!menuOpen && startAfterHandshakeReset) {
                            startAfterHandshakeReset = false
                            maybeStartCarPlay()
                        }
                    }
                }
            }
        }
    }

    private fun saveSettingsAndReconnect() {
        if (!menuOpen) return
        if (!validateMfiSettings()) return
        if (!validateManualHotspotSettings()) return
        if (!validateWebDisplaySettings()) return
        persistMenuSettings()
        settingsBaseline = null
        finishSettingsMenu("Settings saved")
    }

    private fun cancelSettingsEdits() {
        if (!menuOpen) return
        restoreSettingsBaseline()
        finishSettingsMenu("Settings changes discarded")
    }

    private fun finishSettingsMenu(prefix: String) {
        if (!menuOpen) return
        stopMicrophoneGainTest()
        menuOpen = false
        settingsMenu?.visibility = View.GONE
        gestureOverlay?.visibility = View.VISIBLE
        updateWebDisplayPreviews()
        updateDebugOverlays()
        clearScreenLogs()
        appendLog(
            "$prefix; starting a fresh handshake at " +
                "${CarPlayDisplayScale.label(displayScaleTenths)} with " +
                (if (hevcEnabled) "HEVC (H.265)" else "H.264") +
                ", MFI ${mfiTargetLabel(mfiTarget)}" +
                ", Wi-Fi session ${hotspotModeLabel(wirelessHotspotMode)}",
        )
        if (handshakeResetInProgress) {
            startAfterHandshakeReset = true
        } else {
            maybeStartCarPlay()
        }
    }

    private fun exitApplication() {
        if (shuttingDown.get()) return
        restoreSettingsBaseline()
        finishAndRemoveTask()
        shutdown(terminateProcess = true, reason = "settings exit application")
    }

    private fun shutdown(terminateProcess: Boolean, reason: String) {
        if (!shuttingDown.compareAndSet(false, true)) return
        stopMicrophoneGainTest()
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.close()
            airPlayCommandExecutor.shutdown()
            if (terminateProcess) {
                applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java))
            }
            sessionLog.append("CarPlay stopped; clean=$clean")
            sessionLog.close()
            Log.i(TAG, "shutdown complete clean=$clean")
            teardownExecutor.shutdown()
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun attachSurface(surface: Surface) = attachDisplaySurface(SCREEN_TYPE_MAIN, surface)

    private fun attachDisplaySurface(type: Int, surface: Surface) {
        val outputs = CarPlayWebDisplayFactory.find(sink)
        if (outputs?.owns(type) == true) outputs.preview(type, surface) else sink?.setSurface(type, surface)
    }

    private fun detachSurface(type: Int, surface: Surface) {
        val outputs = CarPlayWebDisplayFactory.find(sink)
        if (outputs?.owns(type) == true) outputs.preview(type, null) else sink?.clearSurface(type, surface)
    }

    private fun updateWebDisplayPreviews() {
        val view = videoView
        view?.visibility = if (!mainWebDisplay.enabled || mainWebDisplay.preview) View.VISIBLE else View.INVISIBLE
        val matrix = android.graphics.Matrix()
        if (view != null && view.width > 0 && view.height > 0 && mainWebDisplay.enabled) {
            val rect = CarPlayTouchMapper.contentRect(view.width, view.height, mainWebDisplay.width, mainWebDisplay.height)
            matrix.setScale((rect[2] / view.width).toFloat(), (rect[3] / view.height).toFloat(), view.width / 2f, view.height / 2f)
        }
        view?.setTransform(matrix)
        clusterPreview?.let { cluster ->
            cluster.visibility = if (clusterWebDisplay.enabled && clusterWebDisplay.preview) View.VISIBLE else View.GONE
            val root = contentRoot
            if (root != null && root.width > 0 && root.height > 0) {
                val scale = minOf(root.width.toDouble() / clusterWebDisplay.width, root.height * 0.35 / clusterWebDisplay.height)
                val params = cluster.layoutParams
                val width = (clusterWebDisplay.width * scale).toInt().coerceAtLeast(1)
                val height = (clusterWebDisplay.height * scale).toInt().coerceAtLeast(1)
                if (params.width != width || params.height != height) { params.width = width; params.height = height; cluster.layoutParams = params }
            }
        }
        currentSurface?.let { webDisplays?.preview(SCREEN_TYPE_MAIN, it) }
        clusterPreviewSurface?.let { webDisplays?.preview(SCREEN_TYPE_ALT, it) }
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
                edgeSettingsGestureCaptured = moreGesturesToSettings &&
                    event.x in 0f..(view.width / 8f) &&
                    event.y in 0f..(view.height / 4f)
                edgeSettingsGestureEligible = edgeSettingsGestureCaptured
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == THREE_FINGER_COUNT && !gestureSequenceActive) {
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    controller?.sendTouch(emptyList())
                    appendLog("Three-finger swipe tracking started")
                    return true
                }
            }
        }

        if (edgeSettingsGestureCaptured) {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> edgeSettingsGestureEligible = false
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount != 1 || event.x !in 0f..(view.width / 8f)) {
                        edgeSettingsGestureEligible = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val openSettings = edgeSettingsGestureEligible &&
                        event.x in 0f..(view.width / 8f) &&
                        event.y in (view.height * 3f / 4f)..view.height.toFloat()
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                    if (openSettings) openSettingsMenu()
                }
                MotionEvent.ACTION_CANCEL -> {
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                }
            }
            return true
        }

        if (gestureSequenceActive) {
            if (!gestureTracking || event.pointerCount != THREE_FINGER_COUNT) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                if (
                    deltaY >= dp(THREE_FINGER_SWIPE_DISTANCE_DP) &&
                    deltaY >= deltaX * THREE_FINGER_SWIPE_DIRECTION_RATIO
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                    openSettingsMenu()
                    return true
                }
            }
            return true
        }

        if (mainWebDisplay.enabled && !mainWebDisplay.preview) return true
        val contacts = CarPlayTouchMapper.contacts(event, view.width, view.height,
            if (mainWebDisplay.enabled) mainWebDisplay.width else view.width,
            if (mainWebDisplay.enabled) mainWebDisplay.height else view.height)
        val queued = controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
            }
            updateDebugOverlays()
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            setConnectionStage(message)
            appendLog(message)
        }
    }

    private fun setConnectionStage(message: String) {
        handshakePanel?.setStatus(message)
        updateDebugOverlays()
    }

    private fun updateDebugOverlays() {
        val showLogs = debugLogsEnabled && !menuOpen
        statusScrollView?.visibility = if (showLogs) View.VISIBLE else View.GONE
        disconnectedSettingsButton?.visibility =
            if (!menuOpen && SCREEN_TYPE_MAIN !in activeScreenStreamTypes) View.VISIBLE else View.GONE
        val showPanel = !debugLogsEnabled && !menuOpen && SCREEN_TYPE_MAIN !in activeScreenStreamTypes
        handshakePanel?.visibility = if (showPanel) View.VISIBLE else View.GONE
        updateMediaMetricsOverlay()
    }

    private fun recordHandshake(controllerGeneration: Int, message: String, atMillis: Long) {
        if (controllerGeneration != restartGeneration) return
        val timeline = handshakeTimeline ?: return
        if (timeline.accept(message, atMillis)) handshakePanel?.notifyChanged()
    }

    private fun failHandshake(title: String, detail: String) {
        val timeline = handshakeTimeline ?: return
        timeline.fail(title, detail, System.currentTimeMillis())
        lastAttemptFailure = timeline.failure?.let { "${it.title}: ${it.detail}" }
        handshakePanel?.notifyChanged()
    }

    private fun updateMediaMetricsOverlay() {
        val root = contentRoot ?: return
        if (!mediaMetricsEnabled) {
            sink?.setMediaMetricsMonitor(null)
            mediaMetricsOverlay?.let(root::removeView)
            mediaMetricsOverlay = null
            mediaMetricsMonitor = null
            return
        }
        val monitor = mediaMetricsMonitor ?: MediaMetricsMonitor().also {
            mediaMetricsMonitor = it
        }
        sink?.setMediaMetricsMonitor(monitor)
        // The frame-rate axis follows the configured target, which the settings menu can change
        // while the overlay stays alive.
        val overlay = mediaMetricsOverlay ?: MediaMetricsOverlayView(
            this,
            monitor,
            fpsAxisMax = { fps.toFloat() },
        ).also {
            mediaMetricsOverlay = it
            val settingsIndex = root.indexOfChild(settingsMenu)
            root.addView(
                it,
                if (settingsIndex >= 0) settingsIndex else root.childCount,
                mediaMetricsOverlayLayoutParams(root.width, root.height),
            )
        }
        overlay.visibility = if (!menuOpen && !safeAreaEditorActive) View.VISIBLE else View.GONE
        updateMediaMetricsOverlayLayout(root.width, root.height)
    }

    private fun updateMediaMetricsOverlayLayout(screenWidth: Int, screenHeight: Int) {
        val overlay = mediaMetricsOverlay ?: return
        val desired = mediaMetricsOverlayLayoutParams(screenWidth, screenHeight)
        val current = overlay.layoutParams
        if (current.width != desired.width || current.height != desired.height) {
            overlay.layoutParams = desired
        }
    }

    private fun mediaMetricsOverlayLayoutParams(
        screenWidth: Int,
        screenHeight: Int,
    ): FrameLayout.LayoutParams {
        val fallback = resources.displayMetrics
        val panelWidth = (screenWidth.takeIf { it > 0 } ?: fallback.widthPixels) / 3
        val panelHeight = (screenHeight.takeIf { it > 0 } ?: fallback.heightPixels) / 3
        return FrameLayout.LayoutParams(panelWidth, panelHeight, Gravity.TOP or Gravity.START)
    }

    private fun appendLog(message: String) {
        val now = System.currentTimeMillis()
        appendFileLog(message, now)
        if (debugLogsEnabled && !menuOpen) appendScreenLog(now, message)
    }

    private fun appendScreenLog(timestampMillis: Long, message: String) {
        val summary = ScreenLogPolicy.summary(message) ?: return
        logLines.add(timestampMillis, formattedLogLine(summary, timestampMillis))
        if (!logRenderScheduled) {
            logRenderScheduled = true
            mainHandler.postDelayed(renderLogLines, LOG_RENDER_INTERVAL_MILLIS)
        }
    }

    private fun appendFileLog(message: String, timestampMillis: Long) =
        sessionLog.appendTimestamped(message, timestampMillis)

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val baseDirectory = getExternalFilesDir(null) ?: filesDir
        val logFile = File(File(baseDirectory, "logs"), "xcertplay.log")
        sessionLog.start(
            logFile,
            "xcertplay log started " +
                "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                "pid=${Process.myPid()} path=${logFile.absolutePath}",
            onError = { Log.e(TAG, it) },
        )
    }

    private fun refreshLogView(nowMillis: Long) {
        if (!debugLogsEnabled || menuOpen) return
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        logLines.expireBefore(cutoff)
        statusView?.text = logLines.renderedText()
        scrollLogsToBottom()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstTimestampMillis?.let { oldest ->
            val delay = (oldest + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun clearScreenLogs() {
        synchronized(pendingScreenLogsLock) {
            pendingScreenLogs.clear()
            screenDrainPosted = false
        }
        mainHandler.removeCallbacks(drainScreenLogs)
        logLines.clear()
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(renderLogLines)
        logRenderScheduled = false
        statusView?.text = ""
    }

    private fun scrollLogsToBottom() {
        statusScrollView?.post {
            statusScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun applyFullscreenMode() {
        val hideTop = hideTopBar
        val hideBottom = hideBottomBar
        WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hideTop) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> "Preparing MFi authentication"
        CarPlayStatus.WaitingForMfi -> "Waiting for MFi coprocessor"
        CarPlayStatus.RequestingMfiPermission -> "Requesting MFi USB permission"
        CarPlayStatus.MfiReady -> "MFi authentication ready"
        CarPlayStatus.StartingHotspot -> "Starting wireless hotspot"
        is CarPlayStatus.HotspotReady ->
            "Hotspot ready: $backend, $ssid, $band, " +
                "channel ${if (channel == 0) "auto" else channel}"
        CarPlayStatus.WaitingForPairedIphone -> "Waiting for paired iPhone"
        CarPlayStatus.ConnectingBluetooth -> "Connecting Bluetooth"
        CarPlayStatus.RunningWireless -> "Wireless CarPlay control running"
        CarPlayStatus.WirelessActive -> "Wireless CarPlay active"
        CarPlayStatus.DiscoveringIphone -> "Discovering iPhone"
        CarPlayStatus.WaitingForIphone -> "Waiting for iPhone over USB"
        CarPlayStatus.RequestingIphonePermission -> "Requesting iPhone USB permission"
        CarPlayStatus.WaitingForReenumeration -> "Waiting for iPhone re-enumeration"
        CarPlayStatus.SelectingConfiguration -> "Selecting CarPlay configuration"
        CarPlayStatus.OpeningDataPaths -> "Opening USB data paths"
        CarPlayStatus.Pairing -> "Pairing with iPhone"
        CarPlayStatus.ConnectingControl -> "Connecting iAP2 control"
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) "Starting AirPlay service" else "Attaching NCM/AirPlay network"
        CarPlayStatus.RunningControl -> "CarPlay control running"
        CarPlayStatus.ControlEnded -> "CarPlay control window ended"
        is CarPlayStatus.Failed -> "Failed: ${message}"
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        const val LOG_RENDER_INTERVAL_MILLIS = 100L
        const val MAX_SCREEN_LOG_LINES = 100
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val THREE_FINGER_COUNT = 3
        const val THREE_FINGER_SWIPE_DISTANCE_DP = 72
        const val THREE_FINGER_SWIPE_DIRECTION_RATIO = 1.15f
        const val MAX_SETTINGS_MENU_WIDTH_PX = 1200
        const val MENU_CORNER_DP = 22
        const val MENU_BUTTON_CORNER_DP = 14
        // Settings share the connection panel's palette (see HandshakePanelView).
        val MENU_BACKGROUND = HandshakePanelView.CARD
        val MENU_LABEL = HandshakePanelView.LABEL
        // LABEL at ~67% over CARD, kept opaque so switch thumbs stay solid.
        val MENU_SECONDARY = Color.rgb(0xAA, 0xAA, 0xAF)
        val MENU_ACCENT = HandshakePanelView.ACCENT
        val MENU_ACCENT_TRACK = HandshakePanelView.FILL
        val MENU_TRACK_OFF = HandshakePanelView.FILL_DARK
        val MENU_SEPARATOR = HandshakePanelView.SEPARATOR
        val MENU_BUTTON = HandshakePanelView.FILL_DARK
        val MENU_BUTTON_PRIMARY = HandshakePanelView.FILL
        val MENU_DANGER = HandshakePanelView.RED
        val MENU_DANGER_FILL = HandshakePanelView.withAlpha(HandshakePanelView.RED, 36)
        val MENU_RIPPLE = HandshakePanelView.withAlpha(HandshakePanelView.LABEL, 40)
        val NO_VIDEO_BACKGROUND = Color.BLACK
    }

    private data class DisplaySize(val width: Int, val height: Int)
    private data class PendingLog(
        val generation: Int,
        val timestampMillis: Long,
        val message: String,
    )
    private data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )
}

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
private object CarPlayBackgroundSession {
    val log = SessionLog()
    private var audioFocusSession: AndroidMediaSink.AudioFocusSession? = null

    @Synchronized
    fun audioFocus(context: android.content.Context): AndroidMediaSink.AudioFocusSession =
        audioFocusSession ?: AndroidMediaSink.createAudioFocus(context).also { audioFocusSession = it }

    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
        val handshakeTimeline: HandshakeTimeline,
        val activeScreenStreamTypes: MutableSet<Int>,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0
    private var handshakeTimeline: HandshakeTimeline? = null
    private var activeScreenStreamTypes = mutableSetOf<Int>()

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        val timeline = handshakeTimeline ?: return null
        return Snapshot(currentController, currentSink, width, height, timeline, activeScreenStreamTypes)
    }

    @Synchronized
    fun store(
        controller: CarPlayController,
        sink: AndroidMediaSink,
        width: Int,
        height: Int,
        handshakeTimeline: HandshakeTimeline,
        activeScreenStreamTypes: MutableSet<Int>,
    ) {
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
        this.handshakeTimeline = handshakeTimeline
        this.activeScreenStreamTypes = activeScreenStreamTypes
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null) {
        if (expected != null && controller !== expected) return
        controller = null
        sink = null
        width = 0
        height = 0
        handshakeTimeline = null
        activeScreenStreamTypes = mutableSetOf()
    }
}
