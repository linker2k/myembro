// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro

import android.content.Intent
import android.hardware.usb.UsbManager

/** Inspect only action/flags: a history USB launch must never deserialize a stale UsbDevice. */
internal fun routeLaunchIntent(
    action: String?,
    flags: Int,
    dispatchCurrent: Boolean,
    onUsbHistoryLaunch: () -> Unit,
    onCurrentLaunch: () -> Unit,
) {
    if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED &&
        flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
        onUsbHistoryLaunch()
    } else if (dispatchCurrent) {
        onCurrentLaunch()
    }
}
