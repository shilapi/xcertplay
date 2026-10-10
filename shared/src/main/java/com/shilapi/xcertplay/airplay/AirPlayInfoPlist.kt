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

    fun build(config: AirPlayConfig, enabledFeatures: Set<String>? = null): Map<String, Any?> {
        val cluster = config.cluster.takeIf { enabledFeatures == null || "altScreen" in enabledFeatures }
        val displays = arrayListOf<Any?>(
            displayEntry(config.main, STREAM_TYPE_MAIN_SCREEN, MAIN_UUID),
        )
        cluster?.let { displays.add(displayEntry(it, STREAM_TYPE_ALT_SCREEN, ALT_UUID)) }

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
        if (cluster != null && (enabledFeatures == null || "uiContext" in enabledFeatures)) {
            info["uiContextLastOnDisplayURLs"] = emptyList<String>()
            info["uiContextNowOnDisplayURLs"] = listOfNotNull(config.main.initialUrl, cluster.initialUrl)
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
        if (config.hevc && (enabledFeatures == null || "hevc" in enabledFeatures)) {
            info["hevcInfo"] = emptyMap<String, Any?>()
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

    private fun displayEntry(display: AirPlayDisplayConfig, type: Int, uuid: String): Map<String, Any?> {
        val widthPhysical = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
            display.widthPhysicalMm ?: AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM,
        )
        val heightPhysical = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
            display.heightPhysicalMm
                ?: Math.round(
                    widthPhysical * display.heightPixels.toDouble() / display.widthPixels,
                ).toInt(),
        )
        val fps = AirPlayDisplaySettings.sanitizeFps(display.fps)

        val entry = linkedMapOf<String, Any?>(
            "uuid" to uuid,
            "type" to type,
            "maxFPS" to fps,
            "widthPixels" to display.widthPixels,
            "heightPixels" to display.heightPixels,
            "widthPixelsMax" to display.widthPixels,
            "heightPixelsMax" to display.heightPixels,
            "widthPhysical" to widthPhysical,
            "heightPhysical" to heightPhysical,
            "features" to (DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH or DISPLAY_FEATURE_KNOBS),
            "primaryInputDevice" to display.primaryInputDevice,
        )

        entry["viewAreas"] = listOf(areaDict(display))
        entry["initialViewArea"] = 0
        entry["adjacentViewAreas"] = emptyList<Int>()
        if (display.initialUrl != null) entry["initialURL"] = display.initialUrl
        return entry
    }

    private fun areaDict(display: AirPlayDisplayConfig): Map<String, Any?> {
        // The session SETUP response enables "viewAreas", so /info must always describe one.
        // A display without custom insets uses the full panel for both the view and safe areas.
        val view = display.viewArea ?: AirPlayInsets()
        val width = display.widthPixels
        val height = display.heightPixels
        val result = linkedMapOf<String, Any?>(
            "viewAreaIndex" to 0,
            "widthPixels" to (width - view.left - view.right),
            "heightPixels" to (height - view.top - view.bottom),
            "originXPixels" to view.left,
            "originYPixels" to view.top,
            "viewAreaTransitionControl" to 0,
            "viewAreaStatusBarEdge" to 0,
            "viewAreaSupportsFocusTransfer" to false,
        )
        val safe = display.safeArea ?: AirPlayInsets()
        val safeArea = linkedMapOf<String, Any?>(
            "widthPixels" to (width - safe.left - safe.right),
            "heightPixels" to (height - safe.top - safe.bottom),
            "originXPixels" to safe.left,
            "originYPixels" to safe.top,
            "drawUIOutsideSafeArea" to (display.safeAreaDrawOutside ?: true),
        )
        result["safeArea"] = safeArea
        return result
    }
}

/** Ordinary alternate-display UI control, independent of the Ultra vehicle/UI-sync stack. */
internal object AirPlayDisplayControlFactory {
    fun create(config: AirPlayConfig, send: (Map<String, Any?>) -> Boolean, log: (String) -> Unit) =
        Session(config, send, log)

    class Session internal constructor(
        private val config: AirPlayConfig,
        private val send: (Map<String, Any?>) -> Boolean,
        private val log: (String) -> Unit,
    ) {
        private var enabled: Set<String>? = null
        private var altUrls = emptyList<String>()
        private val selected = linkedMapOf<String, String>()
        private val previous = linkedMapOf<String, String>()
        private val pending = linkedMapOf<String, Map<String, Any?>>()
        private var pendingResponses = 0
        private var alternateReady = false

        @Synchronized fun negotiate(value: Any?, eventPortAvailable: Boolean): List<String> {
            val requested = strings(value)
            val supported = linkedSetOf("viewAreas")
            if (config.hevc) supported += "hevc"
            if (eventPortAvailable) {
                supported += "iAPChannel"
                if (config.cluster != null) supported += listOf("altScreen", "uiContext")
            }
            val accepted = requested.filterTo(linkedSetOf()) { it in supported }
            // The phone validates these two features together before creating alternate UI.
            if (("altScreen" in accepted) != ("uiContext" in accepted)) {
                accepted.removeAll(setOf("altScreen", "uiContext"))
            }
            enabled = accepted
            if ("altScreen" in accepted) {
                altUrls.firstOrNull()?.let { select(AirPlayInfoPlist.ALT_UUID, it) }
            }
            log("airplay SETUP feature proposal requested=$requested supported=$supported enabled=$accepted")
            return accepted.toList()
        }

        @Synchronized fun info(request: Map<String, Any?>): Map<String, Any?> {
            altUrls = strings(request["altScreenURLs"])
            if ("altScreen" in enabled.orEmpty()) {
                val url = config.cluster?.initialUrl?.takeIf { it in altUrls } ?: altUrls.firstOrNull()
                url?.let { select(AirPlayInfoPlist.ALT_UUID, it) }
            }
            val cluster = config.cluster?.let {
                it.copy(initialUrl = selected[AirPlayInfoPlist.ALT_UUID] ?: it.initialUrl)
            }
            val main = config.main.copy(initialUrl = selected[AirPlayInfoPlist.MAIN_UUID] ?: config.main.initialUrl)
            val info = AirPlayInfoPlist.build(config.copy(main = main, cluster = cluster), enabled).toMutableMap()
            if ("uiContext" in enabled.orEmpty()) {
                info["uiContextLastOnDisplayURLs"] = previous.values.distinct()
            }
            log("airplay /info altScreenURLs=$altUrls selected=${selected[AirPlayInfoPlist.ALT_UUID] ?: "none"}")
            // altScreenURLs belongs to the phone's request; never echo it as a response sidecar.
            return info
        }

        @Synchronized fun screenReady(type: Int) {
            if (type == 111) {
                alternateReady = true
                selected[AirPlayInfoPlist.ALT_UUID]?.let {
                    pending[AirPlayInfoPlist.ALT_UUID] = command("showUI", linkedMapOf("uuid" to AirPlayInfoPlist.ALT_UUID, "url" to it))
                }
            }
        }

        @Synchronized fun screenClosed(type: Int) {
            if (type == 111) alternateReady = false
        }

        @Synchronized fun acceptsAlternateScreen(): Boolean = "altScreen" in enabled.orEmpty()

        @Synchronized fun receive(type: String, params: Map<String, Any?>) {
            val features = enabled.orEmpty()
            when (type) {
                "suggestUI", "changeUIContext" -> {
                    if ("uiContext" !in features) return
                    val offered = if (type == "suggestUI") strings(params["urls"])
                        else listOfNotNull((params["url"] as? String)?.takeIf { it.isNotBlank() })
                    val explicit = (params["uuid"] ?: params["displayUUID"]) as? String
                    val alt = explicit == AirPlayInfoPlist.ALT_UUID || (explicit == null && offered.any { it in altUrls })
                    val uuid = explicit ?: if (alt) AirPlayInfoPlist.ALT_UUID else AirPlayInfoPlist.MAIN_UUID
                    require(uuid == AirPlayInfoPlist.MAIN_UUID || (uuid == AirPlayInfoPlist.ALT_UUID && "altScreen" in features)) {
                        "Unknown UI display UUID"
                    }
                    val url = if (alt && explicit == null) offered.firstOrNull { it in altUrls } else offered.firstOrNull()
                    url?.let { select(uuid, it) }
                }
                "requestViewArea" -> {
                    if ("viewAreas" !in features) return
                    val uuid = params["displayUUID"] as? String ?: throw IllegalArgumentException("Missing displayUUID")
                    require(uuid == AirPlayInfoPlist.MAIN_UUID || (uuid == AirPlayInfoPlist.ALT_UUID && "altScreen" in features))
                    val view = params["viewArea"]
                    val index = (view as? Number)?.toLong() ?: ((view as? Map<*, *>)?.get("viewAreaIndex") as? Number)?.toLong()
                    require(index == 0L) { "Unknown view area" }
                    pending["area:$uuid"] = command("updateViewArea", linkedMapOf(
                        "uuid" to uuid, "viewAreaIndex" to 0, "adjacentViewAreas" to emptyList<Int>(), "animationDurationMillis" to 0,
                    ))
                }
            }
        }

        @Synchronized fun responseStarted() {
            pendingResponses++
        }

        @Synchronized fun responseSent() {
            pendingResponses--
            flush()
        }

        @Synchronized fun flush() {
            if (pendingResponses != 0) return
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val (key, value) = iterator.next()
                if (key == AirPlayInfoPlist.ALT_UUID && !alternateReady) continue
                if (!send(value)) break
                log("airplay display command sent type=${value["type"]} params=${value["params"]}")
                iterator.remove()
            }
        }

        private fun select(uuid: String, url: String) {
            if (selected[uuid] == url) return
            selected[uuid]?.let { previous[uuid] = it }
            selected[uuid] = url
            pending[uuid] = command("showUI", linkedMapOf("uuid" to uuid, "url" to url))
            log("airplay display UI selected uuid=$uuid url=$url")
        }

        private fun command(type: String, params: Map<String, Any?>): Map<String, Any?> =
            linkedMapOf("type" to type, "params" to params)

        private fun strings(value: Any?): List<String> = (value as? List<*>)
            .orEmpty().filterIsInstance<String>().filter { it.isNotBlank() }.distinct()
    }
}
