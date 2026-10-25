// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.EditSession
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class MoreToolsTest {
    private fun args(s:String)=Json.parseToJsonElement(s).jsonObject
    private inner class Fixture {
        val folder=Files.createTempDirectory("more-tools-")
        val base=AgentScenario.builtin("myembro_logo.pes","logo","H100")
        val session=EditSession(base)
        val tools=ToolDispatcher(documentStore=DirectoryDocumentStore(folder)).bind(session)
        fun saveProject(overwrite:Boolean=false)=args("""{"projectRevision":"${session.revision}","destinationId":"output","name":"design","overwrite":$overwrite}""")
        fun prepare()=tools.dispatch("prepare_pes",args("""{"projectRevision":"${session.revision}","machineId":"se700","frameId":"H100","pesVersion":"V6"}"""))
        fun savePes(id:String,overwrite:Boolean=false)=args("""{"artifactId":"$id","destinationId":"output","name":"design","overwrite":$overwrite}""")
    }
    @Test fun newProjectConflictNoConfirmationThenTokenOverwriteAndReplayRejected() {
        val f=Fixture();val first=f.tools.dispatch("save_project",f.saveProject())
        assertTrue(first.ok,first.output.toString());val file=f.folder.resolve("design.myembro");val original=Files.readAllBytes(file)
        f.session.commitSnapshot(f.base.rotateBy("logo",10.0),0)
        val conflict=f.tools.dispatch("save_project",f.saveProject())
        assertEquals("CONFIRMATION_REQUIRED",conflict.code);assertContentEquals(original,Files.readAllBytes(file))
        val noToken=f.tools.dispatch("save_project",f.saveProject(true),confirmed=true)
        assertEquals("CONFIRMATION_REQUIRED",noToken.code);assertContentEquals(original,Files.readAllBytes(file))
        val token=conflict.output.getValue("confirmToken").jsonPrimitive.content
        val saved=f.tools.dispatch("save_project",conflict.output.getValue("retryArguments"),confirmToken=token)
        assertTrue(saved.ok,saved.output.toString());assertNotEquals(original.toList(),Files.readAllBytes(file).toList())
        assertEquals(10.0,ProjectJson.decode(Files.readString(file)).find("logo")!!.transform.rotationDeg)
        assertEquals("INVALID_CONFIRMATION",f.tools.dispatch("save_project",f.saveProject(true),confirmToken=token).code)
        println("PROJECT_SAVE PASS: new; conflict unchanged; confirmed replacement; replay rejected")
    }
    @Test fun pesOnlyCheckedArtifactsCanSaveAndConflictsDoNotModifyBytes() {
        val f=Fixture();val prepared=f.prepare();assertTrue(prepared.ok,prepared.output.toString())
        val id=prepared.output.getValue("artifactId").jsonPrimitive.content
        assertTrue(f.tools.dispatch("save_pes",f.savePes(id)).ok)
        val file=f.folder.resolve("design.pes");val original=Files.readAllBytes(file)
        f.session.commitSnapshot(f.base.rotateBy("logo",90.0),0)
        val next=f.prepare();assertTrue(next.ok,next.output.toString());val nextId=next.output.getValue("artifactId").jsonPrimitive.content
        val conflict=f.tools.dispatch("save_pes",f.savePes(nextId));assertEquals("CONFIRMATION_REQUIRED",conflict.code)
        assertContentEquals(original,Files.readAllBytes(file))
        val saved=f.tools.dispatch("save_pes",conflict.output.getValue("retryArguments"),confirmToken=conflict.output.getValue("confirmToken").jsonPrimitive.content)
        assertTrue(saved.ok,saved.output.toString());assertContentEquals(next.pesBytes,Files.readAllBytes(file))
        val good=Files.readAllBytes(file)
        f.session.commitSnapshot(f.base.copy(objects=f.base.objects.map { it.withTransform(Transform(x=1000.0)) }),1)
        assertEquals("SEWOUT_BLOCKED",f.prepare().code)
        assertEquals("INVALID_ARTIFACT",f.tools.dispatch("save_pes",f.savePes("fabricated",true)).code)
        assertEquals("STALE_ARTIFACT",f.tools.dispatch("save_pes",f.savePes(nextId,true)).code)
        assertContentEquals(good,Files.readAllBytes(file))
        println("PES_SAVE PASS: PASS-only artifact; conflict unchanged; confirmed replacement; BLOCK never writes")
    }
    @Test fun tokenRejectsChangedTargetPayloadAndRevisionAndSavesCannotRunInTurn() {
        val f=Fixture();assertTrue(f.tools.dispatch("save_project",f.saveProject()).ok)
        val a=f.saveProject(true);val token=f.tools.dispatch("save_project",a).output.getValue("confirmToken").jsonPrimitive.content
        val target=f.folder.resolve("design.myembro");Files.writeString(target,"external change")
        assertEquals("INVALID_CONFIRMATION",f.tools.dispatch("save_project",a,confirmToken=token).code)
        assertEquals("external change",Files.readString(target))
        val token2=f.tools.dispatch("save_project",a).output.getValue("confirmToken").jsonPrimitive.content
        f.session.commitSnapshot(f.base.rotateBy("logo",5.0),0)
        assertEquals("INVALID_CONFIRMATION",f.tools.dispatch("save_project",f.saveProject(true),confirmToken=token2).code)
        f.tools.dispatch("begin_edit_turn",args("""{"transactionId":"t","expectedRevision":"1"}"""))
        assertEquals("TURN_IN_PROGRESS",f.tools.dispatch("save_project",f.saveProject()).code)
        assertEquals("external change",Files.readString(target))
    }
    @Test fun fontQueryHasNamesKindsCoveragePolicyRangesAndSettingsAreInjected() {
        val tools=ToolDispatcher(settingsResolver=SettingsResolver { AgentSettings("se700","My machine","H130x180",HoopSpec.H130x180) }).bind(EditSession())
        val fonts=tools.dispatch("list_fonts",args("""{"text":"A","filters":[],"installedOnly":true}"""))
        assertTrue(fonts.ok,fonts.output.toString());val rows=fonts.output.getValue("fonts").jsonArray.map { it.jsonObject }
        assertTrue(rows.size>=2)
        for(row in rows) { assertTrue(row.getValue("displayName").jsonPrimitive.content.isNotBlank());assertTrue(row.getValue("kind").jsonPrimitive.content.isNotBlank());assertTrue(row.getValue("coverage").jsonObject.getValue("ranges").jsonArray.isNotEmpty());assertNotNull(row["heightRanges"]) }
        val hershey=rows.single { it.string("fontId")=="hershey-futural" };assertEquals("HERSHEY",hershey.string("kind"))
        val ink=rows.single { it.string("fontId")=="inkstitch-medium-font" }
        assertEquals(11.5,ink.getValue("heightRanges").jsonArray.single().jsonObject.getValue("minMm").jsonPrimitive.double)
        val settings=tools.dispatch("get_settings",args("{}"));assertTrue(settings.ok)
        assertEquals("se700",settings.output.string("machineId"));assertEquals(130.0,settings.output.getValue("defaultFrame").jsonObject.getValue("widthMm").jsonPrimitive.double)
        assertEquals("INVALID_ARGUMENTS",tools.dispatch("get_settings",args("""{"unknown":true}""")).code)
        println("QUERIES PASS: font identity/name/kind/coverage/height policy; injected machine and hoop")
    }
    @Test fun scriptArtifactsRetainGeneratedOriginAndDryRunCannotWrite() {
        val folder=Files.createTempDirectory("script-save-");var recorded=0
        val store=object:DocumentStore by DirectoryDocumentStore(folder) {
            override fun recordGeneratedPes(bytes:ByteArray) { recorded++ }
        }
        val session=EditSession();val controller=ToolDispatcher(documentStore=store).bind(session)
        val export=args("""{"machineId":"se700","frameId":"H100","pesVersion":"V6"}""")
        val call=ToolTurnController.Call("add_text",args("""{"transactionId":"t","objectId":"text","text":"A","fontId":"hershey-futural","fontRevision":"bundled-hershey-v1:glyph-extractor-v3","heightMm":15,"threadColor":"#000000","singleLineBold":false}"""))
        val result=controller.executeScript("t",0,listOf(call),export)
        assertTrue(result.ok,result.output.toString());assertTrue(result.generatedPes)
        val id=result.output.getValue("artifactId").jsonPrimitive.content
        assertTrue(controller.dispatch("save_pes",args("""{"artifactId":"$id","destinationId":"output","name":"script","overwrite":false}""")).ok)
        assertEquals(1,recorded)
        val save=ToolTurnController.Call("save_project",args("""{"projectRevision":"1","destinationId":"output","name":"dry-run","overwrite":false}"""))
        val before=session.snapshot()
        assertFalse(controller.executeScript("dry",1,listOf(save),export,dryRun=true).ok)
        assertEquals(before,session.snapshot());assertFalse(Files.exists(folder.resolve("dry-run.myembro")))
    }
    @Test fun directoryAdapterRejectsTraversalSymlinkAndCreateRace() {
        val dir=Files.createTempDirectory("store-safety-");val store=DirectoryDocumentStore(dir)
        assertFailsWith<IllegalArgumentException> { store.write("output","../escape",DocumentKind.PES,byteArrayOf(1),null) }
        store.write("output","test",DocumentKind.PES,byteArrayOf(1),null)
        assertFailsWith<DocumentConflict> { store.write("output","test",DocumentKind.PES,byteArrayOf(2),null) }
        assertContentEquals(byteArrayOf(1),Files.readAllBytes(dir.resolve("test.pes")))
        Files.createSymbolicLink(dir.resolve("link.pes"),dir.resolve("test.pes"))
        assertFailsWith<IllegalArgumentException> { store.inspect("output","link",DocumentKind.PES) }
    }
}
