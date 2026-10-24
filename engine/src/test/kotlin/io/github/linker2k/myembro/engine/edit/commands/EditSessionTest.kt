// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit.commands

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class EditSessionTest {
    private fun obj(id: String) = StitchObject(id,id,EmbPattern(listOf(
        Stitch(10,10,Command.STITCH),Stitch(20,20,Command.STITCH),Stitch(20,20,Command.END)),
        listOf(EmbThread(0xffff0000.toInt()))),sourceBytes=byteArrayOf(1,2,3),sourceFileName="test.pes")
    private fun initial() = Project(objects=listOf(obj("a")))
    private fun committed(session: EditSession,id: String,commands: List<ProjectCommand>) =
        assertIs<TurnResult.Committed>(session.executeTurn(id,session.revision,commands))
    private fun withRedo(): EditSession {
        val session=EditSession(initial())
        committed(session,"move",listOf(ProjectCommand.SetTransforms("a",Transform(x=20.0))))
        assertIs<SessionHistoryResult.Applied>(session.undo(session.revision))
        assertTrue(session.snapshot().canRedo)
        return session
    }
    @Test fun successfulTurnIsPrivateUntilOneCommitAndOneUndoRestoresEverything() {
        val original=initial();val session=EditSession(original);val token=session.revision
        val commands=listOf(ProjectCommand.Duplicate("a","copy"),ProjectCommand.SetTransforms("a",Transform(x=40.0)),
            ProjectCommand.SetVisible("copy",false))
        assertIs<TurnResult.InProgress>(session.beginTurn("turn",token,commands))
        for(i in commands.indices) {
            assertEquals(i+1,assertIs<TurnResult.InProgress>(session.applyNext("turn")).appliedCommands)
            assertSame(original,session.project);assertEquals(0,session.snapshot().undoCount);assertEquals(token,session.revision)
        }
        val result=assertIs<TurnResult.Committed>(session.commitTurn("turn",token))
        assertTrue(result.changed);assertEquals(token+1,session.revision);assertEquals(1,session.snapshot().undoCount)
        assertNotNull(session.project.find("copy"));assertEquals(40.0,session.project.find("a")!!.transform.x)
        assertFalse(session.project.find("copy")!!.visible)
        val after=session.project
        assertTrue(assertIs<SessionHistoryResult.Applied>(session.undo(session.revision)).changed)
        assertSame(original,session.project);assertTrue(session.revision>result.snapshot.revision)
        session.redo(session.revision);assertSame(after,session.project)
    }
    @Test fun middleFailurePreservesHistoryRedoAndReportsEntireNestedCommandPath() {
        val session=withRedo();val before=session.snapshot()
        val commands=listOf(ProjectCommand.Add(obj("temp")),ProjectCommand.Batch(listOf(
            ProjectCommand.SetVisible("a",false),ProjectCommand.Remove(setOf("absent")))))
        val result=assertIs<TurnResult.Rejected>(session.executeTurn("failed",before.revision,commands))
        assertEquals(SessionError.COMMAND_REJECTED,result.code)
        val failure=assertNotNull(result.failure)
        assertEquals(CommandError.MISSING_OBJECT,failure.code);assertEquals(listOf(1,1),failure.path)
        assertSame(before.project,failure.project) // Never leak the private candidate containing temp.
        assertEquals(before,session.snapshot());assertNull(session.project.find("temp"))
        assertSame(result,session.commitTurn("failed",before.revision))
        assertTrue(assertIs<SessionHistoryResult.Applied>(session.redo(session.revision)).changed)
    }
    @Test fun cancellationPreservesRedoAndIsRecordedForRetries() {
        val session=withRedo();val before=session.snapshot()
        val commands=listOf(ProjectCommand.Remove(setOf("a")))
        session.beginTurn("cancel",before.revision,commands);session.applyNext("cancel")
        assertSame(before.project,session.project)
        val result=assertIs<TurnResult.Cancelled>(session.cancelTurn("cancel"))
        assertEquals(before,session.snapshot())
        assertSame(result,session.beginTurn("cancel",before.revision,commands))
        assertSame(result,session.applyNext("cancel"));assertSame(result,session.commitTurn("cancel",before.revision))
    }
    @Test fun staleBeginOrCommitTokenCannotChangePublishedStateOrRedo() {
        val session=withRedo();val before=session.snapshot()
        val commands=listOf(ProjectCommand.Remove(setOf("a")))
        val stale=assertIs<TurnResult.Rejected>(session.beginTurn("stale",before.revision-1,commands))
        assertEquals(SessionError.REVISION_MISMATCH,stale.code);assertEquals(before,session.snapshot())
        session.beginTurn("commit-stale",before.revision,commands);session.applyNext("commit-stale")
        val result=assertIs<TurnResult.Rejected>(session.commitTurn("commit-stale",before.revision+1))
        assertEquals(SessionError.REVISION_MISMATCH,result.code);assertEquals(before,session.snapshot())
    }
    @Test fun completedRetryReturnsOriginalReceiptAfterUndoWithoutReapplying() {
        val session=EditSession(initial());val base=session.revision
        val commands=listOf(ProjectCommand.Duplicate("a","copy"))
        val result=assertIs<TurnResult.Committed>(session.executeTurn("turn",base,commands))
        session.undo(session.revision);val beforeRetry=session.snapshot()
        assertNull(session.project.find("copy"));assertTrue(beforeRetry.canRedo)
        assertSame(result,session.executeTurn("turn",base,commands.toList()))
        assertSame(result,session.commitTurn("turn",base));assertEquals(beforeRetry,session.snapshot())
        assertNotNull(result.snapshot.project.find("copy")) // Receipt is historical, not today's live snapshot.
    }
    @Test fun idReuseWithDifferentCommandsOrBaseRevisionIsRejected() {
        val session=EditSession(initial())
        val commands=listOf(ProjectCommand.SetVisible("a",false))
        committed(session,"id",commands);val before=session.snapshot()
        for((token,body) in listOf(0L to listOf(ProjectCommand.Remove(setOf("a"))),before.revision to commands)) {
            assertEquals(SessionError.TRANSACTION_ID_REUSED,assertIs<TurnResult.Rejected>(session.beginTurn("id",token,body)).code)
            assertEquals(before,session.snapshot())
        }
    }
    @Test fun equivalentImportedAddPayloadCanReplayWithoutReferenceEquality() {
        val session=EditSession()
        val result=committed(session,"add",listOf(ProjectCommand.Add(obj("a"))))
        val before=session.snapshot()
        assertSame(result,session.executeTurn("add",0L,listOf(ProjectCommand.Add(obj("a")))))
        assertEquals(before,session.snapshot())
        val different=obj("a").copy(sourceBytes=byteArrayOf(9))
        assertEquals(SessionError.TRANSACTION_ID_REUSED,assertIs<TurnResult.Rejected>(session.beginTurn("add",0L,listOf(ProjectCommand.Add(different)))).code)
    }
    @Test fun unchangedAndEmptyTurnsDoNotAddStepsOrClearRedoOrAdvanceRevision() {
        val session=withRedo();val before=session.snapshot()
        for((id,commands) in listOf("same" to listOf(ProjectCommand.SetVisible("a",true)),
            "empty" to emptyList(),"net-zero" to listOf(ProjectCommand.Add(obj("temp")),ProjectCommand.Remove(setOf("temp"))))) {
            val result=committed(session,id,commands)
            assertFalse(result.changed);assertEquals(before,session.snapshot());assertSame(before.project,result.snapshot.project)
        }
    }
    @Test fun historyOperationsAreRejectedWhileTurnIsActiveAndOldTokensStayStale() {
        val session=withRedo();val base=session.snapshot()
        val commands=listOf(ProjectCommand.SetVisible("a",false))
        session.beginTurn("turn",base.revision,commands);val during=session.snapshot()
        assertFalse(during.canUndo);assertFalse(during.canRedo)
        assertEquals(SessionError.TURN_IN_PROGRESS,assertIs<SessionHistoryResult.Rejected>(session.undo(base.revision)).code)
        assertEquals(SessionError.TURN_IN_PROGRESS,assertIs<SessionHistoryResult.Rejected>(session.redo(base.revision)).code)
        assertEquals(during,session.snapshot())
        session.cancelTurn("turn");assertEquals(base,session.snapshot())
        session.redo(session.revision);val changed=session.snapshot()
        assertEquals(SessionError.REVISION_MISMATCH,assertIs<SessionHistoryResult.Rejected>(session.undo(base.revision)).code)
        assertEquals(changed,session.snapshot())
    }
    @Test fun wrongTransactionCallsAndRepeatedBeginDoNotDisturbActiveTurn() {
        val session=EditSession(initial());val token=session.revision
        val commands=listOf(ProjectCommand.SetVisible("a",false))
        session.beginTurn("owner",token,commands);session.applyNext("owner")
        assertEquals(1,assertIs<TurnResult.InProgress>(session.beginTurn("owner",token,commands)).appliedCommands)
        assertEquals(SessionError.TRANSACTION_ID_REUSED,assertIs<TurnResult.Rejected>(session.beginTurn("owner",token,emptyList())).code)
        assertEquals(SessionError.TURN_IN_PROGRESS,assertIs<TurnResult.Rejected>(session.beginTurn("other",token,emptyList())).code)
        for(result in listOf(session.applyNext("other"),session.commitTurn("other",token),session.cancelTurn("other")))
            assertEquals(SessionError.WRONG_TRANSACTION,assertIs<TurnResult.Rejected>(result).code)
        assertTrue(session.snapshot().turnInProgress)
        assertTrue(assertIs<TurnResult.Committed>(session.commitTurn("owner",token)).changed)
    }
    @Test fun incompleteTurnIsAbortedAndNoHistoryOperationsAtEndsChangeRevision() {
        val session=EditSession(initial());val before=session.snapshot()
        assertFalse(assertIs<SessionHistoryResult.Applied>(session.undo(before.revision)).changed)
        assertFalse(assertIs<SessionHistoryResult.Applied>(session.redo(before.revision)).changed)
        session.beginTurn("incomplete",before.revision,listOf(ProjectCommand.Remove(setOf("a"))))
        assertEquals(SessionError.INCOMPLETE_TURN,assertIs<TurnResult.Rejected>(session.commitTurn("incomplete",before.revision)).code)
        assertEquals(before,session.snapshot())
        assertEquals(SessionError.INVALID_TRANSACTION_ID,assertIs<TurnResult.Rejected>(session.beginTurn(" ",before.revision,emptyList())).code)
        assertEquals(SessionError.NO_ACTIVE_TURN,assertIs<TurnResult.Rejected>(session.applyNext("absent")).code)
    }
    @Test fun capturesDeclaredCommandContainersBeforeIncrementalApplication() {
        val session=EditSession(initial())
        val transforms=linkedMapOf("a" to TransformValues(x=12.0))
        val commands=mutableListOf<ProjectCommand>(ProjectCommand.SetTransforms(transforms))
        session.beginTurn("captured",0L,commands)
        commands.clear();transforms["a"]=TransformValues(x=99.0)
        session.applyNext("captured");session.commitTurn("captured",0L)
        assertEquals(12.0,session.project.find("a")!!.transform.x)
    }
    @Test fun historyRetainsExistingBoundWhileRevisionRemainsMonotonic() {
        val session=EditSession(initial(),maxSteps=2)
        for(i in 1..3)committed(session,"move-$i",listOf(ProjectCommand.SetTransforms("a",Transform(x=i.toDouble()))))
        assertEquals(2,session.snapshot().undoCount);assertEquals(3L,session.revision)
        session.undo(session.revision);session.undo(session.revision)
        assertEquals(1.0,session.project.find("a")!!.transform.x);assertEquals(5L,session.revision)
        val before=session.snapshot();session.undo(session.revision);assertEquals(before,session.snapshot())
    }
}
