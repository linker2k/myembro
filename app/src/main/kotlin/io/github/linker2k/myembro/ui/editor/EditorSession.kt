// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.exceedsHoop
import io.github.linker2k.myembro.engine.edit.commands.EditSession
import io.github.linker2k.myembro.engine.edit.commands.SessionHistoryResult
import io.github.linker2k.myembro.engine.agent.ToolDispatcher
import io.github.linker2k.myembro.engine.agent.ToolTurnController
import kotlinx.serialization.json.*

/** UI previews stay outside the shared engine session until pointer-up: one gesture, one commit. */
internal class EditorSession(initial: Project = Project(), limit: Int = 100) {
    private val engine = EditSession(initial, maxSteps = limit)
    private val scriptControllers = java.util.IdentityHashMap<ToolDispatcher, ToolTurnController>()

    /** Phone path A: same controller and History as toolbar undo. Host pins/reuses its frame/asset kernel.
     * No Compose state or separate history is mutated during dry-run. VM must refresh after a commit. */
    fun runCommandScript(kernel: ToolDispatcher, transactionId: String, expectedRevision: Long,
        calls: List<ToolTurnController.Call>, exportSettings: JsonObject, dryRun: Boolean = false,
        confirmed: Boolean = false): ToolDispatcher.Result {
        if(isGesturing) return ToolDispatcher.Result(project,buildJsonObject {
            put("ok",false);put("code","GESTURE_IN_PROGRESS");put("message","Finish the pointer gesture before running a script")
        })
        val controller=scriptControllers.getOrPut(kernel) { kernel.bind(engine) }
        val result=controller.executeScript(transactionId,expectedRevision,calls,exportSettings,dryRun,confirmed)
        if(!dryRun) project=engine.project
        return result
    }
    /** Read-only compatibility view for existing VM/test history counts; never a second History. */
    val history get() = engine.snapshot()
    var project: Project = initial
        private set
    var isGesturing = false
        private set
    val showsHoopOverflow get() = !isGesturing && project.exceedsHoop()
    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo
    fun beginGesture() { isGesturing = true }
    /** Pointer events can arrive after a toolbar action/navigation has ended the gesture. */
    fun updateGesture(next: Project): Boolean {
        if (!isGesturing) return false
        project = next
        return true
    }
    fun endGesture() {
        if (!isGesturing) return
        isGesturing = false
        // Preserve the exact final preview reference, including equal-value previews, as before.
        commitSnapshot(project)
    }
    fun commit(next: Project) { endGesture(); project = commitSnapshot(next).snapshot.project }
    private fun commitSnapshot(next: Project): SessionHistoryResult.Applied {
        val result = engine.commitSnapshot(next, engine.revision)
        check(result is SessionHistoryResult.Applied) { "Editor snapshot commit rejected: $result" }
        return result
    }
    /** User toolbar actions never close a pointer gesture; true means haptics may fire. */
    fun stepHistory(redo: Boolean, blocked: Boolean = false): Boolean {
        if (blocked || isGesturing || (if (redo) !canRedo else !canUndo)) return false
        val result = stepEngineHistory(redo)
        return result?.changed == true
    }
    private fun stepEngineHistory(redo: Boolean): SessionHistoryResult.Applied? {
        val result = if (redo) engine.redo(engine.revision) else engine.undo(engine.revision)
        if (result !is SessionHistoryResult.Applied) return null
        project = result.snapshot.project
        return result
    }
    fun undo() { endGesture(); stepEngineHistory(redo = false) }
    fun redo() { endGesture(); stepEngineHistory(redo = true) }
}
