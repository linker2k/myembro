// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit.commands

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class ProjectCommandsTest {
    private val red = EmbThread(0xffff0000.toInt(), "red", "R")
    private val blue = EmbThread(0xff0000ff.toInt(), "blue", "B")
    private fun stitch(id: String): StitchObject = StitchObject(id, id,
        EmbPattern(listOf(Stitch(0,0,Command.JUMP), Stitch(10,10,Command.STITCH),
            Stitch(20,10,Command.STITCH), Stitch(20,10,Command.END)), listOf(red), mapOf("test" to "kept"), listOf("kept")),
        sourceBytes = byteArrayOf(1,2,3), sourceFileName = "fixture.pes")
    private fun project() = Project(objects = listOf(stitch("a"), stitch("b"), TextObject("text", text = "Abc")))
    private fun accepted(project: Project, command: ProjectCommand) = assertIs<CommandResult.Applied>(apply(project, command))
    private fun rejected(project: Project, command: ProjectCommand, code: CommandError, path: List<Int> = emptyList()): CommandResult.Rejected {
        val result = assertIs<CommandResult.Rejected>(apply(project, command))
        assertSame(project, result.project)
        assertEquals(code, result.code)
        assertEquals(path, result.path)
        assertTrue(result.message.isNotBlank())
        return result
    }

    @Test fun addAndRemoveWrapExistingOperationsWithoutMutatingInput() {
        val original = project()
        val extra = stitch("new")
        val added = accepted(original, ProjectCommand.Add(extra))
        assertEquals(original.add(extra), added.project)
        assertTrue(added.changed);assertEquals(setOf("new"), added.changedIds)
        assertNull(original.find("new"))
        val removed = accepted(added.project, ProjectCommand.Remove(setOf("a", "new")))
        assertEquals(added.project.remove("a").remove("new"), removed.project)
        assertEquals(listOf("b", "text"), removed.project.objects.map {it.id})
        assertEquals(listOf("a", "b", "text"), original.objects.map {it.id})
    }
    @Test fun missingIdsAreRejectedForEveryTargetedOperation() {
        val original = project()
        val commands = listOf(
            ProjectCommand.SetTransforms(mapOf("a" to TransformValues(x=50.0), "absent" to TransformValues())),
            ProjectCommand.Remove(setOf("a", "absent")),
            ProjectCommand.Duplicate("absent", "new"), ProjectCommand.Reorder("absent", 0),
            ProjectCommand.SetVisible("absent", false), ProjectCommand.Recolor("absent", 0, blue))
        for(command in commands) {
            assertEquals(setOf("absent"), rejected(original, command, CommandError.MISSING_OBJECT).objectIds)
            assertEquals(Transform(), original.find("a")!!.transform)
        }
    }
    @Test fun duplicateAndBlankIdsNeverCreateAmbiguousObjects() {
        val original = project()
        rejected(original, ProjectCommand.Add(stitch("a")), CommandError.DUPLICATE_ID)
        rejected(original, ProjectCommand.Duplicate("a", "a"), CommandError.DUPLICATE_ID)
        rejected(original, ProjectCommand.Duplicate("a", "b"), CommandError.DUPLICATE_ID)
        rejected(original, ProjectCommand.Add(stitch(" ")), CommandError.INVALID_ID)
        rejected(original, ProjectCommand.Duplicate("a", ""), CommandError.INVALID_ID)
        rejected(original, ProjectCommand.Remove(setOf("")), CommandError.INVALID_ID)
        val malformed = original.copy(objects=original.objects+original.objects.first())
        rejected(malformed, ProjectCommand.SetVisible("a", false), CommandError.DUPLICATE_ID)
    }
    @Test fun transformsMatchExistingPlacementAndPreserveEditableTextIds() {
        val original = project()
        val pose = Transform(x=-25.0,y=30.0,rotationDeg=45.0,scaleX=-1.2,scaleY=0.8)
        val command = ProjectCommand.SetTransforms(mapOf("a" to TransformValues(pose), "text" to TransformValues(pose)))
        val result = accepted(original, command)
        val expected = original.update("a") {it.withTransform(pose)}.update("text") {it.withTransform(pose)}
        assertEquals(expected,result.project)
        assertEquals(setOf("a", "text"),result.changedIds)
        assertIs<TextObject>(result.project.find("text"));assertEquals(Transform(),original.find("text")!!.transform)
        assertEquals(original.objects.map {it.id},result.project.objects.map {it.id})
    }
    @Test fun zeroScalesAndEveryNonfiniteTransformFieldReturnTypedErrors() {
        val original = project()
        val invalid = mutableListOf(TransformValues(scaleX=0.0),TransformValues(scaleY=-0.0))
        for(v in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
            invalid += listOf(TransformValues(x=v),TransformValues(y=v),TransformValues(rotationDeg=v),
                TransformValues(scaleX=v),TransformValues(scaleY=v))
        }
        for(values in invalid) {
            rejected(original, ProjectCommand.SetTransforms(mapOf("a" to TransformValues(x=50.0),"text" to values)), CommandError.INVALID_TRANSFORM)
            rejected(original, ProjectCommand.Duplicate("a","new",values), CommandError.INVALID_TRANSFORM)
        }
        assertEquals(Transform(),original.find("a")!!.transform)
    }
    @Test fun duplicateUsesExplicitIdAndExistingInsertionAndPoseSemantics() {
        val original = project()
        val copied = accepted(original,ProjectCommand.Duplicate("a","copy")).project
        assertEquals(original.duplicate("a","copy"),copied)
        assertEquals(listOf("a","copy","b","text"),copied.objects.map {it.id})
        val pose=TransformValues(x=50.0,scaleX=-1.0)
        val moved=accepted(original,ProjectCommand.Duplicate("text","copy",pose)).project
        assertIs<TextObject>(moved.find("copy"));assertEquals(pose.toTransform(),moved.find("copy")!!.transform)
        assertNull(original.find("copy"))
    }
    @Test fun reorderRejectsOutsideFinalListIndicesAndAcceptsBothEdges() {
        val original=project();val size=original.objects.size
        for(index in listOf(-1,size)) rejected(original,ProjectCommand.Reorder("b",index),CommandError.INVALID_REORDER_INDEX)
        assertEquals(listOf("b","a","text"),accepted(original,ProjectCommand.Reorder("b",0)).project.objects.map {it.id})
        assertEquals(listOf("a","text","b"),accepted(original,ProjectCommand.Reorder("b",size-1)).project.objects.map {it.id})
        assertEquals(original.reorder("b",size-1),accepted(original,ProjectCommand.Reorder("b",size-1)).project)
        val one=Project(objects=listOf(stitch("a")))
        assertFalse(accepted(one,ProjectCommand.Reorder("a",0)).changed)
        rejected(one,ProjectCommand.Reorder("a",1),CommandError.INVALID_REORDER_INDEX)
    }
    @Test fun visibilityAndHoopChangesWrapValuesWithoutBoundsClamping() {
        val original=project()
        assertEquals(original.update("a") {it.withVisible(false)},accepted(original,ProjectCommand.SetVisible("a",false)).project)
        val hoop=HoopSpec("custom",321,499)
        val result=accepted(original,ProjectCommand.SetHoop(hoop))
        assertEquals(original.copy(hoop=hoop),result.project);assertTrue(result.changed);assertTrue(result.changedIds.isEmpty())
        val outside=Transform(x=10000.0,y=-10000.0)
        assertEquals(outside,accepted(original,ProjectCommand.SetTransforms("a",outside)).project.find("a")!!.transform)
    }
    @Test fun recolorPreservesStitchGeometryAndMigrationFlagsAndTextData() {
        val original=project();val source=original.find("a") as StitchObject
        val result=accepted(original,ProjectCommand.Recolor("a",0,blue))
        val changed=result.project.find("a") as StitchObject
        assertSame(source.source.stitches,changed.source.stitches)
        assertEquals(source.source.metadata,changed.source.metadata);assertEquals(source.source.warnings,changed.source.warnings)
        assertEquals(blue,changed.source.threads.single());assertNull(changed.sourceBytes);assertTrue(changed.sourceThreadsEdited)
        assertContentEquals(byteArrayOf(1,2,3),source.sourceBytes);assertFalse(source.sourceThreadsEdited)
        assertEquals(source.sourceFileName,changed.sourceFileName)
        val text=original.find("text") as TextObject
        assertEquals(text.copy(thread=blue),accepted(original,ProjectCommand.Recolor("text",0,blue)).project.find("text"))
    }
    @Test fun recolorRejectsInvalidIndicesForStitchesAndTextAndEmptyPatterns() {
        val original=project()
        for(id in listOf("a","text")) for(index in listOf(-1,1,Int.MAX_VALUE))
            rejected(original,ProjectCommand.Recolor(id,index,blue),CommandError.INVALID_THREAD_INDEX)
        val empty=Project(objects=listOf(StitchObject("empty","empty",EmbPattern(emptyList(),emptyList()))))
        rejected(empty,ProjectCommand.Recolor("empty",0,blue),CommandError.INVALID_THREAD_INDEX)
    }
    @Test fun fallbackThreadSlotsUseExistingColorBlockSemantics() {
        val pattern=EmbPattern(listOf(Stitch(10,0,Command.STITCH),Stitch(10,0,Command.COLOR_CHANGE),
            Stitch(20,0,Command.STITCH)),emptyList())
        val original=Project(objects=listOf(StitchObject("a","a",pattern)))
        val changed=accepted(original,ProjectCommand.Recolor("a",1,blue)).project.find("a") as StitchObject
        assertEquals(listOf(pattern.threadAt(0),blue),changed.source.threads)
        rejected(original,ProjectCommand.Recolor("a",2,blue),CommandError.INVALID_THREAD_INDEX)
    }
    @Test fun sameValueCommandsReturnOriginalReferenceAndNoChanges() {
        val original=project();val text=original.find("text") as TextObject
        val commands=listOf(ProjectCommand.SetTransforms("a",Transform()),ProjectCommand.SetVisible("a",true),
            ProjectCommand.Reorder("a",0),ProjectCommand.Recolor("a",0,red),ProjectCommand.Recolor("text",0,text.thread),
            ProjectCommand.SetHoop(original.hoop),ProjectCommand.Remove(emptySet()),ProjectCommand.SetTransforms(emptyMap()),
            ProjectCommand.Batch(emptyList()))
        for(command in commands) {
            val result=accepted(original,command)
            assertSame(original,result.project);assertFalse(result.changed);assertTrue(result.changedIds.isEmpty())
        }
    }
    @Test fun batchFailureRollsBackEveryIntermediateChangeAndReportsNestedPath() {
        val original=project();val bytes=(original.find("a") as StitchObject).sourceBytes!!.copyOf()
        val command=ProjectCommand.Batch(listOf(ProjectCommand.Duplicate("a","copy"),
            ProjectCommand.Batch(listOf(ProjectCommand.Recolor("a",0,blue),ProjectCommand.Remove(setOf("missing")))),
            ProjectCommand.Remove(setOf("b"))))
        val result=rejected(original,command,CommandError.MISSING_OBJECT,listOf(1,1))
        assertEquals(setOf("missing"),result.objectIds)
        assertNull(result.project.find("copy"));assertNotNull(result.project.find("b"))
        assertContentEquals(bytes,(original.find("a") as StitchObject).sourceBytes)
        assertEquals(red,(original.find("a") as StitchObject).source.threads.single())
    }
    @Test fun batchCanReferToPriorAdditionsAndDuplicateCollisionsAreAtomic() {
        val original=project()
        val command=ProjectCommand.Batch(listOf(ProjectCommand.Add(stitch("new")),
            ProjectCommand.SetTransforms("new",Transform(x=10.0)),ProjectCommand.SetVisible("new",false)))
        val result=accepted(original,command)
        assertEquals(10.0,result.project.find("new")!!.transform.x);assertFalse(result.project.find("new")!!.visible)
        assertEquals(setOf("new"),result.changedIds)
        rejected(original,ProjectCommand.Batch(listOf(ProjectCommand.Add(stitch("new")),ProjectCommand.Duplicate("a","new"))),CommandError.DUPLICATE_ID,listOf(1))
    }
    @Test fun aBatchThatReturnsToOriginalValuesIsANoopAndPreservesRedo() {
        val original=project()
        val command=ProjectCommand.Batch(listOf(ProjectCommand.Add(stitch("temp")),ProjectCommand.Remove(setOf("temp")),
            ProjectCommand.SetVisible("a",false),ProjectCommand.SetVisible("a",true)))
        val result=accepted(original,command)
        assertSame(original,result.project);assertFalse(result.changed)
        val history=History(original)
        history.commit(original.rotateBy("a",90.0));history.undo()
        history.commit(result.project)
        assertTrue(history.canRedo);assertEquals(0,history.undoCount)
    }
    @Test fun callerCanCommitSuccessfulBatchAsOneUndoStep() {
        val original=project();val history=History(original)
        val result=accepted(original,ProjectCommand.Batch(listOf(ProjectCommand.Duplicate("a","copy"),ProjectCommand.Recolor("copy",0,blue))))
        assertEquals(0,history.undoCount) // Reducer does not own or mutate history.
        history.commit(result.project);assertEquals(1,history.undoCount)
        assertSame(original,history.undo());assertSame(result.project,history.redo())
    }
    @Test fun reductionDoesNotResolveOrGenerateTextFonts() {
        val text=TextObject("unresolved",text="Abc",fontKind=FontKind.INKSTITCH,fontId="not-installed")
        val original=Project(objects=listOf(text))
        val result=accepted(original,ProjectCommand.Batch(listOf(ProjectCommand.SetVisible(text.id,false),
            ProjectCommand.SetTransforms(text.id,Transform(x=10.0)),ProjectCommand.Recolor(text.id,0,blue))))
        val changed=result.project.find(text.id) as TextObject
        assertEquals(text.fontId,changed.fontId);assertEquals(blue,changed.thread)
        assertEquals(10.0,changed.transform.x)
    }
}
