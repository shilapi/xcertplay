package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.iap2.message.*
import com.shilapi.xcertplay.mfi.*
import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test

class UltraCapabilityRouteTest {
    private fun identity() = Iap2IdentificationConfig("vehicle", "model", "manufacturer", "serial", "1", "1", 1)
    private class Provider(private val enabled: Boolean) : MfiAuthenticator {
        override val mfi4SentMessages = if (enabled) setOf(0x5113, 0x5164, 0x5501, 0x5503) else emptySet()
        override val mfi4ReceivedMessages = if (enabled) setOf(0x5112, 0x51ff, 0x5164, 0x5500, 0x5502) else emptySet()
        override fun protocolMajor() = 3
        override fun readCertificate(maximumOutputLength: Int) = byteArrayOf(1)
        override fun signChallenge(challenge: ByteArray) = byteArrayOf(2)
    }
    @Test fun identificationDeclaresExactlyTheProvidersAuthenticationEndpoints() {
        val provider = Provider(true)
        val client = Iap2MfiAuthenticationClient(provider)
        val reader = Iap2Messages.reader(Iap2IdentificationClient.identificationInformation(client.identification(identity())))
        assertTrue(reader.u16List(6).containsAll(provider.mfi4SentMessages))
        assertTrue(reader.u16List(7).containsAll(provider.mfi4ReceivedMessages))
        assertFalse(0x5115 in reader.u16List(7))
    }
    @Test fun fixedAuthenticationWhitelistDoesNotChangeServiceDeclarations() {
        val client = Iap2MfiAuthenticationClient(Provider(false))
        val configs = listOf(identity(), identity().copy(wireless =
            Iap2WirelessIdentification("02:11:22:33:44:55", "vehicle-wifi")))
        for (config in configs) {
            val baseline = Iap2Messages.reader(Iap2IdentificationClient.identificationInformation(config))
            val actual = Iap2Messages.reader(Iap2IdentificationClient.identificationInformation(client.identification(config)))
            assertEquals(baseline.u16List(6), actual.u16List(6))
            assertEquals(baseline.u16List(7), actual.u16List(7))
            assertTrue(actual.u16List(6).none { it in 0xaa00..0xaa06 })
            assertTrue(actual.u16List(7).none { it in 0xaa00..0xaa06 })
        }
    }
    @Test fun legacyProviderCannotInvokeOrSendMfi4() {
        val client = Iap2MfiAuthenticationClient(Provider(false))
        val reader = Iap2Messages.reader(Iap2IdentificationClient.identificationInformation(client.identification(identity())))
        assertFalse(0x5164 in reader.u16List(6)); assertFalse(client.supportsUltra(0x5164))
        assertThrows(IllegalArgumentException::class.java) { client.handleUltra(Iap2UltraMessages.requestAuthCert(byteArrayOf())) }
    }
    @Test fun nestedReplyMustDeclareItsInnerEndpointToo() {
        val client = Iap2MfiAuthenticationClient(Provider(true))
        val reply = Iap2UltraMessages.relay(Iap2UltraMessages.tunnel(Iap2UltraMessages.authCert(0, byteArrayOf(1)), 0))
        client.validateUltraReply(reply)
        val invalid = Iap2UltraMessages.relay(Iap2UltraMessages.tunnel(Iap2UltraMessages.requestAuthStart(), 0))
        assertThrows(IllegalArgumentException::class.java) { client.validateUltraReply(invalid) }
    }
    @Test fun relayCannotSmuggleAnUndeclaredInnerRequest() {
        val client = Iap2MfiAuthenticationClient(Provider(true))
        val invalid = Iap2UltraMessages.relay(Iap2UltraMessages.tunnel(Iap2UltraMessages.requestAuthStart(), 0, false))
        assertThrows(IllegalArgumentException::class.java) { client.validateUltraIncoming(invalid) }
        val valid = Iap2UltraMessages.relay(Iap2UltraMessages.tunnel(Iap2UltraMessages.requestAuthCert(byteArrayOf()), 0, false))
        client.validateUltraIncoming(valid)
    }
    private fun display(send: (Map<String, Any?>) -> Boolean): CarPlayDisplayControlSession =
        CarPlayDisplayControlSession(AirPlayConfig("vehicle", "device", "bt", "1", AirPlayDisplayConfig(1280, 720),
            ultra = AirPlayUltraConfig(AirPlayDisplayConfig(640, 240))), send, {})
    @Test fun infoNeverInvokesFeaturesBeforeNegotiationAndRejectedFeaturesAreDiscarded() {
        val output = mutableListOf<Map<String, Any?>>()
        val control = display { output.add(it); true }
        control.info(AirPlayInfoRequest(listOf("cluster"), AirPlayUiContextRequest(nowOnDisplayUrls = listOf("main")), emptyMap()))
        control.flush(); assertTrue(output.isEmpty())
        control.negotiated(setOf(AirPlayFeature.VIEW_AREAS))
        assertTrue(output.isEmpty())
    }
    @Test fun inboundFeatureCommandCannotExecuteWithoutItsNegotiatedCapability() {
        val control = display { true }
        assertThrows(IllegalArgumentException::class.java) { control.receive("changeUIContext", mapOf("url" to "main")) }
        control.negotiated(setOf(AirPlayFeature.VIEW_AREAS))
        assertThrows(IllegalArgumentException::class.java) { control.receive("requestViewArea",
            mapOf("displayUUID" to AirPlayInfoPlist.ALT_UUID, "viewArea" to 0)) }
    }
    @Test fun acceptedFeatureStillWaitsForTheEventTransport() {
        var ready = false
        val output = mutableListOf<Map<String, Any?>>()
        val control = display { if (ready) { output.add(it); true } else false }
        control.info(AirPlayInfoRequest(listOf("cluster"), null, emptyMap()))
        control.negotiated(setOf(AirPlayFeature.UI_CONTEXT, AirPlayFeature.ALT_SCREEN))
        assertTrue(output.isEmpty())
        ready = true; control.flush()
        assertEquals("showUI", output.single()["type"])
    }
    @Test fun remoteCapabilityMetadataIsExplicitAndRangeChecked() {
        assertTrue(RemoteMfiJson.endpointIds("{}", "mfi4SentMessages").isEmpty())
        assertEquals(setOf(0x5113, 0x5164), RemoteMfiJson.endpointIds("{\"mfi4SentMessages\":[20755,20836]}", "mfi4SentMessages"))
        assertThrows(MfiInvalidDataException::class.java) { RemoteMfiJson.endpointIds("{\"mfi4SentMessages\":[65536]}", "mfi4SentMessages") }
    }
}
