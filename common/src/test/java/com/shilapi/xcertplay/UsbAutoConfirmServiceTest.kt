package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAutoConfirmServiceTest {

    @Test
    fun onlySystemUsbActivitiesAreAccepted() {
        assertTrue(
            UsbAutoConfirmService.isSystemUsbWindow(
                "com.android.systemui",
                "com.android.systemui.usb.UsbPermissionActivity",
            ),
        )
        assertTrue(
            UsbAutoConfirmService.isSystemUsbWindow(
                "com.android.systemui",
                "com.android.systemui.usb.UsbConfirmActivity",
            ),
        )
        assertTrue(
            UsbAutoConfirmService.isSystemUsbWindow(
                "android",
                "com.android.systemui.usb.UsbPermissionActivity",
            ),
        )
        assertFalse(
            UsbAutoConfirmService.isSystemUsbWindow(
                "com.other.app",
                "com.android.systemui.usb.UsbPermissionActivity",
            ),
        )
        assertFalse(
            UsbAutoConfirmService.isSystemUsbWindow(
                "com.android.systemui",
                "android.app.AlertDialog",
            ),
        )
        assertFalse(
            UsbAutoConfirmService.isSystemUsbWindow(
                "com.android.systemui",
                "com.android.systemui.media.MediaProjectionPermissionActivity",
            ),
        )
        assertFalse(UsbAutoConfirmService.isSystemUsbWindow(null, null))
    }

    @Test
    fun promptMustNameAppAndUsbExplicitly() {
        assertTrue(
            UsbAutoConfirmService.isTargetPrompt(
                "Allow xcertplay to access this USB device?",
                "xcertplay",
            ),
        )
        assertTrue(
            UsbAutoConfirmService.isTargetPrompt(
                "允许 xcertplay 访问 USB 设备？",
                "xcertplay",
            ),
        )
        assertTrue(
            UsbAutoConfirmService.isTargetPrompt(
                "Allow Xcertplay to access the USB device?",
                "Xcertplay",
            ),
        )
        assertFalse(
            UsbAutoConfirmService.isTargetPrompt(
                "Allow CarPlay access to iPhone?",
                "xcertplay",
            ),
        )
        assertFalse(
            UsbAutoConfirmService.isTargetPrompt(
                "Allow xcertplay to access contacts?",
                "xcertplay",
            ),
        )
        assertFalse(
            UsbAutoConfirmService.isTargetPrompt(
                "Allow Fake_xcertplay USB access?",
                "xcertplay",
            ),
        )
        assertFalse(
            UsbAutoConfirmService.isTargetPrompt(
                "Allow USB access?",
                "",
            ),
        )
    }
}
