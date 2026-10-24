// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro

import android.content.Intent
import android.hardware.usb.UsbManager
import kotlin.test.*

class LaunchIntentPolicyTest {
    @Test fun usbHistoryLaunchNeverDispatchesDeviceExtrasEvenWhenRestoringSavedState() {
        var normalLaunches = 0
        for (dispatch in listOf(true, false)) {
            routeLaunchIntent(UsbManager.ACTION_USB_DEVICE_ATTACHED,
                Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY or Intent.FLAG_ACTIVITY_NEW_TASK,
                dispatch, { normalLaunches++ }, { error("Stale USB extras must not be accessed") })
        }
        assertEquals(2, normalLaunches)
        // A fresh USB attach or history VIEW still follows the existing handler.
        var currentLaunches = 0
        for ((action,flags) in listOf(UsbManager.ACTION_USB_DEVICE_ATTACHED to 0,
            Intent.ACTION_VIEW to Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)) {
            routeLaunchIntent(action,flags,true,{ error("Not a stale USB launch") },{ currentLaunches++ })
        }
        routeLaunchIntent(Intent.ACTION_VIEW,0,false,{ error("Not USB history") },{ error("Do not repeat restored imports") })
        assertEquals(2,currentLaunches)
    }
}
