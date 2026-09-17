package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsPayloadStyle
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CafRcsCodecTest {
    @Test
    fun configRequestUsesBplistOuterAndOpackInner() {
        val frame = CarAccessoryMessages.configRequest(pluginId = 7, transactionId = 42)
        @Suppress("UNCHECKED_CAST")
        val wrapper = BplistCodec.decode(frame.rcsBody) as Map<Any?, Any?>
        @Suppress("UNCHECKED_CAST")
        val params = wrapper["params"] as Map<Any?, Any?>
        @Suppress("UNCHECKED_CAST")
        val outer = OpackCodec.decode(params["data"] as ByteArray) as Map<Any?, Any?>

        assertEquals(7L, outer["pluginID"])
        val reader = CarAccessoryMessages.reader(frame.rcsBody)
        assertEquals(7L, reader.pluginId)
        assertEquals(CafCommand.CONFIG_REQUEST, reader.command)
        assertEquals(42L, reader.requireTransactionId())
    }

    @Test
    fun registerAndWriteFactoriesRoundTrip() {
        val wildcard = CarAccessoryMessages.reader(
            CarAccessoryMessages.registerRequest(1, 2).rcsBody,
        )
        assertEquals(CafCommand.REGISTER_REQUEST, wildcard.command)
        assertEquals("*", wildcard.values())

        val selected = CarAccessoryMessages.reader(
            CarAccessoryMessages.registerRequest(
                pluginId = 1,
                transactionId = 3,
                registration = CafRegistration.Identifiers(listOf(10, 11)),
            ).rcsBody,
        )
        assertEquals(listOf(10L, 11L), selected.valuesList())

        val write = CarAccessoryMessages.reader(
            CarAccessoryMessages.writeRequest(
                pluginId = 1,
                transactionId = 4,
                values = linkedMapOf(10L to 21, 11L to null),
            ).rcsBody,
        )
        assertEquals(mapOf(10L to 21L, 11L to null), write.valuesMap())
    }

    @Test
    fun controlAndErrorMessagesRemainTyped() {
        val control = CarAccessoryMessages.reader(
            CarAccessoryMessages.controlRequest(
                pluginId = 2,
                transactionId = 5,
                values = linkedMapOf(20L to null),
            ).rcsBody,
        )
        assertEquals(CafCommand.CONTROL_REQUEST, control.command)
        assertTrue(control.valuesMap().containsKey(20L))

        val error = CarAccessoryMessages.reader(
            CarAccessoryMessages.generalError(pluginId = 2, error = -50, transactionId = 6).rcsBody,
        )
        assertEquals(CafCommand.GENERAL_ERROR, error.command)
        assertEquals(-50L, error.requireError())
    }

    @Test
    fun commandCatalogIsQueryableAndIapChannelStaysRaw() {
        assertEquals(16, CafCommand.entries.size)
        assertEquals(CafCommand.CONTROL_NOTIFY, CafCommand.require("controlNotify"))
        assertEquals(RcsPayloadStyle.RAW_IAP2, RcsClientTypes.IAP_CHANNEL.payloadStyle)

        val rawIap = byteArrayOf(0x40, 0x40, 1, 2, 3)
        val message = IapChannelPayload.asRcsMessage(rawIap)
        assertEquals(
            com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec.MESSAGE_TYPE_COMM,
            message.messageType,
        )
        assertArrayEquals(rawIap, message.body)
        assertArrayEquals(rawIap, IapChannelPayload.from(message))
    }

    @Test
    fun malformedCommandShapeIsRejected() {
        val failure = assertThrows(CafProtocolException::class.java) {
            CafRcsCodec.encode(
                CafEnvelope(
                    pluginId = 1,
                    message = CafMessage(
                        command = CafCommand.WRITE_RESPONSE,
                        transactionId = 9,
                    ),
                ),
            )
        }
        assertTrue(failure.message!!.contains("requires errors"))
    }
}
