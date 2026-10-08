package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayOemVehicleValuesTest {
    @Test fun liveVhalUnitsAndAssetKeysAreMappedWithoutInventingReadings() {
        val now = 10_000_000_000L
        val values = CarPlayOemVehicleValues.snapshot(OemVehicleReading(-10.0f, now, true), OemVehicleReading(2300f, now, true), now)
        assertEquals(36.0, values["vehicleSpeed"] as Double, 0.0)
        assertEquals(1032L, values["vehicleSpeedUnit"])
        assertEquals(2300.0, values["engineRPM"] as Double, 0.0)
        assertFalse(values.containsKey("engineSpeed"))
    }

    @Test fun staleUnavailableFutureAndInvalidReadingsAreCleared() {
        val now = 10_000_000_000L
        for (reading in listOf(null, OemVehicleReading(10.0, now - 3_000_000_000L, true),
            OemVehicleReading(10.0, now + 1, true), OemVehicleReading(10.0, now, false), OemVehicleReading(Double.NaN, now, true))) {
            assertTrue(CarPlayOemVehicleValues.snapshot(reading, reading, now).values.all { it == null })
        }
        assertEquals(0.0, CarPlayOemVehicleValues.snapshot(OemVehicleReading(0, now, true), null, now)["vehicleSpeed"] as Double, 0.0)
    }
}
