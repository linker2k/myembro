// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

/** Old ViewModel expressions versus commands, run before replacing the ViewModel call sites. */
class EditorCommandParityTest {
    private val thread = EmbThread(0xff123456.toInt(), "Original")
    private val replacement = EmbThread(0xff654321.toInt(), "Replacement")
    private fun initial() = Project(objects = listOf(
        StitchObject("a", "sample", EmbPattern(listOf(Stitch(-20,-10,Command.STITCH),
            Stitch(30,20,Command.STITCH)), listOf(thread)), transform=Transform(x=-70.0),
            sourceBytes=byteArrayOf(1,2,3)),
        TextObject(id="t", text="A", thread=thread, transform=Transform(x=80.0)),
        TextObject(id="hidden", text="B", visible=false)))
    private val ids = linkedSetOf("a", "t")
    private fun parity(p: Project, old: Project, command: ProjectCommand) {
        val result = assertIs<CommandResult.Applied>(apply(p, command))
        // EmbPattern uses reference equality: compare all persisted project fields as well.
        assertEquals(ProjectJson.encode(old), ProjectJson.encode(result.project))
        if (command !is ProjectCommand.Recolor && command !is ProjectCommand.Batch)
            assertEquals(old, result.project)
    }
    private fun poses(old: (Project) -> Project) {
        val p=initial(); val expected=old(p)
        parity(p,expected,p.poseCommand(expected))
        assertEquals(expected,assertIs<CommandResult.Applied>(apply(p,p.poseCommand(expected))).project)
    }
    @Test fun `move gesture matches existing group transform`() = poses { it.transformGroup(ids,25.0,-13.0,1.0,0.0) }
    @Test fun `scale gesture matches existing group transform`() = poses { it.transformGroup(ids,0.0,0.0,1.3,0.0) }
    @Test fun `rotation gesture matches existing group transform`() = poses { it.transformGroup(ids,0.0,0.0,1.0,37.0) }
    @Test fun `inspector transform preserves negative scale`() = poses { p -> p.replace(p.find("t")!!.withTransform(Transform(12.0,23.0,18.0,-2.0,0.7))) }
    @Test fun `mirror matches existing operation`() = poses { p -> ids.fold(p) { next,id -> next.mirrorX(id) } }
    @Test fun `rotate ninety matches existing operation`() = poses { p -> ids.fold(p) { next,id -> next.rotateBy(id,90.0) } }
    @Test fun `alignment matches existing operation`() = poses { it.align(ids,Alignment.LEFT) }
    @Test fun `distribution matches existing operation`() = poses { it.distribute(it.objects.mapTo(linkedSetOf()) { obj -> obj.id },Axis.HORIZONTAL) }
    @Test fun `single center matches existing operation`() = poses { it.alignCenter("a") }
    @Test fun `group center matches existing operation`() = poses { p ->
        val b=p.selectionBounds(ids)!!
        p.transformGroup(ids,-(b.minX.toDouble()+b.maxX)/2,-(b.minY.toDouble()+b.maxY)/2,1.0,0.0)
    }
    @Test fun `delete matches existing filtering`() {
        val p=initial(); parity(p,p.copy(objects=p.objects.filterNot { it.id in ids }),ProjectCommand.Remove(ids))
    }
    @Test fun `duplicate preserves ids offset and insertion order`() {
        val p=initial(); val copies=linkedMapOf("a" to "a-copy","t" to "t-copy")
        val old=copies.entries.fold(p) { next,(id,newId) -> next.duplicate(id,newId).update(newId) {
            it.withTransform(it.transform.copy(x=it.transform.x+50,y=it.transform.y+50)) } }
        parity(p,old,p.duplicateCommand(copies))
        assertEquals(old,assertIs<CommandResult.Applied>(apply(p,p.duplicateCommand(copies))).project)
    }
    @Test fun `reorder preserves clamping including stale targets`() {
        val p=initial()
        for (id in listOf("a","t","missing")) for (index in listOf(-1,0,2,3))
            parity(p,p.reorder(id,index),p.reorderCommand(id,index))
    }
    @Test fun `gesture reordering and transforms preserve final order`() = poses { it.reorder("a",2).reorder("t",2).rotateBy("t",40.0) }
    @Test fun `visibility matches existing update`() {
        val p=initial()
        for (obj in p.objects) parity(p,p.update(obj.id) { it.withVisible(!it.visible) },ProjectCommand.SetVisible(obj.id,!obj.visible))
    }
    @Test fun `colors preserve all persisted fields including missing indices`() {
        val p=initial()
        for (id in listOf("a","t")) for(index in listOf(-1,0,1)) for (color in listOf(thread,replacement))
            parity(p,p.replace(p.find(id)!!.recolored(index,color)),p.recolorCommand(id,index,color))
    }
    @Test fun `hoop matches existing copy`() {
        val p=initial(); val hoop=p.hoop.copy(width=1200,height=800)
        parity(p,p.copy(hoop=hoop),ProjectCommand.SetHoop(hoop))
    }
    @Test fun `unchanged poses do not change project`() {
        val p=initial();val result=assertIs<CommandResult.Applied>(apply(p,p.poseCommand(p.copy())))
        assertFalse(result.changed);assertSame(p,result.project)
    }
}
