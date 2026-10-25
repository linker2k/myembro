// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit.commands

import io.github.linker2k.myembro.engine.edit.Project

enum class CommandError {
    INVALID_ID, DUPLICATE_ID, MISSING_OBJECT, INVALID_TRANSFORM,
    INVALID_THREAD_INDEX, INVALID_REORDER_INDEX, INVALID_OBJECT_TYPE,
}

sealed interface CommandResult {
    val project: Project

    data class Applied(
        override val project: Project,
        val changed: Boolean,
        /** IDs whose final content, existence or list position changed. Hoop-only edits omit IDs. */
        val changedIds: Set<String>,
    ) : CommandResult

    data class Rejected(
        /** Always the original input project, even after a nested batch partially evaluated. */
        override val project: Project,
        val code: CommandError,
        val objectIds: Set<String>,
        /** Zero-based child indices through nested batches; empty for a root command/input error. */
        val path: List<Int>,
        val message: String,
    ) : CommandResult
}
