package com.shilapi.xcertplay

/**
 * Connection-panel model for one controller attempt.
 *
 * It reads the controller's existing progress records (STEP, IAP2 TX/RX, airplay rx/tx) and
 * groups them into phases and request/response transactions. TRACE records are ignored. All
 * calls must come from one thread; the host feeds it on the main thread.
 */
class HandshakeTimeline(
    val wireless: Boolean,
    val startedAtMillis: Long,
    val previousFailure: String? = null,
) {
    enum class Channel { LOCAL, NETWORK, BLUETOOTH, USB, LOCKDOWN, IAP2, RTSP, STREAM }
    enum class Direction { ACCESSORY_TO_PHONE, PHONE_TO_ACCESSORY, LOCAL }
    enum class State { IDLE, ACTIVE, DONE, FAILED, SKIPPED }
    enum class ChipState { OK, PARTIAL, MISSING }
    enum class LinkState { OFF, LISTENING, CONNECTING, UP, ENCRYPTED, RELEASED, FAILED }
    enum class PhaseKind {
        MFI, HOTSPOT, ADVERTISE, BLUETOOTH, USB, USB_DATA, LOCKDOWN, NETWORK, IAP2, PAIR, SESSION, STREAMS, HANDOFF,
    }
    enum class LinkKind { WIFI, RFCOMM, USB, NCM, AIRPLAY, TUNNEL }

    data class Chip(val label: String, val state: ChipState)
    data class Reply(val status: String, val bytes: Int?, val latencyMillis: Long, val meaning: String?)
    data class Awaiting(val text: String, val sinceMillis: Long)
    data class Failure(val title: String, val detail: String, val atMillis: Long)

    class Phase internal constructor(
        val kind: PhaseKind,
        val label: String,
        val title: String,
        val channel: Channel,
        val concurrent: Boolean,
    ) {
        var state = State.IDLE
            internal set
        var startedAtMillis = 0L
            internal set
    }

    class Link internal constructor(val kind: LinkKind, val label: String) {
        var state = LinkState.OFF
            internal set
    }

    class Row internal constructor(
        val id: Int,
        val atMillis: Long,
        val direction: Direction,
        val channel: Channel,
        val code: String?,
        name: String,
        val bytes: Int?,
        meaning: String,
        val detail: String?,
    ) {
        var name: String = name
            internal set
        var meaning: String = meaning
            internal set
        var chips: List<Chip> = emptyList()
            internal set
        var reply: Reply? = null
            internal set
        var failed = false
            internal set
        var warning = false
            internal set
        var revision = 0
            internal set
    }

    class Group internal constructor(
        val phase: Phase,
        val title: String,
        val tag: String?,
        val channel: Channel,
        val startedAtMillis: Long,
        internal val needsTerminal: Boolean,
    ) {
        val rows = mutableListOf<Row>()
        var state = State.ACTIVE
            internal set
        var endedAtMillis = 0L
            internal set
        var awaiting: Awaiting? = null
            internal set
        internal var lastActivityMillis = startedAtMillis
        internal var terminal = false
        internal val pendingReplies = mutableSetOf<Int>()
    }

    private class PendingRequest(
        val method: String,
        val path: String,
        val cseq: Int,
        val bytes: Int,
        val atMillis: Long,
    ) {
        var isStream = false
        val streams = mutableListOf<StreamProposal>()
        var setupResponse: String? = null
        var commandType: String? = null
    }

    private class StreamProposal(val type: Int?, var clientType: String? = null)
    private class PendingRow(
        val group: Group, val row: Row, val streamType: Int?,
        val responseKnown: Boolean, val streamResponse: String?, val replyMeaning: String?,
    )

    val phases: MutableList<Phase> = (if (wireless) WIRELESS_PHASES else WIRED_PHASES).map {
        Phase(it.kind, it.label, it.title, it.channel, it.concurrent)
    }.toMutableList()
    val links: List<Link> = if (wireless) {
        listOf(Link(LinkKind.WIFI, "Wi-Fi"), Link(LinkKind.RFCOMM, "RFCOMM"), Link(LinkKind.AIRPLAY, "AirPlay"), Link(LinkKind.TUNNEL, "Tunnel"))
    } else {
        listOf(Link(LinkKind.USB, "USB"), Link(LinkKind.NCM, "NCM"), Link(LinkKind.AIRPLAY, "AirPlay"))
    }
    val groups = mutableListOf<Group>()
    var peer: String? = null
        private set
    var failure: Failure? = null
        private set
    var streamCount = 0
        private set
    var latestRow: Row? = null
        private set
    var latestGroup: Group? = null
        private set
    var revision = 0
        private set
    /** When every phase finished, or 0 while the handshake is still running. */
    var completedAtMillis = 0L
        private set

    private var nextRowId = 0
    private val rowsByCseq = mutableMapOf<Int, MutableList<PendingRow>>()
    private val ignoredCseq = mutableSetOf<Int>()
    private var pending: PendingRequest? = null
    private var controlEncrypted = false
    private val updateCounts = mutableMapOf<Group, LinkedHashMap<String, Int>>()

    /** Applies one progress record. Returns true when the panel needs to redraw. */
    fun accept(message: String, atMillis: Long): Boolean {
        if (!isRelevant(message)) return false
        val before = revision
        handle(message.substringBefore('\n'), message, atMillis)
        if (revision != before) {
            refreshGroupStates()
            if (completedAtMillis == 0L && failure == null && phases.all { it.state == State.DONE || it.state == State.SKIPPED }) completedAtMillis = atMillis
        }
        return revision != before
    }

    /** Marks the attempt as failed from a host-level event such as a transport error. */
    fun fail(title: String, detail: String, atMillis: Long) {
        if (failure != null) return
        flushPending()
        failure = Failure(title, detail, atMillis)
        val group = latestGroup
        val phase = group?.phase ?: phases.firstOrNull { it.state == State.ACTIVE } ?: phases.first()
        phase.state = State.FAILED
        if (group != null && group.phase === phase) group.state = State.FAILED
        phases.forEach { if (it !== phase && it.state != State.DONE) it.state = State.SKIPPED }
        links.forEach { if (it.state == LinkState.CONNECTING) it.state = LinkState.FAILED }
        groups.forEach { it.awaiting = null }
        touch()
    }

    /** The newest unresolved wait in a phase that is still running. */
    fun currentAwaiting(): Pair<Group, Awaiting>? {
        if (failure != null) return null
        return groups.asSequence()
            .filter { it.state == State.ACTIVE && it.phase.state == State.ACTIVE && it.awaiting != null }
            .filter { group -> groups.lastOrNull { it.phase === group.phase } === group }
            .maxByOrNull { it.awaiting!!.sinceMillis }
            ?.let { it to it.awaiting!! }
    }

    private fun handle(line: String, full: String, at: Long) {
        when {
            line.startsWith("STEP ") -> step(line.removePrefix("STEP "), at)
            line.startsWith("ERROR ") -> fail("Connection failed", line.removePrefix("ERROR ").trim(), at)
            line.startsWith("IAP2 TX [") || line.startsWith("IAP2 RX [") -> iap2Frame(line, full, at)
            line.startsWith("IAP2 READY [") -> iap2Ready(line, at)
            line.startsWith("IAP2 CLOSE [") -> iap2Close(line, at)
            line.startsWith("airplay rx ") -> rtspRequest(line, at)
            line.startsWith("airplay tx status=") -> rtspReply(line, at)
            line.startsWith("airplay SETUP keys=") -> {
                val streams = line.substringAfter("keys=").removePrefix("[").substringBefore(']')
                    .split(',').any { it.trim() == "streams" }
                pending?.isStream = streams
                if (!streams) flushPending()
            }
            line.startsWith("airplay SETUP stream type=") -> setupStream(line)
            line.startsWith("AirPlay RCS SETUP clientType=") -> {
                pending?.streams?.lastOrNull()?.clientType = line.removePrefix("AirPlay RCS SETUP clientType=").substringBefore(' ')
            }
            line.startsWith("airplay SETUP response ") -> pending?.setupResponse = line
            line.startsWith("airplay command type=") -> {
                pending?.commandType = line.removePrefix("airplay command type=").substringBefore(' ')
                flushPending()
            }
            line.startsWith("airplay SETUP feature proposal ") -> features(line, at)
            line.startsWith("Ultra UI negotiation ") -> ultraUi(line, at)
            line.startsWith("AirPlay device info ") -> deviceInfo(line)
            line.contains("rx=0x4300 availability=") -> availability(line)
            else -> local(line, at)
        }
    }

    private fun step(body: String, at: Long) {
        val key = body.substringBefore(':')
        val detail = body.substringAfter(": ", "")
        when (key) {
            "mfi/start" -> localRow(PhaseKind.MFI, G_MFI, "Preparing the MFi authentication provider", at)
            "mfi/wait" -> {
                localRow(PhaseKind.MFI, G_MFI, "MFi coprocessor not present; polling", at)
                await("Waiting for the MFi coprocessor", at)
            }
            "mfi/permission" -> {
                localRow(PhaseKind.MFI, G_MFI, "Requesting CH341 USB access", at)
                await("Waiting for CH341 USB permission", at)
            }
            "mfi/ready" -> {
                localRow(PhaseKind.MFI, G_MFI, "MFi authentication provider ready", at)
                complete(PhaseKind.MFI)
            }
            "wifi/ap" -> {
                localRow(PhaseKind.HOTSPOT, G_HOTSPOT, "Starting the CarPlay access point", at)
                link(LinkKind.WIFI, LinkState.CONNECTING)
                await("Starting the access point", at)
            }
            "wifi/ap-ready" -> {
                val ssid = field(detail, "ssid", "band")
                val band = field(detail, "band", "channel")
                val address = field(detail, "address", null)
                localRow(PhaseKind.HOTSPOT, G_HOTSPOT, listOfNotNull("Access point ready", ssid, band, address).joinToString(" · "), at)
                link(LinkKind.WIFI, LinkState.UP)
                complete(PhaseKind.HOTSPOT)
            }
            "bt/select" -> {
                localRow(PhaseKind.BLUETOOTH, G_FIND_IPHONE, "Looking for a paired iPhone", at)
                await("Waiting for a paired iPhone", at)
            }
            "network/attach" -> if (wireless) {
                localRow(PhaseKind.ADVERTISE, G_ADVERTISE, "Attaching the AirPlay network transport", at)
            } else {
                localRow(PhaseKind.NETWORK, G_NCM, "Attaching the NCM network transport", at)
                link(LinkKind.NCM, LinkState.CONNECTING)
                await("Waiting for the VPN service (10 s timeout)", at)
            }
            "handoff/complete" -> {
                localRow(PhaseKind.HANDOFF, G_HANDOFF, "Handoff complete: iAP2 now runs over Wi-Fi", at)
                complete(PhaseKind.HANDOFF)
            }
            "usb/discover" -> {
                localRow(PhaseKind.USB, G_FIND_IPHONE, "Looking for an iPhone on USB", at)
                link(LinkKind.USB, LinkState.CONNECTING)
            }
            "usb/wait" -> await("Waiting for an iPhone on USB", at)
            "usb/permission" -> {
                localRow(PhaseKind.USB, G_USB_PERMISSION, "Requesting USB access to the iPhone", at)
                await("Waiting for USB permission", at)
            }
            "usb/reenum" -> {
                localRow(PhaseKind.USB, G_USB_CONFIG, "Switching to the CarPlay USB configuration", at)
                await("Waiting for the iPhone to re-enumerate", at)
            }
            "usb/config" -> localRow(PhaseKind.USB, G_USB_CONFIG, "Selecting the CarPlay USB configuration", at)
            "usb/data" -> localRow(PhaseKind.USB_DATA, G_USB_DATA, "Opening the iAP2 and NCM USB interfaces", at)
            "lockdown/pair" -> localRow(PhaseKind.LOCKDOWN, G_PAIR_RECORD, "Loading or creating the pairing record", at)
            "lockdown/carkit" -> {
                localRow(PhaseKind.LOCKDOWN, G_CARKIT, "Opening com.apple.carkit.service", at)
                await("Waiting for the CarKit service", at)
            }
            "iap2/wired" -> localRow(PhaseKind.IAP2, G_IDENT, "Wired iAP2 control running", at)
            "control/end" -> latestGroup?.let { group ->
                localRow(group.phase.kind, group.title, "Control window ended", at)?.warning = true
            }
        }
    }

    private fun local(line: String, at: Long) {
        when {
            line.startsWith("mfi local documents ready") ->
                localRow(PhaseKind.MFI, G_MFI, "Local certificate files ready", at)
            line.startsWith("wireless hotspot backend=") -> {
                val backend = field(line, "backend", "iface")
                val iface = field(line, "iface", "host")
                localRow(PhaseKind.HOTSPOT, G_HOTSPOT, listOfNotNull(backend, iface).joinToString(" · "), at)
            }
            line.startsWith("wireless Bluetooth PHONE_SMART candidates=") -> {
                val count = line.removePrefix("wireless Bluetooth PHONE_SMART candidates=").substringBefore(' ')
                localRow(PhaseKind.BLUETOOTH, G_FIND_IPHONE, "Paired phones found: $count", at)
            }
            line.startsWith("wireless AirPlay listener attached") -> {
                val port = field(line, "port", null) ?: "7000"
                localRow(PhaseKind.ADVERTISE, G_ADVERTISE, "AirPlay listening on port $port", at)
                link(LinkKind.AIRPLAY, LinkState.LISTENING)
            }
            line.startsWith("wireless Bonjour services started") -> {
                localRow(PhaseKind.ADVERTISE, G_ADVERTISE, "Bonjour _airplay._tcp advertised", at)
                complete(PhaseKind.ADVERTISE)
            }
            line.startsWith("wireless RFCOMM attempt=") -> {
                val attempt = field(line, "attempt", "name")
                val name = field(line, "name", "address")
                val address = field(line, "address", "uuid")
                val uuid = field(line, "uuid", null)
                if (peer == null && name != null) peer = name
                messageRow(
                    PhaseKind.BLUETOOTH, G_RFCOMM, Direction.ACCESSORY_TO_PHONE, Channel.BLUETOOTH,
                    "RFCOMM", "CONNECT", null, "Connect to the iPhone iAP2 service · attempt $attempt",
                    listOfNotNull(name?.let { "Device: $it" }, address?.let { "Address: ${maskMac(it)}" }, uuid?.let { "Service UUID: $it" })
                        .joinToString("\n").ifEmpty { null },
                    at,
                )
                link(LinkKind.RFCOMM, LinkState.CONNECTING)
                await("Waiting for iPhone to accept RFCOMM (15 s timeout)", at)
            }
            line.startsWith("wireless RFCOMM connected") -> {
                messageRow(
                    PhaseKind.BLUETOOTH, G_RFCOMM, Direction.PHONE_TO_ACCESSORY, Channel.BLUETOOTH,
                    "RFCOMM", "ACCEPT", null, "Bluetooth serial channel open", null, at,
                )
                link(LinkKind.RFCOMM, LinkState.UP)
                complete(PhaseKind.BLUETOOTH)
            }
            line.startsWith("wireless iAP2 CSM channel opened") ->
                localRow(PhaseKind.IAP2, G_IDENT, "iAP2 channel opened on RFCOMM", at)
            line.startsWith("wireless type-130 tunnel data stream accepted") -> {
                localRow(PhaseKind.HANDOFF, G_TUNNEL_STREAM, "Tunnel data stream connected", at)
                link(LinkKind.TUNNEL, LinkState.CONNECTING)
            }
            line.startsWith("wireless CarPlay Bluetooth handoff requested") ->
                awaitIn(PhaseKind.HANDOFF, G_HANDOFF, "Waiting for tunnel iAP2 before releasing Bluetooth", at)
            line.startsWith("wireless iAP2 tunnel ready") -> {
                localRow(PhaseKind.HANDOFF, G_HANDOFF, "Tunnel iAP2 ready", at)
                link(LinkKind.TUNNEL, LinkState.UP)
            }
            line.startsWith("wireless handoff ready; closing Bluetooth") ->
                localRow(PhaseKind.HANDOFF, G_HANDOFF, "Closing the Bluetooth bootstrap channel", at)
            line.startsWith("wireless RFCOMM EOF") -> {
                val handoff = phases.any { it.kind == PhaseKind.HANDOFF }
                messageRow(
                    if (handoff) PhaseKind.HANDOFF else PhaseKind.BLUETOOTH,
                    if (handoff) G_HANDOFF else G_RFCOMM,
                    Direction.PHONE_TO_ACCESSORY, Channel.BLUETOOTH,
                    "RFCOMM", "EOF", null, "iPhone closed the Bluetooth channel", null, at,
                )
            }
            line.startsWith("airplay control encryption enabled") -> {
                localRow(PhaseKind.PAIR, G_PAIR_VERIFY, "Control channel encrypted (ChaCha20-Poly1305)", at)
                controlEncrypted = true
                link(LinkKind.AIRPLAY, LinkState.ENCRYPTED)
            }
            line.startsWith("airplay event connection accepted") -> {
                messageRow(
                    PhaseKind.SESSION, G_SESSION_SETUP, Direction.PHONE_TO_ACCESSORY, Channel.STREAM,
                    "TCP", "Event channel", null, "Touch and button input channel", null, at,
                )
                streamCount++
            }
            line.startsWith("AirPlay session active controller=") -> {
                localRow(PhaseKind.SESSION, G_RECORD, "AirPlay session active", at)
                complete(PhaseKind.SESSION)
            }
            line.startsWith("wired USBMUX host opened") -> {
                localRow(PhaseKind.USB_DATA, G_USBMUX, "USBMUX v2 host open", at)
                link(LinkKind.USB, LinkState.UP)
                complete(PhaseKind.USB_DATA)
            }
            line.startsWith("wired using saved Lockdown pair record") ->
                localRow(PhaseKind.LOCKDOWN, G_PAIR_RECORD, "Using the saved pairing record", at)
            line.startsWith("wired created a new Lockdown pair record") ->
                localRow(PhaseKind.LOCKDOWN, G_PAIR_RECORD, "Created a new pairing record", at)
            line.startsWith("saved Lockdown pair record rejected") ->
                localRow(PhaseKind.LOCKDOWN, G_PAIR_RECORD, "Saved pairing record rejected; pairing again", at)?.warning = true
            line.startsWith("wired com.apple.carkit.service stream opened") ->
                localRow(PhaseKind.LOCKDOWN, G_CARKIT, "CarKit service stream open", at)
            line.startsWith("wired iAP2 CSM channel opened") -> {
                localRow(PhaseKind.LOCKDOWN, G_CARKIT, "iAP2 channel opened on CarKit", at)
                complete(PhaseKind.LOCKDOWN)
            }
            line.startsWith("wired NCM/VPN AirPlay transport attached") -> {
                localRow(PhaseKind.NETWORK, G_NCM, "NCM network attached; AirPlay on port 7000", at)
                link(LinkKind.NCM, LinkState.UP)
                link(LinkKind.AIRPLAY, LinkState.LISTENING)
                complete(PhaseKind.NETWORK)
            }
        }
    }

    private fun iap2Frame(line: String, full: String, at: Long) {
        val match = IAP2_FRAME.find(line) ?: return
        val outgoing = match.groupValues[1] == "TX"
        val context = match.groupValues[2]
        val code = match.groupValues[3].lowercase()
        val id = code.removePrefix("0x").toInt(16)
        val name = match.groupValues[4].ifBlank { code }
        val bytes = match.groupValues[5].toIntOrNull() ?: return
        val phaseKind = iap2Phase(context)
        val tag = iap2Tag(context)
        val updateLabel = UPDATE_LABELS[id]
        if (!outgoing && updateLabel != null) {
            liveUpdate(phaseKind, tag, updateLabel, at)
            return
        }
        val spec = IAP2_MEANINGS[id]
        val title = iap2GroupTitle(id)
        val meaning = when (id) {
            START_SESSION -> if (wireless) "Join the hotspot and connect to AirPlay" else "Connect to AirPlay over the USB network"
            else -> spec?.meaning ?: name
        }
        val row = messageRow(
            phaseKind, title,
            if (outgoing) Direction.ACCESSORY_TO_PHONE else Direction.PHONE_TO_ACCESSORY,
            Channel.IAP2, code, name, bytes, meaning, protocolDetail(full), at,
            tag = tag,
        ) ?: return
        val group = latestGroup ?: return
        when {
            id == IDENTIFICATION_ACCEPTED || id == AUTHENTICATION_SUCCEEDED -> group.terminal = true
            id == START_SESSION && outgoing -> {
                group.terminal = true
                if (phaseKind == PhaseKind.IAP2) {
                    await(if (wireless) "Waiting for iPhone to join Wi-Fi and connect to port 7000" else "Waiting for iPhone to connect to AirPlay", at)
                    complete(PhaseKind.IAP2)
                }
            }
            spec?.failure != null -> {
                row.failed = true
                fail(spec.failure, "$code $name", at)
            }
            outgoing && spec?.awaiting != null -> await(spec.awaiting, at)
        }
    }

    private fun liveUpdate(phaseKind: PhaseKind, tag: String, label: String, at: Long) {
        val phase = phase(phaseKind) ?: return
        val group = groups.lastOrNull { it.phase === phase && it.title == G_UPDATES }
        if (group == null) {
            // Pushed updates arrive throughout the session; they must not take over the current step.
            val previousRow = latestRow
            val previousGroup = latestGroup
            messageRow(
                phaseKind, G_UPDATES, Direction.PHONE_TO_ACCESSORY, Channel.IAP2, "0x…",
                "$label ×1", null, "Subscribed data pushed by iPhone", null, at, tag = tag,
            ) ?: return
            groups.lastOrNull()?.let { updateCounts[it] = linkedMapOf(label to 1) }
            latestRow = previousRow ?: latestRow
            latestGroup = previousGroup ?: latestGroup
            return
        }
        val counts = updateCounts.getOrPut(group) { linkedMapOf() }
        counts[label] = (counts[label] ?: 0) + 1
        group.rows.firstOrNull()?.let { row ->
            row.name = counts.entries.joinToString(" · ") { "${it.key} ×${it.value}" }
            row.revision++
        }
        group.lastActivityMillis = at
        touch()
    }

    private fun iap2Ready(line: String, at: Long) {
        val context = line.substringAfter('[').substringBefore(']')
        val initiator = if (context == "wireless-rfcomm") "iPhone" else "accessory"
        localRow(iap2Phase(context), G_IDENT, "iAP2 link synchronized ($initiator initiated)", at, tag = iap2Tag(context))
    }

    private fun iap2Close(line: String, at: Long) {
        val context = line.substringAfter('[').substringBefore(']')
        if (context == "wireless-rfcomm") {
            link(LinkKind.RFCOMM, LinkState.RELEASED)
            val handoff = phases.any { it.kind == PhaseKind.HANDOFF }
            localRow(
                if (handoff) PhaseKind.HANDOFF else PhaseKind.BLUETOOTH,
                if (handoff) G_HANDOFF else G_RFCOMM,
                "iAP2 over Bluetooth closed", at,
            )
        } else {
            latestGroup?.let { localRow(it.phase.kind, it.title, "iAP2 channel closed ($context)", at)?.warning = true }
        }
    }

    private fun rtspRequest(line: String, at: Long) {
        val match = RTSP_REQUEST.find(line) ?: return
        flushPending()
        val method = match.groupValues[1]
        val path = match.groupValues[2]
        val cseq = match.groupValues[3].toIntOrNull() ?: return
        val bytes = match.groupValues[4].toIntOrNull() ?: return
        val airPlay = links.first { it.kind == LinkKind.AIRPLAY }
        if (airPlay.state == LinkState.OFF || airPlay.state == LinkState.LISTENING) link(LinkKind.AIRPLAY, LinkState.UP)
        when {
            path.endsWith("/feedback") -> ignoredCseq += cseq
            method == "SETUP" || path.endsWith("/command") -> pending = PendingRequest(method, path, cseq, bytes, at)
            else -> addRtspRow(PendingRequest(method, path, cseq, bytes, at))
        }
    }

    private fun setupStream(line: String) {
        val request = pending ?: return
        request.isStream = true
        val type = line.removePrefix("airplay SETUP stream type=").substringBefore(' ').toIntOrNull()
        val tunnel = line.contains(IAP_DATASTREAM_UUID, ignoreCase = true)
        request.streams += StreamProposal(type, if (tunnel) "iAPChannel" else null)
    }

    private fun flushPending() {
        val request = pending ?: return
        pending = null
        if (request.isStream && request.streams.isNotEmpty()) {
            val responses = request.setupResponse?.let { STREAM_RESPONSE.findAll(it).map { it.value }.toMutableList() }
            request.streams.forEach { stream ->
                val index = responses?.indexOfFirst { STREAM_TYPE.find(it)?.groupValues?.get(1)?.toIntOrNull() == stream.type } ?: -1
                val response = if (index >= 0) responses?.removeAt(index) else null
                addRtspRow(request, stream, response)
            }
        } else addRtspRow(request)
    }

    private fun addRtspRow(request: PendingRequest, stream: StreamProposal? = null, streamResponse: String? = null) {
        val path = request.path
        val pairIndex = { title: String -> groups.lastOrNull { it.title == title }?.rows?.count { it.code != null } ?: 0 }
        val target: RtspTarget = when {
            path.endsWith("/pair-setup") -> {
                val step = pairIndex(G_PAIR_SETUP)
                RtspTarget(PhaseKind.PAIR, G_PAIR_SETUP, "/pair-setup", PAIR_SETUP_STEPS.getOrElse(step) { "Pair-setup message" },
                    PAIR_SETUP_REPLIES.getOrNull(step))
            }
            path.endsWith("/pair-verify") -> {
                val step = pairIndex(G_PAIR_VERIFY)
                RtspTarget(PhaseKind.PAIR, G_PAIR_VERIFY, "/pair-verify", PAIR_VERIFY_STEPS.getOrElse(step) { "Pair-verify message" },
                    PAIR_VERIFY_REPLIES.getOrNull(step))
            }
            path.endsWith("/auth-setup") -> RtspTarget(PhaseKind.PAIR, G_AUTH_SETUP, "/auth-setup",
                "iPhone asks for MFi authentication", "MFi certificate and signature")
            path.endsWith("/info") -> RtspTarget(PhaseKind.SESSION, G_INFO, "/info",
                "iPhone asks for the accessory capabilities", "Displays, audio formats and features")
            request.method == "RECORD" -> RtspTarget(PhaseKind.SESSION, G_RECORD, "session",
                "iPhone starts the session", "Session active")
            request.method == "SETUP" && !request.isStream -> RtspTarget(PhaseKind.SESSION, G_SESSION_SETUP, "session",
                "Device info, feature list and timing port", "Event and timing ports")
            request.method == "SETUP" -> {
                val type = stream?.type
                val client = stream?.clientType
                val label = if (type == DATA_STREAM && client != null) client else STREAM_LABELS[type] ?: "stream"
                val meaning = if (type == DATA_STREAM) RCS_MEANINGS[client] ?: "Data stream" else STREAM_MEANINGS[type] ?: "Media stream"
                val tunnel = wireless && client == "iAPChannel"
                RtspTarget(if (tunnel) PhaseKind.HANDOFF else PhaseKind.STREAMS, if (tunnel) G_TUNNEL_STREAM else G_STREAMS,
                    "type ${type ?: "?"} · $label", meaning, null)
            }
            path.endsWith("/command") -> {
                val type = request.commandType ?: "command"
                if (wireless && type == "disableBluetooth") {
                    RtspTarget(PhaseKind.HANDOFF, G_HANDOFF, "/command · $type", "Release Bluetooth; move iAP2 into the tunnel", "Accepted")
                } else {
                    RtspTarget(PhaseKind.STREAMS, G_COMMANDS, "/command · $type", COMMAND_MEANINGS[type] ?: "Session command", "Accepted")
                }
            }
            request.method == "TEARDOWN" -> RtspTarget(PhaseKind.STREAMS, G_STREAMS, "session", "iPhone closes a stream", "Closed")
            else -> RtspTarget(PhaseKind.SESSION, G_OTHER, path, "${request.method} request", null)
        }
        val tag = if (!wireless) "USB network" else if (controlEncrypted) "Encrypted" else "TCP 7000"
        val row = messageRow(
            target.phase, target.group, Direction.PHONE_TO_ACCESSORY, Channel.RTSP,
            request.method, target.name, request.bytes, target.meaning,
            if (request.streams.size > 1) "Shared SETUP cseq=${request.cseq}: ${request.streams.size} streams; byte counts describe the complete request and reply" else null,
            request.atMillis, tag = tag,
        ) ?: return
        val group = latestGroup ?: return
        group.pendingReplies += request.cseq
        rowsByCseq.getOrPut(request.cseq) { mutableListOf() } += PendingRow(
            group, row, stream?.type, request.setupResponse != null, streamResponse, target.replyMeaning,
        )
    }

    private fun rtspReply(line: String, at: Long) {
        val match = RTSP_REPLY.find(line) ?: return
        val status = match.groupValues[1]
        val cseq = match.groupValues[2].toIntOrNull() ?: return
        val bytes = match.groupValues[3].toIntOrNull() ?: return
        if (ignoredCseq.remove(cseq)) return
        if (pending?.cseq == cseq) flushPending()
        val replies = rowsByCseq.remove(cseq) ?: return
        replies.forEach { pendingRow ->
            val group = pendingRow.group
            val row = pendingRow.row
            val accepted = status == "200" &&
                (pendingRow.streamType == null || !pendingRow.responseKnown || pendingRow.streamResponse != null)
            val ports = pendingRow.streamResponse?.let { response ->
                listOfNotNull(
                    DATA_PORT.find(response)?.let { "dataPort ${it.groupValues[1]}" },
                    CONTROL_PORT.find(response)?.let { "controlPort ${it.groupValues[1]}" },
                    STREAM_ID.find(response)?.let { "streamID ${it.groupValues[1]}" },
                ).joinToString(" · ").ifEmpty { null }
            }
            val meaning = if (status == "200" && !accepted) "Stream not accepted" else ports ?: pendingRow.replyMeaning
            row.reply = Reply(status, bytes, (at - row.atMillis).coerceAtLeast(0), meaning)
            row.failed = !accepted
            if (accepted && pendingRow.streamType != null) {
                streamCount++
                if (pendingRow.streamType == MAIN_SCREEN) {
                    complete(PhaseKind.STREAMS)
                    // A working main screen also proves earlier optional phases were passed.
                    val index = phases.indexOfFirst { it.kind == PhaseKind.STREAMS }
                    phases.take(index).forEach {
                        if (it.state == State.IDLE) it.state = State.SKIPPED
                        else if (it.state == State.ACTIVE) it.state = State.DONE
                    }
                }
            }
            row.revision++
            group.pendingReplies -= cseq
            group.lastActivityMillis = at
            touch()
        }
    }

    private fun features(line: String, at: Long) {
        val match = FEATURES.find(line) ?: return
        val requested = list(match.groupValues[1])
        val supported = list(match.groupValues[2])
        val enabled = list(match.groupValues[3])
        val notRequested = supported.filter { it !in requested && it !in enabled }
        val unsupported = requested.filter { it !in supported && it !in enabled }
        val row = localRow(
            PhaseKind.SESSION, G_SESSION_SETUP,
            "Features: ${enabled.size} enabled, ${notRequested.size} not requested, ${unsupported.size} unsupported", at,
        ) ?: return
        row.chips = enabled.map { Chip(it, ChipState.OK) } +
            notRequested.map { Chip(it, ChipState.PARTIAL) } +
            unsupported.map { Chip(it, ChipState.MISSING) }
    }

    private fun ultraUi(line: String, at: Long) {
        val requested = line.contains("uiSyncRequested=true")
        val accepted = line.contains("uiSyncAccepted=true")
        val text = when {
            accepted -> "CarPlay Ultra UI ready"
            requested -> "CarPlay Ultra UI not ready: uiSync was not accepted"
            else -> "CarPlay Ultra UI not ready: iPhone did not request uiSync"
        }
        localRow(PhaseKind.SESSION, G_SESSION_SETUP, text, at)?.warning = !accepted
    }

    private fun deviceInfo(line: String) {
        val name = field(line, "name", "deviceId")
        val model = field(line, "model", null)
        peer = listOfNotNull(name, model).joinToString(" · ").ifEmpty { peer }
        touch()
    }

    private fun availability(line: String) {
        val row = groups.asReversed().asSequence().flatMap { it.rows.asReversed().asSequence() }
            .firstOrNull { it.code == "0x4300" } ?: return
        row.chips = AVAILABILITY.findAll(line).map {
            Chip(AVAILABILITY_LABELS[it.groupValues[1]] ?: it.groupValues[1],
                if (it.groupValues[2] == "true") ChipState.OK else ChipState.MISSING)
        }.toList()
        row.revision++
        touch()
    }

    private fun localRow(kind: PhaseKind, title: String, text: String, at: Long, tag: String? = null): Row? =
        messageRow(kind, title, Direction.LOCAL, phase(kind)?.channel ?: Channel.LOCAL, null, text, null, text, null, at, tag = tag)

    private fun messageRow(
        kind: PhaseKind,
        title: String,
        direction: Direction,
        channel: Channel,
        code: String?,
        name: String,
        bytes: Int?,
        meaning: String,
        detail: String?,
        at: Long,
        tag: String? = null,
    ): Row? {
        val phase = phase(kind) ?: return null
        val group = groups.lastOrNull { it.phase === phase && it.title == title }
            ?: Group(phase, title, tag, if (direction == Direction.LOCAL) phase.channel else channel, at, title in TERMINAL_GROUPS)
                .also { groups += it }
        if (group.state == State.DONE) group.state = State.ACTIVE
        val row = Row(nextRowId++, at, direction, channel, code, name, bytes, meaning, detail)
        group.rows += row
        group.lastActivityMillis = at
        group.awaiting = null
        latestRow = row
        latestGroup = group
        startPhase(phase, at)
        touch()
        return row
    }

    private fun startPhase(phase: Phase, at: Long) {
        if (phase.state == State.IDLE) {
            phase.state = State.ACTIVE
            phase.startedAtMillis = at
        }
        val index = phases.indexOf(phase)
        phases.forEachIndexed { i, earlier ->
            if (i < index && earlier.state == State.ACTIVE && !earlier.concurrent) earlier.state = State.DONE
        }
    }

    private fun complete(kind: PhaseKind) {
        val phase = phase(kind) ?: return
        if (phase.state == State.FAILED) return
        phase.state = State.DONE
        touch()
    }

    private fun await(text: String, at: Long) {
        latestGroup?.awaiting = Awaiting(text, at)
        touch()
    }

    private fun awaitIn(kind: PhaseKind, title: String, text: String, at: Long) {
        val group = groups.lastOrNull { it.phase.kind == kind && it.title == title } ?: return
        group.awaiting = Awaiting(text, at)
        touch()
    }

    private fun link(kind: LinkKind, state: LinkState) {
        val link = links.firstOrNull { it.kind == kind } ?: return
        if (link.state != state) {
            link.state = state
            touch()
        }
    }

    private fun refreshGroupStates() {
        groups.forEachIndexed { index, group ->
            if (group.state == State.FAILED) return@forEachIndexed
            val newer = index < groups.lastIndex
            val done = group.phase.state == State.DONE ||
                group.terminal ||
                (!group.needsTerminal && group.pendingReplies.isEmpty() && newer)
            val next = if (done) State.DONE else State.ACTIVE
            if (group.state != next) {
                group.state = next
                if (done) {
                    group.endedAtMillis = group.lastActivityMillis
                    group.awaiting = null
                }
            }
        }
    }

    private fun phase(kind: PhaseKind): Phase? {
        phases.firstOrNull { it.kind == kind }?.let { return it }
        if (!wireless || kind != PhaseKind.HANDOFF) return null
        return Phase(PhaseKind.HANDOFF, "Handoff", "Tunnel handoff", Channel.IAP2, concurrent = true).also {
            phases += it
            completedAtMillis = 0L
        }
    }

    private fun iap2Phase(context: String): PhaseKind =
        if (context == "wireless-tunnel") PhaseKind.HANDOFF else PhaseKind.IAP2

    private fun iap2Tag(context: String): String = when (context) {
        "wireless-rfcomm" -> "Bluetooth"
        "wireless-tunnel" -> "Tunnel"
        "wired" -> "CarKit"
        else -> context
    }

    private fun touch() {
        revision++
    }

    private data class PhaseSpec(
        val kind: PhaseKind,
        val label: String,
        val title: String,
        val channel: Channel,
        val concurrent: Boolean = false,
    )

    private data class RtspTarget(
        val phase: PhaseKind,
        val group: String,
        val name: String,
        val meaning: String,
        val replyMeaning: String?,
    )

    private data class Iap2Meaning(val meaning: String, val awaiting: String? = null, val failure: String? = null)

    companion object {
        /** Cheap pre-filter for the worker thread, so TRACE floods never reach the main thread. */
        fun isRelevant(message: String): Boolean {
            if (message.startsWith("TRACE ") || message.startsWith("audio ")) return false
            return RELEVANT_PREFIXES.any { message.startsWith(it) } || message.contains("rx=0x4300 availability=")
        }

        private val RELEVANT_PREFIXES = listOf(
            "STEP ", "ERROR ", "IAP2 ", "airplay ", "AirPlay ", "wireless ", "wired ", "mfi local ",
            "Ultra UI negotiation ", "saved Lockdown pair record",
        )

        const val G_MFI = "MFi provider"
        const val G_HOTSPOT = "Access point"
        const val G_ADVERTISE = "AirPlay service"
        const val G_FIND_IPHONE = "Find iPhone"
        const val G_RFCOMM = "RFCOMM connection"
        const val G_IDENT = "Link and identification"
        const val G_AUTH = "MFi authentication"
        const val G_SUBSCRIPTIONS = "Subscriptions"
        const val G_WIFI = "Wi-Fi credentials"
        const val G_START = "Start CarPlay"
        const val G_LOCATION = "Location"
        const val G_UPDATES = "Live updates"
        const val G_OTHER_IAP2 = "Other iAP2 messages"
        const val G_PAIR_SETUP = "Pairing · pair-setup"
        const val G_PAIR_VERIFY = "Verification · pair-verify"
        const val G_AUTH_SETUP = "MFi · auth-setup"
        const val G_SESSION_SETUP = "Session setup"
        const val G_INFO = "Capabilities · /info"
        const val G_RECORD = "Start session · RECORD"
        const val G_STREAMS = "Media and data streams"
        const val G_COMMANDS = "Session commands"
        const val G_OTHER = "Other requests"
        const val G_TUNNEL_STREAM = "Tunnel stream"
        const val G_HANDOFF = "Bluetooth handoff"
        const val G_USB_PERMISSION = "USB permission"
        const val G_USB_CONFIG = "CarPlay configuration"
        const val G_USB_DATA = "USB interfaces"
        const val G_USBMUX = "USBMUX"
        const val G_PAIR_RECORD = "Pairing record"
        const val G_CARKIT = "CarKit service"
        const val G_NCM = "USB network"

        /** Groups that stay open until an explicit success message, even when newer groups start. */
        private val TERMINAL_GROUPS = setOf(G_IDENT, G_AUTH, G_START, G_HANDOFF)

        private const val IDENTIFICATION_ACCEPTED = 0x1d02
        private const val AUTHENTICATION_SUCCEEDED = 0xaa05
        private const val START_SESSION = 0x4301
        private const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
        private const val DATA_STREAM = 130
        private const val MAIN_SCREEN = 110

        private val WIRELESS_PHASES = listOf(
            PhaseSpec(PhaseKind.MFI, "MFi", "MFi provider", Channel.LOCAL),
            PhaseSpec(PhaseKind.HOTSPOT, "Hotspot", "Wi-Fi hotspot", Channel.NETWORK),
            PhaseSpec(PhaseKind.ADVERTISE, "Bonjour", "AirPlay service", Channel.NETWORK),
            PhaseSpec(PhaseKind.BLUETOOTH, "BT", "Bluetooth RFCOMM", Channel.BLUETOOTH),
            PhaseSpec(PhaseKind.IAP2, "iAP2", "iAP2 over Bluetooth", Channel.IAP2),
            PhaseSpec(PhaseKind.PAIR, "Pair", "AirPlay pairing", Channel.RTSP),
            PhaseSpec(PhaseKind.SESSION, "Session", "AirPlay session", Channel.RTSP),
            PhaseSpec(PhaseKind.STREAMS, "Streams", "Media and data streams", Channel.STREAM, concurrent = true),
        )
        private val WIRED_PHASES = listOf(
            PhaseSpec(PhaseKind.MFI, "MFi", "MFi provider", Channel.LOCAL),
            PhaseSpec(PhaseKind.USB, "USB", "USB device", Channel.USB),
            PhaseSpec(PhaseKind.USB_DATA, "Data", "USB data paths", Channel.USB),
            PhaseSpec(PhaseKind.LOCKDOWN, "Lockdn", "Lockdown and CarKit", Channel.LOCKDOWN),
            PhaseSpec(PhaseKind.NETWORK, "NCM", "USB network", Channel.NETWORK),
            PhaseSpec(PhaseKind.IAP2, "iAP2", "iAP2 over USB", Channel.IAP2),
            PhaseSpec(PhaseKind.PAIR, "Pair", "AirPlay pairing", Channel.RTSP),
            PhaseSpec(PhaseKind.SESSION, "Session", "AirPlay session", Channel.RTSP),
            PhaseSpec(PhaseKind.STREAMS, "Streams", "Media and data streams", Channel.STREAM, concurrent = true),
        )

        private val IAP2_MEANINGS = mapOf(
            0x1d00 to Iap2Meaning("iPhone asks the accessory to identify itself"),
            0x1d01 to Iap2Meaning("Accessory name, message lists and transports", "Waiting for iPhone to review the identification"),
            0x1d02 to Iap2Meaning("Identification accepted"),
            0x1d03 to Iap2Meaning("Identification rejected", failure = "iPhone rejected the accessory identification"),
            0x1d05 to Iap2Meaning("Identification cancelled", failure = "iPhone cancelled identification"),
            0x1d06 to Iap2Meaning("Identification update"),
            0xaa00 to Iap2Meaning("iPhone requests the MFi certificate"),
            0xaa01 to Iap2Meaning("MFi certificate", "Waiting for iPhone to check the certificate"),
            0xaa02 to Iap2Meaning("Random challenge from iPhone"),
            0xaa03 to Iap2Meaning("Challenge signed with the MFi key", "Waiting for iPhone to verify the signature"),
            0xaa04 to Iap2Meaning("Authentication failed", failure = "MFi authentication failed"),
            0xaa05 to Iap2Meaning("Authentication succeeded"),
            0x5000 to Iap2Meaning("Subscribe to Now Playing"),
            0x5200 to Iap2Meaning("Subscribe to route guidance"),
            0xae00 to Iap2Meaning("Subscribe to power updates"),
            0x4157 to Iap2Meaning("Subscribe to communications"),
            0x4154 to Iap2Meaning("Subscribe to call state"),
            0x6800 to Iap2Meaning("Register the media remote HID"),
            0x5702 to Iap2Meaning("iPhone asks for the hotspot details"),
            0x5703 to Iap2Meaning("SSID, passphrase, security and channel"),
            0x4e0d to Iap2Meaning("Wireless CarPlay availability"),
            0x4e0e to Iap2Meaning("Bluetooth address and device UDID"),
            0x4300 to Iap2Meaning("CarPlay transports available on iPhone"),
            0xfffa to Iap2Meaning("iPhone asks for location data"),
            0xfffb to Iap2Meaning("Location data"),
            0xfffc to Iap2Meaning("iPhone stops location data"),
        )
        private val SUBSCRIPTION_IDS = setOf(
            0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157, 0x4159, 0x4154, 0x4156, 0x6800, 0x6802,
        )
        private val UPDATE_LABELS = mapOf(
            0x5001 to "Now playing", 0xae01 to "Power", 0x4158 to "Communications", 0x4155 to "Call state",
            0x5201 to "Route guidance", 0x5202 to "Maneuver",
        )

        private fun iap2GroupTitle(id: Int): String = when (id) {
            in 0x1d00..0x1d06 -> G_IDENT
            in 0xaa00..0xaa06 -> G_AUTH
            0x5702, 0x5703, 0x4e0d, 0x4e0e -> G_WIFI
            in 0x4300..0x4303 -> G_START
            in 0xfff0..0xffff -> G_LOCATION
            in SUBSCRIPTION_IDS -> G_SUBSCRIPTIONS
            else -> G_OTHER_IAP2
        }

        private val PAIR_SETUP_STEPS = listOf(
            "M1 · iPhone starts SRP pairing", "M3 · iPhone SRP public key and proof", "M5 · Encrypted long-term identity",
        )
        private val PAIR_SETUP_REPLIES = listOf("M2 · Salt and SRP public key", "M4 · Accessory proof", "M6 · Accessory identity")
        private val PAIR_VERIFY_STEPS = listOf("iPhone ephemeral key (Curve25519)", "iPhone encrypted signature")
        private val PAIR_VERIFY_REPLIES = listOf("Accessory key and signature", "Verified")
        private val STREAM_LABELS = mapOf(
            110 to "main screen", 111 to "cluster screen", 100 to "main audio", 101 to "alt audio", 102 to "buffered audio",
        )
        private val STREAM_MEANINGS = mapOf(
            110 to "Main screen video", 111 to "Instrument cluster video", 100 to "Main audio RTP, also carries the microphone",
            101 to "Alternate audio RTP", 102 to "Buffered high-quality audio",
        )
        private val RCS_MEANINGS = mapOf(
            "iAPChannel" to "iAP2 tunnel over Wi-Fi",
            "CarPlayProtocolData" to "Vehicle state data (CAF)",
            "CarPlayProtocolData2" to "Vehicle state data (CAF, priority 1)",
            "CarPlayClusterControl" to "Cluster UI sync",
            "CarPlayUpdateData" to "Theme asset transfer",
            "CarPlayLoggingData" to "Log transfer",
        )
        private val COMMAND_MEANINGS = mapOf(
            "modesChanged" to "Screen and audio ownership update",
            "requestUI" to "iPhone asks to show a host UI",
        )
        private val AVAILABILITY_LABELS = mapOf("wired" to "Wired", "wireless" to "Wireless", "themeAssets" to "Theme assets")

        private val IAP2_FRAME = Regex("""^IAP2 (TX|RX) \[([^\]]+)\] (0x[0-9a-fA-F]{4})(?: (\S+))? frame=(\d+)B""")
        private val RTSP_REQUEST = Regex("""^airplay rx (\S+) (\S+) cseq=(\d+) body=(\d+)""")
        private val RTSP_REPLY = Regex("""^airplay tx status=(\d+) cseq=(\d+) body=(\d+)""")
        private val FEATURES = Regex("""requested=\[(.*?)] supported=\[(.*?)] enabled=\[(.*?)]""")
        private val AVAILABILITY = Regex("""(\w+)=Iap2AvailabilityState\(available=(true|false)""")
        private val STREAM_RESPONSE = Regex("""\{[^{}]*\}""")
        private val STREAM_TYPE = Regex("""(?:^|[,{ ]+)type=(\d+)\b""")
        private val CONTROL_PORT = Regex("""controlPort=(\d+)""")
        private val DATA_PORT = Regex("""dataPort=(\d+)""")
        private val STREAM_ID = Regex("""streamID=(\d+)""")
        private val MAC = Regex("""\b([0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}):[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:([0-9A-Fa-f]{2})\b""")
        private val UDID = Regex("""\b([0-9A-Fa-f]{4})[0-9A-Fa-f-]{16,}([0-9A-Fa-f]{4})\b""")

        private fun list(value: String): List<String> =
            value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        /** Reads `key=value` up to ` nextKey=`, allowing spaces inside the value. */
        private fun field(text: String, key: String, nextKey: String?): String? {
            val start = Regex("""(^|\s)$key=""").find(text) ?: return null
            val from = start.range.last + 1
            val end = if (nextKey != null) {
                Regex("""\s$nextKey=""").find(text, from)?.range?.first ?: text.length
            } else {
                text.indexOf(' ', from).takeIf { it >= 0 } ?: text.length
            }
            return text.substring(from, end).trim().ifEmpty { null }
        }

        internal fun maskMac(text: String): String =
            MAC.replace(text) { "${it.groupValues[1]}:••:••:${it.groupValues[2]}" }

        private fun maskIdentifiers(text: String): String =
            UDID.replace(maskMac(text)) { "${it.groupValues[1]}••••${it.groupValues[2]}" }

        /** Decoded iAP2 fields for the detail view, without wire dumps or the hotspot passphrase. */
        private fun protocolDetail(full: String): String? {
            val lines = full.lineSequence().drop(1)
                .filterNot { val trimmed = it.trimStart(); trimmed.startsWith("raw-body=") || trimmed.startsWith("frameHex=") }
                .map { line -> if (line.contains("passphrase")) line.substringBefore(':') + ": ••••••••" else maskIdentifiers(line) }
                .take(MAX_DETAIL_LINES)
                .toList()
            return lines.joinToString("\n").trimEnd().ifEmpty { null }
        }

        private const val MAX_DETAIL_LINES = 40
    }
}
