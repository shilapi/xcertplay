package com.shilapi.xcertplay.airplay.rcs.caf

/** The same metadata constructors serve OEM providers, local vehicle state, and /info plugins. */
object CafConfigTreeFactory {
    fun tree(accessories: List<Map<String, Any?>>): Map<String, Any?> =
        linkedMapOf("accessories" to accessories).also { CafConfigTree.decode(it) }

    fun accessory(type: Long, instanceId: Long, services: List<Map<String, Any?>>, version: String = "1.0") =
        linkedMapOf<String, Any?>("type" to type, "iid" to instanceId, "version" to version, "services" to services)

    fun service(type: Long, instanceId: Long, characteristics: List<Map<String, Any?>>,
                controls: List<Map<String, Any?>> = emptyList()) =
        linkedMapOf<String, Any?>("type" to type, "iid" to instanceId,
            "characteristics" to characteristics, "controls" to controls)

    fun characteristic(type: Long, instanceId: Long, format: CafCharacteristicFormat, initialValue: Any?,
                       writable: Boolean = false, priority: Long = 0, fields: Map<String, Any?> = emptyMap(),
                       mutable: Boolean = false) =
        LinkedHashMap(fields).apply {
            put("type", type); put("iid", instanceId); put("format", format.wireValue)
            put("initialValue", initialValue); put("writable", writable); put("mutable", mutable)
            put("priority", priority)
        }

    fun control(type: Long, instanceId: Long, requestParameters: List<Map<String, Any?>> = emptyList(),
                responseParameters: List<Map<String, Any?>> = emptyList(), priority: Long = 1) =
        linkedMapOf<String, Any?>("type" to type, "iid" to instanceId, "sender" to "device",
            "hasResponse" to true, "priority" to priority, "requestParameters" to requestParameters,
            "responseParameters" to responseParameters)
}
