// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit.commands

import io.github.linker2k.myembro.engine.edit.Project

/** Published state only: private turn candidates are never exposed through snapshots/results. */
data class EditSessionSnapshot(
    val project: Project,
    val revision: Long,
    val undoCount: Int,
    val redoCount: Int,
    val turnInProgress: Boolean,
) {
    val canUndo: Boolean get() = !turnInProgress && undoCount > 0
    val canRedo: Boolean get() = !turnInProgress && redoCount > 0
}

enum class SessionError {
    INVALID_TRANSACTION_ID, REVISION_MISMATCH, TURN_IN_PROGRESS, NO_ACTIVE_TURN,
    WRONG_TRANSACTION, TRANSACTION_ID_REUSED, INCOMPLETE_TURN, COMMAND_REJECTED,
}

sealed interface TurnResult {
    val transactionId: String

    data class InProgress(override val transactionId: String, val baseRevision: Long,
        val appliedCommands: Int, val totalCommands: Int) : TurnResult

    sealed interface Completed : TurnResult { val snapshot: EditSessionSnapshot }
    data class Committed(override val transactionId: String, override val snapshot: EditSessionSnapshot,
        val changed: Boolean) : Completed
    data class Rejected(override val transactionId: String, override val snapshot: EditSessionSnapshot,
        val code: SessionError, val failure: CommandResult.Rejected? = null) : Completed
    data class Cancelled(override val transactionId: String, override val snapshot: EditSessionSnapshot) : Completed
}

sealed interface SessionHistoryResult {
    val snapshot: EditSessionSnapshot
    data class Applied(override val snapshot: EditSessionSnapshot, val changed: Boolean) : SessionHistoryResult
    data class Rejected(override val snapshot: EditSessionSnapshot, val code: SessionError) : SessionHistoryResult
}
