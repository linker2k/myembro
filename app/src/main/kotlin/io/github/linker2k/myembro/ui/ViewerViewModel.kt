// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linker2k.myembro.ui.editor.suggestDesignName
import io.github.linker2k.myembro.ui.machines.FrameOption
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.export.ExportChecks
import io.github.linker2k.myembro.library.DesignSourceResolver
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.io.PesExport
import io.github.linker2k.myembro.ui.settings.SettingsPreferences
import java.io.File
import java.io.IOException
import java.util.UUID
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    var fileName by mutableStateOf<String?>(null)
        private set
    var render by mutableStateOf<RenderData?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var exporting by mutableStateOf(false)
        private set
    var exportMessage by mutableStateOf<String?>(null)
        private set
    var shareUri by mutableStateOf<Uri?>(null)
        private set
    val canExport: Boolean get() = render != null && !loading && !exporting && shareUri == null

    private class ExportRequest(val source: ByteArray, val sourceName: String, val fileName: String, val generated: Boolean)
    /** Bytes of the open design, so PES files can be exported unchanged. */
    private var sourceBytes: ByteArray? = null
    private var sourceName: String = ""
    private var openedFile: File? = null
    private var openedFileStamp: Pair<Long, Long>? = null
    private var loadJob: Job? = null
    private var loadVersion = 0
    private var projectSource: Pair<String?, ByteArray>? = null

    /** Editor overwrite may change the library file while this viewer remains on the back stack. */
    fun ensureSourceCurrent(): Boolean {
        val file = openedFile ?: return true
        if (openedFileStamp == (file.lastModified() to file.length())) return true
        openFile(file)
        return false
    }
    var projectHoop by mutableStateOf<HoopSpec?>(null)
        private set
    var preservePlacement by mutableStateOf(false)
        private set
    private var pendingSave: ExportRequest? = null
    private val settingsPreferences = SettingsPreferences(app)
    private var settings by mutableStateOf(settingsPreferences.read())
    private var selectedFrameId by mutableStateOf(settings.defaultFrame.id)
    val myThreads get() = settings.myThreads
    val machineProfile get() = settings.machineProfile
    val availableHoops: List<Hoop> get() = settings.availableViewerHoops
    val availableFrames: List<FrameOption> get() = settings.frames
    val frame: FrameOption get() = projectHoop?.let { FrameOption("project", it) } ?: availableFrames.firstOrNull { it.id == selectedFrameId } ?: settings.defaultFrame
    fun selectFrame(id: String) { if (availableFrames.any { it.id == id }) {
        projectHoop = null; selectedFrameId = id; refreshSewout()
    } }
    /** Legacy host accessor. Native precision is available through frame.spec. */
    var hoop: Hoop
        get() = frame.preset ?: Hoop.H100
        set(value) { selectFrame(value.name) }
    private val stopObservingSettings = settingsPreferences.observe {
        val next = settingsPreferences.read()
        if (next.machineModel != settings.machineModel || next.defaultFrame.id != settings.defaultFrame.id) {
            selectedFrameId = next.defaultFrame.id
        }
        val exportVersionChanged = settings.pesVersion != next.pesVersion
        settings = next
        val project = projectSource
        if (exportVersionChanged && project != null) load { project } else refreshSewout()
    }

    /**
     * AI connection (relay): the open design as the editor would open it (one object in the viewer's frame),
     * for get_project and render_preview only. The viewer has no history, so the revision is always 0.
     */
    internal val relayDocument = object : io.github.linker2k.myembro.relay.RelayDocument {
        override val editor: io.github.linker2k.myembro.relay.RelayEditor? = null
        override suspend fun read() = withContext(Dispatchers.Main.immediate) {
            val pattern = render?.pattern ?: return@withContext null
            io.github.linker2k.myembro.relay.RelayProject(io.github.linker2k.myembro.engine.edit.Project(hoop = frame.spec)
                .add(io.github.linker2k.myembro.engine.edit.StitchObject("viewer", fileName.orEmpty(), pattern)), 0)
        }
    }

    var sewoutReport by mutableStateOf<SewoutReport?>(null)
        private set
    var checkingSewout by mutableStateOf(false)
        private set
    var sewoutError by mutableStateOf<String?>(null)
        private set
    var sewoutSheetOpen by mutableStateOf(false)
        private set
    var focusedHighlight by mutableStateOf<Highlight?>(null)
        private set
    var focusRequest by mutableIntStateOf(0)
        private set
    private var sewoutJob: Job? = null
    private var sewoutVersion = 0
    val sewoutReady get() = sewoutReport != null && !checkingSewout && sewoutError == null
    fun openSewout() { playing = false; sewoutSheetOpen = true }
    fun closeSewout() { sewoutSheetOpen = false }
    fun clearSewoutFocus() { focusedHighlight = null }
    fun focusSewout(highlight: Highlight?) {
        val pattern = render?.pattern ?: return
        if (highlight == null || viewerHighlightArea(pattern, highlight) == null) return
        playing = false
        progress = render!!.playback.endIndex
        focusedHighlight = highlight
        focusRequest++
        sewoutSheetOpen = false
    }
    fun refreshSewout() {
        val version = ++sewoutVersion
        sewoutJob?.cancel()
        sewoutReport = null
        sewoutError = null
        focusedHighlight = null
        val pattern = render?.pattern ?: run { checkingSewout = false; return }
        val machine = viewerSewoutMachine(settings.machineProfile?.id, settings.machineModel)
        val selectedHoop = frame.spec
        val placed = preservePlacement
        val pes = sourceBytes?.takeIf { !placed && sourceName.endsWith(".pes", ignoreCase = true) }
        checkingSewout = true
        sewoutJob = viewModelScope.launch {
            try {
                val identity = sourceBytes
                val report = withContext(Dispatchers.Default) {
                    val origin = if (identity != null && io.github.linker2k.myembro.library.GeneratedPesOrigins(getApplication()).contains(identity))
                        DesignOrigin.APP_GENERATED else SewoutCheck.originOf(pattern)
                    checkViewerSewout(pattern, machine, selectedHoop, placed, pes, origin)
                }
                if (version == sewoutVersion) sewoutReport = report
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (version == sewoutVersion) sewoutError = getApplication<Application>().getString(R.string.sewout_failed)
            } finally { if (version == sewoutVersion) checkingSewout = false }
        }
    }

    override fun onCleared() { stopObservingSettings() }

    /** Index into the pattern's stitch list that has been sewn so far. */
    var progress by mutableIntStateOf(0)
    var playing by mutableStateOf(false)

    /** Automatic UI preview rate, independent of machine speed and stitch generation. */
    val playbackRate: Double get() = viewerPlaybackRate(render?.playback?.needleCount ?: 0)
    private var pending = 0.0
    var showJumps by mutableStateOf(false)

    fun open(uri: Uri) {
        openedFile = null
        openedFileStamp = null
        val resolver = getApplication<Application>().contentResolver
        load {
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Cannot read $uri")
            name to bytes
        }
    }

    /** Opens a design stored in the library. */
    fun openFile(file: File) {
        openedFile = file
        openedFileStamp = file.lastModified() to file.length()
        load { file.name to file.readBytes() }
    }

    fun openSample() {
        openedFile = null
        openedFileStamp = null
        load { "flower.pes" to getApplication<Application>().assets.open("samples/flower.pes").use { it.readBytes() } }
    }

    /** Keep the selected design across the document picker and configuration changes. */
    fun prepareSave(): String? {
        val request = exportRequest(suggestCopy = true) ?: return null
        pendingSave = request
        exporting = true
        exportMessage = null
        return request.fileName
    }

    fun save(uri: Uri?) {
        val request = pendingSave
        pendingSave = null
        if (uri == null) {
            exporting = false
            return
        }
        if (request == null) {
            exporting = false
            // A process recreation may restore the picker result without the design.
            reportExportFailure(IllegalStateException(getApplication<Application>().getString(R.string.export_no_design)))
            return
        }
        export(request, write = { bytes ->
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw IOException(getApplication<Application>().getString(R.string.export_cannot_write))
        }, onSuccess = {
            exportMessage = getApplication<Application>().getString(R.string.export_saved)
        })
    }

    fun savePickerFailed(error: Exception) {
        pendingSave = null
        exporting = false
        reportExportFailure(error)
    }

    fun share() {
        val request = exportRequest() ?: return
        export(request, write = { bytes ->
            val app = getApplication<Application>()
            // Each share keeps its own file so a later share cannot overwrite a recipient's URI.
            val directory = File(app.cacheDir, "pes_exports/${UUID.randomUUID()}")
            try {
                if (!directory.mkdirs()) throw IOException(app.getString(R.string.export_cannot_write))
                val file = File(directory, request.fileName)
                file.writeBytes(bytes)
                FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            } catch (error: Exception) {
                directory.deleteRecursively()
                throw error
            }
        }, onSuccess = { shareUri = it })
    }

    fun consumeShare(uri: Uri) {
        if (shareUri == uri) shareUri = null
    }

    fun dismissExportMessage(message: String) {
        if (exportMessage == message) exportMessage = null
    }

    fun reportExportFailure(error: Exception) {
        val app = getApplication<Application>()
        exportMessage = app.getString(R.string.export_failed, error.message ?: app.getString(R.string.export_unknown_error))
    }

    /** Machine warnings for the file save/share would produce; empty when nothing to check. */
    fun preflight(): List<String> {
        val request = exportRequest() ?: return emptyList()
        val pattern = render?.pattern ?: return emptyList()
        return ExportChecks.check(pattern, request.fileName, settingsPreferences.read(), resources = getApplication<Application>().resources)
    }

    private fun exportRequest(suggestCopy: Boolean = false): ExportRequest? {
        if (!canExport) return null
        val source = sourceBytes ?: return null
        val originalName = fileName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val name = originalName.substringBeforeLast('.')
            .map { if (it.isISOControl() || it in "/\\:*?\"<>|") '_' else it }.joinToString("")
            .trim().trim('.').take(100)
            .ifBlank { getApplication<Application>().getString(R.string.export_default_name) }
        val output = if (suggestCopy) suggestDesignName(name) {
            File(getApplication<Application>().filesDir, "designs/$it.pes").exists()
        } else name
        return ExportRequest(source, sourceName, "$output.pes",
            render?.pattern?.let(SewoutCheck::originOf) == DesignOrigin.APP_GENERATED)
    }

    private fun <T> export(request: ExportRequest, write: (ByteArray) -> T, onSuccess: (T) -> Unit) {
        exporting = true
        exportMessage = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // Normalize/encode before opening the destination, so validation errors
                    // cannot truncate an existing document. Encoding and all writes run on IO.
                    val settings = settingsPreferences.read().writeSettings
                    val bytes = PesExport.prepare(request.source, request.sourceName, settings).bytes
                    val origins = io.github.linker2k.myembro.library.GeneratedPesOrigins(getApplication())
                    write(bytes).also { if (request.generated || origins.contains(request.source)) origins.record(bytes) }
                }
                onSuccess(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportExportFailure(error)
            } finally {
                exporting = false
            }
        }
    }

    private fun load(source: () -> Pair<String?, ByteArray>) {
        val version = ++loadVersion
        loadJob?.cancel()
        projectSource = null
        loadJob = viewModelScope.launch {
            loading = true
            sewoutVersion++
            sewoutJob?.cancel()
            sewoutReport = null
            checkingSewout = true
            sewoutError = null
            sewoutSheetOpen = false
            focusedHighlight = null
            error = null
            playing = false
            runCatching {
                withContext(Dispatchers.IO) {
                    val (name, bytes) = source()
                    val current = settingsPreferences.read()
                    name to readViewerDesign(bytes, name, DesignSourceResolver(File(getApplication<Application>().filesDir, "designs")),
                        current.sewing, current.writeSettings)
                }
            }.onSuccess { (name, opened) ->
                if (version != loadVersion) return@onSuccess
                // An export-version edit during generation must not publish an older result.
                val current = settingsPreferences.read()
                if (opened.projectSource != null && (opened.sewingOptions != current.sewing || opened.writeSettings?.version != current.pesVersion)) {
                    load { name to opened.projectSource }
                    return@onSuccess
                }
                projectSource = opened.projectSource?.let { name to it }
                fileName = name
                sourceBytes = opened.source
                sourceName = opened.sourceName
                projectHoop = opened.hoop
                preservePlacement = opened.hoop != null
                val data = RenderData(opened.pattern)
                render = data
                progress = data.playback.endIndex
                pending = 0.0
                refreshSewout()
            }.onFailure {
                if (it is CancellationException) throw it
                if (version != loadVersion) return@onFailure
                render = null
                sourceBytes = null
                fileName = null
                projectHoop = null
                preservePlacement = false
                checkingSewout = false
                error = it.message ?: it.javaClass.simpleName
            }
            if (version == loadVersion) loading = false
        }
    }

    fun pausePlayback() { playing = false; pending = 0.0 }

    fun seekPlayback(commandIndex: Int) {
        pausePlayback()
        progress = commandIndex.coerceIn(-1, render?.playback?.endIndex ?: -1)
    }

    fun togglePlay() {
        val timeline = render?.playback ?: return
        if (timeline.needleCount == 0) return
        if (!playing && progress >= timeline.endIndex) progress = -1
        pending = 0.0
        playing = !playing
    }

    /** Wall-clock animation only; source stitches and export bytes remain unchanged. */
    fun tick(seconds: Float) {
        if (!playing || !seconds.isFinite() || seconds <= 0) return
        val timeline = render?.playback ?: return
        pending += playbackRate * seconds
        val steps = pending.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
        pending -= steps
        progress = timeline.advance(progress, steps)
        if (progress >= timeline.endIndex) pausePlayback()
    }
}
