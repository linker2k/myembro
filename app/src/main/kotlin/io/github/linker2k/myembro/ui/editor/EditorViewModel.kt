// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.PesExport
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.library.DesignLibrary
import io.github.linker2k.myembro.library.DesignSourceResolver
import io.github.linker2k.myembro.library.DesignImportProgress
import io.github.linker2k.myembro.library.DesignImportReport
import io.github.linker2k.myembro.library.ImportSkip
import io.github.linker2k.myembro.library.ImportSkipReason
import io.github.linker2k.myembro.library.importDesigns
import io.github.linker2k.myembro.library.ImportDecisions
import kotlinx.coroutines.Job
import io.github.linker2k.myembro.ui.settings.SettingsPreferences
import io.github.linker2k.myembro.ui.machines.MachineHoop
import io.github.linker2k.myembro.export.ExportChecks
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ensureActive

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val documents = EditorDocuments()
    private val session get() = documents.session
    private val preferences = SettingsPreferences(app)
    var settings by mutableStateOf(preferences.read())
        private set
    private val stopObserving = preferences.observe { settings = preferences.read() }
    val availableHoops get() = editorHoops(settings.machineProfile)
    val multiPositionFrames get() = settings.machineProfile?.hoops.orEmpty().filter { it.requiresRepositioning }
    val multiPositionFrame get() = multiPositionFrames.firstOrNull { it.coverage.sameArea(project.hoop) }
    var sewingPositions by mutableStateOf<List<SewingPosition>>(emptyList())
        private set
    internal val positionFit get() = if (sewingPositions.isEmpty()) EditorPositionFit(emptySet(), emptySet()) else project.positionFit(sewingPositions)
    fun configureSewingPositions(positions: List<SewingPosition>) {
        sewingPositions = positions.takeIf { multiPositionFrame != null && validEditorPositions(project.hoop, it) }?.toList().orEmpty()
    }
    fun setMultiPositionFrame(frame: MachineHoop) {
        if (interactionBlocked || gestureInProgress) return
        val supported = multiPositionFrames.firstOrNull { it == frame } ?: return
        if (project.hoop.sameArea(supported.coverage)) return
        sewingPositions = emptyList()
        edit(project.copy(hoop = supported.coverage))
    }
    internal fun prepareMultiPositionExport(onReady: (EditorSplitPreview) -> Unit) {
        if (interactionBlocked || gestureInProgress || multiPositionFrame == null || sewingPositions.isEmpty()) return
        val snapshot = project
        val positions = sewingPositions.toList()
        busy = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val split = MultiPositionSplitter.split(snapshot, positions)
                    if (split.errors.isNotEmpty()) throw EditorExportBlocked(split.errors.take(8).map { it.message })
                    val problems = split.parts.flatMap { part ->
                        ExportChecks.check(part.pattern, "myembro_position${part.ordinal}.pes", preferences.read())
                    }
                    if (problems.isNotEmpty()) throw EditorExportBlocked(problems.distinct())
                    require(split.parts.isNotEmpty()) { getApplication<Application>().getString(R.string.editor_empty_export) }
                    split
                }
                onReady(EditorSplitPreview(snapshot, positions, result))
            } catch (e: CancellationException) { throw e
            } catch (e: EditorExportBlocked) { exportProblems = e.problems
            } catch (e: Exception) { report(R.string.editor_save_failed, e)
            } finally { busy = false }
        }
    }
    val interactionBlocked get() = busy || draftLoading || replacementRequested || recoveryProject != null
    private val projectSources = DesignSourceResolver(File(app.filesDir, "designs"))
    private val draftStore = EditorDraftStore(File(app.filesDir, "drafts"), projectSources)
    private val draftWrites = Channel<Project?>(Channel.CONFLATED)
    private var draftLoading by mutableStateOf(true)
    private var deferredEntry: (() -> Unit)? = null
    private var lastDraft: Project? = null
    private var draftQueued = false
    var recoveryProject by mutableStateOf<Project?>(null)
        private set
    var dirty by mutableStateOf(false)
        private set
    var replacementRequested by mutableStateOf(false)
        private set
    var exportProblems by mutableStateOf<List<String>?>(null)
        private set
    private var gestureDraft: Project? = null
    private var targets = SnapTargets(emptyList(), emptyList())
    private val library = DesignLibrary(app)
    private val projectDir get() = File(getApplication<Application>().filesDir, "projects")
    var project by mutableStateOf(session.project)
        private set
    var letteringOpen by mutableStateOf(false)
        private set
    var letteringTargetId by mutableStateOf<String?>(null)
        private set
    val letteringObject: TextObject? get() = letteringTargetId?.let { project.find(it) as? TextObject }
    var undoSteps by mutableIntStateOf(0)
        private set
    var redoSteps by mutableIntStateOf(0)
        private set
    val undoEnabled get() = canUndo && !interactionBlocked && !gestureInProgress && !letteringOpen
    val redoEnabled get() = canRedo && !interactionBlocked && !gestureInProgress && !letteringOpen
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    val hoopOverflowVisible: Boolean get() = !gestureInProgress &&
        (session.showsHoopOverflow || (multiPositionFrame != null && positionFit.outsideIds.isNotEmpty()))
    val selectedId: String? get() = selectedIds.lastOrNull()
    val selected: DesignObject? get() = selectedId?.let(project::find)
    var selectionMode by mutableStateOf(false)
        private set
    var snapEnabled by mutableStateOf(true)
        private set
    internal var snapGuides by mutableStateOf(SnapGuides())
        private set
    var gestureInProgress by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var designs by mutableStateOf<List<File>>(emptyList())
        private set
    var projectFiles by mutableStateOf<List<File>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var lastSavedFile by mutableStateOf<File?>(null)
        private set

    init {
        refreshDesigns()
        // A single writer serializes saves/deletes; an older write cannot resurrect a discarded draft.
        viewModelScope.launch(Dispatchers.IO) {
            for (snapshot in draftWrites) {
                try { draftStore.write(snapshot) }
                catch (e: Exception) { withContext(Dispatchers.Main) { report(R.string.editor_draft_failed, e) } }
            }
        }
        viewModelScope.launch {
            try { recoveryProject = withContext(Dispatchers.IO) { draftStore.read() } }
            catch (e: Exception) { report(R.string.editor_draft_failed, e) }
            finally {
                draftLoading = false
                if (recoveryProject == null) resumeDeferredEntry()
            }
        }
    }
    override fun onCleared() { stopObserving(); draftWrites.close() }

    private fun queueDraft() {
        if (draftLoading || recoveryProject != null || session.isGesturing) return
        val snapshot = project.takeIf { documents.dirty }
        if (!draftQueued || snapshot != lastDraft) {
            draftQueued = true
            lastDraft = snapshot
            draftWrites.trySend(snapshot)
        }
    }
    private fun resumeDeferredEntry() {
        val action = deferredEntry
        deferredEntry = null
        action?.let(::requestReplacement)
    }
    private fun requestReplacement(action: () -> Unit) {
        if (busy || gestureInProgress || replacementRequested) return
        if (draftLoading || recoveryProject != null) { deferredEntry = action; return }
        documents.requestReplacement(action)
        replacementRequested = documents.needsDecision
    }
    fun leave(onBack: () -> Unit) = requestReplacement {
        replaceSession(Project(hoop = supportedHoop(HoopSpec.H100)), savedOnDisk = true)
        onBack()
    }
    fun cancelReplacement() { documents.cancelReplacement(); replacementRequested = false }
    fun discardAndContinue() {
        if (busy || gestureInProgress) return
        replacementRequested = false
        documents.continueReplacement()
    }
    fun saveAndContinue() {
        if (!replacementRequested) return
        saveProject("myembro-${System.currentTimeMillis()}", afterSave = ::discardAndContinue)
    }
    fun restoreDraft() {
        val recovered = recoveryProject ?: return
        deferredEntry = null // Recovery takes precedence over an incoming empty/new design request.
        recoveryProject = null
        replaceSession(recovered, savedOnDisk = false)
    }
    fun discardDraft() {
        recoveryProject = null
        draftQueued = false
        queueDraft()
        resumeDeferredEntry()
    }
    fun dismissExportProblems() { exportProblems = null }
    private fun replaceSession(loaded: Project, savedOnDisk: Boolean, selection: Set<String> = emptySet()) {
        documents.replaceAfterRead(savedOnDisk) { loaded }
        gestureDraft = null
        selectedIds = selection
        selectionMode = false
        dismissLettering()
        snapGuides = SnapGuides()
        sync()
    }
    private fun supportedHoop(requested: HoopSpec) =
        availableHoops.firstOrNull { it.sameArea(requested) } ?: availableHoops.firstOrNull() ?: HoopSpec.H100

    fun refreshDesigns() {
        viewModelScope.launch { designs = withContext(Dispatchers.IO) { library.list() } }
    }
    fun refreshProjects() {
        viewModelScope.launch { projectFiles = withContext(Dispatchers.IO) { listProjectFiles(projectDir) } }
    }
    private fun sync() {
        project = session.project
        gestureInProgress = session.isGesturing
        canUndo = session.canUndo
        canRedo = session.canRedo
        undoSteps = session.history.undoCount
        redoSteps = session.history.redoCount
        selectedIds = selectedIds.filterTo(linkedSetOf()) { project.find(it)?.visible == true }
        dirty = documents.dirty
        replacementRequested = documents.needsDecision
        queueDraft()
    }
    fun newProject(hoop: HoopSpec = HoopSpec.H100) = requestReplacement {
        replaceSession(Project(hoop = supportedHoop(hoop)), savedOnDisk = true)
    }
    /** Imported lettering is an unsaved baseline, while its History starts with zero undo steps. */
    fun startWith(obj: DesignObject, hoop: HoopSpec) = requestReplacement {
        replaceSession(Project(hoop = supportedHoop(hoop)).add(obj), savedOnDisk = false, selection = setOf(obj.id))
    }

    private fun importObject(file: File) = importDesignObject(file)

    /** Viewer entry point: its initial object is the baseline, not an undoable import. */
    fun editDesign(file: File) = requestReplacement {
        busy = true
        viewModelScope.launch {
            try {
                val obj = withContext(Dispatchers.IO) { importObject(file) }
                replaceSession(Project(hoop = supportedHoop(HoopSpec.H100)).add(obj),
                    savedOnDisk = false, selection = setOf(obj.id))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { report(R.string.editor_load_failed, e)
            } finally { busy = false }
        }
    }
    private fun edit(next: Project) {
        if (gestureInProgress || draftLoading || replacementRequested || recoveryProject != null) return
        session.commit(next)
        sync()
    }
    fun select(id: String?) {
        if (gestureInProgress) return
        if (selectionMode && id != null) toggleSelection(id)
        else selectedIds = id?.takeIf { project.find(it)?.visible == true }?.let(::setOf) ?: emptySet()
    }
    internal fun selectForGesture(id: String?) {
        if (id == null) { if (!selectionMode) selectedIds = emptySet() }
        else if (id !in selectedIds) {
            if (selectionMode) selectedIds = selectedIds + id else selectedIds = setOf(id)
        }
    }
    fun toggleSelection(id: String) {
        if (gestureInProgress) return
        if (project.find(id)?.visible != true) return
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    fun toggleSelectionMode() { if (!gestureInProgress) selectionMode = !selectionMode }
    fun selectAll() { if (gestureInProgress) return; selectedIds = project.objects.filter { it.visible }.mapTo(linkedSetOf()) { it.id } }
    fun clearSelection() { if (gestureInProgress) return; selectedIds = emptySet() }
    fun toggleSnap() { if (gestureInProgress) return; snapEnabled = !snapEnabled; snapGuides = SnapGuides() }
    fun setHoop(hoop: HoopSpec) {
        if (interactionBlocked || gestureInProgress) return
        val supported = availableHoops.firstOrNull { it.sameArea(hoop) } ?: return
        if (project.hoop.sameArea(supported)) return
        sewingPositions = emptyList()
        edit(project.copy(hoop = supported))
    }
    fun undo(): Boolean {
        val changed = session.stepHistory(redo = false, blocked = interactionBlocked || letteringOpen)
        sync()
        return changed
    }
    fun redo(): Boolean {
        val changed = session.stepHistory(redo = true, blocked = interactionBlocked || letteringOpen)
        sync()
        return changed
    }
    fun openLettering(id: String? = null) {
        if (interactionBlocked || gestureInProgress || (id != null && (project.find(id) !is TextObject || project.find(id)?.visible != true))) return
        letteringTargetId = id
        letteringOpen = true
    }
    fun dismissLettering() { letteringOpen = false; letteringTargetId = null }
    fun commitLettering(obj: TextObject): Boolean {
        if (busy || gestureInProgress || !letteringOpen) return false
        val target = letteringTargetId
        val next = project.withLettering(obj, target)
        if (target != null && next === project) return false
        edit(next)
        selectedIds = setOf(target ?: next.objects.last().id)
        selectionMode = false
        dismissLettering()
        return true
    }
    fun beginGesture() {
        if (interactionBlocked || gestureDraft != null) return
        session.beginGesture()
        gestureDraft = project
        targets = project.snapTargets(selectedIds)
        sync()
    }
    fun endGesture() {
        session.endGesture()
        gestureDraft = null
        snapGuides = SnapGuides()
        sync()
    }
    internal fun transformBy(dx: Double, dy: Double, factor: Double, rotation: Double, tolerance: Double) {
        val draft = gestureDraft ?: return
        val next = draft.transformGroup(selectedIds, dx, dy, factor, rotation)
        gestureDraft = next
        val snapped = if (snapEnabled && tolerance > 0 && factor == 1.0 && rotation == 0.0)
            next.snapSelection(selectedIds, targets, tolerance) else SnappedSelection(next, SnapGuides())
        snapGuides = snapped.guides
        session.updateGesture(snapped.project)
        sync()
    }
    fun changeThread(index: Int, thread: io.github.linker2k.myembro.engine.model.EmbThread) {
        if (busy || gestureInProgress || selectedIds.size != 1) return
        val obj = selected ?: return
        edit(project.replace(obj.recolored(index, thread)))
    }

    fun applyProperties(transform: Transform) {
        if (selectedIds.size != 1) return
        val obj = selected ?: return
        edit(project.replace(obj.withTransform(transform)))
    }
    fun align(alignment: Alignment) = edit(project.align(selectedIds, alignment))
    fun distribute(axis: Axis) = edit(project.distribute(selectedIds, axis))
    fun mirror() = edit(selectedIds.fold(project) { p, id -> p.mirrorX(id) })
    fun rotate90() = edit(selectedIds.fold(project) { p, id -> p.rotateBy(id, 90.0) })
    fun center() {
        if (selectedIds.size == 1) edit(project.alignCenter(selectedId!!))
        else project.selectionBounds(selectedIds)?.let { b ->
            edit(project.transformGroup(selectedIds, -(b.minX.toDouble() + b.maxX) / 2,
                -(b.minY.toDouble() + b.maxY) / 2, 1.0, 0.0))
        }
    }
    fun delete() = edit(project.copy(objects = project.objects.filterNot { it.id in selectedIds }))
    fun duplicate() {
        if (gestureInProgress) return
        var next = project
        val copies = linkedSetOf<String>()
        for (id in selectedIds) {
            val newId = UUID.randomUUID().toString()
            next = next.duplicate(id, newId).update(newId) {
                it.withTransform(it.transform.copy(x = it.transform.x + 50, y = it.transform.y + 50))
            }
            copies += newId
        }
        edit(next)
        selectedIds = copies
    }
    fun toggleVisible(id: String) = edit(project.update(id) { it.withVisible(!it.visible) })
    fun reorder(id: String, index: Int, duringGesture: Boolean = false) {
        val next = project.reorder(id, index)
        if (duringGesture) { session.updateGesture(next); sync() } else edit(next)
    }
    fun addDesign(file: File) {
        if (interactionBlocked || gestureInProgress) return
        busy = true
        viewModelScope.launch {
            try {
                val obj = withContext(Dispatchers.IO) { importObject(file) }
                edit(project.add(obj))
                selectedIds = setOf(obj.id)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { report(R.string.editor_load_failed, e)
            } finally { busy = false }
        }
    }
    val importProgress = MutableStateFlow<DesignImportProgress?>(null)
    var importReport by mutableStateOf<DesignImportReport?>(null)
        private set
    fun dismissImportReport() { importReport = null }
    val importDecisions = ImportDecisions()
    private var importJob: Job? = null
    fun cancelImport() { importDecisions.cancel(); importJob?.cancel() }

    /** One undo step for the batch, one independently editable object per selected design. */
    fun importDesigns(uris: List<Uri>, tree: Uri? = null, openSingleProject: Boolean = false) {
        if (interactionBlocked || gestureInProgress || (uris.isEmpty() && tree == null)) return
        busy = true
        importReport = null
        importProgress.value = DesignImportProgress(0, null, "")
        importJob = viewModelScope.launch {
            var projectToOpen: File? = null
            try {
                val report = getApplication<Application>().importDesigns(uris, tree, importDecisions::choose) { importProgress.value = it }
                if (report.cancelled) return@launch
                val files = report.imported.distinct()
                val skipped = report.skipped.toMutableList()
                if (openSingleProject && files.size == 1 && files.single().extension == "myembro") {
                    projectToOpen = files.single()
                    importReport = DesignImportReport(files, skipped)
                } else {
                    val added = withContext(Dispatchers.IO) {
                        files.mapIndexedNotNull { index, file ->
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            importProgress.value = DesignImportProgress(index, files.size, file.name, committing = true)
                            try { importObject(file) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { skipped += ImportSkip(file.name, ImportSkipReason.FAILED, e.message); null }
                        }
                    }
                    if (added.isNotEmpty()) {
                        edit(project.copy(objects = project.objects + added))
                        selectedIds = added.mapTo(linkedSetOf()) { it.id }
                    }
                    importReport = DesignImportReport(files.filter { f -> added.any { it.name == f.nameWithoutExtension } }, skipped)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                importReport = DesignImportReport(emptyList(), listOf(ImportSkip(tree?.toString() ?: "", ImportSkipReason.FAILED, e.message)))
            } finally { importProgress.value = null; busy = false }
            projectToOpen?.let(::openProject)
        }
    }
    private fun report(resource: Int, e: Exception) {
        message = getApplication<Application>().getString(resource, e.localizedMessage.orEmpty())
    }
    fun openProject(file: File) = loadProject { file.readText() }
    fun openProject(uri: Uri) = loadProject {
        getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error(getApplication<Application>().getString(R.string.editor_project_read_error))
    }
    private fun loadProject(read: () -> String) = requestReplacement {
        busy = true
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { readEditorProject(read(), projectSources) }
                replaceSession(loaded, savedOnDisk = true)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { report(R.string.editor_project_open_failed, e)
            } finally { busy = false }
        }
    }
    fun saveProject(name: String, uri: Uri? = null, afterSave: (() -> Unit)? = null) {
        if (busy || gestureInProgress) return
        endGesture()
        val snapshot = project
        busy = true
        viewModelScope.launch {
            var savedSuccessfully = false
            try {
                val savedName = withContext(Dispatchers.IO) {
                    if (uri == null) saveProjectSnapshot(projectDir, name, snapshot).name
                    else {
                        val text = ProjectJson.encode(snapshot)
                        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
                            ?: error(getApplication<Application>().getString(R.string.editor_project_write_error))
                        name
                    }
                }
                projectFiles = withContext(Dispatchers.IO) { listProjectFiles(projectDir) }
                documents.markSaved(snapshot)
                sync()
                message = getApplication<Application>().getString(R.string.editor_project_saved, savedName)
                savedSuccessfully = true
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { report(R.string.editor_project_save_failed, e)
            } finally { busy = false }
            if (savedSuccessfully) afterSave?.invoke()
        }
    }

    /** Exports a snapshot atomically, without overwriting any library design. */
    fun save() {
        if (interactionBlocked || gestureInProgress || project.objects.none { it.visible }) return
        endGesture()
        val snapshot = project
        viewModelScope.launch {
            busy = true
            try {
                val saved = withContext(Dispatchers.IO) {
                    val pattern = snapshot.flatten()
                    require(pattern.stitchCount > 0) { getApplication<Application>().getString(R.string.editor_empty_export) }
                    val exportSettings = preferences.read() // Re-read immediately before writing.
                    val problems = editorExportProblems(snapshot, exportSettings, "myembro.pes")
                    if (problems.isNotEmpty()) throw EditorExportBlocked(problems)
                    val bytes = PesWriter.write(pattern, exportSettings.writeSettings)
                    val dir = File(getApplication<Application>().filesDir, "designs").apply { mkdirs() }
                    val name = PesExport.uniqueName("myembro-${System.currentTimeMillis()}.pes") { File(dir, it).exists() }
                    val target = File(dir, name)
                    val temp = File.createTempFile("editor-", ".tmp", dir)
                    try {
                        temp.outputStream().use { it.write(bytes) }
                        check(temp.renameTo(target)) { "Could not finish PES export" }
                    } finally { temp.delete() }
                    target
                }
                lastSavedFile = saved
                designs = withContext(Dispatchers.IO) { library.list() }
                message = getApplication<Application>().getString(R.string.editor_saved, saved.name)
            } catch (e: CancellationException) { throw e
            } catch (e: EditorExportBlocked) {
                exportProblems = e.problems.map { problem ->
                    val resource = when (problem) {
                        "machine_unknown" -> R.string.editor_machine_unknown
                        "hoop_unsupported" -> R.string.editor_hoop_unsupported
                        "hoop_overflow" -> R.string.editor_outside
                        else -> null
                    }
                    resource?.let { getApplication<Application>().getString(it) } ?: problem
                }
            } catch (e: Exception) {
                message = getApplication<Application>().getString(R.string.editor_save_failed, e.localizedMessage.orEmpty())
            } finally { busy = false }
        }
    }
}

private class EditorExportBlocked(val problems: List<String>) : Exception()
