// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.PolicyFixtureWriter
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

/** JSON scenarios are fixed inputs/expectations. Recording only writes observations to a NEW tmp dir. */
object AgentScenarioRegression {
    val files=listOf("01-logo-transform","02-inkstitch-english","03-hershey-weights","04-ttf-hangul-satin",
        "05-emoji-heart","06-align-distribute","07-duplicate-order","08-hoop-boundary","09-height-rejected","10-mid-failure-rollback")
    private fun resource(name:String)=checkNotNull(javaClass.getResourceAsStream("/agent/scenarios/$name.json")).bufferedReader().use { it.readText() }
    fun run(file:String,output:File?,verify:Boolean):List<JsonObject> {
        val document=Json.parseToJsonElement(resource(file)).jsonObject
        return document.getValue("cases").jsonArray.map { item ->
            val c=item.jsonObject;val id=c.string("id");val expected=c.getValue("expected").jsonObject
            val kernel=ToolDispatcher(fontResolver=BundledFontResolver(ScenarioSnapshotResolver()))
            val bindings=mutableMapOf<String,String>()
            c["preparations"]?.jsonArray?.forEach { value ->
                val p=value.jsonObject;val result=kernel.dispatch(Project(),"0",p.string("name"),p.getValue("arguments"))
                check(result.ok) { result.output.toString() };bindings["\$prepared.${p.string("id")}"]=result.output.string("resultId")
            }
            fun resolve(v:JsonElement):JsonElement=when(v) {
                is JsonObject -> JsonObject(v.mapValues { resolve(it.value) })
                is JsonArray -> JsonArray(v.map {resolve(it)})
                is JsonPrimitive -> if(v.isString && v.content in bindings) JsonPrimitive(bindings.getValue(v.content)) else v
                else -> v
            }
            val script=resolve(c.getValue("script")).jsonObject
            val initial=script.getValue("initialProject").jsonObject.let { p ->
                if("builtinDesign" in p) AgentScenario.builtin(p.string("builtinDesign"),p.string("objectId"),p.string("frameId")) else ProjectJson.decode(p.toString())
            }
            val session=io.github.linker2k.myembro.engine.edit.commands.EditSession(initial)
            val calls=script.getValue("calls").jsonArray.map { v ->
                val call=v.jsonObject;ToolTurnController.Call(call.string("name"),call.getValue("arguments"))
            }
            val result=kernel.bind(session).executeScript("scenario",0,calls,script.getValue("export").jsonObject)
            val unchanged=ProjectJson.encode(session.project)==ProjectJson.encode(initial)
            // Failures never release bytes; audit the unchanged live state separately, clearly named.
            val audit=result.pesBytes ?: PolicyFixtureWriter.write(initial.flatten(),PesVersion.V6)
            val report=SewoutCheck.checkPes(audit,options=SewoutOptions(hoop=initial.hoop,origin=SewoutCheck.originOf(session.project.flatten())))
            val record=buildJsonObject {
                put("id",id);put("success",result.ok);put("code",result.output["code"] ?: JsonNull)
                put("sewout",report.verdict.name);put("stitchCount",PesReader.read(audit).stitchCount)
                put("pesSha256",sha(audit));put("released",result.pesBytes!=null);put("unchanged",unchanged)
                put("undoCount",session.snapshot().undoCount)
                put("widthMm",initial.hoop.width/10.0);put("heightMm",initial.hoop.height/10.0)
            }
            if(output!=null) {
                File(output,"$id.audit.pes").writeBytes(audit)
                File(output,"$id.result.json").writeText(result.output.toString())
            }
            if(verify) {
                expected.forEach { (key,value) -> assertEquals(value,record[key],"$id $key: $record") }
                assertEquals(result.ok,result.pesBytes!=null,"$id release guard")
                assertEquals(0,session.snapshot().redoCount,"$id redo depth")
                assertEquals(if(unchanged) 0L else 1L,session.revision,"$id atomic revision")
                if(!result.ok) {assertTrue(unchanged,"$id rollback");assertEquals(0,record.integer("undoCount"))}
                else assertEquals(if(unchanged) 0 else 1,record.integer("undoCount"))
            }
            println("SCENARIO ${if(verify) "PASS" else "MEASURE"} $record")
            record
        }
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it.toInt() and 255)}
    @JvmStatic fun main(args:Array<String>) {
        require(args.size in 1..2);val out=File(args[0]);check(out.mkdir()) {"Output directory must be new"}
        val verify=args.getOrNull(1)!="--measure"
        val records=files.flatMap { run(it,out,verify) }
        File(out,"observed.json").writeText(Json {prettyPrint=true}.encodeToString(JsonArray.serializer(),JsonArray(records)))
    }
}
class AgentScenarioRegressionTest {
    @Test fun logo() {AgentScenarioRegression.run("01-logo-transform",null,true)}
    @Test fun inkstitchEnglish() {AgentScenarioRegression.run("02-inkstitch-english",null,true)}
    @Test fun hersheyWeights() {AgentScenarioRegression.run("03-hershey-weights",null,true)}
    @Test fun ttfHangulSatin() {AgentScenarioRegression.run("04-ttf-hangul-satin",null,true)}
    @Test fun emojiHeart() {AgentScenarioRegression.run("05-emoji-heart",null,true)}
    @Test fun alignmentDistribution() {AgentScenarioRegression.run("06-align-distribute",null,true)}
    @Test fun duplicationOrder() {AgentScenarioRegression.run("07-duplicate-order",null,true)}
    @Test fun hoopBoundary() {AgentScenarioRegression.run("08-hoop-boundary",null,true)}
    @Test fun rejectedHeight() {AgentScenarioRegression.run("09-height-rejected",null,true)}
    @Test fun midScriptRollback() {AgentScenarioRegression.run("10-mid-failure-rollback",null,true)}
}
