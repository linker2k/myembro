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
import io.github.linker2k.myembro.ui.machines.FrameOption
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.export.ExportChecks
import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.engine.io.PesExport
import io.github.linker2k.myembro.ui.settings.SettingsPreferences
import java.io.File
import java.io.IOException
import java.util.UUID
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

    private class ExportRequest(val source: ByteArray, val sourceName: String, val fileName: String)
    /** Bytes of the open design, so PES files can be exported unchanged. */
    private var sourceBytes: ByteArray? = null
    private var pendingSave: ExportRequest? = null
    private val settingsPreferences = SettingsPreferences(app)
    private var settings by mutableStateOf(settingsPreferences.read())
    private var selectedFrameId by mutableStateOf(settings.defaultFrame.id)
    val machineProfile get() = settings.machineProfile
    val availableHoops: List<Hoop> get() = settings.availableViewerHoops
    val availableFrames: List<FrameOption> get() = settings.frames
    val frame: FrameOption get() = availableFrames.firstOrNull { it.id == selectedFrameId } ?: settings.defaultFrame
    fun selectFrame(id: String) { if (availableFrames.any { it.id == id }) selectedFrameId = id }
    /** Legacy host accessor. Native precision is available through frame.spec. */
    var hoop: Hoop
        get() = frame.preset ?: Hoop.H100
        set(value) { selectedFrameId = value.name }
    private val stopObservingSettings = settingsPreferences.observe {
        val next = settingsPreferences.read()
        if (next.machineModel != settings.machineModel || next.defaultFrame.id != settings.defaultFrame.id) {
            selectedFrameId = next.defaultFrame.id
        }
        settings = next
    }

    override fun onCleared() { stopObservingSettings() }

    /** Index into the pattern's stitch list that has been sewn so far. */
    var progress by mutableIntStateOf(0)
    var playing by mutableStateOf(false)

    /** Machine commands (stitches, jumps, trims…) advanced per second while playing. */
    var speed by mutableIntStateOf(400)
    private var pending = 0f
    var showJumps by mutableStateOf(false)

    fun open(uri: Uri) {
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
    fun openFile(file: java.io.File) = load { file.name to file.readBytes() }

    fun openSample() = load {
        "flower.pes" to getApplication<Application>().assets.open("samples/flower.pes").use { it.readBytes() }
    }

    /** Keep the selected design across the document picker and configuration changes. */
    fun prepareSave(): String? {
        val request = exportRequest() ?: return null
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
        return ExportChecks.check(pattern, request.fileName, settingsPreferences.read())
    }

    private fun exportRequest(): ExportRequest? {
        if (!canExport) return null
        val source = sourceBytes ?: return null
        val originalName = fileName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val name = originalName.substringBeforeLast('.')
            .map { if (it.isISOControl() || it in "/\\:*?\"<>|") '_' else it }.joinToString("")
            .trim().trim('.').take(100)
            .ifBlank { getApplication<Application>().getString(R.string.export_default_name) }
        return ExportRequest(source, originalName, "$name.pes")
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
                    write(PesExport.prepare(request.source, request.sourceName, settings).bytes)
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
        viewModelScope.launch {
            loading = true
            error = null
            playing = false
            runCatching {
                withContext(Dispatchers.IO) {
                    val (name, bytes) = source()
                    Triple(name, bytes, RenderData(PatternReader.read(bytes, name)))
                }
            }.onSuccess { (name, bytes, data) ->
                fileName = name
                sourceBytes = bytes
                render = data
                progress = data.pattern.stitches.lastIndex
            }.onFailure {
                error = it.message ?: it.javaClass.simpleName
            }
            loading = false
        }
    }

    fun togglePlay() {
        val data = render ?: return
        if (!playing && progress >= data.pattern.stitches.lastIndex) progress = 0
        pending = 0f
        playing = !playing
    }

    /** Advance playback by [seconds] of wall time; stops at the end. */
    fun tick(seconds: Float) {
        val last = render?.pattern?.stitches?.lastIndex ?: return
        // Carry the fractional part so slow speeds stay accurate at any frame rate.
        pending += speed * seconds
        val steps = pending.toInt()
        pending -= steps
        progress = (progress + steps).coerceAtMost(last)
        if (progress == last) playing = false
    }
}
