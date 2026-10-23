// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linker2k.myembro.ui.format.Dimensions
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.model.Bounds
import io.github.linker2k.myembro.ui.settings.SettingsPreferences
import io.github.linker2k.myembro.library.DesignLibrary
import io.github.linker2k.myembro.library.DesignFormats
import io.github.linker2k.myembro.library.DesignImportProgress
import io.github.linker2k.myembro.library.DesignImportReport
import io.github.linker2k.myembro.library.ImportSkip
import io.github.linker2k.myembro.library.ImportSkipReason
import io.github.linker2k.myembro.library.importDesigns
import io.github.linker2k.myembro.library.ImportDecisions
import kotlinx.coroutines.Job
import io.github.linker2k.myembro.library.Thumbnails
import io.github.linker2k.myembro.usb.UsbExporter
import io.github.linker2k.myembro.usb.DirectUsbExporter
import io.github.linker2k.myembro.usb.DirectUsbFailure
import io.github.linker2k.myembro.usb.UsbTransferHistory
import io.github.linker2k.myembro.usb.UsbTransferRecord
import io.github.linker2k.myembro.usb.UsbTransferProblem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.linker2k.myembro.export.ExportChecks
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val library = DesignLibrary(app)
    private val usb = UsbExporter(app)
    private val unwatch = usb.watch { refreshDrives() }
    private val settingsPreferences = SettingsPreferences(app)
    private var settings by mutableStateOf(settingsPreferences.read())
    private val stopObservingSettings = settingsPreferences.observe { settings = settingsPreferences.read() }
    val machineProfile get() = settings.machineProfile

    /** One design in the list; [summary] and [thumbnail] fill in after parsing in the background. */
    data class Item(
        val file: File,
        val summary: String? = null,
        val thumbnail: Bitmap? = null,
        val error: String? = null,
        val fits100: Boolean? = null,
        val stitchCount: Int? = null,
        val modifiedAt: Long = file.lastModified(),
        val bounds: Bounds? = null,
    )

    enum class Filter { ALL, FITS_100, PES, RECENT, FAVORITES }
    enum class Sort { RECENT, NAME, STITCHES }

    val items = mutableStateListOf<Item>()
    private val preferences = app.getSharedPreferences("myembro_library", Context.MODE_PRIVATE)
    private var favorites by mutableStateOf(preferences.getStringSet("favorites", emptySet()).orEmpty().toSet())
    var searchQuery by mutableStateOf("")
    var filter by mutableStateOf(Filter.ALL)
    var sort by mutableStateOf(Sort.RECENT)
    var loading by mutableStateOf(true)
        private set
    val visibleItems: List<Item> by derivedStateOf {
        val recentCutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        val filtered = items.filter { item ->
            item.file.name.contains(searchQuery.trim(), ignoreCase = true) && when (filter) {
                Filter.ALL -> true
                Filter.FITS_100 -> item.fits100 == true
                Filter.PES -> item.file.extension.equals("pes", ignoreCase = true)
                Filter.RECENT -> item.modifiedAt >= recentCutoff
                Filter.FAVORITES -> item.file.name in favorites
            }
        }
        val byName = compareBy<Item> { it.file.name.lowercase(Locale.ROOT) }.thenBy { it.file.path }
        when (sort) {
            Sort.RECENT -> filtered.sortedWith(compareByDescending<Item> { it.modifiedAt }.then(byName))
            Sort.NAME -> filtered.sortedWith(byName)
            Sort.STITCHES -> filtered.sortedWith(compareBy<Item> { it.stitchCount ?: Int.MAX_VALUE }.then(byName))
        }
    }
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

    // Declared before init: refreshDrives() reads these during construction.
    internal data class DirectSpaceRequest(val drive: UsbExporter.Drive, val plan: DirectUsbExporter.SpacePlan)
    internal var directSpaceRequest by mutableStateOf<DirectSpaceRequest?>(null)
        private set
    private var permissionDrive: UsbExporter.Drive? = null
    private var cancelUsbPermission: (() -> Unit)? = null
    private var usbPermissionEpoch = 0L
    private val usbHistoryStore = UsbTransferHistory(File(app.filesDir, "usb-transfers.json"))
    private val usbHistoryMutex = Mutex()
    var usbHistory by mutableStateOf<List<UsbTransferRecord>>(emptyList())
        private set
    var usbResult by mutableStateOf<UsbTransferRecord?>(null)
        private set
    var showUsbHistory by mutableStateOf(false)

    private fun applyUsbHistory(state: UsbTransferHistory.State) {
        usbHistory = state.records
        usbResult = state.pending
    }
    private suspend fun publishUsbResult(record: UsbTransferRecord) = usbHistoryMutex.withLock {
        // Keep the result visible even if local history storage fails (e.g. phone storage full).
        usbResult = record
        usbHistory = (listOf(record) + usbHistory).take(20)
        try { applyUsbHistory(withContext(Dispatchers.IO) { usbHistoryStore.add(record) }) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = getApplication<Application>().getString(R.string.usb_history_save_failed) }
    }
    fun dismissUsbResult() {
        val id = usbResult?.id ?: return
        viewModelScope.launch { usbHistoryMutex.withLock {
            usbResult = null
            try { applyUsbHistory(withContext(Dispatchers.IO) { usbHistoryStore.dismiss(id) }) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = getApplication<Application>().getString(R.string.usb_history_save_failed) }
        } }
    }
    private fun usbFailed(files: List<File>, reason: String, drive: UsbExporter.Drive? = null,
        diagnostics: String = "", problem: UsbTransferProblem? = null) {
        val record = UsbTransferRecord(drive = drive?.label ?: "USB",
            failures = files.map { it.name to reason }.ifEmpty { listOf("USB" to reason) }, technical = diagnostics, problemHint = problem)
        viewModelScope.launch { publishUsbResult(record) }
    }
    private suspend fun usbCompleted(drive: UsbExporter.Drive, report: UsbExporter.Report) {
        publishUsbResult(UsbTransferRecord(drive = drive.label, files = report.files, failures = report.failures,
            remainingBytes = report.remainingBytes, notices = report.notices,
            technical = report.diagnostics + "\n" + report.warnings.joinToString("\n")))
    }
    val importProgress = MutableStateFlow<DesignImportProgress?>(null)
    var importReport by mutableStateOf<DesignImportReport?>(null)
        private set
    private var importing = false
    val importDecisions = ImportDecisions()
    private var importJob: Job? = null
    private var reloadJob: Job? = null
    fun cancelImport() { importDecisions.cancel(); importJob?.cancel() }

    init {
        viewModelScope.launch { usbHistoryMutex.withLock {
            try { applyUsbHistory(withContext(Dispatchers.IO) { usbHistoryStore.read() }) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = app.getString(R.string.usb_history_load_failed) }
        } }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { library.seedSampleIfEmpty() }
            refresh()
        }
        refreshDrives()
    }

    override fun onCleared() { cancelUsbPermission?.invoke(); unwatch(); stopObservingSettings() }

    /** Unknown custom-machine compatibility stays unknown instead of inheriting SE400 limits. */
    fun fitsMachine(item: Item): Boolean? = item.bounds?.let { bounds ->
        settings.defaultFrame.fits(bounds.width, bounds.height) && settings.machineProfile?.fits(bounds) != false
    }

    fun refreshDrives() {
        drives = usb.drives()
        permissionDrive?.let { waiting ->
            if (drives.none { it.uuid == waiting.uuid }) {
                usbPermissionEpoch++
                cancelUsbPermission?.invoke(); cancelUsbPermission = null; permissionDrive = null
                val pending = pendingExport
                pendingExport = emptyList(); busy = false
                usbFailed(pending, getApplication<Application>().getString(R.string.usb_direct_disconnected), waiting, problem = UsbTransferProblem.DISCONNECTED)
            }
        }
        directSpaceRequest?.let { waiting ->
            if (drives.none { it.uuid == waiting.drive.uuid }) {
                directSpaceRequest = null
                usbFailed(waiting.plan.payloads.map { File(it.source) },
                    getApplication<Application>().getString(R.string.usb_direct_disconnected), waiting.drive, problem = UsbTransferProblem.DISCONNECTED)
            }
        }
    }

    /** Picks up designs saved elsewhere, e.g. by the editor. */
    fun refresh() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        loading = true
        try {
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
        } finally {
            loading = false
        }
    }

    private fun describe(file: File): Item = runCatching {
        val data = RenderData(DesignFormats.read(file))
        val p = data.pattern
        val size = p.bounds?.let { Dimensions.size(it.width / 10.0, it.height / 10.0) } ?: "-"
        val res = getApplication<Application>().resources
        val summary = res.getString(R.string.library_summary, size, p.stitchCount, p.colorBlocks.size)
        Item(
            file, summary, Thumbnails.render(data, 320),
            fits100 = p.bounds?.let { Hoop.H100.fits(it.width, it.height) },
            stitchCount = p.stitchCount,
            bounds = p.bounds,
        )
    }.getOrElse { Item(file, error = it.message ?: it.javaClass.simpleName) }

    fun import(uris: List<Uri>, tree: Uri? = null, onComplete: (DesignImportReport) -> Unit = {}) {
        if ((uris.isEmpty() && tree == null) || importing) return
        importing = true
        importReport = null
        importProgress.value = DesignImportProgress(0, null, "")
        importJob = viewModelScope.launch {
            try {
                val report = getApplication<Application>().importDesigns(uris, tree, importDecisions::choose) { importProgress.value = it }
                if (report.cancelled) return@launch
                // Publish the summary before thumbnail generation (which can take time for large batches).
                importReport = report
                importProgress.value = null
                onComplete(report)
                refresh()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                importReport = DesignImportReport(emptyList(), listOf(ImportSkip(tree?.toString() ?: "", ImportSkipReason.FAILED, e.message)))
            } finally {
                importProgress.value = null
                importing = false
            }
        }
    }

    fun dismissImportReport() { importReport = null }

    fun toggle(file: File) {
        if (!selection.remove(file)) selection += file
    }

    fun isFavorite(file: File): Boolean = file.name in favorites

    fun toggleFavorite(file: File) {
        favorites = if (isFavorite(file)) favorites - file.name else favorites + file.name
        preferences.edit().putStringSet("favorites", favorites).apply()
    }

    fun selectAll() {
        selection.clear()
        selection += visibleItems.map { it.file }
    }

    fun clearSelection() = selection.clear()

    fun deleteSelected() {
        val doomed = selection.toList()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { doomed.forEach(library::delete) }
            favorites = favorites - doomed.map { it.name }.toSet()
            preferences.edit().putStringSet("favorites", favorites).apply()
            selection.clear()
            refresh()
        }
    }

    /** Machine warnings waiting for the user before a USB export; the host shows them. */
    var preflightWarnings by mutableStateOf<List<String>?>(null)
        private set
    private var preflightFiles: List<File> = emptyList()

    /**
     * Starts exporting [files] (default: the selection) to a USB drive, asking for access if needed.
     * Designs are first checked against the machine in settings.
     */
    fun exportToUsb(files: List<File> = selection.toList()) {
        if (files.isEmpty() || busy || usbResult != null || directSpaceRequest != null || driveChoice != null || accessRequest != null || preflightWarnings != null) return
        val requested = files.toList()
        busy = true
        viewModelScope.launch {
            val shown = try {
                val settings = SettingsPreferences(getApplication()).read()
                val warnings = withContext(Dispatchers.IO) {
                    requested.flatMap { file ->
                        val pattern = runCatching { DesignFormats.read(file, settings.sewing) }.getOrNull()
                            ?: return@flatMap emptyList()
                        val name = "${file.nameWithoutExtension}.pes"
                        ExportChecks.check(pattern, name, settings, requested.size).map { "$name: $it" }
                    }
                }
                val batch = warnings.map { it.substringAfter(": ") }.groupingBy { it }.eachCount()
                    .filterValues { it == requested.size && requested.size > 1 }.keys
                batch.toList() + warnings.filterNot { it.substringAfter(": ") in batch }
            } catch (e: Exception) {
                busy = false
                if (e is kotlinx.coroutines.CancellationException) throw e
                usbFailed(requested, e.message ?: e.javaClass.simpleName)
                return@launch
            }
            busy = false
            if (shown.isEmpty()) startUsbExport(requested) else { preflightFiles = requested; preflightWarnings = shown }
        }
    }

    fun confirmPreflight() {
        val files = preflightFiles
        cancelPreflight()
        startUsbExport(files)
    }

    fun cancelPreflight() {
        preflightWarnings = null
        preflightFiles = emptyList()
    }

    private fun startUsbExport(files: List<File>) {
        if (files.isEmpty()) return
        refreshDrives()
        val res = getApplication<Application>().resources
        when (drives.size) {
            0 -> usbFailed(files, res.getString(R.string.usb_none), problem = UsbTransferProblem.NO_DEVICE)
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
        if (drive.isDirect) { exportDirect(drive, files); return }
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
        val files = pendingExport
        pendingExport = emptyList()
        if (tree == null) return // Cancelling the picker is not a failed transfer.
        try {
            if (usb.remember(drive, tree)) write(drive, tree, files)
            else usbFailed(files, getApplication<Application>().getString(R.string.usb_wrong_folder, drive.label),
                drive, problem = UsbTransferProblem.FOLDER)
        } catch (e: Exception) {
            usbFailed(files, e.message ?: e.javaClass.simpleName, drive,
                problem = if (e is SecurityException) UsbTransferProblem.PERMISSION else null)
        }
    }


    private fun exportDirect(drive: UsbExporter.Drive, files: List<File>) {
        val target = drive.direct ?: return
        if (usb.directAccess.hasPermission(target)) { writeDirect(drive, files); return }
        pendingExport = files
        permissionDrive = drive
        busy = true
        val epoch = ++usbPermissionEpoch
        try {
            cancelUsbPermission = usb.directAccess.requestPermission(target) { granted ->
                if (epoch != usbPermissionEpoch) return@requestPermission
                cancelUsbPermission?.invoke(); cancelUsbPermission = null; permissionDrive = null
                val pending = pendingExport
                pendingExport = emptyList()
                busy = false
                if (granted) writeDirect(drive, pending)
                else usbFailed(pending, getApplication<Application>().getString(R.string.usb_direct_permission_denied), drive, problem = UsbTransferProblem.PERMISSION)
            }
        } catch (e: Exception) {
            permissionDrive = null; pendingExport = emptyList(); busy = false
            usbFailed(files, e.message ?: e.javaClass.simpleName, drive)
        }
    }

    internal fun cancelDirectSpace() {
        val request = directSpaceRequest ?: return
        directSpaceRequest = null
        viewModelScope.launch { publishUsbResult(UsbTransferRecord(drive = request.drive.label,
            failures = request.plan.payloads.map { it.source to "Not enough space/root entries; export cancelled before deletion" } + request.plan.failures,
            remainingBytes = request.plan.freeBytes, technical = request.plan.diagnostics)) }
    }
    internal fun confirmDirectDelete(names: Set<String>) {
        val request = directSpaceRequest ?: return
        if (busy || !request.plan.canProceed(names)) return
        directSpaceRequest = null
        writeDirect(request.drive, emptyList(), request.plan, names.toSet())
    }

    private fun writeDirect(drive: UsbExporter.Drive, files: List<File>,
        plan: DirectUsbExporter.SpacePlan? = null, deleteNames: Set<String> = emptySet()) {
        val target = drive.direct ?: return
        pendingExport = emptyList()
        busy = true
        viewModelScope.launch {
            try {
                val settings = SettingsPreferences(getApplication()).read()
                when (val result = withContext(Dispatchers.IO) {
                    if (plan == null) usb.directExporter.export(target, files, settings.writeSettings, settings.sewing)
                    else usb.directExporter.resume(target, plan, deleteNames)
                }) {
                    is DirectUsbExporter.Result.NeedSpace -> directSpaceRequest = DirectSpaceRequest(drive, result.plan)
                    is DirectUsbExporter.Result.Complete -> {
                        usbCompleted(drive, result.report)
                        selection.clear()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                usbFailed(if (plan == null) files else plan.payloads.map { File(it.source) },
                    e.message ?: e.javaClass.simpleName, drive, (e as? DirectUsbFailure)?.diagnostics.orEmpty())
            }
            finally { busy = false }
        }
    }

    private fun write(drive: UsbExporter.Drive, folder: Uri, files: List<File>) {
        pendingExport = emptyList()
        busy = true
        viewModelScope.launch {
            try {
                val settings = SettingsPreferences(getApplication()).read()
                val report = withContext(Dispatchers.IO) { usb.export(folder, files, settings.writeSettings, settings.sewing) }
                usbCompleted(drive, report)
                selection.clear()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { usbFailed(files, e.message ?: e.javaClass.simpleName, drive) }
            finally { busy = false }
        }
    }
}
