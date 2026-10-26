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
import io.github.linker2k.myembro.engine.edit.commands.ProjectCommand
import io.github.linker2k.myembro.engine.edit.commands.CommandResult
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val documents = EditorDocuments()
    private val session get() = documents.session
    private val preferences = SettingsPreferences(app)
    var settings by mutableStateOf(preferences.read())
        private set
    private val stopObserving = preferences.observe { settings = preferences.read() }
    /** Same installed-font loading as the lettering screen; shared by Ask AI preview and apply. */
    private val agentFonts by lazy { io.github.linker2k.myembro.ui.lettering.AndroidTextFontResolver(app) }
    /** Ask AI preview/apply and the AI connection's reads and edits, on this editor's session. */
    private val agentExecutor = EditorAgentExecutor({ session }, { settings }, { availableHoops }, publish = { block ->
            busy = true
            try { block() } finally { busy = false; sync() }
        }, kernelFactory = { frames -> androidAgentKernel(app, agentFonts, frames, io.github.linker2k.myembro.engine.agent.AttachmentStore { id, request ->
            // Photos handed to AI over the relay (opaque UUIDs) first, then Ask AI's own attached photos.
            relayPhotos.raster(id, request) ?: askAiPhotos.raster(id, request)
        }) }, fontRevision = { agentFonts.revision(it) })
    private val askAiPhotos by lazy { io.github.linker2k.myembro.ui.photo.AndroidAttachmentStore(app) { id -> agent.source(id) } }
    /** Photos the user handed to the AI connection in this editor (memory only, one consent per photo). */
    internal val relayPhotos = io.github.linker2k.myembro.relay.RelayPhotoAttachments(app)
    /** "Ask AI" flow on this editor's session; survives rotation with the rest of the editor. */
    internal val agent: io.github.linker2k.myembro.ui.agent.AgentAssistController = io.github.linker2k.myembro.ui.agent.AgentAssistController(
        agentExecutor, revision = { session.history.revision }, catalog = {
            io.github.linker2k.myembro.ui.agent.AgentCatalog.fonts(app) to io.github.linker2k.myembro.ui.agent.AgentCatalog.designs(app)
        })
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
        edit(ProjectCommand.SetHoop(supported.coverage))
    }
    internal fun prepareMultiPositionExport(onReady: (EditorSplitPreview) -> Unit) {
        if (interactionBlocked || gestureInProgress || multiPositionFrame == null || sewingPositions.isEmpty()) return
        val snapshot = project
        val positions = sewingPositions.toList()
        busy = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val split = MultiPositionSplitter.split(snapshot.flatten(preferences.read().sewing), positions)
                    if (split.errors.isNotEmpty()) throw EditorExportBlocked(split.errors.take(8).map { it.message })
                    val problems = split.parts.flatMap { part ->
                        ExportChecks.check(part.pattern, "myembro_position${part.ordinal}.pes", preferences.read(), resources = getApplication<Application>().resources)
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
    val interactionBlocked get() = busy || draftLoading || replacementRequested || recoveryProject != null || savePrompt != null
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
    private var gestureStart: Project? = null
    private var targets = SnapTargets(emptyList(), emptyList())
    private val library = DesignLibrary(app)
    private val projectDir get() = File(getApplication<Application>().filesDir, "designs")
    private val legacyProjectDir get() = File(getApplication<Application>().filesDir, "projects")
    private var documentName: String? = null
    internal var savePrompt by mutableStateOf<EditorSavePrompt?>(null)
        private set
    internal var overwriteName by mutableStateOf<String?>(null)
        private set
    private var continueAfterNamedSave = false

    fun requestProjectSave() = requestNamedSave(EditorSaveKind.PROJECT)
    private fun requestNamedSave(kind: EditorSaveKind, continueAfterSave: Boolean = false) {
        if (busy || gestureInProgress || recoveryProject != null) return
        busy = true
        viewModelScope.launch {
            try {
                val original = documentName ?: getApplication<Application>().getString(R.string.editor_default_name)
                val proposed = withContext(Dispatchers.IO) {
                    val extension = if (kind == EditorSaveKind.PES) "pes" else "myembro"
                    suggestDesignName(original) { designSaveTarget(projectDir, it, extension).exists() }
                }
                continueAfterNamedSave = continueAfterSave
                savePrompt = EditorSavePrompt(kind, proposed)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { report(R.string.editor_project_save_failed, e)
            } finally { busy = false }
        }
    }
    internal fun cancelNamedSave() { savePrompt = null; overwriteName = null; continueAfterNamedSave = false }
    internal fun hideSavePrompt() { savePrompt = null }
    internal fun cancelOverwrite() { overwriteName = null }
    internal fun submitSaveName(name: String, overwrite: Boolean = false) {
        val request = savePrompt ?: return
        if (busy || gestureInProgress || designSaveStem(name).isBlank()) return
        val extension = if (request.kind == EditorSaveKind.PES) "pes" else "myembro"
        if (!overwrite && designSaveTarget(projectDir, name, extension).exists()) { overwriteName = name; return }
        savePrompt = null
        overwriteName = null
        if (request.kind == EditorSaveKind.PROJECT) saveProject(name, afterSave = ::finishNamedSave, overwrite = overwrite)
        else writePes(name, overwrite)
    }
    internal fun finishNamedSave() {
        val resume = continueAfterNamedSave
        continueAfterNamedSave = false
        if (resume) discardAndContinue()
    }
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
    val hoopOverflowVisible: Boolean get() = !gestureInProgress && !rendering &&
        (session.showsHoopOverflow || (multiPositionFrame != null && positionFit.outsideIds.isNotEmpty()))
    val selectedId: String? get() = selectedIds.lastOrNull()
    val selected: DesignObject? get() = selectedId?.let(project::find)
    var selectionMode by mutableStateOf(false)
        private set
    var snapEnabled by mutableStateOf(true)
        private set
    internal var snapGuides by mutableStateOf(SnapGuides())
        private set
    var rendering by mutableStateOf(false)
        internal set
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
    /** RelayPhotoTools on the session's own controller; a new one when the session or kernel is replaced. */
    private var relayPhotoTools: Pair<io.github.linker2k.myembro.engine.agent.ToolTurnController, io.github.linker2k.myembro.relay.RelayPhotoTools>? = null
    private fun relayPhotoTools(): io.github.linker2k.myembro.relay.RelayPhotoTools {
        val controller = agentExecutor.relayController()
        relayPhotoTools?.takeIf { it.first === controller }?.let { return it.second }
        val tools = io.github.linker2k.myembro.relay.RelayPhotoTools(relayPhotos, controller,
            exportSettings = { agentExecutor.relayExport() ?: buildJsonObject { put("machineId", ""); put("frameId", ""); put("pesVersion", "") } },
            // The same editing lock and refresh as Ask AI Apply, on the main thread like the UI.
            publish = { block -> withContext(Dispatchers.Main.immediate) {
                busy = true
                try { withContext(Dispatchers.Default) { block() } } finally { session.adoptEngineProject(); busy = false; sync() }
            } },
            canEdit = { io.github.linker2k.myembro.relay.RelayProjectSource.current() === relayDocument && agentExecutor.relayController() === controller })
        relayPhotoTools = controller to tools
        return tools
    }

    /** AI connection (relay): remove_objects waits for the user's answer in this dialog. */
    internal val relayConfirmation = io.github.linker2k.myembro.relay.RelayConfirmation(
        onAsk = { names -> io.github.linker2k.myembro.relay.RelayConfirmNotification.show(app, names) },
        onDone = { io.github.linker2k.myembro.relay.RelayConfirmNotification.cancel(app) })
    /**
     * AI connection (relay): this editor's project and revision, as the Ask AI prompt reads them, and its
     * Ask AI executor for reads and edits. The session is read and changed on the main thread, like the UI.
     */
    internal val relayDocument = object : io.github.linker2k.myembro.relay.RelayDocument {
        private val executor = agentExecutor
        override suspend fun read() = withContext(Dispatchers.Main.immediate) {
            io.github.linker2k.myembro.relay.RelayProject(session.project, session.history.revision)
        }
        override val editor = object : io.github.linker2k.myembro.relay.RelayEditor {
            override val photos get() = relayPhotoTools()
            override suspend fun query(tool: String, args: JsonObject) = executor.relayQuery(tool, args)
            override suspend fun edit(tool: String, args: JsonObject) = withContext(Dispatchers.Main.immediate) {
                if (interactionBlocked || gestureInProgress) io.github.linker2k.myembro.relay.RelayEdits.failure("EDITOR_BUSY",
                    "myembro is busy with another task on screen. Try again in a moment.", session.history.revision, session.project)
                else executor.relayEdit(tool, args, relayConfirmation::ask)
            }
        }
    }
    override fun onCleared() { stopObserving(); draftWrites.close() }

    private fun queueDraft() {
        if (draftLoading || recoveryProject != null || session.isGesturing) return
        val snapshot = documents.draft
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
        requestNamedSave(EditorSaveKind.PROJECT, continueAfterSave = true)
    }
    fun restoreDraft() {
        val recovered = recoveryProject ?: return
        deferredEntry = null // Recovery takes precedence over an incoming empty/new design request.
        recoveryProject = null
        replaceSession(recovered, savedOnDisk = false, restoredDraft = true)
    }
    fun discardDraft() {
        recoveryProject = null
        draftQueued = false
        queueDraft()
        resumeDeferredEntry()
    }
    fun dismissExportProblems() { exportProblems = null }
    private fun replaceSession(loaded: Project, savedOnDisk: Boolean, selection: Set<String> = emptySet(), restoredDraft: Boolean = false) {
        documents.replaceAfterRead(savedOnDisk, restoredDraft) { loaded }
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
        viewModelScope.launch { projectFiles = withContext(Dispatchers.IO) { (listProjectFiles(projectDir) + listProjectFiles(legacyProjectDir)).sortedByDescending { it.lastModified() } } }
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
        documentName = null
        replaceSession(Project(hoop = supportedHoop(hoop)), savedOnDisk = true)
    }
    /** The entry snapshot starts clean; subsequent edits form the undo history. */
    fun startWith(obj: DesignObject, hoop: HoopSpec) = requestReplacement {
        documentName = designSaveStem(obj.name).ifBlank { null }
        replaceSession(Project(hoop = supportedHoop(hoop)).add(obj), savedOnDisk = false, selection = setOf(obj.id))
    }

    private fun importObject(file: File) = importDesignObject(file)

    /** Viewer entry point: its initial object is the baseline, not an undoable import. */
    fun editDesign(file: File) = requestReplacement {
        busy = true
        viewModelScope.launch {
            try {
                val obj = withContext(Dispatchers.IO) { importObject(file) }
                documentName = file.nameWithoutExtension
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
    private fun edit(command: ProjectCommand) { dispatch(command) }
    private fun dispatch(command: ProjectCommand): CommandResult? {
        if (gestureInProgress || draftLoading || replacementRequested || recoveryProject != null) return null
        val result = session.commit(command)
        sync()
        return result
    }
    /** Existing geometry calculations prepare values; command validation is shared with tools. */
    private fun editPoses(next: Project) = edit(project.poseCommand(next))
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
        edit(ProjectCommand.SetHoop(supported))
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
        val result = dispatch(project.letteringCommand(obj, target)) as? CommandResult.Applied ?: return false
        selectedIds = setOf(target ?: result.project.objects.last().id)
        selectionMode = false
        dismissLettering()
        return true
    }
    fun beginGesture() {
        if (interactionBlocked || gestureDraft != null) return
        session.beginGesture()
        gestureDraft = project
        gestureStart = project
        targets = project.snapTargets(selectedIds)
        sync()
    }
    fun endGesture() {
        rendering=project.objects.any { obj -> gestureStart?.find(obj.id)?.let {
            it.transform.scaleX!=obj.transform.scaleX || it.transform.scaleY!=obj.transform.scaleY
        }==true }
        gestureStart=null
        session.endGesture()
        gestureDraft = null
        snapGuides = SnapGuides()
        sync()
    }
    internal fun transformBy(dx: Double, dy: Double, factor: Double, rotation: Double, tolerance: Double) {
        val draft = gestureDraft ?: return
        val next = draft.transformGroup(selectedIds, dx, dy, factor, rotation,fastPreview=true)
        gestureDraft = next
        val snapped = if (snapEnabled && tolerance > 0 && factor == 1.0 && rotation == 0.0)
            next.snapSelection(selectedIds, targets, tolerance,fastPreview=true) else SnappedSelection(next, SnapGuides())
        snapGuides = snapped.guides
        session.updateGesture(snapped.project)
        sync()
    }
    fun changeThread(index: Int, thread: io.github.linker2k.myembro.engine.model.EmbThread) {
        if (busy || gestureInProgress || selectedIds.size != 1) return
        val obj = selected ?: return
        edit(project.recolorCommand(obj.id, index, thread))
    }

    fun applyProperties(transform: Transform) {
        if (selectedIds.size != 1) return
        val obj = selected ?: return
        edit(ProjectCommand.SetTransforms(obj.id, transform))
    }

    /** Precompute filled glyphs off the UI thread; commit style and transform in one undo step. */
    fun applyTextProperties(transform: Transform, style: io.github.linker2k.myembro.engine.lettering.StitchStyle,
        threadWeight: io.github.linker2k.myembro.engine.lettering.ThreadWeight? = null) {
        if (busy || gestureInProgress || selectedIds.size != 1) return
        val original = selected as? TextObject ?: return
        if(style==original.stitchStyle && (threadWeight==null || threadWeight==original.threadWeight)) {
            edit(ProjectCommand.SetTransforms(original.id, transform))
            return
        }
        val before = project
        viewModelScope.launch {
            busy = true
            try {
                val changed = withContext(Dispatchers.Default) {
                    original.withThreadWeight(threadWeight ?: original.threadWeight).copy(transform = transform, stitchStyle = style).also { it.localPattern() }
                }
                if (project == before) edit(textPropertiesCommand(original.id, changed))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { report(R.string.lettering_failed, e)
            } finally { busy = false }
        }
    }
    fun align(alignment: Alignment) = editPoses(project.align(selectedIds, alignment))
    fun distribute(axis: Axis) = editPoses(project.distribute(selectedIds, axis))
    fun mirror() = editPoses(selectedIds.fold(project) { p, id -> p.mirrorX(id) })
    fun rotate90() = editPoses(selectedIds.fold(project) { p, id -> p.rotateBy(id, 90.0) })
    fun center() {
        if (selectedIds.size == 1) editPoses(project.alignCenter(selectedId!!))
        else project.selectionBounds(selectedIds)?.let { b ->
            editPoses(project.transformGroup(selectedIds, -(b.minX.toDouble() + b.maxX) / 2,
                -(b.minY.toDouble() + b.maxY) / 2, 1.0, 0.0))
        }
    }
    fun delete() = edit(ProjectCommand.Remove(selectedIds.filterTo(linkedSetOf()) { project.find(it) != null }))
    fun duplicate() {
        if (gestureInProgress) return
        val copies = selectedIds.associateWith { UUID.randomUUID().toString() }
        edit(project.duplicateCommand(copies))
        selectedIds = copies.values.toSet()
    }
    fun toggleVisible(id: String) {
        val obj = project.find(id) ?: return
        edit(ProjectCommand.SetVisible(id, !obj.visible))
    }
    fun reorder(id: String, index: Int, duringGesture: Boolean = false) {
        if (duringGesture) { session.updateGesture(project.reorder(id, index)); sync() }
        else edit(project.reorderCommand(id, index))
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
    fun openProject(file: File) = loadProject(file.nameWithoutExtension) { file.readText() }
    fun openProject(uri: Uri) = loadProject(uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')) {
        getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error(getApplication<Application>().getString(R.string.editor_project_read_error))
    }
    private fun loadProject(name: String?, read: () -> String) = requestReplacement {
        busy = true
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { readEditorProject(read(), projectSources) }
                documentName = name
                replaceSession(loaded, savedOnDisk = true)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { report(R.string.editor_project_open_failed, e)
            } finally { busy = false }
        }
    }
    fun saveProject(name: String, uri: Uri? = null, afterSave: (() -> Unit)? = null, overwrite: Boolean = false) {
        if (busy || gestureInProgress) return
        endGesture()
        val snapshot = project
        busy = true
        viewModelScope.launch {
            var savedSuccessfully = false
            try {
                val savedName = withContext(Dispatchers.IO) {
                    if (uri == null) saveProjectSnapshot(projectDir, name, snapshot, overwrite).name
                    else {
                        val text = ProjectJson.encode(snapshot)
                        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
                            ?: error(getApplication<Application>().getString(R.string.editor_project_write_error))
                        name
                    }
                }
                projectFiles = withContext(Dispatchers.IO) { (listProjectFiles(projectDir) + listProjectFiles(legacyProjectDir)).sortedByDescending { it.lastModified() } }
                documentName = designSaveStem(name)
                documents.markSaved(snapshot)
                sync()
                message = getApplication<Application>().getString(R.string.editor_project_saved, savedName)
                savedSuccessfully = true
            } catch (e: CancellationException) { throw e
            } catch (e: EditorSaveConflict) {
                savePrompt = EditorSavePrompt(EditorSaveKind.PROJECT, name)
                overwriteName = name
            } catch (e: Exception) { report(R.string.editor_project_save_failed, e)
            } finally { busy = false }
            if (savedSuccessfully) afterSave?.invoke()
        }
    }

    /** Named PES export; replacement requires explicit confirmation. */
    fun save() { if (!interactionBlocked && project.objects.any { it.visible }) requestNamedSave(EditorSaveKind.PES) }
    private fun writePes(name: String, overwrite: Boolean) {
        if (interactionBlocked || gestureInProgress || project.objects.none { it.visible }) return
        endGesture()
        val snapshot = project
        viewModelScope.launch {
            busy = true
            try {
                val saved = withContext(Dispatchers.IO) {
                    val exportSettings = preferences.read() // Re-read immediately before writing.
                    val pattern = snapshot.flatten(exportSettings.sewing)
                    require(pattern.stitchCount > 0) { getApplication<Application>().getString(R.string.editor_empty_export) }
                    val problems = editorExportProblems(snapshot, exportSettings, "${designSaveStem(name)}.pes", getApplication<Application>().resources)
                    if (problems.isNotEmpty()) throw EditorExportBlocked(problems)
                    val bytes = PesWriter.write(pattern, exportSettings.writeSettings.copy(hoop = snapshot.hoop))
                    writeNamedDesign(projectDir, name, "pes", overwrite, bytes).also {
                        if (io.github.linker2k.myembro.engine.sewout.SewoutCheck.originOf(pattern) == io.github.linker2k.myembro.engine.sewout.DesignOrigin.APP_GENERATED)
                            io.github.linker2k.myembro.library.GeneratedPesOrigins(getApplication()).record(bytes)
                    }
                }
                documentName = saved.nameWithoutExtension
                documents.markSaved(snapshot)
                sync()
                lastSavedFile = saved
                designs = withContext(Dispatchers.IO) { library.list() }
                message = getApplication<Application>().getString(R.string.editor_saved, saved.name)
            } catch (e: CancellationException) { throw e
            } catch (e: EditorSaveConflict) {
                savePrompt = EditorSavePrompt(EditorSaveKind.PES, name)
                overwriteName = name
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
