package com.shilapi.xcertplay.airplay.rcs.caf

enum class CafTypeKind {
    ACCESSORY,
    SERVICE,
    CHARACTERISTIC,
    CONTROL,
}

data class CafTypeDescriptor(
    val kind: CafTypeKind,
    val name: String,
    val type: CafTypeId,
)

/**
 * Names confirmed by the CarPlay Ultra firmware tables. Unknown numeric type IDs remain valid
 * extension values in the decoded config tree; this catalog is diagnostic metadata, not a filter.
 */
object CafTypeCatalog {
    private val descriptors = listOf(
        accessory("NULL", 0x0000000000000000),
        accessory("Climate", 0x0000000001000001),
        accessory("AudioSettings", 0x0000000002000001),
        accessory("Media", 0x0000000003000001),
        accessory("TripComputer", 0x0000000004000001),
        accessory("AutomakerSettings", 0x0000000005000001),
        accessory("AutomakerNotifications", 0x0000000006000001),
        accessory("Charging", 0x0000000009000008),
        accessory("VehicleMotion", 0x0000000009000001),
        accessory("DriveState", 0x0000000009000003),
        accessory("ElectricEngine", 0x0000000009000004),
        accessory("InternalCombustionEngine", 0x0000000009000005),
        accessory("Fuel", 0x0000000009000006),
        accessory("HighVoltageBattery", 0x0000000009000007),
        accessory("Tire", 0x000000000A000001),
        accessory("PairedDevices", 0x000000000B000001),
        accessory("AutomakerInputStreams", 0x000000000C000001),
        accessory("AutomakerOverlays", 0x000000000C000002),
        accessory("Closure", 0x000000000D000001),
        accessory("DriverAssistance", 0x000000000E000001),
        accessory("Navigation", 0x000000000E000002),
        accessory("NowPlayingInformation", 0x000000000F000001),
        accessory("UIControl", 0x0000000001100001),
        accessory("AutomakerRequestContent", 0x0000000001200001),
        accessory("VehicleResources", 0x0000000001200002),
        accessory("AutomakerExteriorCamera", 0x0000000001300001),
        accessory("AutomakerNotificationHistory", 0x0000000001400001),
        accessory("Seat", 0x0000000001500001),
        accessory("EnvironmentalConditions", 0x0000000001600001),
        accessory("VehicleUnits", 0x0000000001700001),
        accessory("AutomakerApps", 0x0000000001800001),
        accessory("Indicators", 0x0000000001900001),
        accessory("Lighting", 0x0000000002100001),
        accessory("StatusIndicators", 0x0000000005100001),
        accessory("TestingUseOnly", 0x00000000FD000001),

        service("Cabin", 0x0000000011000001),
        service("Temperature", 0x0000000011000002),
        service("SteeringWheelHeatingCooling", 0x0000000011000003),
        service("Defrost", 0x0000000011000005),
        service("Vent", 0x0000000011000006),
        service("Fan", 0x0000000011000007),
        service("AutoClimateControl", 0x0000000011000008),
        service("ClimateControlsLocked", 0x0000000011000010),
        service("Recirculation", 0x0000000011000011),
        service("ZonesSynced", 0x0000000011000012),
        service("ZoneOn", 0x0000000011000014),
        service("TemperatureLevel", 0x0000000011000015),
        service("SeatHeatingCooling", 0x0000000022000001),
        service("SeatFan", 0x0000000022000002),

        characteristic("Temperature", 0x000000003000001D),
        characteristic("TemperatureState", 0x000000003000001E),
        characteristic("TemperatureMin", 0x0000000030000054),
        characteristic("TemperatureMax", 0x0000000030000055),
        characteristic("TemperatureMarkerCold", 0x0000000030000056),
        characteristic("TemperatureMarkerHot", 0x0000000030000057),
        characteristic("FanLevel", 0x0000000031000012),
        characteristic("DefrostTypes", 0x0000000031000014),
        characteristic("VentTypes", 0x0000000031000015),
        characteristic("TargetTemperature", 0x0000000031000017),
        characteristic("CurrentTemperature", 0x0000000031000019),
        characteristic("VentCombinations", 0x0000000031000025),
        characteristic("MaxDefrostOn", 0x0000000031000029),
        characteristic("TargetTemperatureFahrenheit", 0x000000003100002A),
        characteristic("TemperatureMinMaxLabel", 0x0000000031000032),
        characteristic("TemperatureUnit", 0x0000000046000005),

        control("NULL", 0x0000000000000000),
        control("Play", 0x000000000F000032),
        control("Pause", 0x000000000F000033),
        control("Stop", 0x000000000F000034),
        control("NextItem", 0x000000000F000035),
        control("PreviousItem", 0x000000000F000036),
        control("BeginSeekForward", 0x000000000F000037),
        control("BeginSeekBackward", 0x000000000F000038),
        control("EndSeek", 0x000000000F000039),
        control("JumpForward", 0x000000000F000040),
        control("JumpBackward", 0x000000000F00004A),
        control("TuneToIdentifier", 0x000000000F00004B),
        control("TuneToFrequency", 0x000000000F00004C),
        control("ChangeMediaSource", 0x000000000F00004D),
        control("SetArtistSongNotification", 0x000000000F00004E),
        control("ConnectDevice", 0x000000003600001A),
        control("DisconnectDevice", 0x000000003600001B),
        control("ForgetDevice", 0x000000003600001C),
        control("GetImageArchive", 0x0000000048000008),
        control("Reset", 0x0000000030000062),
        control("TemporaryContentChanged", 0x0000000037000016),
        control("TestAccRequestNoParams", 0x00000000FF00002E),
        control("TestAccRequestWithReqParams", 0x00000000FF00002F),
        control("TestAccRequestWithResParams", 0x00000000FF000030),
        control("TestAccRequestWithReqAndResParams", 0x00000000FF000031),
        control("TestAccEventNoParams", 0x00000000FF000032),
        control("TestAccEventWithParams", 0x00000000FF000033),
        control("TestDevRequestNoParams", 0x00000000FF000028),
        control("TestDevRequestWithReqParams", 0x00000000FF000029),
        control("TestDevRequestWithResParams", 0x00000000FF00002A),
        control("TestDevRequestWithReqAndResParams", 0x00000000FF00002B),
        control("TestDevEventNoParams", 0x00000000FF00002C),
        control("TestDevEventWithParams", 0x00000000FF00002D),
    )

    private val byKey = descriptors.associateBy { it.kind to it.type.value }

    fun find(kind: CafTypeKind, type: CafTypeId): CafTypeDescriptor? =
        byKey[kind to type.value]

    fun all(): List<CafTypeDescriptor> = descriptors

    private fun accessory(name: String, type: Long) =
        CafTypeDescriptor(CafTypeKind.ACCESSORY, name, CafTypeId(type))

    private fun service(name: String, type: Long) =
        CafTypeDescriptor(CafTypeKind.SERVICE, name, CafTypeId(type))

    private fun characteristic(name: String, type: Long) =
        CafTypeDescriptor(CafTypeKind.CHARACTERISTIC, name, CafTypeId(type))

    private fun control(name: String, type: Long) =
        CafTypeDescriptor(CafTypeKind.CONTROL, name, CafTypeId(type))
}
