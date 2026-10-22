// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.DesignObject
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.StitchObject
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.edit.Transform
import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.engine.io.PesExport
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.library.DesignLibrary
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Public activity entry point: create with the usual AndroidViewModel factory. */
class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private var history = EditorHistory()
    private val library = DesignLibrary(app)
    var project by mutableStateOf(history.project)
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    val selected: DesignObject? get() = selectedId?.let(project::find)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var designs by mutableStateOf<List<File>>(emptyList())
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

    private fun sync() {
        project = history.project
        canUndo = history.canUndo
        canRedo = history.canRedo
        if (selectedId != null && selected == null) selectedId = null
    }

    /** Starts an empty project (library "만들기"). */
    fun newProject() {
        history = EditorHistory()
        selectedId = null
        sync()
    }

    /** Starts a project holding [file] as its only object (viewer "편집"); undo cannot remove it. */
    fun editDesign(file: File) {
        newProject()
        busy = true
        viewModelScope.launch {
            try {
                val obj = withContext(Dispatchers.IO) {
                    StitchObject(UUID.randomUUID().toString(), file.nameWithoutExtension,
                        PatternReader.read(file.readBytes(), file.name))
                }
                history = EditorHistory(Project().add(obj))
                selectedId = obj.id
                sync()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                message = getApplication<Application>().getString(R.string.editor_load_failed, e.localizedMessage.orEmpty())
            } finally { busy = false }
        }
    }

    private fun edit(next: Project) { history.commit(next); sync() }
    fun select(id: String?) { selectedId = id?.takeIf { project.find(it)?.visible == true } }
    fun setHoop(hoop: HoopSpec) = edit(project.copy(hoop = hoop))
    fun undo() { history.undo(); sync() }
    fun redo() { history.redo(); sync() }
    fun beginGesture() = history.beginGesture()
    fun endGesture() { history.endGesture(); sync() }

    fun transformSelection(transform: Transform) {
        val obj = selected ?: return
        history.updateGesture(project.replace(obj.withTransform(transform)))
        sync()
    }

    private fun changeTransform(change: (Transform) -> Transform) {
        val obj = selected ?: return
        edit(project.replace(obj.withTransform(change(obj.transform))))
    }

    fun mirror() = changeTransform { it.copy(scaleX = -it.scaleX) }
    fun rotate90() = changeTransform { it.copy(rotationDeg = (it.rotationDeg + 90) % 360) }
    fun center() = changeTransform { it.copy(x = 0.0, y = 0.0) }
    fun delete() { selectedId?.let { edit(project.remove(it)) } }

    fun duplicate() {
        val obj = selected ?: return
        val id = UUID.randomUUID().toString()
        val t = obj.transform.copy(x = obj.transform.x + 50, y = obj.transform.y + 50)
        // Preserve the editable type for stitch objects; future object types can add a copy API.
        val copy = if (obj is StitchObject) obj.copy(id = id, transform = t)
        else StitchObject(id, obj.name, obj.localPattern(), t, obj.visible)
        edit(project.add(copy))
        selectedId = id
    }

    fun toggleVisible(id: String) {
        edit(project.update(id) { it.withVisible(!it.visible) })
        if (selectedId == id && project.find(id)?.visible == false) selectedId = null
    }

    fun reorder(id: String, index: Int, duringGesture: Boolean = false) {
        val next = project.reorder(id, index)
        if (duringGesture) { history.updateGesture(next); sync() } else edit(next)
    }

    /** Also usable by the viewer when its existing design should be added to the editor. */
    fun addDesign(file: File) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                val obj = withContext(Dispatchers.IO) {
                    StitchObject(UUID.randomUUID().toString(), file.nameWithoutExtension,
                        PatternReader.read(file.readBytes(), file.name))
                }
                edit(project.add(obj))
                selectedId = obj.id
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                message = getApplication<Application>().getString(R.string.editor_load_failed, e.localizedMessage.orEmpty())
            } finally { busy = false }
        }
    }

    /** Adds lettering built by the text tool and selects it. */
    fun addText(obj: TextObject) {
        edit(project.add(obj))
        selectedId = obj.id
    }

    /** Exports a snapshot atomically, without overwriting any library design. */
    fun save() {
        if (busy || project.objects.none { it.visible }) return
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
