package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MfiTargetConfigTest {
    @Test
    fun usbTargetRequiresACh341Device() {
        assertThrows(IllegalArgumentException::class.java) {
            config(mfiTarget = MfiTarget.USB_CH341)
        }
    }

    @Test
    fun i2cTargetUsesTheConfiguredDevicePath() {
        val config = config(
            mfiTarget = MfiTarget.I2C,
            linuxI2cPath = "/dev/i2c-7",
        )

        assertEquals("/dev/i2c-7", config.linuxI2cPath)
    }

    @Test
    fun i2cTargetRejectsABlankDevicePath() {
        assertThrows(IllegalArgumentException::class.java) {
            config(mfiTarget = MfiTarget.I2C, linuxI2cPath = "  ")
        }
    }

    @Test
    fun remoteTargetDoesNotRequireALocalDevice() {
        val config = config(
            mfiTarget = MfiTarget.REMOTE,
            remoteMfiServer = "https://mfi.example.test",
            remoteMfiToken = "secret",
        )

        assertEquals(MfiTarget.REMOTE, config.mfiTarget)
        assertEquals("https://mfi.example.test", config.remoteMfiServer)
        assertEquals("secret", config.remoteMfiToken)
    }

    @Test
    fun remoteTargetRequiresAServerAddress() {
        assertThrows(IllegalArgumentException::class.java) {
            config(mfiTarget = MfiTarget.REMOTE, remoteMfiServer = "  ")
        }
    }

    @Test
    fun localFilesTargetAcceptsPickedDocuments() {
        val config = config(
            mfiTarget = MfiTarget.LOCAL_FILES,
            localMfiCertificateUri = "content://com.android.externalstorage.documents/document/primary%3Acert.pem",
            localMfiPrivateKeyUri = "content://com.android.externalstorage.documents/document/primary%3Akey.pem",
        )

        assertEquals(MfiTarget.LOCAL_FILES, config.mfiTarget)
    }

    @Test
    fun localFilesTargetAcceptsThePrivateCopies() {
        // BYD's picker cannot grant access, so the documents are copied into the app's files.
        val config = config(
            mfiTarget = MfiTarget.LOCAL_FILES,
            localMfiCertificateUri = "file:///data/user/0/com.shilapi.xcertplay/files/mfi/certificate/cert.pem",
            localMfiPrivateKeyUri = "file:///data/user/0/com.shilapi.xcertplay/files/mfi/private-key/key.pem",
        )

        assertEquals(
            "file:///data/user/0/com.shilapi.xcertplay/files/mfi/certificate/cert.pem",
            config.localMfiCertificateUri,
        )
    }

    @Test
    fun localFilesTargetRejectsOtherAddresses() {
        assertThrows(IllegalArgumentException::class.java) {
            config(
                mfiTarget = MfiTarget.LOCAL_FILES,
                localMfiCertificateUri = "/sdcard/Download/cert.pem",
                localMfiPrivateKeyUri = "file:///sdcard/Download/key.pem",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            config(
                mfiTarget = MfiTarget.LOCAL_FILES,
                localMfiCertificateUri = "file:///sdcard/Download/cert.pem",
                localMfiPrivateKeyUri = "https://example.test/key.pem",
            )
        }
    }

    private fun config(
        mfiTarget: MfiTarget,
        ch341Devices: List<UsbDeviceId> = emptyList(),
        linuxI2cPath: String? = null,
        remoteMfiServer: String? = null,
        remoteMfiToken: String? = null,
        localMfiCertificateUri: String? = null,
        localMfiPrivateKeyUri: String? = null,
    ): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        ch341Devices = ch341Devices,
        linuxI2cPath = linuxI2cPath,
        remoteMfiServer = remoteMfiServer,
        remoteMfiToken = remoteMfiToken,
        localMfiCertificateUri = localMfiCertificateUri,
        localMfiPrivateKeyUri = localMfiPrivateKeyUri,
        identification = Iap2IdentificationConfig(
            name = "test",
            modelIdentifier = "test",
            manufacturer = "test",
            serialNumber = "test",
            firmwareVersion = "1",
            hardwareVersion = "1",
            carPlayUsbInterfaceNumber = 3,
        ),
    )
}
