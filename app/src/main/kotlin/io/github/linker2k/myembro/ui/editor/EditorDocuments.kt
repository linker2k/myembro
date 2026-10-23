// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.ProjectJson
import io.github.linker2k.myembro.engine.edit.ProjectSourceResolver
import java.io.File

/** A replacement is only installed after its reader succeeds. The old History survives failures. */
internal class EditorDocuments {
    var session = EditorSession()
        private set
    private var saved: Project? = session.project
    private var pending: (() -> Unit)? = null
    val dirty get() = session.project != saved
    val draft get() = session.project.takeIf { dirty }
    val needsDecision get() = pending != null

    fun requestReplacement(action: () -> Unit) {
        if (dirty) pending = action else action()
    }
    fun cancelReplacement() { pending = null }
    fun continueReplacement() {
        val action = pending
        pending = null
        action?.invoke()
    }
    fun markSaved(snapshot: Project) { saved = snapshot }
    @Suppress("UNUSED_PARAMETER") // Compatibility: opened snapshot, rather than file existence, defines clean.
    fun replaceAfterRead(savedOnDisk: Boolean, restoredDraft: Boolean = false, read: () -> Project) {
        val loaded = read() // Do not reset the project, selection or History before this succeeds.
        session = EditorSession(loaded)
        saved = loaded.takeUnless { restoredDraft }
    }
}

/** File/SAF project opening and automatic recovery share the same source migration. */
internal fun readEditorProject(text: String, sourceResolver: ProjectSourceResolver? = null): Project =
    ProjectJson.decode(text, sourceResolver = sourceResolver)

/** One recoverable editable snapshot; atomic rename keeps the previous draft on write failure. */
internal class EditorDraftStore(private val directory: File, private val sourceResolver: ProjectSourceResolver? = null) {
    private val target get() = File(directory, "editor.myembro")
    fun read(): Project? = if (target.isFile) readEditorProject(target.readText(), sourceResolver) else null
    fun write(project: Project?) {
        if (project == null) {
            check(!target.exists() || target.delete()) { "Could not clear editor draft" }
            return
        }
        val encoded = ProjectJson.encode(project)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create drafts directory" }
        val temp = File.createTempFile("editor-", ".tmp", directory)
        try {
            temp.writeText(encoded)
            check(temp.renameTo(target)) { "Could not finish editor draft" }
        } finally { temp.delete() }
    }
}
