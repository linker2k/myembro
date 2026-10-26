// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.edit.commands.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class ShapeObjectOrderTest {
    private fun shape()=ShapeObject.fromSvg("heart","heart",File("src/test/resources/emoji/fluent/red_heart_flat.svg").readText(),30.0)
    @Test fun allMovesPreserveActualFinishedNeedlesAndPassSewout() {
        val sameColor=ShapeObject.fromSvg("heart","same-color", """<svg viewBox="0 0 32 8"><path fill="#ff0000" d="M0 0H8V8H0Z M12 0H20V8H12Z M24 0H32V8H24Z"/></svg>""",30.0)
        for(original in listOf(shape(),sameColor)) {
        val p=Project(objects=listOf(original))
        val originalPes=PesReader.read(PesWriter.write(p.flatten()))
        val originalNeedles=original.localPattern().stitches.filter { it.command==Command.STITCH }
        val byRegion=original.regionOrder.associateWith { original.regionPattern(it).stitches.filter { s -> s.command==Command.STITCH } }
        assertEquals(originalNeedles,original.regionOrder.flatMap { byRegion.getValue(it) })
        for(move in BlockOrderMove.entries) {
            val selected=if(move==BlockOrderMove.BACKWARD || move==BlockOrderMove.LAST) original.regionOrder.take(2).toSet() else original.regionOrder.takeLast(2).toSet()
            val c=ProjectCommand.SetBlockOrder("heart",selected,move)
            val applied=assertIs<CommandResult.Applied>(apply(p,c),move.name)
            assertTrue(applied.changed)
            val next=assertIs<ShapeObject>(applied.project.find("heart"))
            for(id in original.regionOrder) assertEquals(original.regionPattern(id).stitches,next.regionPattern(id).stitches,"$move $id")
            val needles=next.localPattern().stitches.filter { it.command==Command.STITCH }
            assertEquals(next.regionOrder.flatMap { byRegion.getValue(it) },needles,"$move actual native needles changed")
            assertEquals(originalNeedles.size,needles.size)
            val bytes=PesWriter.write(applied.project.flatten())
            assertEquals(originalPes.stitchCount,PesReader.read(bytes).stitchCount,"$move encoded PES needle count changed")
            val check=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
            assertEquals(SewoutVerdict.PASS,check.verdict,"$move ${check.findings}")
            assertTrue(PecViewportCheck.check(bytes,p.hoop).valid)
            val restored=ProjectJson.decode(ProjectJson.encode(applied.project))
            assertEquals(applied.project,restored)
            assertEquals(next.localPattern().stitches,restored.objects.single().localPattern().stitches)
            assertContentEquals(bytes,PesWriter.write(restored.flatten()))
            println("BLOCK_ORDER ${original.name} $move order=${next.regionOrder} needles=${needles.size} unchanged=true sewout=PASS JSON/PES=identical")
        }
        }
    }
    @Test fun stableMultiSelectionAndOneStepBoundarySemantics() {
        val order=listOf("a","b","c","d","e")
        assertEquals(listOf("b","a","d","c","e"),moveRegions(order,setOf("b","d"),BlockOrderMove.FORWARD))
        assertEquals(listOf("a","c","b","e","d"),moveRegions(order,setOf("b","d"),BlockOrderMove.BACKWARD))
        assertEquals(listOf("b","d","a","c","e"),moveRegions(order,setOf("d","b"),BlockOrderMove.FIRST))
        assertEquals(listOf("a","c","e","b","d"),moveRegions(order,setOf("d","b"),BlockOrderMove.LAST))
        assertEquals(order,moveRegions(order,setOf("a"),BlockOrderMove.FORWARD))
        assertEquals(order,moveRegions(order,setOf("e"),BlockOrderMove.BACKWARD))
        for(move in BlockOrderMove.entries) assertEquals(order,moveRegions(order,order.toSet(),move))
    }
    @Test fun oneUndoNoopsFailuresAndLegacyCodec() {
        val p=Project(objects=listOf(shape()));val s=EditSession(p)
        val c=ProjectCommand.SetBlockOrder("heart",setOf("region-0"),BlockOrderMove.LAST)
        assertIs<TurnResult.Committed>(s.executeTurn("move",0,listOf(c)))
        assertEquals(1,s.snapshot().undoCount)
        s.undo(s.revision);assertEquals(p,s.project);assertTrue(s.snapshot().canRedo)
        for(ids in listOf(emptySet(),setOf("missing"))) {
            val rejected=assertIs<CommandResult.Rejected>(apply(p,c.copy(regionIds=ids)))
            assertEquals(CommandError.INVALID_BLOCK_SETTINGS,rejected.code);assertSame(p,rejected.project)
        }
        val noOp=assertIs<CommandResult.Applied>(apply(p,c.copy(move=BlockOrderMove.FIRST)))
        assertFalse(noOp.changed);assertSame(p,noOp.project)
        val missing=assertIs<CommandResult.Rejected>(apply(p,c.copy(id="missing")))
        assertEquals(CommandError.MISSING_OBJECT,missing.code)
        val encoded=ProjectJson.encode(p)
        // Pre-order .myembro objects omit the additive payload field entirely.
        val legacy=encoded.replace(Regex("\\\"regionOrder\\\"\\s*:\\s*\\[[^]]*]\\s*,?"), "")
        val restored=ProjectJson.decode(legacy)
        assertEquals(p,restored)
        assertEquals(p.flatten().stitches,restored.flatten().stitches)
    }
    @Test fun orderChangesRetainOverridesAndLaterSettingsRetainOrder() {
        val p=Project(objects=listOf(shape()))
        val changed=assertIs<CommandResult.Applied>(apply(p,ProjectCommand.SetBlockStitch("heart",setOf("region-0"),BlockStitchPatch(angle=135.0)))).project
        val reordered=assertIs<CommandResult.Applied>(apply(changed,ProjectCommand.SetBlockOrder("heart",setOf("region-0"),BlockOrderMove.LAST))).project
        val obj=assertIs<ShapeObject>(reordered.find("heart"))
        assertEquals(135.0,obj.regions.first().stitch.fill.angle)
        val next=assertIs<CommandResult.Applied>(apply(reordered,ProjectCommand.SetBlockStitch("heart",setOf("region-0"),BlockStitchPatch(angle=45.0)))).project
        assertEquals(obj.regionOrder,assertIs<ShapeObject>(next.find("heart")).regionOrder)
    }
}
