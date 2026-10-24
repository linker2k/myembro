// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.*
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

/** Execute with original withLettering/inspector expressions before replacing their call sites. */
class EditorTextCommandParityTest {
    private fun text(id: String="text") = TextObject(id=id,text="A",transform=Transform(40.0,-20.0,35.0,-1.2,0.8),visible=false)
    private fun initial() = Project(HoopSpec.H130x180,listOf(
        StitchObject("first","first",EmbPattern(emptyList(),emptyList())),text(),TextObject(id="last",text="B")))
    // Frozen EditorActions.withLettering from ead480c; independent after the wrapper migrates.
    private fun Project.legacyLettering(incoming: TextObject, editingId: String?): Project {
        if (editingId == null) {
            val id = incoming.id.takeIf { find(it) == null } ?: java.util.UUID.randomUUID().toString()
            return add(incoming.copy(id = id))
        }
        val original = find(editingId) as? TextObject ?: return this
        return replace(incoming.copy(id = original.id, transform = original.transform, visible = original.visible))
    }
    private fun applied(p: Project,c: ProjectCommand) = assertIs<CommandResult.Applied>(apply(p,c)).project
    @Test fun `compatibility helper matches frozen implementation for additions replacements and stale edits`() {
        val p=initial();val incoming=text("new").copy(text="Changed",visible=true)
        for (id in listOf(null,"text","missing","first"))
            assertEquals(p.legacyLettering(incoming,id),p.withLettering(incoming,id))
    }
    @Test fun `new prepared text appends exactly as old lettering path`() {
        val p=initial();val incoming=text("new").copy(visible=true)
        assertEquals(p.legacyLettering(incoming,null),applied(p,p.letteringCommand(incoming,null)))
    }
    @Test fun `colliding new id keeps old object and uses host allocated id`() {
        val p=initial();val incoming=text().copy(text="New")
        val old=p.legacyLettering(incoming,null);val allocated=old.objects.last().id
        val next=applied(p,p.letteringCommand(incoming,null) { allocated })
        assertEquals(old,next);assertSame(p.find("text"),next.find("text"))
    }
    @Test fun `replacement content and font preserve id pose visibility and sewing order`() {
        val p=initial();val incoming=text("incoming").copy(text="Different",fontId="offline-font",heightMm=22.0,
            transform=Transform(),visible=true,thread=EmbThread(0xff654321.toInt()))
        assertEquals(p.legacyLettering(incoming,"text"),applied(p,p.letteringCommand(incoming,"text")))
    }
    @Test fun `missing or nontext target rejects without resurrecting matching old no-op state`() {
        val p=initial()
        for(id in listOf("missing","first")) {
            val old=p.legacyLettering(text(),id)
            val result=assertIs<CommandResult.Rejected>(apply(p,p.letteringCommand(text(),id)))
            assertSame(p,old);assertSame(old,result.project)
        }
    }
    @Test fun `inspector style weight and transform match original prepared replacement`() {
        val p=initial();val original=p.find("text") as TextObject
        val transform=Transform(-25.0,16.0,80.0,-0.8,1.4)
        for(style in listOf(StitchStyle.RUNNING,StitchStyle.BEAN)) for(weight in ThreadWeight.entries) {
            val prepared=original.withThreadWeight(weight).copy(transform=transform,stitchStyle=style).also {it.localPattern()}
            assertEquals(p.replace(prepared),applied(p,textPropertiesCommand(original.id,prepared)))
        }
    }
    @Test fun `unchanged inspector transform matches existing fast path`() {
        val p=initial();val original=p.find("text")!!
        val old=p.replace(original.withTransform(original.transform))
        val result=assertIs<CommandResult.Applied>(apply(p,ProjectCommand.SetTransforms(original.id,original.transform)))
        assertEquals(old,result.project);assertFalse(result.changed)
    }
    @Test fun `engine preparation to add is headless and one undo unit`() {
        val p=initial()
        val prepared=prepareText(TextPreparationRequest("Ab",HersheyFont.DEFAULT_ID,10.0,1.0,
            LetteringAlignment.CENTER,0.0,EmbThread(0xff000000.toInt())),ResolvedTextFont.Hershey(HersheyFont.DEFAULT),
            TextPreparationPolicy("prepared")).textObject
        val old=p.legacyLettering(prepared,null);val session=EditorSession(p)
        session.commit(p.letteringCommand(prepared,null))
        assertEquals(old,session.project);assertEquals(old.flatten().stitches,session.project.flatten().stitches)
        assertEquals(1,session.history.undoCount);session.undo();assertSame(p,session.project)
    }
}
