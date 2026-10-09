package com.shilapi.xcertplay.airplay

/**
 * Builds the /info response the phone reads before it requests media streams.
 *
 * The declaration is complete on purpose: it must carry the display, audio formats/latencies,
 * CarPlay resource modes, and the HID input devices, otherwise the phone aborts the session.
 */
object AirPlayInfoPlist {
    const val MAIN_UUID = "b7e6c5a0-1111-4000-8000-000000000001"
    const val ALT_UUID = "b7e6c5a0-2222-4000-8000-000000000002"

    private const val STREAM_TYPE_MAIN_SCREEN = 110
    private const val STREAM_TYPE_ALT_SCREEN = 111
    private const val DISPLAY_FEATURE_KNOBS = 0x02
    private const val DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH = 0x08
    private const val CARPLAY_FEATURES = 0x615653aee2L
    private const val CARPLAY_AUDIO_FEATURES = 0x10004540a00L
    private val CARPLAY_FEATURES_NO_AUDIO = CARPLAY_FEATURES and CARPLAY_AUDIO_FEATURES.inv()

    private const val RESOURCE_SCREEN = 1
    private const val RESOURCE_AUDIO = 2
    private const val TRANSFER_TAKE = 1
    private const val PRIORITY_NICE_TO_HAVE = 100
    private const val CONSTRAINT_ANYTIME = 100

    /** Null advertises capabilities before SETUP; an empty set means SETUP accepted no features. */
    fun build(
        config: AirPlayConfig,
        negotiatedFeatures: Set<AirPlayFeature>? = null,
    ): Map<String, Any?> {
        val capabilities = AirPlayFeatureNegotiation.capabilities(
            config = config,
            eventPortAvailable = true,
        )
        val responseFeatures = negotiatedFeatures?.toSet() ?: capabilities.supportedFeatureSet
        require(capabilities.supportedFeatureSet.containsAll(responseFeatures)) {
            "/info cannot describe features unsupported by this receiver"
        }
        val clusterDisplay = config.ultra?.takeIf { it.declareClusterDisplay }?.cluster
        val displays = arrayListOf<Map<String, Any?>>(
            AirPlayDisplayDescriptionFactory.create(config.main, STREAM_TYPE_MAIN_SCREEN, MAIN_UUID),
        )
        if (AirPlayFeature.ALT_SCREEN in responseFeatures) {
            clusterDisplay?.let {
                displays.add(AirPlayDisplayDescriptionFactory.create(it, STREAM_TYPE_ALT_SCREEN, ALT_UUID))
            }
        }

        val info = linkedMapOf<String, Any?>(
            "sourceVersion" to config.sourceVersion,
            "features" to if (config.disableAudioOutput) CARPLAY_FEATURES_NO_AUDIO else CARPLAY_FEATURES,
            "statusFlags" to 4L,
            "model" to config.model,
            "manufacturer" to config.manufacturer,
            "deviceID" to config.deviceId,
            "bluetoothIDs" to listOf(config.btMac),
            "name" to config.deviceName,
            "rightHandDrive" to config.rightHandDrive,
            "keepAliveLowPower" to false,
            "keepAliveSendStatsAsBody" to false,
            "modes" to modes(),
        )
        if (!config.disableAudioOutput) {
            info["audioLatencies"] = audioLatencies()
            info["audioFormats"] = audioFormats(
                config.entertainmentSampleRate,
                config.microphone,
                config.wirelessAudio,
            )
        }
        info["extendedFeatures"] = listOf("vocoderInfo", "enhancedRequestCarUI")
        info["displays"] = displays
        // DDP is a physical display/plugin relationship, not displays[].features bit 3.
        // Keep it out of classic and partial negotiations, including an unaccepted alternate stream.
        if (AirPlayFeature.UI_SYNC in responseFeatures && AirPlayFeature.ALT_SCREEN in responseFeatures &&
            clusterDisplay != null) {
            config.ultra?.clusterDisplayPluginId?.let { pluginId ->
                info["displayPanels"] = AirPlayDisplayDescriptionFactory.createPanels(
                    displays.first(), displays[1], pluginId, config.ultra?.centerDisplayPluginId,
                )
            }
        }
        if (AirPlayFeature.UI_CONTEXT in responseFeatures) {
            info["uiContextLastOnDisplayURLs"] = emptyList<String>()
            info["uiContextNowOnDisplayURLs"] = listOfNotNull(config.main.initialUrl, clusterDisplay?.initialUrl)
        }
        info["hidDevices"] = listOf(
            AirPlayHid.touchHidDevice(config.main.widthPixels, config.main.heightPixels, MAIN_UUID),
            AirPlayHid.knobHidDevice(MAIN_UUID),
            AirPlayHid.mediaHidDevice(MAIN_UUID),
            AirPlayHid.telephonyHidDevice(MAIN_UUID),
        )
        if (config.icons.isNotEmpty()) {
            info["oemIconVisible"] = true
            info["oemIconLabel"] = config.oemLabel
            info["oemIcons"] = config.icons.map { icon ->
                linkedMapOf(
                    "imageData" to icon.data,
                    "widthPixels" to icon.widthPixels,
                    "heightPixels" to icon.heightPixels,
                    "prerendered" to true,
                )
            }
        }
        responseFeatures
            .filter { it.requiresInfoResponseSidecar }
            .forEach { feature ->
                val key = requireNotNull(feature.infoResponseKey)
                info[key] = AirPlayFeatureNegotiation.resolveInfoSidecar(config, feature)
                    ?: throw AirPlayConfigurationException(
                        "AirPlay feature '${feature.wireName}' has no '${feature.infoResponseKey}'",
                    )
            }
        if (AirPlayFeature.HEVC !in responseFeatures) {
            info.remove("hevcInfo")
        }
        return info
    }

    private fun resource(resourceId: Int): Map<String, Any?> = linkedMapOf(
        "resourceID" to resourceId,
        "transferType" to TRANSFER_TAKE,
        "transferPriority" to PRIORITY_NICE_TO_HAVE,
        "takeConstraint" to CONSTRAINT_ANYTIME,
        "borrowConstraint" to CONSTRAINT_ANYTIME,
        "unborrowConstraint" to CONSTRAINT_ANYTIME,
    )

    private fun modes(): Map<String, Any?> = linkedMapOf(
        "resources" to listOf(resource(RESOURCE_SCREEN), resource(RESOURCE_AUDIO)),
        "appStates" to listOf(
            linkedMapOf("appStateID" to 2, "state" to false),
            linkedMapOf("appStateID" to 1, "speechMode" to -1),
            linkedMapOf("appStateID" to 3, "state" to false),
        ),
    )

    private fun audioLatencies(): List<Map<String, Any?>> {
        fun base(type: Int, audioType: String? = null): Map<String, Any?> {
            val entry = linkedMapOf<String, Any?>(
                "type" to type,
                "inputLatencyMicros" to 0L,
                "outputLatencyMicros" to 0L,
            )
            if (audioType != null) entry["audioType"] = audioType
            return entry
        }
        return listOf(
            base(100), base(100, "default"), base(100, "media"), base(100, "telephony"),
            base(100, "speechRecognition"), base(100, "alert"), base(101), base(101, "default"),
            base(102, "default"),
        )
    }

    private fun audioFormats(
        entertainmentRate: Int,
        microphone: Boolean,
        wirelessAudio: Boolean,
    ): List<Map<String, Any?>> {
        fun format(type: Int, audioType: String, outputFormats: Int, inputFormats: Int? = null): Map<String, Any?> {
            val entry = linkedMapOf<String, Any?>(
                "type" to type,
                "audioType" to audioType,
                "audioOutputFormats" to outputFormats,
            )
            if (inputFormats != null) entry["audioInputFormats"] = inputFormats
            return entry
        }

        val is48 = entertainmentRate == 48000
        val pcmVoice = 0x3fc
        val pcm = pcmVoice or (if (is48) 0xc000 else 0xc00)
        val pcmMono = 0x154 or (if (is48) 0x4000 else 0x400)
        // Wired CarPlay uses PCM for the low-latency streams. Keep Opus for wireless sessions.
        val opus = if (wirelessAudio) 0x70000000 else 0
        val aacLc = if (is48) 0x800000 else 0x400000
        val pcmInput = if (microphone) pcmMono else null
        val input = if (microphone) pcmMono or opus else null

        return listOf(
            format(100, "compatibility", pcm, pcmInput),
            format(101, "compatibility", pcm),
            format(100, "default", pcm or opus, input),
            format(100, "alert", pcm or opus),
            format(100, "media", pcm),
            format(100, "telephony", pcmMono or opus, input),
            format(100, "speechRecognition", pcmMono or opus, input),
            format(101, "default", pcm or opus),
            format(102, "media", aacLc),
        )
    }

}
