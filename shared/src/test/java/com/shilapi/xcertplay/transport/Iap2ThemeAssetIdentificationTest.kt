package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAsset
import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test

class Iap2ThemeAssetIdentificationTest {
    private fun identity() = Iap2IdentificationConfig(
        "vehicle", "model", "manufacturer", "serial", "1", "1", 1,
        wireless = Iap2WirelessIdentification("12:34:56:78:9A:BC", "wifi"),
    )

    // Independent length/ID parsing, rather than a round trip through the product's TLV reader.
    private fun parameters(bytes: ByteArray): Map<Int, ByteArray> {
        fun u16(offset: Int) = ((bytes[offset].toInt() and 255) shl 8) or
            (bytes[offset + 1].toInt() and 255)
        val result = linkedMapOf<Int, ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            require(offset + 4 <= bytes.size)
            val length = u16(offset)
            require(length >= 4 && offset + length <= bytes.size)
            val id = u16(offset + 2)
            require(id !in result)
            result[id] = bytes.copyOfRange(offset + 4, offset + length)
            offset += length
        }
        return result
    }

    private fun wirelessParameters(config: Iap2IdentificationConfig) = parameters(
        parameters(Iap2IdentificationClient.identificationInformation(config).payload).getValue(24),
    )

    @Test fun unconfiguredAssetDoesNotUseBluetoothMacAsThemeIdentity() {
        assertFalse(wirelessParameters(identity()).containsKey(6))
        val bluetooth = parameters(parameters(
            Iap2IdentificationClient.identificationInformation(identity()).payload).getValue(17))
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9a.toByte(), 0xbc.toByte()), bluetooth[3])
    }

    @Test fun classicIdentificationDoesNotDeclareThemeSupport() {
        assertFalse(wirelessParameters(identity()).containsKey(5))
        assertTrue(wirelessParameters(identity()).containsKey(4))
    }

    private fun airPlay(profile: Map<String, Any?> = emptyMap()): AirPlayConfig {
        val provisioning = CarPlayUltraVehicleProvisioning.create(profile = profile)
        return AirPlayConfig("vehicle", "device", "bt", "1", AirPlayDisplayConfig(1280, 720),
            ultra = provisioning.createConfig(AirPlayDisplayConfig(640, 240), provisioning.createRuntime()))
    }

    @Test fun deployedIdentityIsIdenticalInIdentificationAndStartSession() {
        val assetId = "xcertplay.test.asset"
        val config = airPlay(mapOf("clusterAsset" to mapOf("assetID" to assetId, "assetVer" to 0xffff_ffffL)))
        val identification = CarPlayUltraIdentification.configure(identity(), config)
        val expected = (assetId + '\u0000').toByteArray(Charsets.UTF_8)
        val wireless = wirelessParameters(identification)
        assertArrayEquals(byteArrayOf(), wireless[5])
        assertArrayEquals(expected, wireless[6])
        val session = Iap2CarPlayMessages.startSession(7000, "key", "1", clusterAsset = config.ultra!!.clusterAsset)
        val asset = parameters(parameters(session.payload).getValue(7))
        assertArrayEquals(expected, asset[0])
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), asset[1])
    }

    @Test fun referenceModeDoesNotClaimThemeSupportBeforeAssetDeployment() {
        val config = airPlay()
        val identification = CarPlayUltraIdentification.configure(identity(), config)
        assertFalse(wirelessParameters(identification).containsKey(5))
        assertFalse(wirelessParameters(identification).containsKey(6))
        assertFalse(parameters(Iap2CarPlayMessages.startSession(7000, "key", "1",
            clusterAsset = config.ultra!!.clusterAsset).payload).containsKey(7))
        val proposal = AirPlayFeatureNegotiation.propose(config, listOf("uiSync", "fileTransfer"), true)
        assertTrue("uiSync" in proposal.enabledFeatures)
        assertFalse("fileTransfer" in proposal.enabledFeatures)
    }

    @Test fun deployedThemeCannotAdvertiseAnEmptyOrEmbeddedNulSdk() {
        val config = airPlay(mapOf("clusterAsset" to mapOf("assetID" to "asset", "assetVer" to 0)))
        for (sdk in listOf("", " ", "19.0\u0000suffix")) {
            assertThrows(IllegalArgumentException::class.java) {
                CarPlayUltraIdentification.configure(identity(), config.copy(ultra = config.ultra!!.copy(sdkVersion = sdk)))
            }
        }
    }

    private fun wirelessEndpoint(sdk: String?, asset: Iap2ClusterAsset?) = Iap2WirelessCarPlayEndpoint(
        "wifi", "password", 6, Iap2WirelessSecurity.WPA_WPA2, listOf("192.0.2.1"),
        7000, "vehicle", "key", "1", sdkVersion = sdk, clusterAsset = asset)

    @Test fun assetWithoutSdkCannotBecomeAClassicStartSessionOnEitherTransport() {
        val asset = Iap2ClusterAsset("asset", 0)
        assertThrows(IllegalArgumentException::class.java) { wirelessEndpoint(null, asset) }
        assertThrows(IllegalArgumentException::class.java) {
            Iap2WiredCarPlayEndpoint(listOf("fe80::2"), 7000, "key", "1", clusterAsset = asset)
        }
        for (sdk in listOf("", " ", "19.0\u0000suffix")) {
            assertThrows(IllegalArgumentException::class.java) { wirelessEndpoint(sdk, asset) }
        }
    }

    @Test fun zeroAssetVersionStillCarriesCompletePhoneAssetInformation() {
        val config = airPlay(mapOf("sdkVersion" to "19.0",
            "clusterAsset" to mapOf("assetID" to "asset", "assetVer" to 0)))
        val identification = CarPlayUltraIdentification.configure(identity(), config)
        val endpoint = wirelessEndpoint(config.ultra!!.sdkVersion, config.ultra!!.clusterAsset)
        val fields = parameters(Iap2WirelessControlClient.carPlayStartSession(endpoint).payload)
        val asset = parameters(fields.getValue(7))
        // carkitd checks a present NSNumber, not assetVersion > 0 (0x1000671e4).
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), asset[1])
        assertArrayEquals("19.0\u0000".toByteArray(), fields[6])
        assertArrayEquals(wirelessParameters(identification)[6], asset[0])
    }

    @Test fun preflightRejectsMissingOrDifferentStartAssetForThemeIdentification() {
        val declared = identity().copy(supportsThemeAssets = true, themeAssetIdentifier = "declared.asset")
        assertThrows(IllegalArgumentException::class.java) {
            Iap2CarPlayThemeContract.validateIdentification(declared, "19.0", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Iap2CarPlayThemeContract.validateIdentification(declared, "19.0", Iap2ClusterAsset("other.asset", 0))
        }
        Iap2CarPlayThemeContract.validateIdentification(declared, "19.0", Iap2ClusterAsset("declared.asset", 0))
    }

    @Test fun classicRestartClearsPreviouslyConfiguredThemeIdentity() {
        val base = identity().copy(supportsThemeAssets = true, themeAssetIdentifier = "prior.asset")
        val classic = airPlay().copy(ultra = null)
        val wireless = wirelessParameters(CarPlayUltraIdentification.configure(base, classic))
        assertFalse(wireless.containsKey(5))
        assertFalse(wireless.containsKey(6))
    }

    @Test fun assetRequiresReadyUiSyncAndValidCString() {
        assertThrows(IllegalArgumentException::class.java) {
            identity().copy(themeAssetIdentifier = "asset")
        }
        for (invalid in listOf("", " ", "valid\u0000suffix")) {
            assertThrows(IllegalArgumentException::class.java) { Iap2ClusterAsset(invalid, 1) }
            assertThrows(IllegalArgumentException::class.java) {
                identity().copy(supportsThemeAssets = true, themeAssetIdentifier = invalid)
            }
        }
        val config = airPlay(mapOf("clusterAsset" to mapOf("assetID" to "asset", "assetVer" to 1)))
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayUltraIdentification.configure(identity(), config.copy(ultra = config.ultra!!.copy(readyFeatures = emptySet())))
        }
    }

    @Test fun malformedDeploymentAssetCannotBeSilentlyOmittedOrTruncated() {
        val invalid = listOf<Any>("asset", mapOf("assetID" to "asset", "assetVer" to 1.5),
            mapOf("assetID" to "asset", "assetVer" to -1),
            mapOf("assetID" to "asset", "assetVer" to 0x1_0000_0000L))
        for (asset in invalid) {
            assertThrows(IllegalArgumentException::class.java) { airPlay(mapOf("clusterAsset" to asset)) }
        }
    }

    @Test fun authenticationCopyPreservesThemeConfiguration() {
        val configured = CarPlayUltraIdentification.configure(identity(), airPlay(
            mapOf("clusterAsset" to mapOf("assetID" to "asset", "assetVer" to 1))))
        val provider = object : MfiAuthenticator {
            override fun protocolMajor() = 3
            override fun readCertificate(maximumOutputLength: Int) = byteArrayOf(1)
            override fun signChallenge(challenge: ByteArray) = byteArrayOf(2)
        }
        val result = Iap2MfiAuthenticationClient(provider).identification(configured)
        assertArrayEquals(wirelessParameters(configured)[6], wirelessParameters(result)[6])
        assertTrue(result.supportsThemeAssets)
    }

    @Test fun wiredStartSessionPreservesSdkAndAsset() {
        val frame = Iap2WiredControlClient.carPlayStartSession(Iap2WiredCarPlayEndpoint(
            listOf("fe80::2"), 7000, "key", "1", sdkVersion = "2.0", clusterAsset = Iap2ClusterAsset("asset", 42)))
        val body = parameters(frame.payload)
        assertArrayEquals("2.0\u0000".toByteArray(), body[6])
        val asset = parameters(body.getValue(7))
        assertArrayEquals("asset\u0000".toByteArray(), asset[0])
        assertArrayEquals(byteArrayOf(0, 0, 0, 42), asset[1])
        assertFalse(body.containsKey(1)) // Wired session must not acquire a wireless group.
    }
}
