// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.edit.commands.*
import io.github.linker2k.myembro.engine.agent.*
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

class BlockGenerationErrorsTest {
    private fun heart()=ShapeObject.fromSvg("shape","heart",File("src/test/resources/emoji/fluent/red_heart_flat.svg").readText(),30.0)
    private fun face()=ShapeObject.fromSvg("shape","face",File("src/test/resources/emoji/fluent/grinning_face_flat.svg").readText(),30.0)
    private fun command(patch: BlockStitchPatch, region: String="region-0")=ProjectCommand.SetBlockStitch("shape",setOf(region),patch)
    private fun assertGeneration(p: Project,c: ProjectCommand,kind: BlockGenerationKind) {
        val r=assertIs<CommandResult.Rejected>(apply(p,c))
        assertSame(p,r.project);assertEquals(CommandError.GENERATION_FAILED,r.code);assertEquals(kind,r.kind)
        val s=EditSession(p);val before=s.snapshot()
        val turn=assertIs<TurnResult.Rejected>(s.executeTurn("failed",0,listOf(c)))
        assertEquals(kind,turn.failure?.kind);assertEquals(before,s.snapshot())
        println("BLOCK_ERROR ${kind.name} code=${r.code} history=unchanged")
    }
    @Test fun unsupportedBackendAndMeanderGraphAreTypedGenerationFailures() {
        val p=Project(objects=listOf(heart()))
        assertGeneration(p,command(BlockStitchPatch(fill_method="guided_fill")),BlockGenerationKind.UNSUPPORTED_FILL_BACKEND)
        assertGeneration(p,command(BlockStitchPatch(fill_method="meander_fill",meander_scale_percent=listOf(100000.0))),BlockGenerationKind.MEANDER_GRAPH)
    }
    @Test fun satinGeometryAndScaledStitchLengthHaveDistinctKinds() {
        val p=Project(objects=listOf(face()))
        assertGeneration(p,command(BlockStitchPatch(type=BlockStitch.Type.SATIN),"region-3"),BlockGenerationKind.SATIN_GEOMETRY_UNSUPPORTED)
        val h=heart().withTransform(Transform(scaleX=3.0,scaleY=3.0))
        assertGeneration(Project(objects=listOf(h)),command(BlockStitchPatch(type=BlockStitch.Type.SATIN)),BlockGenerationKind.SATIN_STITCH_TOO_LONG)
    }
    @Test fun orderGenerationFailureRetainsBackendKindAndBatchPath() {
        // Editable source may contain deferred generation that has not run yet (same as a restored project).
        val h=heart().withOverrides(mapOf("region-0" to BlockStitch(fill=io.github.linker2k.myembro.engine.inkstitch.elements.FillParameters(fill_method="guided_fill"))))
        val p=Project(objects=listOf(h))
        val c=ProjectCommand.SetBlockOrder("shape",setOf("region-0"),BlockOrderMove.LAST)
        assertGeneration(p,c,BlockGenerationKind.UNSUPPORTED_FILL_BACKEND)
        val batch=assertIs<CommandResult.Rejected>(apply(p,ProjectCommand.Batch(listOf(ProjectCommand.SetVisible("shape",true),c))))
        assertEquals(listOf(1),batch.path);assertEquals(BlockGenerationKind.UNSUPPORTED_FILL_BACKEND,batch.kind);assertSame(p,batch.project)
    }
    @Test fun actualSewingBlockersRemainSewoutBlockedWithoutGenerationKind() {
        val p=Project(objects=listOf(heart().withTransform(Transform(x=1000.0))))
        for(c in listOf<ProjectCommand>(command(BlockStitchPatch(angle=135.0)),ProjectCommand.SetBlockOrder("shape",setOf("region-0"),BlockOrderMove.LAST))) {
            val r=assertIs<CommandResult.Rejected>(apply(p,c))
            assertEquals(CommandError.SEWOUT_BLOCKED,r.code,r.message);assertNull(r.kind);assertSame(p,r.project)
            println("BLOCK_ERROR HOOP code=${r.code} kind=null")
        }
    }
    @Test fun dispatcherAndTurnReceiptCarryKindWithoutPublishingOrBytes() {
        val p=Project(objects=listOf(heart()));val kernel=ToolDispatcher()
        val args=Json.parseToJsonElement("""{"transactionId":"t","objectId":"shape","regionIds":["region-0"],"change":{"fill_method":"guided_fill"}}""").jsonObject
        val direct=kernel.dispatch(p,"0","set_block_stitch",args)
        assertEquals("GENERATION_FAILED",direct.code);assertEquals("UNSUPPORTED_FILL_BACKEND",direct.output.getValue("kind").jsonPrimitive.content)
        val s=EditSession(p);val controller=kernel.bind(s)
        val calls=listOf(ToolTurnController.Call("set_block_stitch",args))
        val export=Json.parseToJsonElement("""{"machineId":"se400","frameId":"H100","pesVersion":"V1"}""").jsonObject
        val failed=controller.executeScript("t",0,calls,export)
        val retry=controller.executeScript("t",0,calls,export)
        assertEquals(direct.code,failed.code);assertEquals(direct.output["kind"],failed.output["kind"])
        assertEquals(failed.code,retry.code);assertEquals(failed.output["kind"],retry.output["kind"]);assertNull(failed.pesBytes);assertNull(failed.preparedCommand)
        assertEquals(0,s.snapshot().undoCount);assertSame(p,s.project)
    }
}
