package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayDataStreamFactoryTest {
    private val factory = CarPlayDataStreamFactory()

    @Test
    fun clientTypeCatalogSelectsTheSharedTransportStrategy() {
        assertEquals(
            CarPlayDataStreamKind.IAP_TUNNEL,
            factory.kindFor(RcsClientTypes.IAP_CHANNEL),
        )
        assertEquals(
            CarPlayDataStreamKind.RCS,
            factory.kindFor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
        )
        assertEquals(
            CarPlayDataStreamKind.RCS,
            factory.kindFor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2),
        )
        assertEquals(
            CarPlayDataStreamKind.RCS,
            factory.kindFor(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL),
        )
    }
}
