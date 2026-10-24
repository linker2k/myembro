// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.edit.commands.apply

/** In-place text replacement keeps placement and sewing order; a stale edit never resurrects an object. */
internal fun Project.withLettering(incoming: TextObject, editingId: String?): Project {
    return apply(this, letteringCommand(incoming, editingId)).project
}

internal data class EditorLayout(val expanded: Boolean, val panelWidthDp: Float)
internal fun editorLayout(widthDp: Float) = EditorLayout(widthDp >= 600f, (widthDp * 0.38f).coerceIn(248f, 320f))
