// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ToolDispatcherTest {
    private val dispatcher = ToolDispatcher()
    private fun args(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun objectProject(): Project {
        val p = GeneratedPatternProcessor.finish(EmbPattern(listOf(100 to 0,100 to 100,0 to 100,0 to 0,100 to 0).map { Stitch(it.first,it.second,Command.STITCH) },listOf(PecThreads.forIndex(5))))
        return Project(HoopSpec.H100, listOf("a","b","c").mapIndexed { i,id -> StitchObject(id,id,p,Transform(x=i*150.0)) })
    }
    private val transform = """{"xMm":1,"yMm":2,"rotationDeg":90,"scaleXPercent":100,"scaleYPercent":100}"""
    private fun cases(): Map<String,JsonObject> = linkedMapOf(
        "get_project" to args("{}"),
        "set_object_transforms" to args("""{"transactionId":"t","transforms":[{"objectId":"a",${transform.drop(1)}]}"""),
        "transform_objects" to args("""{"transactionId":"t","objectIds":["a"],"deltaXMm":1,"deltaYMm":2,"scaleFactor":1,"rotationDeg":90}"""),
        "mirror_objects" to args("""{"transactionId":"t","objectIds":["a"]}"""),
        "center_objects" to args("""{"transactionId":"t","objectIds":["a"]}"""),
        "align_objects" to args("""{"transactionId":"t","objectIds":["a","b"],"alignment":"LEFT"}"""),
        "distribute_objects" to args("""{"transactionId":"t","objectIds":["a","b","c"],"axis":"HORIZONTAL"}"""),
        "duplicate_object" to args("""{"transactionId":"t","sourceId":"a","newObjectId":"copy","transform":$transform}"""),
        "reorder_object" to args("""{"transactionId":"t","objectId":"a","index":2}"""),
        "set_object_visibility" to args("""{"transactionId":"t","objectId":"a","visible":false}"""),
        "recolor_object" to args("""{"transactionId":"t","objectId":"a","threadIndex":0,"threadColor":"#112233"}"""),
        "remove_objects" to args("""{"transactionId":"t","objectIds":["a"]}"""),
        "set_project_frame" to args("""{"transactionId":"t","frameId":"H130x180"}"""),
        "sewout_check" to args("""{"projectRevision":"0","pesAssetId":"","machineId":"se700","frameId":"H100"}"""),
        "prepare_pes" to args("""{"projectRevision":"0","machineId":"se700","frameId":"H100","pesVersion":"V6"}"""),
    )
    @Test fun everySupportedToolHasSuccessfulAndRejectedInputWithoutPartialMutation() {
        assertEquals(ToolDispatcher.SUPPORTED-setOf("add_text","replace_text"), cases().keys)
        for ((name,a) in cases()) {
            // A single actual, upstream-locked component avoids creating artificial cross-object boundaries.
            val p=if(name in setOf("prepare_pes","sewout_check")) objectProject().let { it.copy(objects=it.objects.take(1)) } else objectProject()
            val good=dispatcher.dispatch(p,"0",name,a,confirmed=true)
            assertTrue(good.ok,"$name: ${good.output}")
            when(name) {
                "set_object_transforms", "transform_objects" -> { assertEquals(10.0,good.project.find("a")!!.transform.x); assertEquals(20.0,good.project.find("a")!!.transform.y); assertEquals(90.0,good.project.find("a")!!.transform.rotationDeg) }
                "mirror_objects" -> assertEquals(-1.0,good.project.find("a")!!.transform.scaleX)
                "center_objects" -> assertEquals(0.0,good.project.find("a")!!.transform.x)
                "align_objects" -> assertEquals(good.project.find("a")!!.transform.x,good.project.find("b")!!.transform.x)
                "distribute_objects" -> assertEquals(150.0,good.project.find("b")!!.transform.x)
                "duplicate_object" -> assertEquals(10.0,good.project.find("copy")!!.transform.x)
                "reorder_object" -> assertEquals("a",good.project.objects.last().id)
                "set_object_visibility" -> assertFalse(good.project.find("a")!!.visible)
                "recolor_object" -> assertEquals(0x112233,good.project.find("a")!!.localPattern().threads.first().color and 0xFFFFFF)
                "remove_objects" -> assertNull(good.project.find("a"))
                "set_project_frame" -> assertEquals(HoopSpec.H130x180,good.project.hoop)
            }
            if(name=="prepare_pes") { assertNotNull(good.pesBytes); assertTrue(PecViewportCheck.check(good.pesBytes, p.hoop).valid) }
            val bad=dispatcher.dispatch(p,"0",name,JsonObject(a+ ("unknown" to JsonPrimitive(1))),confirmed=true)
            assertFalse(bad.ok,name);assertEquals("INVALID_ARGUMENTS",bad.code);assertSame(p,bad.project);assertNull(bad.pesBytes)
            println("TOOL_CASE $name NORMAL PASS REJECT PASS")
        }
    }
    @Test fun schemaRejectsTypesBoundsMissingAndNestedUnknownFieldsBeforeAnyToolRuns() {
        val p=objectProject();val a=cases().getValue("duplicate_object")
        for ((field,value) in listOf("xMm" to JsonPrimitive(10001),"scaleXPercent" to JsonPrimitive(4),"yMm" to JsonPrimitive("2"),"rotationDeg" to JsonPrimitive(false))) {
            val pose=JsonObject(a.getValue("transform").jsonObject+(field to value))
            assertEquals("INVALID_ARGUMENTS",dispatcher.dispatch(p,"0","duplicate_object",JsonObject(a+("transform" to pose))).code)
        }
        assertEquals("INVALID_ARGUMENTS",dispatcher.dispatch(p,"0","get_project",JsonNull).code)
        assertEquals("INVALID_ARGUMENTS",dispatcher.dispatch(p,"0","duplicate_object",JsonObject(a-"sourceId")).code)
        assertEquals("INVALID_ARGUMENTS",dispatcher.dispatch(p,"0","duplicate_object",JsonObject(a+("transform" to JsonObject(a.getValue("transform").jsonObject+("extra" to JsonPrimitive(1)))))).code)
        assertEquals("INVALID_ARGUMENTS",dispatcher.dispatch(p,"0","list_fonts",args("""{"text":"x","filters":[],"installedOnly":"yes"}""")).code)
        assertEquals("NOT_IMPLEMENTED",dispatcher.dispatch(p,"0","list_fonts",args("""{"text":"x","filters":[],"installedOnly":true}""")).code)
        assertEquals("UNKNOWN_TOOL",dispatcher.dispatch(p,"0","nonexistent",args("{}")).code)
    }
    @Test fun everyOtherCatalogToolIsExplicitlyUnimplementedAfterStrictValidation() {
        val rows=Json.parseToJsonElement(javaClass.getResourceAsStream("/agent/tools.json")!!.bufferedReader().use { it.readText() }).jsonObject.getValue("tools").jsonArray
        fun sample(s: JsonObject): JsonElement {
            s["enum"]?.let { return it.jsonArray.first() }
            return when(s.string("type")) {
                "object" -> JsonObject(s.getValue("properties").jsonObject.mapValues { sample(it.value.jsonObject) })
                "array" -> JsonArray(emptyList())
                "string" -> JsonPrimitive(if("pattern" in s) "#123456" else "example")
                "boolean" -> JsonPrimitive(false)
                else -> s["minimum"] ?: JsonPrimitive(0)
            }
        }
        val p=objectProject(); var count=0
        for(row in rows) {
            val t=row.jsonObject;val name=t.string("name")
            if(name in ToolDispatcher.SUPPORTED) continue
            val a=sample(t.getValue("inputSchema").jsonObject).jsonObject
            val valid=dispatcher.dispatch(p,"0",name,a)
            assertEquals("NOT_IMPLEMENTED",valid.code,name);assertSame(p,valid.project);assertNull(valid.pesBytes)
            assertEquals("INVALID_ARGUMENTS",dispatcher.dispatch(p,"0",name,JsonObject(a+("extra" to JsonPrimitive(true)))).code,name)
            count++
        }
        assertEquals(69-ToolDispatcher.SUPPORTED.size,count)
        println("UNIMPLEMENTED_CATALOG $count typed errors; strict validation first PASS")
    }
    @Test fun malformedApprovedPesAndInvalidSourceSelectorsReturnErrorsWithoutBytes() {
        val p=objectProject()
        val d=ToolDispatcher(assets=mapOf("bad" to byteArrayOf(1,2)))
        val a=args("""{"projectRevision":"","pesAssetId":"bad","machineId":"se700","frameId":"H100"}""")
        val bad=d.dispatch(p,"0","sewout_check",a)
        assertFalse(bad.ok);assertEquals("INVALID_PES",bad.code);assertNull(bad.pesBytes)
        assertEquals("INVALID_ARGUMENTS",d.dispatch(p,"0","sewout_check",JsonObject(a+("projectRevision" to JsonPrimitive("0")))).code)
    }
    @Test fun semanticRejectionsAndConsentPreserveSnapshot() {
        val p=objectProject()
        val requests=listOf(
            "reorder_object" to """{"transactionId":"t","objectId":"a","index":3}""",
            "recolor_object" to """{"transactionId":"t","objectId":"a","threadIndex":1,"threadColor":"#112233"}""",
            "transform_objects" to """{"transactionId":"t","objectIds":["missing"],"deltaXMm":0,"deltaYMm":0,"scaleFactor":1,"rotationDeg":0}""",
            "set_project_frame" to """{"transactionId":"t","frameId":"unknown"}""",
        )
        for((name,a) in requests) {val result=dispatcher.dispatch(p,"0",name,args(a));assertFalse(result.ok);assertSame(p,result.project)}
        assertEquals("CONFIRMATION_REQUIRED",dispatcher.dispatch(p,"0","remove_objects",cases().getValue("remove_objects")).code)
        assertEquals("STALE_REVISION",dispatcher.dispatch(p,"new","prepare_pes",cases().getValue("prepare_pes")).code)
    }
    @Test fun outputMustNotRecenterAwayAnInvalidPlacementAndNeverReleasesBlockedBytes() {
        val p=objectProject().let { it.copy(objects=listOf(it.objects.first().withTransform(Transform(x=1000.0)))) }
        val result=dispatcher.dispatch(p,"0","prepare_pes",cases().getValue("prepare_pes"))
        assertEquals("SEWOUT_BLOCKED",result.code);assertNull(result.pesBytes)
    }
    @Test fun native999FitsBut1000IsBlockedIncludingActualEncodedAssetCheck() {
        fun project(max: Int): Project {
            val points=(0..max step 100).toMutableList().also { if(it.last()!=max)it+=max }
            // End locks are functional short return moves, not generated fake metadata.
            val xs=listOf(0,5,0)+points.drop(1)+listOf(max-5,max)
            val p=EmbPattern(xs.map { Stitch(it,0,Command.STITCH) },listOf(PecThreads.forIndex(5)))
            return Project(HoopSpec.H100,listOf(StitchObject("edge","edge",p,Transform(x=-500.0+max/2))))
        }
        val good=dispatcher.dispatch(project(999),"0","prepare_pes",cases().getValue("prepare_pes"))
        assertTrue(good.ok,good.output.toString());assertEquals(999,PecViewportCheck.check(good.pesBytes!!,HoopSpec.H100).maxX)
        val bad=dispatcher.dispatch(project(1000),"0","prepare_pes",cases().getValue("prepare_pes"))
        assertFalse(bad.ok);assertNull(bad.pesBytes)
        // Deliberately invalid public synthetic bytes: the test helper first asserts real writer rejection.
        val edgeBytes=io.github.linker2k.myembro.engine.PolicyFixtureWriter.write(project(1000).objects.single().localPattern())
        val asset=ToolDispatcher(assets=mapOf("edge" to edgeBytes)).dispatch(project(999),"0","sewout_check",
            args("""{"projectRevision":"","pesAssetId":"edge","machineId":"se700","frameId":"H100"}"""))
        assertTrue(asset.ok) // A read-only analysis succeeded, and its verdict is BLOCK.
        assertEquals("BLOCK",asset.output.getValue("report").jsonObject.string("verdict"))
        assertEquals(1000,asset.output.getValue("viewport").jsonObject.getValue("maxXUnits").jsonPrimitive.int)
        assertFalse(asset.output.getValue("viewport").jsonObject.getValue("valid").jsonPrimitive.boolean)
        assertNull(asset.pesBytes)
        println("HOOP_BOUNDARY 999 PASS 1000 BLOCK; encoded imported 1000 BLOCK")
    }
    @Test fun builtinScenarioMovesRotatesRecolorsExportsAndChecksActualPes() {
        val scenario=args("""{"initialProject":{"builtinDesign":"myembro_logo.pes","objectId":"logo","frameId":"H100"},"calls":[
            {"name":"transform_objects","arguments":{"transactionId":"scenario","objectIds":["logo"],"deltaXMm":1,"deltaYMm":2,"scaleFactor":1,"rotationDeg":90}},
            {"name":"recolor_object","arguments":{"transactionId":"scenario","objectId":"logo","threadIndex":0,"threadColor":"#B5492A"}},
            {"name":"prepare_pes","arguments":{"projectRevision":"${'$'}revision","machineId":"se700","frameId":"H100","pesVersion":"V6"}},
            {"name":"sewout_check","arguments":{"projectRevision":"${'$'}revision","pesAssetId":"","machineId":"se700","frameId":"H100"}}
            ],"export":{"machineId":"se700","frameId":"H100","pesVersion":"V6"}}""")
        val result=AgentScenario.run(scenario)
        assertTrue(result.success,result.output.toString());assertNotNull(result.pesBytes)
        assertEquals(SewoutVerdict.PASS,SewoutCheck.checkPes(result.pesBytes,SewoutMachines.SE700).verdict)
        println("BUILTIN_SCENARIO PASS actual PES bytes=${result.pesBytes.size}")
    }
}
