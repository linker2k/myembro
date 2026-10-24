// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit.commands

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class ReplaceTextCommandTest {
    private fun original() = TextObject(id="text",text="A",transform=Transform(30.0,-20.0,45.0,-1.0,2.0),visible=false)
    private fun initial() = Project(objects=listOf(TextObject(id="first",text="B"), original(),
        StitchObject("stitch","imported",EmbPattern(emptyList(),emptyList()))))
    @Test fun `replacement preserves target identity placement visibility and index`() {
        val p=initial();val incoming=TextObject(id="first",text="New",fontId="unresolved-font",heightMm=23.0)
        val result=assertIs<CommandResult.Applied>(apply(p,ProjectCommand.ReplaceText("text",incoming)))
        assertEquals(p.replace(incoming.copy(id="text",transform=original().transform,visible=false)),result.project)
        assertEquals(setOf("text"),result.changedIds);assertTrue(result.changed)
        assertEquals(listOf("first","text","stitch"),result.project.objects.map {it.id})
    }
    @Test fun `missing target rejects and batch reports its failure path without partial mutation`() {
        val p=initial();val command=ProjectCommand.Batch(listOf(ProjectCommand.Remove(setOf("first")),
            ProjectCommand.ReplaceText("missing",original())))
        val result=assertIs<CommandResult.Rejected>(apply(p,command))
        assertSame(p,result.project);assertEquals(CommandError.MISSING_OBJECT,result.code)
        assertEquals(setOf("missing"),result.objectIds);assertEquals(listOf(1),result.path)
    }
    @Test fun `blank target and imported target reject explicitly`() {
        val p=initial()
        for ((id,error) in listOf("" to CommandError.INVALID_ID,"stitch" to CommandError.INVALID_OBJECT_TYPE)) {
            val result=assertIs<CommandResult.Rejected>(apply(p,ProjectCommand.ReplaceText(id,original())))
            assertSame(p,result.project);assertEquals(error,result.code)
        }
    }
    @Test fun `no-op replacement retains original reference revision and redo`() {
        val p=initial();val session=EditSession(p)
        session.executeTurn("change",0,listOf(ProjectCommand.Remove(setOf("first"))))
        session.undo(session.revision);val before=session.snapshot()
        val incoming=original().copy(id="ignored",transform=Transform(),visible=true)
        val result=assertIs<TurnResult.Committed>(session.executeTurn("noop",session.revision,
            listOf(ProjectCommand.ReplaceText("text",incoming))))
        assertFalse(result.changed);assertEquals(before,session.snapshot());assertSame(p,session.project)
    }
    @Test fun `prepared geometry cache survives replacement and transform batch`() {
        val p=initial();val incoming=TextObject(id="prepared",text="Ab")
        val pattern=incoming.localPattern()
        val command=ProjectCommand.Batch(listOf(ProjectCommand.ReplaceText("text",incoming),
            ProjectCommand.SetTransforms("text",Transform(x=90.0))))
        val result=assertIs<CommandResult.Applied>(apply(p,command))
        assertSame(pattern,result.project.find("text")!!.localPattern())
    }
    @Test fun `prepare add and replace commit together and replay is idempotent`() {
        val p=initial();val session=EditSession(p)
        val prepared=prepareText(TextPreparationRequest("Ab",HersheyFont.DEFAULT_ID,10.0,1.0,
            LetteringAlignment.CENTER,0.0,EmbThread(0xff123456.toInt())),
            ResolvedTextFont.Hershey(HersheyFont.DEFAULT),TextPreparationPolicy("new")).textObject
        val commands=listOf(ProjectCommand.Add(prepared),ProjectCommand.ReplaceText("text",prepared))
        val result=assertIs<TurnResult.Committed>(session.executeTurn("turn",0,commands))
        assertEquals(1,session.snapshot().undoCount);assertEquals(4,session.project.objects.size)
        assertSame(result,session.executeTurn("turn",0,commands))
        val committed=session.project;session.undo(session.revision);assertSame(p,session.project)
        session.redo(session.revision);assertSame(committed,session.project)
    }
    @Test fun `replacement payload replay compares decoded text values`() {
        val p=initial();val session=EditSession(p);val incoming=TextObject(id="incoming",text="New")
        val decoded=ProjectJson.decode(ProjectJson.encode(Project(objects=listOf(incoming)))).objects.single() as TextObject
        val result=session.executeTurn("replace",0,listOf(ProjectCommand.ReplaceText("text",incoming)))
        assertSame(result,session.executeTurn("replace",0,listOf(ProjectCommand.ReplaceText("text",decoded))))
    }
}
