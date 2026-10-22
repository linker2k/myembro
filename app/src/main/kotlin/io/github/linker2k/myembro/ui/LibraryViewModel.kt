// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.library.DesignLibrary
import io.github.linker2k.myembro.library.Thumbnails
import io.github.linker2k.myembro.usb.UsbExporter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val library = DesignLibrary(app)
    private val usb = UsbExporter(app)
    private val unwatch = usb.watch { refreshDrives() }

    /** One design in the list; [summary] and [thumbnail] fill in after parsing in the background. */
    data class Item(
        val file: File,
        val summary: String? = null,
        val thumbnail: Bitmap? = null,
        val error: String? = null,
    )

    val items = mutableStateListOf<Item>()
    val selection = mutableStateListOf<File>()
    var drives by mutableStateOf<List<UsbExporter.Drive>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set

    /** One-shot message for a snackbar; the screen clears it after showing. */
    var message by mutableStateOf<String?>(null)

    /** Set when the user must grant a folder on this drive; the screen launches the picker. */
    var accessRequest by mutableStateOf<UsbExporter.Drive?>(null)
        private set
    private var pendingExport: List<File> = emptyList()

    /** Several drives are mounted: the screen asks which one. */
    var driveChoice by mutableStateOf<List<UsbExporter.Drive>?>(null)
        private set

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { library.seedSampleIfEmpty() }
            reload()
        }
        refreshDrives()
    }

    override fun onCleared() = unwatch()

    fun refreshDrives() {
        drives = usb.drives()
    }

    private suspend fun reload() {
        val files = withContext(Dispatchers.IO) { library.list() }
        val cached = items.associateBy { it.file }
        items.clear()
        items += files.map { f -> cached[f]?.takeIf { it.thumbnail != null } ?: Item(f) }
        selection.retainAll(files.toSet())
        for (file in files) {
            if (items.firstOrNull { it.file == file }?.thumbnail != null) continue
            val loaded = withContext(Dispatchers.Default) { describe(file) }
            val i = items.indexOfFirst { it.file == file }
            if (i >= 0) items[i] = loaded
        }
    }

    private fun describe(file: File): Item = runCatching {
        val data = RenderData(PatternReader.read(file.readBytes(), file.name))
        val p = data.pattern
        val size = p.bounds?.let { "%.0f×%.0f mm".format(it.width / 10f, it.height / 10f) } ?: "-"
        val res = getApplication<Application>().resources
        val summary = res.getString(R.string.library_summary, size, p.stitchCount, p.colorBlocks.size)
        Item(file, summary, Thumbnails.render(data, 320))
    }.getOrElse { Item(file, error = it.message ?: it.javaClass.simpleName) }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val failed = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> runCatching { library.import(uri) }.exceptionOrNull()?.message }
            }
            reload()
            val res = getApplication<Application>().resources
            message = if (failed.isEmpty()) res.getString(R.string.library_imported, uris.size)
            else res.getString(R.string.library_import_failed, failed.first())
        }
    }

    /** Imports a file opened from outside the app and returns the library copy (for the viewer). */
    suspend fun importForViewing(uri: Uri): File {
        val file = withContext(Dispatchers.IO) { library.import(uri) }
        reload()
        return file
    }

    fun toggle(file: File) {
        if (!selection.remove(file)) selection += file
    }

    fun selectAll() {
        selection.clear()
        selection += items.map { it.file }
    }

    fun clearSelection() = selection.clear()

    fun deleteSelected() {
        val doomed = selection.toList()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { doomed.forEach(library::delete) }
            selection.clear()
            reload()
        }
    }

    /** Starts exporting [files] (default: the selection) to a USB drive, asking for access if needed. */
    fun exportToUsb(files: List<File> = selection.toList()) {
        if (files.isEmpty()) return
        refreshDrives()
        val res = getApplication<Application>().resources
        when (drives.size) {
            0 -> message = res.getString(R.string.usb_none)
            1 -> exportTo(drives.single(), files)
            else -> { pendingExport = files; driveChoice = drives }
        }
    }

    fun accessIntent(drive: UsbExporter.Drive) = usb.accessIntent(drive)

    fun chooseDrive(drive: UsbExporter.Drive?) {
        driveChoice = null
        if (drive != null) exportTo(drive, pendingExport) else pendingExport = emptyList()
    }

    private fun exportTo(drive: UsbExporter.Drive, files: List<File>) {
        val folder = usb.grantedFolder(drive)
        if (folder == null) {
            pendingExport = files
            accessRequest = drive
        } else {
            write(drive, folder, files)
        }
    }

    /** Result of the folder picker launched for [accessRequest]. */
    fun onAccessResult(tree: Uri?) {
        val drive = accessRequest ?: return
        accessRequest = null
        val res = getApplication<Application>().resources
        when {
            tree == null -> pendingExport = emptyList()
            !usb.remember(drive, tree) -> {
                message = res.getString(R.string.usb_wrong_folder, drive.label)
                pendingExport = emptyList()
            }
            else -> write(drive, tree, pendingExport)
        }
    }

    private fun write(drive: UsbExporter.Drive, folder: Uri, files: List<File>) {
        pendingExport = emptyList()
        viewModelScope.launch {
            busy = true
            val res = getApplication<Application>().resources
            message = runCatching { withContext(Dispatchers.IO) { usb.export(folder, files) } }.fold(
                onSuccess = { r ->
                    when {
                        r.failures.isEmpty() -> res.getString(R.string.usb_exported, r.exported.size, drive.label)
                        else -> res.getString(
                            R.string.usb_partial, r.exported.size, r.failures.size,
                            "${r.failures.first().first}: ${r.failures.first().second}",
                        )
                    }
                },
                onFailure = { res.getString(R.string.usb_failed, it.message ?: it.javaClass.simpleName) },
            )
            busy = false
            selection.clear()
        }
    }
}
