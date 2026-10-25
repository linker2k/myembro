// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Bounds

/** Size of the selected composition as exported, never the conservative pointer/hit-test box. */
internal fun Project.selectionOutputBounds(ids: Set<String>, sewing: SewingOptions = SewingOptions()): Bounds? {
    val selection = copy(objects = objects.filter { it.visible && it.id in ids })
    return try {
        PesWriter.outputBounds(selection.flatten(sewing))
    } catch (_: IllegalArgumentException) {
        // A composition beyond PEC's representable limits remains editable, without a false size.
        null
    }
}
