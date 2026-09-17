package com.shilapi.xcertplay.airplay.rcs.catalog

import com.shilapi.xcertplay.airplay.AirPlayFeature
import java.util.UUID

enum class RcsPayloadStyle {
    CAF_BINARY_PLIST_OPACK,
    RAW_IAP2,

    /** UUID and carrier are known, but the application-level schema is not confirmed. */
    UNCONFIRMED_OPAQUE,
}

/**
 * RCS client metadata from the CarPlay Ultra references.
 */
data class RcsClientType(
    val name: String,
    val uuid: UUID,
    val feature: AirPlayFeature?,
    val priority: Int? = null,
    val qualityOfService: Int? = null,
    val streamPriority: Int? = null,
    val withoutReply: Boolean,
    val payloadStyle: RcsPayloadStyle,
)

object RcsClientTypes {
    val CAR_PLAY_PROTOCOL_DATA = RcsClientType(
        name = "CarPlayProtocolData",
        uuid = UUID.fromString("3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E"),
        feature = AirPlayFeature.VEHICLE_STATE_PROTOCOL,
        priority = 0,
        qualityOfService = 0,
        streamPriority = 0,
        withoutReply = true,
        payloadStyle = RcsPayloadStyle.CAF_BINARY_PLIST_OPACK,
    )
    val CAR_PLAY_PROTOCOL_DATA_2 = RcsClientType(
        name = "CarPlayProtocolData2",
        uuid = UUID.fromString("FF4A6720-F2BE-4F56-A3E1-DB3B4E37D634"),
        feature = AirPlayFeature.VEHICLE_STATE_PROTOCOL,
        priority = 1,
        qualityOfService = 12,
        streamPriority = 33,
        withoutReply = true,
        payloadStyle = RcsPayloadStyle.CAF_BINARY_PLIST_OPACK,
    )
    val CAR_PLAY_CLUSTER_CONTROL = RcsClientType(
        name = "CarPlayClusterControl",
        uuid = UUID.fromString("07D9F906-8D64-4B54-A808-A20BCA2C51C2"),
        feature = AirPlayFeature.UI_SYNC,
        withoutReply = false,
        payloadStyle = RcsPayloadStyle.UNCONFIRMED_OPAQUE,
    )
    val CAR_PLAY_UPDATE_DATA = RcsClientType(
        name = "CarPlayUpdateData",
        uuid = UUID.fromString("09A8AA2D-8932-4048-9492-6B658D42C2A3"),
        feature = AirPlayFeature.FILE_TRANSFER,
        withoutReply = false,
        payloadStyle = RcsPayloadStyle.UNCONFIRMED_OPAQUE,
    )
    val CAR_PLAY_LOGGING_DATA = RcsClientType(
        name = "CarPlayLoggingData",
        uuid = UUID.fromString("75AD9926-4777-42B2-A7D8-823EBEECF7AA"),
        feature = AirPlayFeature.LOG_TRANSFER,
        withoutReply = false,
        payloadStyle = RcsPayloadStyle.UNCONFIRMED_OPAQUE,
    )
    val IAP_CHANNEL = RcsClientType(
        name = "iAPChannel",
        uuid = UUID.fromString("E9459FD0-BCAD-4C45-820F-1E72447EF2F2"),
        feature = AirPlayFeature.IAP_CHANNEL,
        withoutReply = false,
        payloadStyle = RcsPayloadStyle.RAW_IAP2,
    )

    val ALL: List<RcsClientType> = listOf(
        CAR_PLAY_PROTOCOL_DATA,
        CAR_PLAY_PROTOCOL_DATA_2,
        CAR_PLAY_CLUSTER_CONTROL,
        CAR_PLAY_UPDATE_DATA,
        CAR_PLAY_LOGGING_DATA,
        IAP_CHANNEL,
    )

    private val byUuid = ALL.associateBy(RcsClientType::uuid)
    private val byName = ALL.associateBy(RcsClientType::name)

    fun find(uuid: UUID): RcsClientType? = byUuid[uuid]

    fun findByUuid(uuid: String): RcsClientType? =
        runCatching { UUID.fromString(uuid) }.getOrNull()?.let(::find)

    fun findByName(name: String): RcsClientType? = byName[name]

    fun require(uuid: UUID): RcsClientType =
        requireNotNull(byUuid[uuid]) { "Unknown RCS client UUID $uuid" }

    fun requireByUuid(uuid: String): RcsClientType =
        requireNotNull(findByUuid(uuid)) { "Unknown RCS client UUID $uuid" }

    fun requireByName(name: String): RcsClientType =
        requireNotNull(byName[name]) { "Unknown RCS client name $name" }
}
