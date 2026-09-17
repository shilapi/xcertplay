package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CafTransactionTrackerTest {
    @Test
    fun responseCompletesOnlyTheMatchingRequest() {
        val tracker = CafTransactionTracker()
        val pending = tracker.begin(
            pluginId = 7,
            requestCommand = CafCommand.WRITE_REQUEST,
            expectedResponses = setOf(CafCommand.WRITE_RESPONSE, CafCommand.GENERAL_ERROR),
        )
        val response = CarAccessoryMessages.reader(
            CarAccessoryMessages.writeResponse(
                pluginId = 7,
                transactionId = pending.transactionId,
                errors = emptyMap(),
            ).rcsBody,
        )

        assertEquals(pending, tracker.accept(response))
        assertEquals(0, tracker.size())
    }

    @Test
    fun mismatchedResponseAndPluginAreRejected() {
        val tracker = CafTransactionTracker()
        val pending = tracker.begin(
            pluginId = 7,
            requestCommand = CafCommand.READ_REQUEST,
            expectedResponses = setOf(CafCommand.READ_RESPONSE),
        )
        val wrongCommand = CarAccessoryMessages.reader(
            CarAccessoryMessages.writeResponse(
                pluginId = 7,
                transactionId = pending.transactionId,
                errors = emptyMap(),
            ).rcsBody,
        )
        val wrongPlugin = CarAccessoryMessages.reader(
            CarAccessoryMessages.readResponse(
                pluginId = 8,
                transactionId = pending.transactionId,
                values = emptyMap(),
                errors = emptyMap(),
            ).rcsBody,
        )

        assertThrows(CafProtocolException::class.java) {
            tracker.accept(wrongCommand)
        }
        assertThrows(CafProtocolException::class.java) {
            tracker.accept(wrongPlugin)
        }
    }

    @Test
    fun cafHandlerFactoryRoutesOnlyRegisteredClientTypes() {
        val handler = object : CafPluginHandler {}
        val registry = CafPluginRegistry.from(
            listOf(
                CafPluginRegistration(
                    pluginId = 7,
                    protocolVersion = "1.0",
                    clientTypes = setOf(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
                    handler = handler,
                ),
            ),
        )
        val factory = CafProtocolSession.handlerFactory(registry)

        assertTrue(factory.supports(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA))
        assertFalse(factory.supports(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2))
        assertFalse(factory.supports(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL))
    }
}
