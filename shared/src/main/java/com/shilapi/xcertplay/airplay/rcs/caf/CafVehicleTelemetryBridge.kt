package com.shilapi.xcertplay.airplay.rcs.caf

/** Bridges the existing mock/VHAL snapshot keys (km/h, rpm, km, Celsius, percent) to CAF.
 * Type IDs: agent_docs/2026-10-10_caf-type-id-firmware-extract.zh-CN.md.
 * iOS 27 CAF _NSStringFromUnitType (0x25707e630) confirms units 151/981/1032/1082/1205;
 * _NSStringFromTransmissionMode (0x25707e4a0) confirms ASCII P/R/N/D/L/M/S.
 * Bounds/steps below are local reference choices, not OEM specifications.
 */
class CafVehicleTelemetryBridge(private val plugin: CafVehicleStatePlugin) {
    private val bindings = CafConfigTree.decode(plugin.config).accessories.flatMap { accessory ->
        accessory.services.flatMap { service ->
            fields.filter { it.accessory == accessory.type.value && it.service == service.type.value }
                .mapNotNull { field -> service.characteristics.singleOrNull { it.type.value == field.type }
                    ?.takeIf { it.format == field.format && CafNumbers.toLongOrNull(it.units) == field.unit && it.supportsInvalid == true }
                    ?.let { field to it } }
        }
    }
    private val published = linkedMapOf<Long, Any?>()

    /** A full source snapshot: absent, null, malformed and out-of-range readings become invalid.
     * OEM trees with incompatible format/units are not bound; their metadata remains untouched.
     */
    @Synchronized fun publish(snapshot: Map<String, Any?>) {
        val changes = linkedMapOf<Long, Any?>()
        bindings.forEach { (field, metadata) ->
            val id = metadata.iid.value
            val raw = snapshot[field.key]
            val value: Any? = if (field.key == "prndl") {
                (raw as? String)?.takeIf { it.length == 1 && it[0] in "PRNDLMS" }?.get(0)?.code?.toLong()
            } else {
                (raw as? Number)?.toDouble()?.takeIf { it.isFinite() && it in field.min..field.max }
                    ?.takeIf { field.unit != KMH || snapshot["vehicleSpeedUnit"] == KMH }
                    ?.takeIf { field.unit != KM || snapshot["lengthUnit"] == "km" }
                    ?.takeIf { field.unit != CELSIUS || snapshot["temperatureUnit"] == "C" || field.key == "outsideTemperature" }
                    ?.takeIf { field.key != "transmission" || it % 1.0 == 0.0 }
                    ?.let {
                        val min = (metadata.minimumValue?.toWireValue() as? Number)?.toDouble() ?: field.min
                        val max = (metadata.maximumValue?.toWireValue() as? Number)?.toDouble() ?: field.max
                        val step = (metadata.stepValue?.toWireValue() as? Number)?.toDouble() ?: field.step
                        if (it !in min..max || step <= 0.0) null
                        else (kotlin.math.round((it - min) / step) * step + min).takeIf { value -> value in min..max }
                    }
            }
            if (!published.containsKey(id) || published[id] != value) changes[id] = value
        }
        if (changes.isNotEmpty()) {
            plugin.update(changes)
            published.putAll(changes)
        }
    }

    companion object {
        const val KM = 151L
        const val RPM = 981L
        const val KMH = 1032L
        const val CELSIUS = 1082L
        const val PERCENT = 1205L
        private data class Field(val key: String, val accessory: Long, val service: Long, val type: Long,
            val unit: Long? = null, val min: Double = 0.0, val max: Double = 1000000.0,
            val step: Double = 0.1, val format: CafCharacteristicFormat = CafCharacteristicFormat.FLOAT)
        private val fields = listOf(
            Field("vehicleSpeed", 0x09000001, 0x1A000009, 0x30000046, KMH, max = 500.0),
            Field("vehicleSpeedMax", 0x09000001, 0x1A000009, 0x30000048, KMH, max = 500.0),
            Field("engineRPM", 0x09000005, 0x1A00000F, 0x30000024, RPM, max = 20000.0),
            Field("engineRPMMax", 0x09000005, 0x1A00000F, 0x3000004E, RPM, max = 20000.0),
            Field("engineMarkerRedlineRPM", 0x09000005, 0x1A00000F, 0x3000004F, RPM, max = 20000.0),
            Field("engineTemperature", 0x09000005, 0x1A000010, 0x3000001D, CELSIUS, -40.0, 200.0),
            Field("engineTemperatureMin", 0x09000005, 0x1A000010, 0x30000054, CELSIUS, -40.0, 200.0),
            Field("engineTemperatureMax", 0x09000005, 0x1A000010, 0x30000055, CELSIUS, -40.0, 200.0),
            Field("fuelLevel", 0x09000006, 0x1A000005, 0x41000002, PERCENT, max = 100.0),
            Field("fuelLevelMarkerLow", 0x09000006, 0x1A000005, 0x4100001A, PERCENT, max = 100.0),
            Field("odometer", 0x09000001, 0x1A00000A, 0x3000004A, KM),
            Field("remainingDistance", 0x09000006, 0x1A000015, 0x3000004A, KM),
            Field("transmission", 0x09000003, 0x1A000003, 0x41000012,
                max = 255.0, step = 1.0),
            Field("prndl", 0x09000003, 0x1A000003, 0x41000011,
                min = 0.0, max = 255.0, step = 1.0, format = CafCharacteristicFormat.UINT8),
        )

        /** Reference instance IDs occupy 3000..; climate and request-content retain their IDs. */
        fun referenceAccessories(): List<Map<String, Any?>> {
            val ids = fields.withIndex().associate { it.value to 3000L + it.index }
            return fields.groupBy { it.accessory }.entries.mapIndexed { accessoryIndex, (type, values) ->
                CafConfigTreeFactory.accessory(type, 300L + accessoryIndex,
                    values.groupBy { it.service }.map { (service, entries) ->
                        CafConfigTreeFactory.service(service, 1000L + ids.getValue(entries.first()), entries.map { field ->
                            val constraints = linkedMapOf<String, Any?>("supportsInvalid" to true,
                                "minimumValue" to if (field.format == CafCharacteristicFormat.FLOAT) field.min else field.min.toLong(),
                                "maximumValue" to if (field.format == CafCharacteristicFormat.FLOAT) field.max else field.max.toLong(),
                                "stepValue" to if (field.format == CafCharacteristicFormat.FLOAT) field.step else field.step.toLong())
                            field.unit?.let { constraints["units"] = it }
                            if (field.key == "prndl") constraints["validValues"] = "PRNDLMS".map { it.code.toLong() }
                            CafConfigTreeFactory.characteristic(field.type, ids.getValue(field), field.format,
                                null, priority = 1, mutable = true, fields = constraints)
                        })
                    })
            }
        }
    }
}
