// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit.commands

import io.github.linker2k.myembro.engine.edit.History
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.StitchObject

/**
 * One turn, one History.commit. All state transitions are serialized; callers may generate/prepare
 * commands outside the session, then submit the frozen list with its starting revision.
 * Project/object payloads retain the existing immutable-value ownership contract.
 * Receipts are kept for this session's lifetime (no eviction or persistence in this implementation).
 */
class EditSession(initial: Project = Project(), maxSteps: Int = 100) {
    private val history = History(initial, maxSteps)
    private var currentRevision = 0L
    private data class Request(val id: String, val revision: Long, val commands: List<ProjectCommand>)
    private class Turn(val request: Request, var candidate: Project, var index: Int = 0)
    private data class Receipt(val request: Request, val result: TurnResult.Completed)
    private var active: Turn? = null
    private val receipts = mutableMapOf<String, Receipt>()

    val project: Project get() = snapshot().project
    val revision: Long get() = snapshot().revision

    @Synchronized fun snapshot() = EditSessionSnapshot(history.current, currentRevision,
        history.undoCount, history.redoCount, active != null)

    /** Declare the full turn body, allowing a completed retry to be checked before revision validation. */
    @Synchronized fun beginTurn(transactionId: String, expectedRevision: Long,
        commands: List<ProjectCommand>): TurnResult {
        if (transactionId.isBlank()) return reject(transactionId, SessionError.INVALID_TRANSACTION_ID)
        val request = Request(transactionId, expectedRevision, commands.map(::capture))
        receipts[transactionId]?.let { receipt ->
            return if (sameRequest(receipt.request, request)) receipt.result
                else reject(transactionId, SessionError.TRANSACTION_ID_REUSED)
        }
        active?.let { turn ->
            return if (turn.request.id != transactionId) reject(transactionId, SessionError.TURN_IN_PROGRESS)
                else if (sameRequest(turn.request, request)) progress(turn)
                else reject(transactionId, SessionError.TRANSACTION_ID_REUSED)
        }
        if (expectedRevision != currentRevision) return reject(transactionId, SessionError.REVISION_MISMATCH)
        return Turn(request, history.current).also { active = it }.let(::progress)
    }

    /** Applies exactly the next declared command to an unpublished candidate. Failure aborts the turn. */
    @Synchronized fun applyNext(transactionId: String): TurnResult {
        receipts[transactionId]?.let { return it.result }
        val turn = active ?: return reject(transactionId, SessionError.NO_ACTIVE_TURN)
        if (turn.request.id != transactionId) return reject(transactionId, SessionError.WRONG_TRANSACTION)
        if (turn.request.revision != currentRevision) return finish(turn, rejected(transactionId, SessionError.REVISION_MISMATCH))
        if (turn.index == turn.request.commands.size) return progress(turn)
        return when (val result = apply(turn.candidate, turn.request.commands[turn.index])) {
            is CommandResult.Applied -> {
                turn.candidate = result.project
                turn.index++
                progress(turn)
            }
            is CommandResult.Rejected -> finish(turn, rejected(transactionId, SessionError.COMMAND_REJECTED,
                result.copy(project = history.current, path = listOf(turn.index) + result.path)))
        }
    }

    /** A wrong token, incomplete body or rejected command can never publish a partial candidate. */
    @Synchronized fun commitTurn(transactionId: String, expectedRevision: Long): TurnResult {
        receipts[transactionId]?.let { return it.result }
        val turn = active ?: return reject(transactionId, SessionError.NO_ACTIVE_TURN)
        if (turn.request.id != transactionId) return reject(transactionId, SessionError.WRONG_TRANSACTION)
        if (expectedRevision != turn.request.revision || expectedRevision != currentRevision)
            return finish(turn, rejected(transactionId, SessionError.REVISION_MISMATCH))
        if (turn.index != turn.request.commands.size) return finish(turn, rejected(transactionId, SessionError.INCOMPLETE_TURN))
        val changed = turn.candidate != history.current
        val nextRevision = if (changed) Math.incrementExact(currentRevision) else currentRevision
        history.commit(turn.candidate)
        currentRevision = nextRevision
        active = null
        return TurnResult.Committed(transactionId, snapshot(), changed).also {
            receipts[transactionId] = Receipt(turn.request, it)
        }
    }

    @Synchronized fun cancelTurn(transactionId: String): TurnResult {
        receipts[transactionId]?.let { return it.result }
        val turn = active ?: return reject(transactionId, SessionError.NO_ACTIVE_TURN)
        if (turn.request.id != transactionId) return reject(transactionId, SessionError.WRONG_TRANSACTION)
        return finish(turn, TurnResult.Cancelled(transactionId, snapshot().copy(turnInProgress = false)))
    }

    /** Convenience for JVM scenarios/tools; incremental methods allow cancellation between commands. */
    fun executeTurn(transactionId: String, expectedRevision: Long, commands: List<ProjectCommand>): TurnResult {
        var result = beginTurn(transactionId, expectedRevision, commands)
        while (result is TurnResult.InProgress && result.appliedCommands < result.totalCommands)
            result = applyNext(transactionId)
        return if (result is TurnResult.InProgress) commitTurn(transactionId, expectedRevision) else result
    }

    @Synchronized fun undo(expectedRevision: Long): SessionHistoryResult = stepHistory(expectedRevision, redo = false)
    @Synchronized fun redo(expectedRevision: Long): SessionHistoryResult = stepHistory(expectedRevision, redo = true)

    private fun stepHistory(expectedRevision: Long, redo: Boolean): SessionHistoryResult {
        if (active != null) return SessionHistoryResult.Rejected(snapshot(), SessionError.TURN_IN_PROGRESS)
        if (expectedRevision != currentRevision) return SessionHistoryResult.Rejected(snapshot(), SessionError.REVISION_MISMATCH)
        val available = if (redo) history.canRedo else history.canUndo
        if (!available) return SessionHistoryResult.Applied(snapshot(), changed = false)
        val nextRevision = Math.incrementExact(currentRevision)
        if (redo) history.redo() else history.undo()
        currentRevision = nextRevision
        return SessionHistoryResult.Applied(snapshot(), changed = true)
    }

    private fun progress(turn: Turn) = TurnResult.InProgress(turn.request.id, turn.request.revision,
        turn.index, turn.request.commands.size)
    private fun reject(id: String, error: SessionError) = TurnResult.Rejected(id, snapshot(), error)
    private fun rejected(id: String, error: SessionError, failure: CommandResult.Rejected? = null) =
        TurnResult.Rejected(id, snapshot().copy(turnInProgress = false), error, failure)
    private fun finish(turn: Turn, result: TurnResult.Completed): TurnResult.Completed {
        active = null
        receipts[turn.request.id] = Receipt(turn.request, result)
        return result
    }

    private fun sameRequest(a: Request, b: Request): Boolean = a.id == b.id && a.revision == b.revision &&
        a.commands.size == b.commands.size && a.commands.zip(b.commands).all { (x,y) -> sameCommand(x,y) }

    /** StitchObject contains arrays and EmbPattern has reference equality; compare replay payload values. */
    private fun sameCommand(a: ProjectCommand, b: ProjectCommand): Boolean = when {
        a is ProjectCommand.Batch && b is ProjectCommand.Batch -> a.commands.size == b.commands.size &&
            a.commands.zip(b.commands).all { (x,y) -> sameCommand(x,y) }
        a is ProjectCommand.Add && b is ProjectCommand.Add && a.obj is StitchObject && b.obj is StitchObject -> {
            val x = a.obj; val y = b.obj
            x.id == y.id && x.name == y.name && x.transform == y.transform && x.visible == y.visible &&
                x.sourceFileName == y.sourceFileName && x.sourceThreadsEdited == y.sourceThreadsEdited &&
                (x.sourceBytes?.contentEquals(y.sourceBytes) ?: (y.sourceBytes == null)) &&
                x.source.stitches == y.source.stitches && x.source.threads == y.source.threads &&
                x.source.metadata == y.source.metadata && x.source.warnings == y.source.warnings
        }
        else -> a == b
    }

    /** Capture command containers, not caller-owned mutable collection views. */
    private fun capture(command: ProjectCommand): ProjectCommand = when (command) {
        is ProjectCommand.Batch -> command.copy(commands = command.commands.map(::capture))
        is ProjectCommand.SetTransforms -> command.copy(byId = command.byId.toMap())
        is ProjectCommand.Remove -> command.copy(ids = command.ids.toSet())
        else -> command
    }
}
