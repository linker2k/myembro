// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.PesExport
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.library.DesignLibrary
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private var session = EditorSession()
    private var gestureDraft: Project? = null
    private var targets = SnapTargets(emptyList(), emptyList())
    private val library = DesignLibrary(app)
    private val projectDir get() = File(getApplication<Application>().filesDir, "projects")
    var project by mutableStateOf(session.project)
        private set
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    val hoopOverflowVisible: Boolean get() = session.showsHoopOverflow
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

    init { refreshDesigns() }

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
        selectedIds = selectedIds.filterTo(linkedSetOf()) { project.find(it)?.visible == true }
    }
    fun newProject(hoop: HoopSpec = HoopSpec.H100) {
        session = EditorSession(Project(hoop = hoop))
        gestureDraft = null
        selectedIds = emptySet()
        selectionMode = false
        snapGuides = SnapGuides()
        sync()
    }
    /** Lettering entry point: like [editDesign], the object is the baseline, not an undoable add. */
    fun startWith(obj: DesignObject, hoop: HoopSpec) {
        if (busy || gestureInProgress) return
        newProject(hoop)
        session = EditorSession(Project(hoop = hoop).add(obj))
        selectedIds = setOf(obj.id)
        sync()
    }

    private fun importObject(file: File) = StitchObject.fromBytes(UUID.randomUUID().toString(),
        file.nameWithoutExtension, file.readBytes(), file.name)

    /** Viewer entry point: its initial object is the baseline, not an undoable import. */
    fun editDesign(file: File) {
        if (busy || gestureInProgress) return
        newProject()
        busy = true
        viewModelScope.launch {
            try {
                val obj = withContext(Dispatchers.IO) { importObject(file) }
                session = EditorSession(Project().add(obj))
                selectedIds = setOf(obj.id)
                sync()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { report(R.string.editor_load_failed, e)
            } finally { busy = false }
        }
    }
    private fun edit(next: Project) {
        if (gestureInProgress) return
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
    fun setHoop(hoop: HoopSpec) = edit(project.copy(hoop = hoop))
    fun undo() { if (busy || gestureInProgress) return; session.undo(); sync() }
    fun redo() { if (busy || gestureInProgress) return; session.redo(); sync() }
    fun beginGesture() {
        if (gestureDraft != null) return
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
        if (busy || gestureInProgress) return
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
    private fun report(resource: Int, e: Exception) {
        message = getApplication<Application>().getString(resource, e.localizedMessage.orEmpty())
    }
    fun openProject(file: File) = loadProject { file.readText() }
    fun openProject(uri: Uri) = loadProject {
        getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error(getApplication<Application>().getString(R.string.editor_project_read_error))
    }
    private fun loadProject(read: () -> String) {
        if (busy || gestureInProgress) return
        busy = true
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { ProjectJson.decode(read()) }
                edit(loaded) // Opening is reversible, so an existing composition is never lost.
                selectedIds = emptySet()
                selectionMode = false
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { report(R.string.editor_project_open_failed, e)
            } finally { busy = false }
        }
    }
    fun saveProject(name: String, uri: Uri? = null) {
        if (busy || gestureInProgress) return
        endGesture()
        val snapshot = project
        busy = true
        viewModelScope.launch {
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
                message = getApplication<Application>().getString(R.string.editor_project_saved, savedName)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { report(R.string.editor_project_save_failed, e)
            } finally { busy = false }
        }
    }

    /** Exports a snapshot atomically, without overwriting any library design. */
    fun save() {
        if (busy || gestureInProgress || project.objects.none { it.visible }) return
        endGesture()
        val snapshot = project
        viewModelScope.launch {
            busy = true
            try {
                val saved = withContext(Dispatchers.IO) {
                    val pattern = snapshot.flatten()
                    require(pattern.stitchCount > 0) { getApplication<Application>().getString(R.string.editor_empty_export) }
                    val bytes = PesWriter.write(pattern)
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
            } catch (e: Exception) {
                message = getApplication<Application>().getString(R.string.editor_save_failed, e.localizedMessage.orEmpty())
            } finally { busy = false }
        }
    }
}
