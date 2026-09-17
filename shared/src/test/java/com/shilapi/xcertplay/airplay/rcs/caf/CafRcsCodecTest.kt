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

    @Test
    fun unknownCommandAndPluginAreRejected() {
        val inner = OpackCodec.encode(
            linkedMapOf(
                "command" to "unknownCommand",
                "transactionID" to 1L,
            ),
        )
        val outer = OpackCodec.encode(
            linkedMapOf(
                "pluginID" to 7L,
                "pluginData" to inner,
            ),
        )
        val body = BplistCodec.encode(
            linkedMapOf(
                "params" to linkedMapOf(
                    "data" to outer,
                ),
            ),
        )

        assertThrows(CafProtocolException::class.java) {
            CarAccessoryMessages.reader(body)
        }
        assertThrows(CafProtocolException::class.java) {
            CafPluginRegistry(emptyMap()).require(7L)
        }
    }

    @Test
    fun configTreeFactoryDecodesConfirmedProtocolOneSchema() {
        val decoder = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1.create(
            protocolVersion = "1.0",
            pluginConfig = mapOf("pluginID" to 7),
        )
        val decoded = decoder.decode(
            linkedMapOf(
                "accessories" to listOf(
                    linkedMapOf(
                        "type" to CLIMATE_ACCESSORY,
                        "iid" to 1L,
                        "version" to "1.0",
                        "services" to listOf(
                            linkedMapOf(
                                "type" to TEMPERATURE_SERVICE,
                                "iid" to 2L,
                                "characteristics" to listOf(
                                    linkedMapOf(
                                        "type" to TARGET_TEMPERATURE,
                                        "iid" to 3L,
                                        "format" to 9L,
                                        "writable" to true,
                                        "initialValue" to 22.5,
                                        "priority" to 1L,
                                        "oemExtension" to "kept",
                                    ),
                                ),
                                "controls" to listOf(
                                    linkedMapOf(
                                        "type" to 0x000000000F000032L,
                                        "iid" to 4L,
                                        "sender" to "device",
                                        "hasResponse" to true,
                                        "requestParameters" to listOf(
                                            linkedMapOf(
                                                "name" to "reason",
                                                "format" to 10L,
                                                "supportsInvalid" to false,
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                "oemRootExtension" to 7,
            ),
        )

        val accessory = decoded.accessories.single()
        assertEquals(CLIMATE_ACCESSORY, accessory.type.value)
        assertEquals("Climate", CafTypeCatalog.find(CafTypeKind.ACCESSORY, accessory.type)?.name)
        val characteristic = accessory.services.single().characteristics.single()
        assertEquals(CafCharacteristicFormat.FLOAT, characteristic.format)
        assertEquals(
            CafFormatValue.Floating(22.5),
            characteristic.initialValue,
        )
        assertEquals("kept", characteristic.extensions["oemExtension"])
        val control = accessory.services.single().controls.single()
        assertEquals(CafControlSender.DEVICE, control.sender)
        assertTrue(control.hasResponse)
        assertEquals(CafCharacteristicFormat.STRING, control.requestParameters.single().format)
        assertEquals(7, decoded.extensions["oemRootExtension"])
    }

    @Test
    fun configTreeFactoryRejectsUnknownVersionAndMalformedValues() {
        assertThrows(CafProtocolException::class.java) {
            ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1.create(
                protocolVersion = "2.0",
                pluginConfig = null,
            )
        }
        assertThrows(CafProtocolException::class.java) {
            CafConfigTree.decode(
                linkedMapOf(
                    "accessories" to listOf(
                        linkedMapOf(
                            "type" to 1L,
                            "iid" to 1L,
                            "version" to "1.0",
                            "services" to listOf(
                                linkedMapOf(
                                    "type" to 2L,
                                    "iid" to 2L,
                                    "characteristics" to listOf(
                                        linkedMapOf(
                                            "type" to 3L,
                                            "iid" to 3L,
                                            "format" to 99L,
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun directionalFactoriesKeepRequestResponseAndNotificationSeparate() {
        val request = CafDirectionalMessage.from(
            CarAccessoryMessages.readRequest(1, 2, listOf(3)),
        )
        val response = CafDirectionalMessage.from(
            CarAccessoryMessages.readResponse(1, 2, emptyMap(), emptyMap()),
        )
        val notification = CafDirectionalMessage.from(
            CarAccessoryMessages.updateNotify(1, mapOf(3L to 4)),
        )

        assertTrue(request is CafDirectionalMessage.Request)
        assertTrue(response is CafDirectionalMessage.Response)
        assertTrue(notification is CafDirectionalMessage.Notification)
    }

    private companion object {
        const val CLIMATE_ACCESSORY = 0x0000000001000001L
        const val TEMPERATURE_SERVICE = 0x0000000011000002L
        const val TARGET_TEMPERATURE = 0x0000000031000017L
    }
}
