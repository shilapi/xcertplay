package com.shilapi.xcertplay.airplay

/**
 * Factories for binary-plist messages sent over the encrypted AirPlay event channel.
 *
 * The iAP2 frame inside `params.data` remains raw iAP2. The wrapper is the event-command layer and
 * is not CAF/OPACK.
 */
internal object AirPlayEventCommands {
    fun iapSendMessage(data: ByteArray): Map<String, Any?> =
        linkedMapOf(
            "type" to "iAPSendMessage",
            "params" to linkedMapOf("data" to data.copyOf()),
        )
}
