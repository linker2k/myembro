// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

/** Warn-only advice for a USB flash drive folder; never blocks a save.
 * Basis: docs/research/usb-media-requirements.md [M1] (some machines read the root folder only) and
 * [B6] "The maximum total number of embroidery data files and folders you can save in a USB media is 200."
 */
internal object UsbFolderAdvice {
    /** [B6]: 200 entries is the stated maximum, so a full root (200, inclusive) already gets the warning. */
    const val MAX_MACHINE_ENTRIES = 200

    /** SAF tree document IDs on removable volumes are "<volume>:<path>"; an empty path is the drive root. */
    fun isRoot(treeDocumentId: String): Boolean =
        treeDocumentId.substringAfter(':', "").trim('/').isEmpty()

    /** [rootEntries] = files plus folders in the root after this export; counted only when the root itself was chosen. */
    fun notices(treeDocumentId: String, rootEntries: Int): Set<UsbTransferNotice> = buildSet {
        if (!isRoot(treeDocumentId)) add(UsbTransferNotice.NOT_ROOT_FOLDER)
        else if (rootEntries >= MAX_MACHINE_ENTRIES) add(UsbTransferNotice.MANY_ENTRIES)
    }
}
