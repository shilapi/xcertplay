package com.shilapi.xcertplay.airplay.rcs.catalog

import com.shilapi.xcertplay.airplay.AirPlayFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RcsClientTypeTest {
    @Test
    fun uuidFactoryResolvesEveryCataloguedClientType() {
        RcsClientTypes.ALL.forEach { expected ->
            val actual = RcsClientTypes.findByUuid(expected.uuid.toString())
            assertEquals(expected, actual)
        }
        assertNull(RcsClientTypes.findByUuid("00000000-0000-0000-0000-000000000000"))
        assertThrows(IllegalArgumentException::class.java) {
            RcsClientTypes.requireByUuid("not-a-uuid")
        }
    }

    @Test
    fun clientTypesCarrySemanticFeatureAndTransportMetadata() {
        val data = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA
        assertEquals(AirPlayFeature.VEHICLE_STATE_PROTOCOL, data.feature)
        assertEquals(0, data.priority)
        assertEquals(0, data.qualityOfService)
        assertEquals(0, data.streamPriority)
        assertEquals(RcsPayloadStyle.CAF_BINARY_PLIST_OPACK, data.payloadStyle)

        val data2 = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2
        assertEquals(1, data2.priority)
        assertEquals(12, data2.qualityOfService)
        assertEquals(33, data2.streamPriority)

        assertEquals(
            AirPlayFeature.UI_SYNC,
            RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.feature,
        )
        assertEquals(
            RcsPayloadStyle.UNCONFIRMED_OPAQUE,
            RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.payloadStyle,
        )
        assertEquals(RcsPayloadStyle.RAW_IAP2, RcsClientTypes.IAP_CHANNEL.payloadStyle)
    }
}
