// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DesignToolTest {
    private fun args(s: String)=Json.parseToJsonElement(s).jsonObject
    private fun add(id: String="builtin:myembro-logo",objectId: String="logo") = args("""{"transactionId":"t","assetId":"$id","objectId":"$objectId","name":"Logo"}""")
    private val export=args("""{"projectRevision":"0","machineId":"se700","frameId":"H100","pesVersion":"V6"}""")
    @Test fun `builtin logo add move and prepare PES passes without rewriting original source`() {
        val kernel=ToolDispatcher();val p=Project()
        val added=kernel.dispatch(p,"0","add_design",add());assertTrue(added.ok,added.output.toString())
        val obj=assertIs<StitchObject>(added.project.find("logo"))
        val original=BundledDesignResolver().resolve("builtin:myembro-logo")!!.sourceBytes!!
        assertContentEquals(original,obj.sourceBytes);assertFalse(obj.sourceThreadsEdited)
        val moved=kernel.dispatch(added.project,"0","transform_objects",args("""{"transactionId":"t","objectIds":["logo"],"deltaXMm":1,"deltaYMm":2,"scaleFactor":1,"rotationDeg":0}"""))
        assertTrue(moved.ok,moved.output.toString());assertContentEquals(original,(moved.project.find("logo") as StitchObject).sourceBytes)
        val checked=kernel.dispatch(moved.project,"0","prepare_pes",export)
        assertTrue(checked.ok,checked.output.toString());assertNotNull(checked.pesBytes)
        assertEquals("PASS",checked.output["checks"]!!.jsonObject["report"]!!.jsonObject["verdict"]!!.jsonPrimitive.content)
        println("DESIGN_LOGO add -> move -> prepare_pes PASS")
    }
    @Test fun `unmodified imported PES is returned byte for byte even if another output version is requested`() {
        val kernel=ToolDispatcher();val added=kernel.dispatch(Project(),"0","add_design",add())
        val obj=added.project.find("logo") as StitchObject
        val prepared=kernel.dispatch(added.project,"0","prepare_pes",export)
        assertTrue(prepared.ok,prepared.output.toString());assertContentEquals(obj.sourceBytes,prepared.pesBytes)
    }
    @Test fun `missing and caller path ids reject and duplicate object id does not mutate project`() {
        val kernel=ToolDispatcher();val p=Project()
        for(id in listOf("missing","../../secret.pes","file:///tmp/private.pes")) {
            val result=kernel.dispatch(p,"0","add_design",add(id))
            assertEquals("DESIGN_NOT_FOUND",result.code);assertSame(p,result.project)
        }
        val added=kernel.dispatch(p,"0","add_design",add()).project
        val duplicate=kernel.dispatch(added,"0","add_design",add())
        assertEquals("DUPLICATE_ID",duplicate.code);assertSame(added,duplicate.project)
    }
    @Test fun `builtin and library lists expose ids names millimeters colors and content revision`() {
        val bundled=BundledDesignResolver();val asset=bundled.resolve("builtin:myembro-logo")!!
        val resolver=object: DesignResolver {
            override fun builtins()=bundled.builtins()
            override fun designs()=listOf(DesignAsset("library:logo",asset.name,asset.fileName,asset.pattern,asset.sourceBytes,123,true))
        }
        val kernel=ToolDispatcher(designResolver=resolver,nowMillis={123})
        val result=kernel.dispatch(Project(),"0","list_builtin_designs",args("{}"))
        assertTrue(result.ok);val rows=result.output.getValue("designs").jsonArray
        assertTrue(rows.size>=21)
        val row=rows.first { it.jsonObject.string("id")==asset.id }.jsonObject
        assertEquals(asset.name,row.string("name"));assertEquals(asset.pattern.bounds!!.width/10.0,row.number("widthMm"))
        assertEquals(asset.pattern.bounds!!.height/10.0,row.number("heightMm"));assertEquals(asset.pattern.colorBlocks.size,row.integer("colorCount"))
        assertEquals(asset.sha256,row.string("sha256"))
        val library=kernel.dispatch(Project(),"0","list_designs",args("""{"query":"logo","filter":"FAVORITES","sort":"NAME"}"""))
        assertEquals("library:logo",library.output.getValue("designs").jsonArray.single().jsonObject.string("id"))
        val added=kernel.dispatch(Project(),"0","add_design",add("library:logo"))
        assertTrue(added.ok);assertContentEquals(asset.sourceBytes,(added.project.find("logo") as StitchObject).sourceBytes)
    }
    @Test fun `JVM sample library lists real fixtures and resolves its returned ids`() {
        val resolver=BundledDesignResolver();val kernel=ToolDispatcher(designResolver=resolver)
        val listed=kernel.dispatch(Project(),"0","list_designs",args("""{"query":"","filter":"ALL","sort":"NAME"}"""))
        val rows=listed.output.getValue("designs").jsonArray
        assertTrue(rows.size>=20)
        val id=rows.first().jsonObject.string("id")
        val added=kernel.dispatch(Project(),"0","add_design",add(id))
        assertTrue(added.ok);assertContentEquals(resolver.resolve(id)!!.sourceBytes,(added.project.find("logo") as StitchObject).sourceBytes)
    }
    @Test fun `catalog queries outside a turn preserve history and source arrays are defensive copies`() {
        val session=EditSession();val kernel=ToolDispatcher();val controller=kernel.bind(session)
        val before=session.snapshot()
        assertTrue(controller.dispatch("list_builtin_designs",args("{}")).ok)
        assertTrue(controller.dispatch("list_designs",args("""{"query":"","filter":"ALL","sort":"RECENT"}""")).ok)
        assertEquals(before,session.snapshot())
        val asset=BundledDesignResolver().builtins().first();val bytes=asset.sourceBytes!!;val original=bytes.copyOf()
        bytes.fill(0);assertContentEquals(original,asset.sourceBytes)
    }
    @Test fun `imported density thirty passes blocker threshold and thirty one blocks`() {
        val cell=(0..4).flatMap { x -> (0..4).map { y -> x to y } }.filter { it!=(0 to 0) }
        for(count in listOf(30,31)) {
            val p=EmbPattern((cell+cell).take(count).map { Stitch(it.first,it.second,Command.STITCH) },listOf(EmbThread(0xff000000.toInt())))
            val report=SewoutCheck.check(p,options=SewoutOptions(origin=DesignOrigin.IMPORTED))
            assertEquals(count>30,report.hasBlockingIssues)
        }
    }
    @Test fun `density warnings remain usable for both origins and imported provenance stays imported`() {
        val points=(0..4).flatMap { x -> (0..4).map { y -> x to y } }.filter {it!=(0 to 0)}.take(20)
        val p=EmbPattern(points.map { Stitch(it.first,it.second,Command.STITCH) },listOf(EmbThread(0xff000000.toInt())))
        val header=ByteArray(22).also { "#PES0001".toByteArray().copyInto(it);it[8]=22 }
        val bytes=header+PecWriter.writePayload(p,topLeftViewport=true)
        val asset=DesignAsset.read("library:dense","Dense","dense.pes",bytes)
        val resolver=object: DesignResolver { override fun builtins()=emptyList<DesignAsset>();override fun designs()=listOf(asset) }
        val kernel=ToolDispatcher(designResolver=resolver)
        val added=kernel.dispatch(Project(),"0","add_design",add(asset.id))
        assertTrue(added.ok);assertEquals(DesignOrigin.IMPORTED,SewoutCheck.originOf(added.project.flatten()))
        val imported=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.IMPORTED))
        assertFalse(imported.hasBlockingIssues)
        assertFalse(SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED)).hasBlockingIssues)
        val check=kernel.dispatch(added.project,"0","sewout_check",JsonObject(export-"pesVersion"+("pesAssetId" to JsonPrimitive(""))))
        assertTrue(check.ok,check.output.toString())
        assertEquals("WARNING",check.output["checks"]!!.jsonObject["report"]!!.jsonObject["findingSeverities"]!!.jsonObject["DENSITY_HOTSPOT"]!!.jsonPrimitive.content)
        // User-approved warnings permit saving; the imported origin is retained.
        assertTrue(kernel.dispatch(added.project,"0","prepare_pes",export).ok)
    }
}
