// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import io.github.linker2k.myembro.R
import android.hardware.usb.UsbManager
import io.github.linker2k.myembro.engine.io.PesExport
import io.github.linker2k.myembro.engine.io.PesWriteSettings
import java.io.File

/**
 * Exports through SAF when mounted volumes exist, otherwise exposes a permission-gated
 * USB BOT/SCSI route for the SE400 FAT12 RAM disk.
 *
 * Android only lets apps write to a USB drive after the user grants a folder on it once; the
 * grant is persisted per volume UUID so later exports go straight through.
 */
class UsbExporter(private val context: Context) {
    private val storage = context.getSystemService(StorageManager::class.java)
    private val prefs = context.getSharedPreferences("usb_grants", Context.MODE_PRIVATE)

    internal val directAccess = DirectUsbAccess(context)
    internal val directExporter = DirectUsbExporter(directAccess)

    /** SAF volume or an unmounted BOT/SCSI target; direct IDs never persist a folder grant. */
    data class Drive(val label: String, val uuid: String?, internal val volume: StorageVolume?,
        internal val direct: DirectUsbTarget? = null) {
        val isDirect: Boolean get() = direct != null
    }

    class Report(val exported: List<String>, val converted: Int, val failures: List<Pair<String, String>>)

    fun drives(): List<Drive> {
        val mounted = storage.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
            .map { Drive(it.getDescription(context), it.uuid, it) }
        if (mounted.isNotEmpty()) return mounted
        return directAccess.devices().map { target ->
            Drive(context.getString(R.string.usb_direct_drive, target.device.productName ?: "USB ${target.device.vendorId}:${target.device.productId}"),
                target.id, null, target)
        }
    }

    /** The folder previously granted on [drive], if the permission is still held. */
    fun grantedFolder(drive: Drive): Uri? {
        val saved = prefs.getString(drive.uuid ?: return null, null)?.let(Uri::parse) ?: return null
        val held = context.contentResolver.persistedUriPermissions.any { it.uri == saved && it.isWritePermission }
        return saved.takeIf { held }
    }

    /** Intent asking the user to pick (and thereby grant) a folder; opens at the drive's root on Android 10+. */
    fun accessIntent(drive: Drive): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) requireNotNull(drive.volume).createOpenDocumentTreeIntent()
        else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

    /**
     * Persists the grant for [tree]. Returns false if the user picked a folder that is not on
     * [drive] (e.g. internal storage), so the UI can ask again instead of exporting to the phone.
     */
    fun remember(drive: Drive, tree: Uri): Boolean {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val onDrive = drive.uuid == null || docId.substringBefore(':').equals(drive.uuid, ignoreCase = true)
        if (!onDrive) return false
        context.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        drive.uuid?.let { prefs.edit().putString(it, tree.toString()).apply() }
        return true
    }

    /** Writes each design as PES into [tree]. Blocking: call from a background dispatcher. */
    fun export(tree: Uri, designs: List<File>, settings: PesWriteSettings = PesWriteSettings()): Report {
        val folder = DocumentFile.fromTreeUri(context, tree) ?: error("Cannot open the USB folder")
        val existing = folder.listFiles().mapNotNull { it.name }.toMutableSet()
        val exported = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, String>>()
        var converted = 0
        for (design in designs) {
            runCatching {
                val prepared = PesExport.prepare(design.readBytes(), design.name, settings)
                val name = PesExport.uniqueName(prepared.fileName) { it in existing }
                // octet-stream keeps the exact file name; a PES MIME type would let providers rename it.
                val doc = folder.createFile("application/octet-stream", name)
                    ?: error("Could not create $name")
                context.contentResolver.openOutputStream(doc.uri, "w")?.use { it.write(prepared.bytes) }
                    ?: error("Could not write $name")
                existing += name
                exported += name
                if (prepared.converted) converted++
            }.onFailure { failures += design.name to (it.message ?: it.javaClass.simpleName) }
        }
        return Report(exported, converted, failures)
    }

    /** Calls [onChange] whenever removable media is mounted or removed. Returns an unregister function. */
    fun watch(onChange: () -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onChange()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onChange()
        }
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, usbReceiver, usbFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return { context.unregisterReceiver(receiver); context.unregisterReceiver(usbReceiver) }
    }
}
