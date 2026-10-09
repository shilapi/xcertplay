package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.ProtocolTraceFormatter
import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reusable UI Sync state machine based on the asset's APL/ClusterControlSession.lua.
 * The accessory initiates with RESET. Unlike the asset, every phone RESET (including one that
 * collides with our initial RESET) is answered: the phone's ssn is adopted, RESET_COMPLETE is sent,
 * and the phone's RESET_COMPLETE makes the session ready. A RESET_COMPLETE answering our own RESET
 * is confirmed as in the asset. After [resetWatchdogMillis] (2 s by default; null disables it),
 * an unanswered accessory RESET starts a new session; an answered peer RESET retransmits its
 * RESET_COMPLETE without replacing the peer's session. [peerResetReadyTimeoutMillis] optionally
 * enables a diagnostic readiness probe after answering a peer RESET, without peer confirmation.
 */
class CarPlayUiSyncSession(
    private val vehicleId: String,
    private val send: (CarPlayUiSyncMessage) -> Unit,
    private val trace: (String) -> Unit = {},
    private val onCommand: (CarPlayUiSyncCommandPayload) -> Unit = {},
    private val onReady: () -> Unit = {},
    private val onReset: () -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val retryMillis: Long = 1_000,
    private val maxRetries: Int = 3,
    /** Retries a pending handshake after this interval. null waits indefinitely. */
    private val resetWatchdogMillis: Long? = 2_000,
    private val expectedRemoteIdentifier: String? = null,
    // The phone (DashBoard) sends v3; the asset's Lua session caps at v2.
    private val maximumVersion: CarPlayUiSyncProtocolVersion = CarPlayUiSyncProtocolVersion.V3,
    /** Explicit diagnostic probe; null requires a real peer confirmation. */
    private val peerResetReadyTimeoutMillis: Long? = null,
) {
    init {
        require(peerResetReadyTimeoutMillis == null || peerResetReadyTimeoutMillis > 0)
    }
    var state = CarPlayUiSyncSessionState.INITIALIZED
        private set
    var version = maximumVersion
        private set
    private var ssn = 0L
    private var tx = 0L
    private var rx = 0L
    private var lastAck = 0L
    private var remoteIdentifier = expectedRemoteIdentifier
    private var resetSentAt: Long? = null
    private var peerResetComplete: CarPlayUiSyncMessage? = null
    private var peerResetAcceptedAt: Long? = null
    private data class Pending(val message: CarPlayUiSyncMessage, var sentAt: Long, var retries: Int = 0)
    private val pending = linkedMapOf<Long, Pending>()

    @Synchronized fun start(peerIdentifier: String? = null) {
        if (peerIdentifier != null) {
            require(peerIdentifier.isNotBlank())
            require(remoteIdentifier == null || remoteIdentifier == peerIdentifier) { "UI Sync paired id mismatch" }
            remoteIdentifier = peerIdentifier
        }
        onReset()
        beginReset()
    }

    /** ClusterControlSession.reset(): new session number, sequence restarts at 1, RESET carries the maximum version. */
    private fun beginReset() {
        check(ssn < Long.MAX_VALUE) { "UI Sync session sequence exhausted" }
        ssn++; tx = 0; rx = 0; lastAck = 0; pending.clear()
        peerResetComplete = null
        peerResetAcceptedAt = null
        version = maximumVersion
        state = CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED
        trace("UI Sync assumptions initiator=accessory resetWatchdogMillis=${resetWatchdogMillis ?: "off"} commandRetries=$maxRetries")
        resetSentAt = now()
        emit(CarPlayUiSyncMessageFactory.reset(ssn, nextTx(), ack(), version = version)
            .copy(remoteIdentifier = if (version != CarPlayUiSyncProtocolVersion.V1) remoteIdentifier else null), track = false)
    }

    @Synchronized fun receive(message: CarPlayUiSyncMessage) {
        trace("UI Sync RX state=${state.wireName} message=${ProtocolTraceFormatter.pretty(message.toWireMap())}")
        if (message.type == CarPlayUiSyncMessageType.RESET) {
            if (state == CarPlayUiSyncSessionState.INITIALIZED || !validPeer(message)) return
            acceptPeerReset(message)
            return
        }
        if (state == CarPlayUiSyncSessionState.INITIALIZED) {
            trace("UI Sync ignored ${message.type.wireName}: no reset handshake")
            return
        }
        if (message.sessionSequenceNumber != ssn) {
            trace("UI Sync ignored stale session expected=$ssn received=${message.sessionSequenceNumber}")
            return
        }
        if (message.acknowledgementSequenceNumber > tx || message.acknowledgementSequenceNumber < lastAck) {
            trace("UI Sync ignored invalid asn=${message.acknowledgementSequenceNumber} lastAck=$lastAck lastTx=$tx")
            return
        }
        when (message.type) {
            CarPlayUiSyncMessageType.RESET_COMPLETE -> {
                if (!validPeer(message)) return
                if (state == CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED) {
                    // The phone completes the reset we answered; nothing further is sent.
                    version = negotiatedVersion(message)
                    rx = message.packetSequenceNumber
                    acknowledge(message.acknowledgementSequenceNumber)
                    message.remoteIdentifier?.let { remoteIdentifier = it }
                    resetSentAt = null
                    peerResetComplete = null
                    ready()
                } else if (state != CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED) {
                    trace("UI Sync ignored resetComplete in state=${state.wireName}")
                } else {
                    // _handleResetComplete: confirm with our own RESET_COMPLETE (no noAck field, not retransmitted).
                    version = negotiatedVersion(message)
                    rx = message.packetSequenceNumber
                    acknowledge(message.acknowledgementSequenceNumber)
                    remoteIdentifier = message.remoteIdentifier
                    resetSentAt = null
                    peerResetComplete = null
                    emit(CarPlayUiSyncMessageFactory.resetComplete(ssn, nextTx(), ack(), version = version)
                        .copy(remoteIdentifier = if (version != CarPlayUiSyncProtocolVersion.V1) remoteIdentifier else null), track = false)
                    ready()
                }
            }
            CarPlayUiSyncMessageType.COMMAND, CarPlayUiSyncMessageType.COMMAND_ACK -> {
                if (state != CarPlayUiSyncSessionState.READY) { trace("UI Sync command ignored: sessionNotReady"); return }
                if (message.packetSequenceNumber > rx + 1) {
                    trace("UI Sync missing packet expected=${rx + 1} received=${message.packetSequenceNumber}; resetting")
                    start()
                    return
                }
                if (message.packetSequenceNumber == rx + 1) {
                    // A consumer may send a reply synchronously. Its ASN must include this packet.
                    rx = message.packetSequenceNumber
                    acknowledge(message.acknowledgementSequenceNumber)
                    if (message.type == CarPlayUiSyncMessageType.COMMAND) {
                        val command = message.commandPayload() ?: throw CarPlayUiSyncProtocolException("command requires data")
                        onCommand(command)
                    }
                } else trace("UI Sync duplicate command psn=${message.packetSequenceNumber} lastRx=$rx")
                if (message.type == CarPlayUiSyncMessageType.COMMAND && !message.noAck)
                    emit(CarPlayUiSyncMessageFactory.commandAck(ssn, nextTx(), ack(), noAck = true))
            }
            CarPlayUiSyncMessageType.RESET -> Unit
        }
    }

    @Synchronized fun command(command: CarPlayUiSyncCommand, fields: Map<String, Any?> = emptyMap()) {
        command(CarPlayUiSyncCommandPayload(command, fields))
    }

    @Synchronized fun command(payload: CarPlayUiSyncCommandPayload) {
        check(state == CarPlayUiSyncSessionState.READY) { "UI Sync sessionNotReady" }
        emit(CarPlayUiSyncMessageFactory.command(ssn, nextTx(), ack(), payload))
    }

    /** Drives the reset watchdog and command retransmission. Never asks the caller to close the channel. */
    @Synchronized fun tick(): Boolean {
        if (state == CarPlayUiSyncSessionState.INITIALIZED) return true
        val acceptedAt = peerResetAcceptedAt
        val readyTimeout = peerResetReadyTimeoutMillis
        if (state == CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED &&
            acceptedAt != null && readyTimeout != null && now() - acceptedAt >= readyTimeout) {
            trace("UI Sync experimental ready without peer resetComplete ssn=$ssn waitedMillis=${now() - acceptedAt}; sending UI probe")
            resetSentAt = null; peerResetComplete = null; peerResetAcceptedAt = null
            ready()
            return true
        }
        resetSentAt?.let { sentAt ->
            val watchdog = resetWatchdogMillis ?: return true
            if (state != CarPlayUiSyncSessionState.READY && now() - sentAt >= watchdog) {
                val answer = peerResetComplete
                if (state == CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED && answer != null) {
                    // A late confirmation still belongs to this peer session. Retry the exact
                    // response rather than advancing ssn and rejecting that confirmation as stale.
                    trace("UI Sync retransmit resetComplete ssn=$ssn psn=${answer.packetSequenceNumber}; awaiting peer confirmation")
                    resetSentAt = now()
                    emit(answer, track = false)
                } else {
                    trace("UI Sync reset watchdog expired ssn=$ssn; starting new session")
                    beginReset()
                }
            }
            return true
        }
        for (entry in pending.values.toList()) {
            if (now() - entry.sentAt < retryMillis) continue
            if (entry.retries >= maxRetries) {
                trace("UI Sync command unacknowledged ssn=$ssn psn=${entry.message.packetSequenceNumber}; resetting session")
                start()
                return true
            }
            entry.retries++; entry.sentAt = now()
            trace("UI Sync retransmit psn=${entry.message.packetSequenceNumber} retry=${entry.retries}")
            send(entry.message)
        }
        return true
    }

    @Synchronized fun close() {
        pending.clear(); resetSentAt = null; peerResetComplete = null; peerResetAcceptedAt = null
        remoteIdentifier = expectedRemoteIdentifier
        state = CarPlayUiSyncSessionState.INITIALIZED
    }
    /** Adopts the phone's session and answers its RESET; the phone's RESET_COMPLETE then makes us ready. */
    private fun acceptPeerReset(message: CarPlayUiSyncMessage) {
        onReset()
        // Preserve the transmit sequence when the peer adopts the same session number:
        // our RESET was psn=1, so its RESET_COMPLETE must be psn=2.
        val previousTx = if (ssn == message.sessionSequenceNumber) tx else 0L
        ssn = message.sessionSequenceNumber; tx = previousTx; rx = message.packetSequenceNumber; lastAck = 0; pending.clear()
        version = minOf(message.version ?: CarPlayUiSyncProtocolVersion.V1, maximumVersion)
        message.remoteIdentifier?.let { remoteIdentifier = it }
        state = CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED
        resetSentAt = now()
        peerResetAcceptedAt = resetSentAt
        trace("UI Sync accepted peer reset ssn=$ssn version=${version.wireName}; sending resetComplete")
        val answer = CarPlayUiSyncMessageFactory.resetComplete(ssn, nextTx(), ack(), version = version)
            .copy(remoteIdentifier = if (version != CarPlayUiSyncProtocolVersion.V1) remoteIdentifier else null)
        peerResetComplete = answer
        emit(answer, track = false)
    }

    private fun negotiatedVersion(message: CarPlayUiSyncMessage): CarPlayUiSyncProtocolVersion =
        minOf(message.version ?: CarPlayUiSyncProtocolVersion.V1, version)

    private fun validPeer(message: CarPlayUiSyncMessage): Boolean {
        if ((message.version ?: CarPlayUiSyncProtocolVersion.V1) != CarPlayUiSyncProtocolVersion.V1 &&
            remoteIdentifier != null && message.remoteIdentifier != remoteIdentifier) {
            trace("UI Sync ignored mismatched paired id")
            return false
        }
        return true
    }
    private fun acknowledge(number: Long) {
        lastAck = number
        pending.keys.filter { it <= number }.forEach(pending::remove)
    }
    private fun ready() { peerResetAcceptedAt = null; state = CarPlayUiSyncSessionState.READY; trace("UI Sync ready version=${version.wireName} ssn=$ssn"); onReady() }
    private fun nextTx(): Long { check(tx < Long.MAX_VALUE) { "UI Sync sequence exhausted" }; return ++tx }
    private fun ack() = rx.coerceAtLeast(0)
    private fun emit(message: CarPlayUiSyncMessage,
                     track: Boolean = !message.noAck && message.type == CarPlayUiSyncMessageType.COMMAND) {
        trace("UI Sync enqueue state=${state.wireName} model=${ProtocolTraceFormatter.pretty(message.toWireMap())}")
        if (track) pending[message.packetSequenceNumber] = Pending(message, now())
        try { send(message) } catch (error: Exception) { pending.remove(message.packetSequenceNumber); throw error }
    }
}

class CarPlayUiSyncHandlerFactory(
    private val vehicleId: String,
    private val initialLayout: String = "default",
    private val initialAppearance: String = "dark",
    private val onConfiguration: (CarPlayUiSyncCommandPayload) -> Unit = {},
    private val themeRuntime: com.shilapi.xcertplay.airplay.CarPlayOemThemeRuntime? = null,
    private val deferInitialResetUntilPeer: Boolean = false,
    private val peerResetReadyTimeoutMillis: Long? = null,
    /** v3 command-key experiment; framing and command indexes are unchanged. */
    private val commandPayloadKey: String = "data",
    private val commandPayloadDictionary: Boolean = false,
    private val omitFadeInNewLayout: Boolean = false,
    private val rcsBackpressureProbeBytes: Int = 0,
    /** Diagnostic probe: one extra APTransport `sync` frame that the phone's transport should answer. */
    private val transportSyncProbe: Boolean = false,
    /**
     * APTransport header form for every UI Sync frame we send. The default reproduces the historical
     * `asyn`/`comm` shape; the other variants exist because the phone's receive gate accepts only
     * the `cmnd`/`died` OSTypes. See [UiSyncUplinkVariant].
     */
    private val uplinkVariant: UiSyncUplinkVariant = UiSyncUplinkVariant.DEFAULT,
    /**
     * Diagnostic probe: this many minimal RESET carriers are sent on stream open, 300 ms apart,
     * before the handshake. Any of them landing makes the phone's `RCS Summary` count a received
     * message, which is the only read-out the phone exposes for "did the RCS layer accept our
     * uplink at all". Zero disables the probe.
     */
    private val uplinkProbeFrames: Int = 0,
) : RcsDataStreamHandlerFactory {
    init {
        require(commandPayloadKey == "data" || commandPayloadKey == "payload")
        require(rcsBackpressureProbeBytes == 0 || rcsBackpressureProbeBytes in 1_048_577..2_097_152)
        require(uplinkProbeFrames in 0..16)
    }
    override fun supports(clientType: RcsClientType) = clientType == RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL
    override fun create(stream: RcsDataStream): RcsDataStreamHandler? = if (supports(stream.clientType)) Handler(stream) else null

    private inner class Handler(private val stream: RcsDataStream) : RcsDataStreamHandler {
        private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "carplay-ui-sync-retry").apply { isDaemon = true }
        }
        private val probeStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        private inner class DisplaySession(val displayUuid: String?,
            val runtime: com.shilapi.xcertplay.airplay.CarPlayOemThemeRuntime?) {
            var layout = initialLayout
            var appearance = initialAppearance
            private val syncProbeSent = java.util.concurrent.atomic.AtomicBoolean(false)
            val engine = CarPlayUiSyncSession(vehicleId,
                send = ::sendMessage, trace = { stream.traceProtocol("$it displayUUID=${displayUuid ?: "reference"}") },
                onCommand = { applyCommand(this, it) }, onReady = { initializeUi(this) },
                onReset = { runtime?.disconnect() }, peerResetReadyTimeoutMillis = peerResetReadyTimeoutMillis)

            private fun sendMessage(message: CarPlayUiSyncMessage) {
                val key = if (message.type == CarPlayUiSyncMessageType.COMMAND &&
                    engine.version == CarPlayUiSyncProtocolVersion.V3) commandPayloadKey else "data"
                val dictionary = message.type == CarPlayUiSyncMessageType.COMMAND &&
                    engine.version == CarPlayUiSyncProtocolVersion.V3 && commandPayloadDictionary
                val omitFadeIn = engine.version == CarPlayUiSyncProtocolVersion.V3 && omitFadeInNewLayout
                val body = CarPlayUiSyncCarrier.encode(message, displayUuid, commandPayloadKey = key,
                    commandPayloadDictionary = dictionary, omitFadeInNewLayout = omitFadeIn)
                val fields = CarPlayUiSyncCarrier.wireFields(message, key, dictionary, omitFadeIn)
                stream.traceProtocol("UI Sync TX state=${engine.state.wireName} displayUUID=${displayUuid ?: "reference"} carrier=plist " +
                    "uplinkVariant=${uplinkVariant.wireName} " +
                    "commandPayloadKey=$key commandPayloadFormat=${if (dictionary) "dictionary" else "bplist"} " +
                    "message=${ProtocolTraceFormatter.pretty(fields)} body=${body.size}B bodyHex=${ProtocolTraceFormatter.hex(body)}")
                val frame = uplinkVariant.frame(body)
                stream.send(frame)
                if (transportSyncProbe && syncProbeSent.compareAndSet(false, true)) {
                    // APTransport answers a sync package with an rply regardless of the business
                    // handler, so an inbound "RCS transport reply" line proves the phone read this
                    // socket at all. No reply only fails to prove it; it does not prove the opposite.
                    stream.traceProtocol("RCS transport sync probe sent displayUUID=${displayUuid ?: "reference"} " +
                        "body=${body.size}B awaitingTransportReply=true")
                    stream.send(RcsMessage.sync(body))
                }
                if (message.type == CarPlayUiSyncMessageType.COMMAND && rcsBackpressureProbeBytes > 0 &&
                    probeStarted.compareAndSet(false, true)) Thread({
                    runCatching {
                        RcsBackpressureProbe.run(frame, rcsBackpressureProbeBytes, stream::send,
                            stream::fail, { stream.traceProtocol("$it displayUUID=$displayUuid") })
                    }.onFailure { stream.traceProtocol("RCS backpressure probe failed: ${it.message}") }
                }, "carplay-rcs-backpressure-probe").apply { isDaemon = true; start() }
            }
        }
        private val primaryDisplayUuid = themeRuntime?.descriptor?.display?.get("id") as? String
        private val sessions = linkedMapOf(primaryDisplayUuid to DisplaySession(primaryDisplayUuid, themeRuntime)).apply {
            val centerId = themeRuntime?.descriptor?.center?.get("id") as? String
            if (centerId != null && centerId != primaryDisplayUuid)
                put(centerId, DisplaySession(centerId, themeRuntime!!.forDisplay(centerId)))
        }

        override fun onStreamOpened(stream: RcsDataStream) {
            if (!deferInitialResetUntilPeer) sessions.getValue(primaryDisplayUuid).engine.start()
            else stream.traceProtocol("UI Sync bootstrap waiting for configured display peer RESET to learn paired id; " +
                "experimentalReadyTimeoutMillis=${peerResetReadyTimeoutMillis ?: "off"} uplinkVariant=${uplinkVariant.wireName}")
            if (uplinkProbeFrames > 0) scheduler.schedule({
                runCatching { sendUplinkProbe(stream) }
                    .onFailure { stream.traceProtocol("UI Sync uplink probe failed: ${it.message}") }
            }, 0, TimeUnit.MILLISECONDS)
            scheduler.scheduleAtFixedRate({
                runCatching { sessions.values.forEach { it.engine.tick() } }.onFailure { stream.traceProtocol("UI Sync retry failed: ${it.message}"); stream.fail(it) }
            }, 250, 250, TimeUnit.MILLISECONDS)
        }
        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            stream.traceProtocol("UI Sync RX type=${message.messageType} body=${message.body.size}B bodyHex=${ProtocolTraceFormatter.hex(message.body)}")
            require(message.messageType == ApTransportPackageCodec.MESSAGE_TYPE_COMM) { "Invalid UI Sync carrier" }
            val received = CarPlayUiSyncCarrier.decode(message.body)
            val target = sessions[received.displayUuid]
            if (target == null) {
                stream.traceProtocol("UI Sync ignored displayUUID=${received.displayUuid} supported=${sessions.keys}")
                return
            }
            // The center starts when its configured plugin first sends RESET. Arbitrary display
            // IDs never create sessions, and non-RESET traffic cannot unlock a dormant display.
            if (target.engine.state == CarPlayUiSyncSessionState.INITIALIZED &&
                received.message.type == CarPlayUiSyncMessageType.RESET) target.engine.start(received.message.remoteIdentifier)
            target.engine.receive(received.message)
        }
        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
            scheduler.shutdownNow()
            sessions.values.forEach { it.engine.close(); it.runtime?.disconnect() }
            stream.traceProtocol("UI Sync closed cause=${cause?.message ?: "EOF"}")
        }
        /**
         * Fires [uplinkProbeFrames] minimal RESET carriers, 300 ms apart, as soon as the stream opens.
         *
         * The body is a RESET the peer can actually parse, so one accepted frame both raises the
         * phone's `RCS Summary received messages` above zero and shows up as a `UI Sync RX` line
         * here — a positive result on either side means the uplink reached the peer's RCS layer.
         */
        private fun sendUplinkProbe(stream: RcsDataStream) {
            val probe = CarPlayUiSyncCarrier.encode(
                CarPlayUiSyncMessageFactory.reset(1, 1, 0, version = CarPlayUiSyncProtocolVersion.V3),
                primaryDisplayUuid)
            val frame = uplinkVariant.frame(probe)
            repeat(uplinkProbeFrames) { index ->
                scheduler.schedule({
                    runCatching {
                        stream.traceProtocol("RCS uplink probe ${index + 1}/$uplinkProbeFrames " +
                            "displayUUID=${primaryDisplayUuid ?: "reference"} uplinkVariant=${uplinkVariant.wireName} " +
                            "packageType=0x${frame.packageType.toString(16)} messageType=0x${frame.messageType.toString(16)} " +
                            "body=${probe.size}B bodyHex=${ProtocolTraceFormatter.hex(probe)}")
                        stream.send(frame)
                    }.onFailure { stream.traceProtocol("RCS uplink probe ${index + 1}/$uplinkProbeFrames failed: ${it.message}") }
                }, 300L * index, TimeUnit.MILLISECONDS)
            }
        }

        private fun initializeUi(target: DisplaySession) {
            val engine = target.engine
            target.runtime?.let { runtime ->
                runtime.initialCommands().forEach(engine::command)
                return
            }
            engine.command(CarPlayUiSyncCommand.UI_CONFIGURATION_CHANGE, mapOf("uiConfiguration" to "extendedMode", "data" to mapOf("layoutIdentifier" to target.layout)))
            engine.command(CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE, mapOf("appearanceMode" to target.appearance))
        }
        private fun applyCommand(target: DisplaySession, payload: CarPlayUiSyncCommandPayload) {
            val engine = target.engine
            onConfiguration(payload)
            target.runtime?.let { runtime ->
                runtime.handle(payload).forEach(engine::command)
                stream.traceProtocol("OEM UI displayUUID=${target.displayUuid} command=${payload.command.wireName} layout=${runtime.scene.layoutId} " +
                    "transitioning=${runtime.scene.transitioning} backend=android-declarative-experimental")
                return
            }
            when (payload.command) {
                CarPlayUiSyncCommand.REQUEST_LAYOUT -> {
                    target.layout = payload.fields["layout"] as? String ?: target.layout
                    engine.command(CarPlayUiSyncCommand.LAYOUT_CHANGE, mapOf("layoutIdentifier" to target.layout,
                        "activeComponents" to (payload.fields["activeComponents"] ?: emptyList<String>())))
                }
                CarPlayUiSyncCommand.REQUEST_FOCUS -> engine.command(CarPlayUiSyncCommand.GIVE_FOCUS, mapOf("focusToken" to payload.fields["focusToken"]))
                CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE -> target.appearance = payload.fields["appearanceMode"] as? String ?: target.appearance
                CarPlayUiSyncCommand.LAYOUT_CHANGE -> target.layout = payload.fields["layoutIdentifier"] as? String ?: target.layout
                else -> Unit // Validated OEM payload is retained by onConfiguration.
            }
            stream.traceProtocol("UI Sync applied command=${payload.command.wireName} displayUUID=${target.displayUuid} layout=${target.layout} appearance=${target.appearance} fields=${ProtocolTraceFormatter.pretty(payload.fields)}")
        }
    }
}
