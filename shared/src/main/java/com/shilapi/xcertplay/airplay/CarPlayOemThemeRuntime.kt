package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.transfer.CarPlayThemeFileHeader
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandPayload
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Literal metadata generated from the signed package. No OEM Lua or custom bytecode is executed. */
class CarPlayOemThemeDescriptor private constructor(private val values: Map<String, Any?>,
    private val displayId: String = "Cluster_Display") {
    val packageData = dictionary(values["package"])
    val assetId = packageData["identifier"] as String
    val version = (packageData["version"] as Number).toLong()
    val sdkVersion = packageData["minimumSDKVersion"] as String
    val archiveSHA256 = values["archiveSHA256"] as String
    val bridgeVersion = values["bridgeVersion"] as String
    val displays = sequence(packageData["displays"]).map(::dictionary)
    val cluster = displays.single { it["id"] == "Cluster_Display" }
    val display = displays.single { it["id"] == displayId }
    /** Shares immutable package metadata while selecting this display's layouts and palette. */
    fun forDisplay(id: String): CarPlayOemThemeDescriptor = CarPlayOemThemeDescriptor(values, id)
    /** Enums.DisplayPanelIdentifier.CENTER_DISPLAY; packages without a centerConsole display omit it. */
    val center = displays.firstOrNull { it["type"] == "centerConsole" }
    val clusterWidth = (sequence(cluster["size"])[0] as Number).toInt()
    val clusterHeight = (sequence(cluster["size"])[1] as Number).toInt()
    val layouts = sequence(display["layouts"]).map(::dictionary)
    val initialLayout = layouts.first { it["layoutType"] == "primary" }["id"] as String
    private val resources = dictionary(values["resourcePaths"])
    private val theme = dictionary(values["theme"])

    fun layout(id: String): Map<String, Any?> = layouts.firstOrNull { it["id"] == id }
        ?: throw IllegalArgumentException("Unknown OEM layout $id")

    fun palette(id: String): String = dictionary(theme["defaultPaletteIDs"])[layout(id)["layoutStyleID"]] as String

    /** Palettes the asset offers for this layout's style (keys of `layoutStyles[style]`), default first. */
    fun palettes(id: String): List<String> {
        val default = palette(id)
        val offered = dictionary(theme["layoutStyles"])[layout(id)["layoutStyleID"]]
            ?.let { dictionary(it).keys.sorted() } ?: emptyList()
        return listOf(default) + offered.filter { it != default }
    }

    /** Every selectable cluster theme (layout × palette) in asset order. */
    fun themeChoices(): List<Pair<String, String>> =
        layouts.flatMap { layout -> (layout["id"] as String).let { id -> palettes(id).map { id to it } } }

    fun validatePalette(id: String, paletteId: String) {
        layout(id)
        require(dictionary(theme["palettes"]).containsKey(paletteId)) { "Unknown OEM palette $paletteId" }
    }

    fun colorDefinition(id: String, paletteId: String, colorId: String): Map<String, Any?>? {
        validatePalette(id, paletteId)
        val base = dictionary(dictionary(dictionary(theme["palettes"])[paletteId])["colors"])[colorId]
        val style = dictionary(dictionary(theme["layoutStyles"])[layout(id)["layoutStyleID"]] ?: emptyMap<String, Any?>())
        val override = dictionary(style[paletteId] ?: emptyMap<String, Any?>())[colorId]
        val value = override ?: base ?: return null
        return dictionary(value)
    }

    fun remoteComponents(id: String): Map<String, List<String>> = sequence(layout(id)["zones"]).map(::dictionary)
        .filter { dictionary(it["type"]).let { type -> type.containsKey("dca") || type.containsKey("mapsMedia") } }.associate { zone ->
            (zone["id"] as String) to sequence(zone["components"]).map(::dictionary)
                .filter { it["type"] == "primary" }.map { it["id"] as String }
        }

    /** Request the asset's map component explicitly; asset order otherwise defaults to ADAS. */
    fun initialComponents(id: String): Map<String, String> = sequence(layout(id)["zones"]).map(::dictionary)
        .filter { dictionary(it["type"]).let { type -> type.containsKey("dca") || type.containsKey("mapsMedia") } }
        .mapNotNull { zone ->
            val primary = sequence(zone["components"]).map(::dictionary).filter { it["type"] == "primary" }
            val selected = primary.firstOrNull { it["name"] == "DCA_Map" } ?: primary.firstOrNull()
            selected?.let { (zone["id"] as String) to (it["id"] as String) }
        }.toMap()

    fun resourcePath(identifier: String): String {
        val relative = dictionary(resources[identifier])["path"] as? String
            ?: throw IllegalArgumentException("Unknown OEM resource $identifier")
        require(!relative.startsWith('/') && '\\' !in relative && relative.split('/').none { it == ".." || it == "." })
        // The archive carries one resource tree per display (r/Cluster_Display/, r/Center_Display/);
        // a Center runtime must not resolve under the cluster tree.
        return "r/$displayId/$relative"
    }

    /** Wire SDK follows the selected asset requirement. The verified bundled package is advertised as applied. */
    fun resolveProfile(override: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>("sdkVersion" to sdkVersion,
            "clusterAsset" to mapOf("assetID" to assetId, "assetVer" to version),
            "layoutIdentifier" to initialLayout, "appearanceMode" to "dark")
        result.putAll(override)
        val asset = dictionary(result["clusterAsset"])
        require(asset["assetID"] == assetId) { "Deployment profile does not match bundled OEM asset" }
        require(asset["assetVer"] is Long || asset["assetVer"] is Int) { "Invalid applied asset version" }
        require((asset["assetVer"] as Number).toLong().let { it == 0L || it == version }) {
            "Applied asset version must be 0 or the bundled content version $version"
        }
        require(result["sdkVersion"] == sdkVersion) { "Deployment SDK must match selected asset requirement $sdkVersion" }
        layout(result["layoutIdentifier"] as? String ?: error("OEM layoutIdentifier missing"))
        require(result["appearanceMode"] == "dark" || cluster["requiresDarkAppearance"] != true) {
            "Selected OEM cluster requires dark appearance"
        }
        return result
    }

    internal fun verifySources(archive: File) = ZipFile(archive).use { zip ->
        dictionary(values["sourceSHA256"]).forEach { (path, expected) ->
            val entry = zip.getEntry(path) ?: error("OEM metadata source missing: $path")
            require(entry.size in 1..4L * 1024 * 1024)
            val actual = zip.getInputStream(entry).use(::digest)
            require(actual == expected) { "OEM metadata source changed: $path; update the bundled descriptor" }
        }
    }

    companion object {
        fun decode(bytes: ByteArray): CarPlayOemThemeDescriptor {
            require(bytes.size in 1..4 * 1024 * 1024) { "OEM descriptor exceeds limit" }
            val result = CarPlayOemThemeDescriptor(dictionary(BplistCodec.decode(bytes)))
            require(result.assetId.isNotBlank() && result.version in 0..0xffff_ffffL)
            CarPlayThemeAssetStore.compareSDKVersions(result.sdkVersion, result.sdkVersion)
            require(result.clusterWidth in 1..8192 && result.clusterHeight in 1..8192)
            require(result.layouts.isNotEmpty() && result.layouts.map { it["id"] }.distinct().size == result.layouts.size)
            return result
        }

        internal fun dictionary(value: Any?): Map<String, Any?> {
            require(value is Map<*, *> && value.keys.all { it is String }) { "Expected OEM dictionary" }
            @Suppress("UNCHECKED_CAST") return value as Map<String, Any?>
        }
        internal fun sequence(value: Any?): List<Any?> = when (value) {
            is List<*> -> value
            is Map<*, *> -> { require(value.isEmpty()) { "Expected OEM array" }; emptyList() }
            null -> emptyList()
            else -> throw IllegalArgumentException("Expected OEM array")
        }
    }
}

data class CarPlayOemScene(val layoutId: String, val appearance: Int = 1,
    val appearancePreference: Int = 0,
    val uiConnected: Boolean = false,
    val transitioning: Boolean = false, val activeComponents: Map<String, String> = emptyMap(),
    val vehicleValues: Map<String, Any?> = emptyMap(),
    val layoutConfirmed: Boolean = false,
    val paletteId: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
    val transitionData: Map<String, Any?> = emptyMap(),
    val focusTokenId: Long? = null,
    val hasFocus: Boolean = false)

/** Android declarative scene host. Full APL/CP and verified telltales are not yet implemented. */
class CarPlayOemThemeRuntime(val descriptor: CarPlayOemThemeDescriptor,
    private val trace: (String) -> Unit = {}) {
    private val displayRuntimes = linkedMapOf<String, CarPlayOemThemeRuntime>()
    /** Separate scenes/queues with the same already-verified archive; no duplicate extraction. */
    @Synchronized fun forDisplay(id: String): CarPlayOemThemeRuntime {
        if (id == descriptor.display["id"]) return this
        check(loaded) { "OEM theme must load before creating a display runtime" }
        return displayRuntimes.getOrPut(id) {
            CarPlayOemThemeRuntime(descriptor.forDisplay(id), trace).also { it.archive = archive }
        }
    }

    fun onDisplayVideoFrame(id: String, presentationTimeUs: Long) {
        val runtime = if (id == descriptor.display["id"]) this
            else synchronized(this) { displayRuntimes[id] }
        runtime?.onVideoFrame(presentationTimeUs)
    }
    private val frameCommands = java.util.ArrayDeque<CarPlayUiSyncCommandPayload>()
    private var lastMetadataPtsUs = 0L
    private var pendingLayoutId: String? = null
    private var requestedLayoutId: String? = null
    private var targetAppearance = 1
    private val frameCommandTypes = setOf(CarPlayUiSyncCommand.METADATA_TRANSFER,
        CarPlayUiSyncCommand.TRANSITION_START, CarPlayUiSyncCommand.TRANSITION_DATA,
        CarPlayUiSyncCommand.TRANSITION_END, CarPlayUiSyncCommand.LAYOUT_CHANGE,
        CarPlayUiSyncCommand.END_LAYOUT_CHANGE)
    @Volatile private var archive: File? = null
    @Volatile var scene = CarPlayOemScene(descriptor.initialLayout, focusTokenId = newFocusToken(), hasFocus = true)
        private set
    @Volatile var listener: ((CarPlayOemScene) -> Unit)? = null
    private val sceneListeners = mutableListOf<(CarPlayOemScene) -> Unit>()
    private val vehicleValuesListeners = mutableListOf<(Map<String, Any?>) -> Unit>()

    /** Session-scoped observers independent of the Activity's replaceable rendering listener. */
    @Synchronized fun addSceneListener(listener: (CarPlayOemScene) -> Unit): () -> Unit {
        sceneListeners.add(listener)
        listener(scene)
        return { synchronized(this) { sceneListeners.removeAll { it === listener }; Unit } }
    }

    /** Independent of the renderer listener; late subscribers receive the latest vehicle snapshot. */
    @Synchronized fun addVehicleValuesListener(listener: (Map<String, Any?>) -> Unit): () -> Unit {
        vehicleValuesListeners.add(listener)
        listener(scene.vehicleValues)
        return { synchronized(this) { vehicleValuesListeners.removeAll { it === listener }; Unit } }
    }
    val loaded: Boolean get() = archive != null
    // The verified bundled archive is declared applied; the full OEM script/rendering contract is still partial.
    val appliedVersion: Long get() = if (loaded) descriptor.version else 0

    @Synchronized fun load(asset: CarPlayStagedThemeAsset) {
        require(asset.assetId == descriptor.assetId && asset.version == descriptor.version) { "OEM runtime asset mismatch" }
        require(asset.minimumSDKVersion == descriptor.sdkVersion)
        require(asset.archive.inputStream().use(::digest) == descriptor.archiveSHA256) { "OEM runtime archive mismatch" }
        descriptor.verifySources(asset.archive)
        archive = asset.archive
        trace("OEM runtime loaded assetID=${descriptor.assetId} contentVersion=${descriptor.version} " +
            "sdkRequirement=${descriptor.sdkVersion} backend=android-declarative-experimental fullAPL=false appliedVersion=$appliedVersion")
        publish(scene)
    }

    @Synchronized fun initialCommands(): List<CarPlayUiSyncCommandPayload> {
        check(loaded) { "OEM theme must load before UI Sync ready" }
        requestedLayoutId = scene.layoutId
        publish(scene.copy(uiConnected = true, layoutConfirmed = false, paletteId = scene.paletteId ?: descriptor.palette(scene.layoutId)))
        // The verified asset sends allowLayoutChange(true) on every UI Sync connect, in both the
        // remote-UI-visible branch (after REQUEST_LAYOUT) and the hidden branch (before
        // REQUEST_APPEARANCE). Without it the phone holds the layout locked and streams a
        // placeholder frame. Workspace.lua:570,576; LayoutController.lua:654.
        return listOf(requestLayout(scene.layoutId), allowLayoutChange(true))
    }

    @Synchronized fun selectInitialLayout(id: String) { descriptor.layout(id); publish(scene.copy(layoutId = id)) }

    /**
     * Accessory-side theme choice while the phone does not own the cluster UI. The next UI Sync
     * requestLayout carries this layout/palette. Refused once UI Sync is connected.
     */
    @Synchronized fun selectLocalTheme(layoutId: String, paletteId: String): Boolean {
        if (scene.uiConnected) return false
        descriptor.validatePalette(layoutId, paletteId)
        publish(scene.copy(layoutId = layoutId, paletteId = paletteId))
        trace("OEM local theme layout=$layoutId style=${descriptor.layout(layoutId)["layoutStyleID"]} palette=$paletteId")
        return true
    }
    @Synchronized fun disconnect() { frameCommands.clear(); lastMetadataPtsUs = 0; pendingLayoutId = null; requestedLayoutId = null; publish(scene.copy(uiConnected = false, transitioning = false,
        layoutConfirmed = false, vehicleValues = emptyMap(), metadata = emptyMap(), transitionData = emptyMap(),
        focusTokenId = newFocusToken(), hasFocus = true)) }

    private fun requestLayout(id: String) = CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.REQUEST_LAYOUT,
        mapOf("layoutID" to id, "paletteID" to (scene.paletteId ?: descriptor.palette(id)), "appearanceMode" to scene.appearance.toLong(),
            "fadeInNewLayout" to true, "fadeOutOldLayout" to true,
            "activeComponents" to descriptor.initialComponents(id)), assetWire = true)

    /** ALLOW_LAYOUT_CHANGE (0x11) with the asset's `data.allow` field. LayoutController.lua:654. */
    private fun allowLayoutChange(allow: Boolean) = CarPlayUiSyncCommandPayload(
        CarPlayUiSyncCommand.ALLOW_TRANSITIONS, mapOf("allow" to allow), assetWire = true)

    @Synchronized fun handle(command: CarPlayUiSyncCommandPayload): List<CarPlayUiSyncCommandPayload> {
        check(loaded && command.assetWire) { "OEM runtime requires loaded theme and numeric commands" }
        if (command.command in frameCommandTypes) {
            require(frameCommands.size < 512) { "OEM transition queue limit exceeded" }
            if (command.command == CarPlayUiSyncCommand.METADATA_TRANSFER)
                require(command.fields["isTransitioning"] is Boolean) { "OEM metadata transition marker missing" }
            if (command.command == CarPlayUiSyncCommand.TRANSITION_START && command.fields["targetComponents"] != null)
                validateLayoutStart(mapOf("layoutID" to scene.layoutId, "activeComponents" to command.fields["targetComponents"]))
            if (command.command == CarPlayUiSyncCommand.TRANSITION_DATA) {
                val pts = transitionPtsUs(command.fields)
                require(command.fields["regions"] is Map<*, *> || command.fields["regions"] is List<*>) {
                    "OEM transition regions missing"
                }
                require(pts > 0 && pts >= lastMetadataPtsUs) { "OEM transition timestamp out of order" }
                lastMetadataPtsUs = pts
            }
            // Validate layout identity/regions at delivery, then commit only on a rendered sender frame.
            if (command.command == CarPlayUiSyncCommand.LAYOUT_CHANGE) validateLayoutStart(command.fields)
            if (command.command == CarPlayUiSyncCommand.END_LAYOUT_CHANGE) {
                val id = command.fields["layoutID"] as? String ?: error("OEM layout completion ID missing")
                descriptor.layout(id)
                val started = frameCommands.lastOrNull { it.command == CarPlayUiSyncCommand.LAYOUT_CHANGE }
                val expected = started?.fields?.get("layoutID") ?: pendingLayoutId
                require(expected == id || (expected == null && isRequestedCompletion(command.fields))) {
                    "OEM layout completion requires matching phone start or requested layout confirmation"
                }
                validateLayoutStart(command.fields)
            }
            if (command.command == CarPlayUiSyncCommand.LAYOUT_CHANGE ||
                command.command == CarPlayUiSyncCommand.END_LAYOUT_CHANGE) {
                validateLayoutAppearance(command.fields)
                if (command.fields.containsKey("pts")) transitionPtsUs(command.fields)
            }
            frameCommands.addLast(command.copy(fields = LinkedHashMap(command.fields)))
            trace("OEM transition queued type=${command.command.wireName} pending=${frameCommands.size}")
            return emptyList()
        }
        return apply(command)
    }

    /** Applies phone metadata against decoded sender PTS, never packet arrival or wall time.
     * LayoutController.lua uses the next video-frame window; timeline parity still needs device QA.
     */
    @Synchronized fun onVideoFrame(presentationTimeUs: Long, frameDurationUs: Long = 33_333) {
        require(presentationTimeUs >= 0 && frameDurationUs in 1..1_000_000)
        val end = presentationTimeUs + frameDurationUs
        while (frameCommands.isNotEmpty()) {
            val next = frameCommands.peekFirst() ?: break
            if (next.fields.containsKey("pts") && transitionPtsUs(next.fields) >= end) break
            frameCommands.removeFirst()
            if (scene.metadata["isTransitioning"] != true && next.command != CarPlayUiSyncCommand.METADATA_TRANSFER &&
                next.command != CarPlayUiSyncCommand.END_LAYOUT_CHANGE) {
                trace("OEM transition discarded outside metadata marker type=${next.command.wireName}")
                continue
            }
            apply(next)
        }
    }

    private fun newFocusToken(): Long = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, 10_001)

    private fun focusToken(data: Map<String, Any?>): Long {
        val token = when (val raw = data["tokenID"]) {
            is Long -> raw
            is Int -> raw.toLong()
            else -> throw IllegalArgumentException("OEM tokenID must be an integer")
        }
        require(token > 0) { "Invalid OEM focus token" }
        return token
    }

    private fun transitionPtsUs(data: Map<String, Any?>): Long {
        val seconds = (data["pts"] as? Number)?.toDouble() ?: error("OEM transition pts missing")
        require(seconds.isFinite() && seconds >= 0 && seconds <= Long.MAX_VALUE / 1_000_000.0) {
            "Invalid OEM transition pts"
        }
        return (seconds * 1_000_000).toLong()
    }

    private fun validateLayoutStart(data: Map<String, Any?>): Pair<String, Map<String, String>> {
        val id = data["layoutID"] as? String ?: error("OEM layoutID missing")
        descriptor.layout(id)
        val active = CarPlayOemThemeDescriptor.dictionary(data["activeComponents"] ?: emptyMap<String, String>())
            .mapValues { (_, value) -> value as? String ?: error("Invalid active component") }
        val allowed = descriptor.remoteComponents(id)
        require(active.all { (zone, component) -> component in (allowed[zone] ?: emptyList()) }) {
            "Unknown OEM remote zone/component"
        }
        return id to active
    }

    private fun isRequestedCompletion(data: Map<String, Any?>): Boolean =
        scene.uiConnected && requestedLayoutId != null && data["layoutID"] == requestedLayoutId &&
            data.containsKey("activeComponents") && data.containsKey("paletteID") && data.containsKey("appearanceMode")

    private fun appearanceMode(raw: Any?): Int {
        require(raw is Long || raw is Int) { "OEM appearance must be an integer" }
        val mode = (raw as Number).toLong()
        require(mode in 0L..1L) { "Invalid OEM appearance mode" }
        return mode.toInt()
    }

    private fun resolvedAppearance(target: Int = targetAppearance, preference: Int = scene.appearancePreference): Int =
        if (descriptor.display["requiresDarkAppearance"] == true || preference == 2) 1
        else if (preference == 1) 0 else target

    private fun validateLayoutAppearance(data: Map<String, Any?>) {
        if (data.containsKey("paletteID")) {
            val palette = data["paletteID"] as? String ?: throw IllegalArgumentException("OEM paletteID missing")
            descriptor.validatePalette(data["layoutID"] as String, palette)
        }
        if (data.containsKey("appearanceMode")) appearanceMode(data["appearanceMode"])
    }

    private fun layoutAppearance(data: Map<String, Any?>, id: String): Pair<String, Int> {
        if (data.containsKey("appearanceMode")) targetAppearance = appearanceMode(data["appearanceMode"])
        val palette = data["paletteID"] as? String ?:
            if (id == scene.layoutId) scene.paletteId ?: descriptor.palette(id) else descriptor.palette(id)
        return palette to resolvedAppearance()
    }

    private fun apply(command: CarPlayUiSyncCommandPayload): List<CarPlayUiSyncCommandPayload> {
        val data = command.fields
        when (command.command) {
            CarPlayUiSyncCommand.LAYOUT_CHANGE -> {
                val (id, active) = validateLayoutStart(data)
                val (palette, appearance) = layoutAppearance(data, id)
                pendingLayoutId = id
                publish(scene.copy(layoutId = id, transitioning = true, layoutConfirmed = false,
                    paletteId = palette, appearance = appearance,
                    activeComponents = active))
            }
            CarPlayUiSyncCommand.END_LAYOUT_CHANGE -> {
                val (id, active) = validateLayoutStart(data)
                require(id == pendingLayoutId || (pendingLayoutId == null && isRequestedCompletion(data))) {
                    "OEM layout completion ID mismatch"
                }
                val (palette, appearance) = layoutAppearance(data, id)
                pendingLayoutId = null
                requestedLayoutId = null
                publish(scene.copy(layoutId = id, paletteId = palette, appearance = appearance,
                    activeComponents = if (data.containsKey("activeComponents")) active else scene.activeComponents,
                    transitionData = if (data.containsKey("regions")) LinkedHashMap(data) else scene.transitionData,
                    transitioning = false, layoutConfirmed = true))
                trace("OEM layout confirmed layoutID=$id activeComponents=${scene.activeComponents} appliedVersion=$appliedVersion")
            }
            CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE -> {
                targetAppearance = appearanceMode(data["appearanceMode"])
                publish(scene.copy(appearance = resolvedAppearance()))
            }
            CarPlayUiSyncCommand.APPEARANCE_PREFERENCE_CHANGE -> {
                val raw = data["preference"]
                require(raw is Long || raw is Int) { "OEM appearance preference must be an integer" }
                val preference = (raw as Number).toLong(); require(preference in 0L..2L)
                publish(scene.copy(appearancePreference = preference.toInt(),
                    appearance = resolvedAppearance(preference = preference.toInt())))
            }
            CarPlayUiSyncCommand.REQUEST_LAYOUT -> return listOf(requestLayout(scene.layoutId))
            CarPlayUiSyncCommand.PALETTE_CHANGE -> {
                val id = data["layoutIdentifier"] as? String ?: error("OEM palette layoutIdentifier missing")
                val palette = data["paletteIdentifier"] as? String ?: error("OEM paletteIdentifier missing")
                descriptor.validatePalette(id, palette)
                require(id == scene.layoutId) { "OEM palette does not target active layout" }
                publish(scene.copy(paletteId = palette))
            }
            CarPlayUiSyncCommand.METADATA_TRANSFER -> {
                val transitioning = data["isTransitioning"] as? Boolean ?: error("OEM metadata transition marker missing")
                publish(scene.copy(metadata = LinkedHashMap(data)))
                if (!transitioning) lastMetadataPtsUs = 0
            }
            CarPlayUiSyncCommand.GIVE_FOCUS -> {
                val token = focusToken(data)
                require(token == scene.focusTokenId) { "OEM focus token does not match local token" }
                require(!scene.hasFocus) { "OEM focus token is already locally owned" }
                publish(scene.copy(hasFocus = true))
            }
            CarPlayUiSyncCommand.REQUEST_FOCUS -> {
                val token = focusToken(data)
                require(scene.hasFocus && token == scene.focusTokenId) { "OEM focus token is not locally owned" }
                publish(scene.copy(hasFocus = false))
                return listOf(CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.GIVE_FOCUS,
                    mapOf("tokenID" to token), assetWire = true))
            }
            CarPlayUiSyncCommand.TRANSITION_DATA -> publish(scene.copy(transitionData = LinkedHashMap(data)))
            CarPlayUiSyncCommand.TRANSITION_START -> {
                val targets = data["targetComponents"]
                if (targets != null) {
                    val (_, active) = validateLayoutStart(mapOf("layoutID" to scene.layoutId, "activeComponents" to targets))
                    publish(scene.copy(activeComponents = active))
                } else trace("OEM settings stream transition preserved implementation=partial fields=${ProtocolTraceFormatter.pretty(data)}")
            }
            CarPlayUiSyncCommand.TRANSITION_END -> {
                if (data["regions"] != null) publish(scene.copy(transitionData = LinkedHashMap(data)))
            }
            CarPlayUiSyncCommand.ALLOW_TRANSITIONS,
            CarPlayUiSyncCommand.DRIVE_MODE_CHANGE -> trace("OEM command preserved type=${command.command.wireName} " +
                "fields=${ProtocolTraceFormatter.pretty(data)} implementation=partial")
            else -> throw IllegalArgumentException("Unconfirmed OEM command ${command.command}")
        }
        return emptyList()
    }

    @Synchronized fun updateVehicleValues(values: Map<String, Any?>) {
        // A provider must supply actual readings. Missing speed/RPM remains unavailable, never 0.
        publish(scene.copy(vehicleValues = LinkedHashMap(scene.vehicleValues).apply { putAll(values) }))
    }

    /** 字体目录只允许读取图集文件，不能执行其中的 LayoutData.lua。 */
    fun readFontResource(identifier: String, filename: String): ByteArray {
        require(filename == "$identifier.png") { "Invalid OEM font filename" }
        val path = descriptor.resourcePath(identifier)
        require(path.startsWith("r/Cluster_Display/Fonts/") && path.substringAfterLast('/') == identifier)
        return readArchiveResource("$path/$filename")
    }

    fun readResource(identifier: String): ByteArray = readArchiveResource(descriptor.resourcePath(identifier))

    private fun readArchiveResource(path: String): ByteArray {
        val file = archive ?: error("OEM runtime not loaded")
        return ZipFile(file).use { zip ->
            val entry = zip.getEntry(path) ?: error("OEM resource missing $path")
            require(entry.size in 1..8L * 1024 * 1024) { "OEM resource exceeds limit" }
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    private fun publish(value: CarPlayOemScene) {
        val vehicleValuesChanged = scene.vehicleValues != value.vehicleValues
        scene = value
        listener?.invoke(value)
        sceneListeners.toList().forEach { it(value) }
        if (vehicleValuesChanged) vehicleValuesListeners.toList().forEach { it(value.vehicleValues) }
    }
}

/** Loads APK resources, verifies/stages the archive, then supplies one coherent default profile. */
object CarPlayOemAssetDeployment {
    const val ASSET_DIRECTORY = "carplay-ultra"
    fun prepare(root: File, openResource: (String) -> InputStream,
        trace: (String) -> Unit = {}): CarPlayOemThemeRuntime {
        val descriptorBytes = openResource("manifest.bplist").use { input ->
            readBounded(input, 4 * 1024 * 1024)
        }
        val descriptor = CarPlayOemThemeDescriptor.decode(descriptorBytes)
        require(root.isDirectory || root.mkdirs())
        val incoming = File.createTempFile("bundled-theme-", ".partial", root)
        try {
            openResource("CarPlayAccessory.assets.zip").use { source -> incoming.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    val count = source.read(buffer); if (count < 0) break
                    total += count; require(total <= CarPlayThemeAssetStore.MAX_ARCHIVE_BYTES)
                    sink.write(buffer, 0, count)
                }
            } }
            fun metadata(name: String) = openResource(name).use {
                readBounded(it, 64 * 1024)
            }
            val header = CarPlayThemeFileHeader("bundled", descriptor.version, 1,
                metadata("Certificate.chain.pem"), metadata("CarPlayAccessory.assets.sha256"), metadata("AccessoryAdditions"))
            val asset = CarPlayThemeAssetStore(File(root, "staged")).stage(incoming, header,
                descriptor.assetId, descriptor.sdkVersion)
            return CarPlayOemThemeRuntime(descriptor, trace).apply { load(asset) }
        } finally { incoming.delete() }
    }
}

private fun digest(input: InputStream): String {
    val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
    while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
    return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(64 * 1024)
    while (true) {
        val size = input.read(buffer); if (size < 0) break
        require(size <= limit - output.size()) { "OEM resource exceeds limit" }
        output.write(buffer, 0, size)
    }
    return output.toByteArray()
}
