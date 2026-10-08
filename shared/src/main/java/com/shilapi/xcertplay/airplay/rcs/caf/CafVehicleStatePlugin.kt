package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.util.concurrent.ConcurrentHashMap

/** Stateful local vehicle provider. Hardware adapters can publish through update() and supply controls. */
class CafVehicleStatePlugin(
    private val pluginId: Long,
    val config: Map<String, Any?>,
    private val controls: Map<Long, (Any?) -> Any?> = emptyMap(),
    private val writeToVehicle: (Long, Any?) -> Unit = { _, _ -> },
) : CafPluginHandler {
    private val tree = CafConfigTree.decode(config)
    private val characteristics = tree.accessories.flatMap { it.services }.flatMap { it.characteristics }.associateBy { it.iid.value }
    private val controlMetadata = tree.accessories.flatMap { it.services }.flatMap { it.controls }.associateBy { it.iid.value }
    private val values = characteristics.mapValuesTo(linkedMapOf()) { (_, value) -> value.initialValue?.toWireValue() }
    private val subscriptions = ConcurrentHashMap<CafProtocolSession, MutableSet<Long>>()
    init {
        val all = tree.accessories.flatMap { it.services }.flatMap { service ->
            service.characteristics.map { it.iid.value } + service.controls.map { it.iid.value }
        }
        require(all.distinct().size == all.size) { "Duplicate CAF characteristic/control instanceID" }
        characteristics.values.forEach { metadata ->
            metadata.initialValue?.let { validateValue(metadata, it.toWireValue()) }
        }
    }

    override fun onConfigRequest(session: CafProtocolSession, message: CafMessageReader) =
        session.send(CarAccessoryMessages.configResponse(pluginId, message.requireTransactionId(), snapshotConfig()))

    override fun onRegisterRequest(session: CafProtocolSession, message: CafMessageReader) {
        val ids = if (message.values() == "*") characteristics.keys.toList()
            else message.valuesList().map { CafNumbers.toLong(it, "registerRequest instanceID") }
        val errors = ids.filter { it !in characteristics }.associateWith { NOT_FOUND }
        val valid = ids.filter { it in characteristics }
        synchronized(subscriptions) { subscriptions.getOrPut(session) { linkedSetOf() }.addAll(valid) }
        session.send(CarAccessoryMessages.registerResponse(pluginId, message.requireTransactionId(), readValues(valid), errors))
    }

    override fun onUnregisterRequest(session: CafProtocolSession, message: CafMessageReader) {
        val ids = if (message.values() == "*") characteristics.keys.toList() else message.valuesList().map { CafNumbers.toLong(it, "unregisterRequest instanceID") }
        synchronized(subscriptions) { subscriptions[session]?.removeAll(ids.toSet()) }
        session.send(CarAccessoryMessages.unregisterResponse(pluginId, message.requireTransactionId()))
    }

    override fun onReadRequest(session: CafProtocolSession, message: CafMessageReader) {
        val ids = message.valuesList().map { CafNumbers.toLong(it, "readRequest instanceID") }
        session.send(CarAccessoryMessages.readResponse(pluginId, message.requireTransactionId(),
            readValues(ids.filter { it in characteristics }), ids.filter { it !in characteristics }.associateWith { NOT_FOUND }))
    }

    override fun onWriteRequest(session: CafProtocolSession, message: CafMessageReader) {
        val errors = linkedMapOf<Long, Long>()
        val changes = linkedMapOf<Long, Any?>()
        message.valuesMap().forEach { (id, raw) ->
            val metadata = characteristics[id]
            when {
                metadata == null -> errors[id] = NOT_FOUND
                metadata.writable != true -> errors[id] = NOT_WRITABLE
                else -> try {
                    validateValue(metadata, raw)
                    writeToVehicle(id, raw)
                    synchronized(values) { values[id] = raw }
                    changes[id] = raw
                } catch (error: Exception) {
                    errors[id] = INVALID_VALUE
                    session.stream.traceProtocol("CAF write rejected instanceID=$id value=$raw reason=${error.message}")
                }
            }
        }
        session.send(CarAccessoryMessages.writeResponse(pluginId, message.requireTransactionId(), errors))
        notifyChanges(changes)
    }

    override fun onControlRequest(session: CafProtocolSession, message: CafMessageReader) {
        val results = linkedMapOf<Long, Any?>()
        val errors = linkedMapOf<Long, Long>()
        message.valuesMap().forEach { (id, parameters) ->
            val action = controls[id]
            if (id !in controlMetadata || action == null) errors[id] = NOT_FOUND
            else try {
                val metadata = controlMetadata.getValue(id)
                val requested = if (parameters == null) emptyList<Any?>() else parameters as? List<*>
                    ?: throw CafProtocolException("control parameters must be an array")
                require(requested.size == metadata.requestParameters.size) { "Control parameter count does not match" }
                requested.zip(metadata.requestParameters).forEach { (raw, parameter) ->
                    CafFormatValue.decode(raw, parameter.format, parameter.name)
                }
                results[id] = action(parameters)
            } catch (error: Exception) {
                errors[id] = INVALID_VALUE
                session.stream.traceProtocol("CAF control rejected instanceID=$id reason=${error.message}")
            }
        }
        session.send(CarAccessoryMessages.controlResponse(pluginId, message.requireTransactionId(), results, errors))
    }

    override fun onSessionClosed(session: CafProtocolSession) { subscriptions.remove(session) }

    /** External telemetry is validated exactly as writes, but does not invoke the hardware writer. */
    fun update(changes: Map<Long, Any?>) {
        changes.forEach { (id, raw) -> validateValue(characteristics[id]
            ?: throw CafProtocolException("Unknown vehicle instanceID=$id"), raw) }
        synchronized(values) { values.putAll(changes) }
        notifyChanges(changes)
    }

    private fun readValues(ids: List<Long>): Map<Long, Any?> = synchronized(values) { ids.associateWith { values[it] } }

    private fun snapshotConfig(): Map<String, Any?> {
        // Config generation reuses decoded metadata, preserving every OEM extension field.
        fun copy(value: Any?): Any? = when (value) {
            is Map<*, *> -> LinkedHashMap<Any?, Any?>().apply {
                value.forEach { (key, item) -> put(key, copy(item)) }
                val id = CafNumbers.toLongOrNull(value["iid"])
                if (id in characteristics) put("initialValue", synchronized(this@CafVehicleStatePlugin.values) { this@CafVehicleStatePlugin.values[id] })
            }
            is List<*> -> value.map { copy(it) }
            else -> value
        }
        @Suppress("UNCHECKED_CAST")
        return copy(config) as Map<String, Any?>
    }

    private fun notifyChanges(changes: Map<Long, Any?>) {
        val targets = synchronized(subscriptions) { subscriptions.map { it.key to it.value.toSet() } }
        targets.forEach { (session, registered) ->
            val selected = changes.filter { (id, _) ->
                val preferred = if (characteristics[id]?.priority == 1L) RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2 else RcsClientTypes.CAR_PLAY_PROTOCOL_DATA
                id in registered && (session.clientType == preferred || targets.none { it.first.clientType == preferred && id in it.second })
            }
            if (selected.isNotEmpty()) runCatching { session.send(CarAccessoryMessages.updateNotify(pluginId, selected)) }
                .onFailure { session.stream.traceProtocol("CAF notify failed reason=${it.message}") }
        }
    }

    private fun validateValue(metadata: CafCharacteristicMetadata, raw: Any?) {
        if (raw == null) { require(metadata.supportsInvalid == true) { "Null value is not supported" }; return }
        CafFormatValue.decode(raw, metadata.format, "instanceID=${metadata.iid.value}")
        if (raw is Number) {
            val number = raw.toDouble()
            require(number.isFinite()) { "Non-finite value" }
            (metadata.minimumValue?.toWireValue() as? Number)?.let { require(number >= it.toDouble()) { "Below minimum" } }
            (metadata.maximumValue?.toWireValue() as? Number)?.let { require(number <= it.toDouble()) { "Above maximum" } }
            val step = (metadata.stepValue?.toWireValue() as? Number)?.toDouble()
            if (step != null && step > 0) {
                val origin = (metadata.minimumValue?.toWireValue() as? Number)?.toDouble() ?: 0.0
                val position = (number - origin) / step
                require(kotlin.math.abs(position - kotlin.math.round(position)) < 0.000001) { "Invalid step" }
            }
        }
        metadata.maximumLength?.let { limit ->
            val size = when (raw) { is String -> raw.length; is ByteArray -> raw.size; else -> 0 }
            require(size <= limit) { "Value exceeds maximumLength" }
        }
        if (metadata.validValues.isNotEmpty()) require(metadata.validValues.any { it.toWireValue() == raw }) { "Value not in validValues" }
    }

    companion object {
        // OSStatus choices for undocumented vehicle-provider failures; recorded as assumptions in the profile.
        const val INVALID_VALUE = -50L
        const val NOT_FOUND = -6727L
        const val NOT_WRITABLE = -6762L
    }
}
