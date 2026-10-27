// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.edit.commands.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.sewout.*
import java.io.File
import kotlin.test.*

class ShapeObjectOverridesTest {
    private fun shape()=ShapeObject.fromSvg("heart","heart",File("src/test/resources/emoji/fluent/red_heart_flat.svg").readText(),30.0)
    private fun project()=Project(objects=listOf(shape()))
    private fun command(p: Project,change: BlockStitchPatch,ids: Set<String>?=null)=
        ProjectCommand.SetBlockStitch("heart",ids ?: setOf((p.objects.single() as ShapeObject).regions.first().id),change)

    @Test fun heartOverrideSewoutReasonTableAndJsonStitchParity() {
        val p=project()
        val cases=linkedMapOf(
            "meander" to BlockStitchPatch(type=BlockStitch.Type.FILL,fill_method="meander_fill"),
            "contour" to BlockStitchPatch(type=BlockStitch.Type.FILL,fill_method="contour_fill"),
            "circular" to BlockStitchPatch(type=BlockStitch.Type.FILL,fill_method="circular_fill"),
            "angle135" to BlockStitchPatch(type=BlockStitch.Type.FILL,angle=135.0))
        var accepted=0
        for((label,patch) in cases) {
            when(val result=apply(p,command(p,patch))) {
                is CommandResult.Rejected -> {
                    assertEquals(CommandError.SEWOUT_BLOCKED,result.code,"$label ${result.message}")
                    assertSame(p,result.project);assertTrue(result.message.isNotBlank())
                    println("BLOCK_TABLE $label | BLOCK | ${result.message.replace('\n',' ')}")
                    val restored=ProjectJson.decode(ProjectJson.encode(result.project))
                    assertEquals(p.flatten().stitches,restored.flatten().stitches)
                }
                is CommandResult.Applied -> {
                    accepted++
                    val changed=result.project
                    val obj=assertIs<ShapeObject>(changed.objects.single());assertTrue(obj.overrides.isNotEmpty())
                    val bytes=PesWriter.write(changed.flatten())
                    assertTrue(PecViewportCheck.check(bytes,changed.hoop).valid)
                    val report=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
                    assertEquals(SewoutVerdict.PASS,report.verdict)
                    val restored=ProjectJson.decode(ProjectJson.encode(changed))
                    assertEquals(changed,restored)
                    assertEquals(obj.localPattern().stitches,restored.objects.single().localPattern().stitches)
                    assertContentEquals(bytes,PesWriter.write(restored.flatten()))
                    println("BLOCK_TABLE $label | PASS | stitches=${obj.localPattern().stitchCount}; JSON/PES identical")
                }
            }
        }
        assertTrue(accepted>0,"No functioning override backend")
    }
    @Test fun invalidUpstreamRangesAndUnknownRegionsRejectAtomically() {
        val p=project()
        for(patch in listOf(BlockStitchPatch(angle=Double.NaN),BlockStitchPatch(automatic_angle=true,angle=Double.POSITIVE_INFINITY),BlockStitchPatch(row_spacing_mm=0.099),
            BlockStitchPatch(zigzag_spacing_mm=0.0),BlockStitchPatch(meander_scale_percent=listOf(0.99)),
            BlockStitchPatch(meander_pattern="missing"),BlockStitchPatch(fill_method="missing"))) {
            val result=assertIs<CommandResult.Rejected>(apply(p,command(p,patch)))
            assertEquals(CommandError.INVALID_BLOCK_SETTINGS,result.code);assertSame(p,result.project)
        }
        val missing=assertIs<CommandResult.Rejected>(apply(p,command(p,BlockStitchPatch(angle=135.0),setOf("missing"))))
        assertSame(p,missing.project)
        ShapeBlockRenderer.validate(BlockStitch(fill=io.github.linker2k.myembro.engine.inkstitch.elements.FillParameters(row_spacing_mm=0.1),
            meander_scale_percent=listOf(1.0),satin=io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters(
                zigzag_spacing_mm=0.01/io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM)))
        for(angle in listOf(-720.0,135.0,720.0)) ShapeBlockRenderer.validate(BlockStitch(fill=io.github.linker2k.myembro.engine.inkstitch.elements.FillParameters(angle=angle),automatic_angle=false))
    }
    @Test fun oneRegionListCommandIsOneUndoAndAutomaticAngleRestoresAppDefault() {
        val p=project();val session=EditSession(p)
        val c=command(p,BlockStitchPatch(row_spacing_mm=0.4), (p.objects.single() as ShapeObject).regions.map { it.id }.toSet())
        assertIs<TurnResult.InProgress>(session.beginTurn("blocks",0,listOf(c)))
        assertIs<TurnResult.InProgress>(session.applyNext("blocks"))
        assertIs<TurnResult.Committed>(session.commitTurn("blocks",0))
        assertEquals(1,session.snapshot().undoCount)
        session.undo(session.revision);assertEquals(p,session.project)
        val manual=BlockStitchPatch(angle=135.0).applyTo(BlockStitch())
        assertFalse(manual.automatic_angle)
        val auto=BlockStitchPatch(automatic_angle=true).applyTo(manual)
        assertTrue(auto.automatic_angle);assertEquals(0.0,auto.fill.angle)
        val noOp=assertIs<CommandResult.Applied>(apply(p,command(p,BlockStitchPatch())))
        assertFalse(noOp.changed);assertSame(p,noOp.project)
    }
    @Test fun batchFailureDoesNotPublishFirstBlockChangeAndUndoRedoKeepOverrides() {
        val p=project()
        val valid=command(p,BlockStitchPatch(angle=135.0))
        val invalid=command(p,BlockStitchPatch(row_spacing_mm=0.01))
        val result=assertIs<CommandResult.Rejected>(apply(p,ProjectCommand.Batch(listOf(valid,invalid))))
        assertSame(p,result.project);assertEquals(listOf(1),result.path)
        val session=EditSession(p)
        assertIs<TurnResult.Committed>(session.executeTurn("angle",0,listOf(valid)))
        val changed=session.project
        val reset=assertIs<CommandResult.Applied>(apply(changed,command(changed,BlockStitchPatch(automatic_angle=true))))
        assertEquals(p,reset.project)
        assertEquals(p.flatten().stitches,reset.project.flatten().stitches)
        session.undo(session.revision);assertEquals(p,session.project)
        session.redo(session.revision);assertEquals(changed,session.project)
    }

    @Test fun methodAndSatinDensityChangesUseDifferentBackendsAndRoundTrip() {
        val s=ShapeObject.fromSvg("heart","strip","<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 4 25\"><path fill=\"#F8312F\" d=\"M0 0H4V25H0Z\"/></svg>",4.0)
        val p=Project(objects=listOf(s))
        val patterns=mutableListOf<List<io.github.linker2k.myembro.engine.model.Stitch>>()
        for(type in BlockStitch.Type.entries) {
            val patch=BlockStitchPatch(type=type,zigzag_spacing_mm=if(type==BlockStitch.Type.SATIN) 0.6 else null)
            val result=assertIs<CommandResult.Applied>(apply(p,command(p,patch)))
            val restored=ProjectJson.decode(ProjectJson.encode(result.project))
            assertEquals(result.project.flatten().stitches,restored.flatten().stitches)
            patterns+=restored.flatten().stitches
        }
        assertEquals(BlockStitch.Type.entries.size,patterns.distinct().size)
    }

}
