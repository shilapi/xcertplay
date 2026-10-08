package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.ProtocolTraceFormatter
import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Reusable UI Sync state machine. Sequence and retry defaults are isolated from the wire factory. */
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
    private val expectedRemoteIdentifier: String? = null,
    private val waitForPeerReset: Boolean = false,
) {
    var state = CarPlayUiSyncSessionState.INITIALIZED
        private set
    var version = CarPlayUiSyncProtocolVersion.V2
        private set
    private var ssn = 0L
    private var tx = 0L
    private var rx = 0L
    private var lastAck = 0L
    private var remoteIdentifier = expectedRemoteIdentifier
    private var peerWaitStartedAt: Long? = null
    private data class Pending(val message: CarPlayUiSyncMessage, var sentAt: Long, var retries: Int = 0)
    private val pending = linkedMapOf<Long, Pending>()

    @Synchronized fun start() {
        onReset()
        if (waitForPeerReset && state == CarPlayUiSyncSessionState.INITIALIZED) {
            pending.clear()
            peerWaitStartedAt = now()
            trace("UI Sync awaiting peer reset before accessory handshake timeoutMillis=${retryMillis * (maxRetries + 1L)}")
            return
        }
        beginReset()
    }

    private fun beginReset() {
        peerWaitStartedAt = null
        check(ssn < Long.MAX_VALUE) { "UI Sync session sequence exhausted" }
        ssn++; tx = 0; rx = 0; lastAck = 0; pending.clear()
        version = CarPlayUiSyncProtocolVersion.V2
        state = CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED
        trace("UI Sync assumptions sequence=cumulative-ack retryMillis=$retryMillis maxRetries=$maxRetries")
        emit(CarPlayUiSyncMessageFactory.reset(ssn, nextTx(), ack(), version = version)
            .copy(remoteIdentifier = remoteIdentifier))
    }

    @Synchronized fun receive(message: CarPlayUiSyncMessage) {
        trace("UI Sync RX state=${state.wireName} message=${ProtocolTraceFormatter.pretty(message.toWireMap())}")
        if (peerWaitStartedAt != null) {
            if (message.type != CarPlayUiSyncMessageType.RESET) {
                trace("UI Sync ignored ${message.type.wireName}: waiting for peer reset")
                return
            }
            if (!validPeer(message)) return
            // Local readiness preparation precedes the existing protocol state machine.
            // Keep the accessory as initiator: this is not a resetComplete response.
            remoteIdentifier = message.remoteIdentifier ?: remoteIdentifier
            trace("UI Sync peer reset observed; initiating accessory reset with paired id=${remoteIdentifier != null}")
            beginReset()
            return
        }
        if (message.type == CarPlayUiSyncMessageType.RESET) {
            if (state == CarPlayUiSyncSessionState.INITIALIZED || !validPeer(message)) return
            // DashBoard only accepts resetComplete in its remote-initiated state.
            // Keep the accessory as initiator when the phone also sends an initial reset;
            // the phone must first receive our RESET and reply before we confirm it.
            if (state == CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED && tx == 1L) {
                trace("UI Sync ignored simultaneous initial reset")
                return
            }
            start()
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
                if (state == CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED && validPeer(message)) {
                    version = negotiatedVersion(message)
                    rx = message.packetSequenceNumber
                    acknowledge(message.acknowledgementSequenceNumber)
                    remoteIdentifier = message.remoteIdentifier
                    emit(CarPlayUiSyncMessageFactory.resetComplete(ssn, nextTx(), ack(), noAck = true, version = version)
                        .copy(remoteIdentifier = if (version != CarPlayUiSyncProtocolVersion.V1) remoteIdentifier else null))
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

    @Synchronized fun tick(): Boolean {
        peerWaitStartedAt?.let { startedAt ->
            if (now() - startedAt >= retryMillis * (maxRetries + 1L)) {
                trace("UI Sync timeout phase=awaitingPeerReset: no peer reset observed")
                return false
            }
            return true
        }
        for (entry in pending.values.toList()) {
            if (now() - entry.sentAt < retryMillis) continue
            if (entry.retries >= maxRetries) {
                trace("UI Sync timeout ssn=$ssn psn=${entry.message.packetSequenceNumber} state=${state.wireName}")
                return false
            }
            entry.retries++; entry.sentAt = now()
            trace("UI Sync retransmit psn=${entry.message.packetSequenceNumber} retry=${entry.retries}")
            send(entry.message)
        }
        return true
    }

    @Synchronized fun close() { pending.clear(); peerWaitStartedAt = null; state = CarPlayUiSyncSessionState.INITIALIZED }
    private fun negotiatedVersion(message: CarPlayUiSyncMessage): CarPlayUiSyncProtocolVersion =
        minOf(message.version ?: CarPlayUiSyncProtocolVersion.V1, CarPlayUiSyncProtocolVersion.V2)

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
    private fun ready() { state = CarPlayUiSyncSessionState.READY; trace("UI Sync ready version=${version.wireName} ssn=$ssn"); onReady() }
    private fun nextTx(): Long { check(tx < Long.MAX_VALUE) { "UI Sync sequence exhausted" }; return ++tx }
    private fun ack() = rx.coerceAtLeast(0)
    private fun emit(message: CarPlayUiSyncMessage) {
        trace("UI Sync TX state=${state.wireName} message=${ProtocolTraceFormatter.pretty(message.toWireMap())}")
        val track = !message.noAck && message.type != CarPlayUiSyncMessageType.COMMAND_ACK
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
) : RcsDataStreamHandlerFactory {
    override fun supports(clientType: RcsClientType) = clientType == RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL
    override fun create(stream: RcsDataStream): RcsDataStreamHandler? = if (supports(stream.clientType)) Handler(stream) else null

    private inner class Handler(private val stream: RcsDataStream) : RcsDataStreamHandler {
        private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "carplay-ui-sync-retry").apply { isDaemon = true }
        }
        private var layout = initialLayout
        private var appearance = initialAppearance
        private val displayUuid = themeRuntime?.descriptor?.cluster?.get("id") as? String
        private val engine: CarPlayUiSyncSession = CarPlayUiSyncSession(vehicleId,
            send = { message ->
                val body = CarPlayUiSyncCarrier.encode(message, displayUuid)
                stream.traceProtocol("UI Sync TX displayUUID=${displayUuid ?: "reference"} body=${body.size}B bodyHex=${ProtocolTraceFormatter.hex(body)}")
                stream.send(RcsMessage.comm(body))
            }, trace = stream::traceProtocol, onCommand = ::applyCommand, onReady = ::initializeUi,
            onReset = { themeRuntime?.disconnect() }, waitForPeerReset = themeRuntime != null)

        override fun onStreamOpened(stream: RcsDataStream) {
            engine.start()
            scheduler.scheduleAtFixedRate({
                runCatching { if (!engine.tick()) stream.close() }
                    .onFailure { stream.traceProtocol("UI Sync retry failed: ${it.message}"); stream.close() }
            }, 250, 250, TimeUnit.MILLISECONDS)
        }
        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            stream.traceProtocol("UI Sync RX type=${message.messageType} body=${message.body.size}B bodyHex=${ProtocolTraceFormatter.hex(message.body)}")
            require(message.messageType == ApTransportPackageCodec.MESSAGE_TYPE_COMM) { "Invalid UI Sync carrier" }
            val received = CarPlayUiSyncCarrier.decode(message.body)
            if (received.displayUuid != displayUuid) {
                stream.traceProtocol("UI Sync ignored displayUUID=${received.displayUuid} expected=$displayUuid")
                return
            }
            engine.receive(received.message)
        }
        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
            scheduler.shutdownNow(); engine.close()
            themeRuntime?.disconnect()
            stream.traceProtocol("UI Sync closed cause=${cause?.message ?: "EOF"}")
        }
        private fun initializeUi() {
            themeRuntime?.let { runtime ->
                runtime.initialCommands().forEach(engine::command)
                return
            }
            engine.command(CarPlayUiSyncCommand.UI_CONFIGURATION_CHANGE, mapOf("uiConfiguration" to "extendedMode", "data" to mapOf("layoutIdentifier" to layout)))
            engine.command(CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE, mapOf("appearanceMode" to appearance))
        }
        private fun applyCommand(payload: CarPlayUiSyncCommandPayload) {
            onConfiguration(payload)
            themeRuntime?.let { runtime ->
                runtime.handle(payload).forEach(engine::command)
                stream.traceProtocol("OEM UI command=${payload.command.wireName} layout=${runtime.scene.layoutId} " +
                    "transitioning=${runtime.scene.transitioning} backend=android-declarative-experimental")
                return
            }
            when (payload.command) {
                CarPlayUiSyncCommand.REQUEST_LAYOUT -> {
                    layout = payload.fields["layout"] as? String ?: layout
                    engine.command(CarPlayUiSyncCommand.LAYOUT_CHANGE, mapOf("layoutIdentifier" to layout,
                        "activeComponents" to (payload.fields["activeComponents"] ?: emptyList<String>())))
                }
                CarPlayUiSyncCommand.REQUEST_FOCUS -> engine.command(CarPlayUiSyncCommand.GIVE_FOCUS, mapOf("focusToken" to payload.fields["focusToken"]))
                CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE -> appearance = payload.fields["appearanceMode"] as? String ?: appearance
                CarPlayUiSyncCommand.LAYOUT_CHANGE -> layout = payload.fields["layoutIdentifier"] as? String ?: layout
                else -> Unit // Validated OEM payload is retained by onConfiguration.
            }
            stream.traceProtocol("UI Sync applied command=${payload.command.wireName} layout=$layout appearance=$appearance fields=${ProtocolTraceFormatter.pretty(payload.fields)}")
        }
    }
}
