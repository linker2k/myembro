// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import java.util.Locale

/** File data allocation only; FAT/root/boot metadata is outside the usable data-cluster pool. */
internal fun usbAllocatedBytes(size: Long, clusterBytes: Int): Long {
    require(size >= 0 && clusterBytes > 0)
    val clusters = size / clusterBytes + if (size % clusterBytes == 0L) 0 else 1
    return Math.multiplyExact(clusters, clusterBytes.toLong())
}

/** Machine-style KB = 1024 bytes, rounded for the short summary; diagnostics retain exact bytes. */
internal fun formatUsbBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    require(bytes >= 0)
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(locale, "%.0fKB", bytes / 1024.0)
        else -> String.format(locale, "%.1fMB", bytes / (1024.0 * 1024))
    }
}
