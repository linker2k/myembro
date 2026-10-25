// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.safety

import io.github.linker2k.myembro.engine.agent.*
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

object SafetyFixtures {
    val catalog=checkNotNull(javaClass.getResourceAsStream("/agent/tools.json")).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    val confirmation=catalog.getValue("tools").jsonArray.map { it.jsonObject }.filter { it.getValue("annotations").jsonObject.getValue("needsUserConfirmation") != JsonPrimitive(false) }
    fun json(s:String)=Json.parseToJsonElement(s).jsonObject
    val export=json("""{"machineId":"se700","frameId":"H100","pesVersion":"V6"}""")
    val prepare=json("""{"projectRevision":"0","machineId":"se700","frameId":"H100","pesVersion":"V6"}""")
    fun project():Project {
        val p=AgentScenario.builtin("myembro_logo.pes","logo","H100")
        return p.copy(objects=p.objects+ p.objects.single().withId("victim").withVisible(false))
    }
    fun blockedProject()=project().copy(objects=project().objects.map { it.withTransform(Transform(x=9999.0)) })
    fun arguments(tool:JsonObject,artifact:String="missing"):JsonObject {
        fun value(s:JsonObject,key:String):JsonElement {
            s["enum"]?.let { return it.jsonArray.first() }
            return when(s.getValue("type").jsonPrimitive.content) {
                "object" -> JsonObject(s.getValue("properties").jsonObject.mapValues { (k,v)->value(v.jsonObject,k) })
                "array" -> if(key in setOf("objectIds","designIds","artifactIds","deleteFileIds")) JsonArray(listOf(JsonPrimitive(if(key=="artifactIds")artifact else "victim"))) else JsonArray(emptyList())
                "boolean" -> JsonPrimitive(false)
                "number","integer" -> JsonPrimitive(if(key.endsWith("Mm"))1 else 0)
                else -> JsonPrimitive(when(key) { "transactionId"->"attack";"projectRevision","expectedRevision"->"0";"destinationId"->"output";"name"->"unapproved";"artifactId"->artifact;"frameId"->"H100";else->"id" })
            }
        }
        return value(tool.getValue("inputSchema").jsonObject,"").jsonObject
    }
    data class Attack(val label:String,val tool:String,val args:JsonObject,val mustReject:Boolean=true)
    fun malformed():List<Attack> {
        val pose=json("""{"transactionId":"attack","transforms":[{"objectId":"logo","xMm":0,"yMm":0,"rotationDeg":0,"scaleXPercent":100,"scaleYPercent":100}]}""")
        fun poseValue(key:String,v:JsonElement)=JsonObject(pose+("transforms" to JsonArray(listOf(JsonObject(pose.getValue("transforms").jsonArray[0].jsonObject+(key to v))))))
        return listOf(
            Attack("additional_field","get_settings",json("""{"confirmed":true}""")),
            Attack("NaN","set_object_transforms",poseValue("xMm",JsonPrimitive(Double.NaN))),
            Attack("infinity","set_object_transforms",poseValue("xMm",JsonPrimitive(Double.POSITIVE_INFINITY))),
            Attack("huge_coordinate","set_object_transforms",poseValue("xMm",JsonPrimitive(1e100))),
            Attack("negative_size","set_object_transforms",poseValue("scaleXPercent",JsonPrimitive(-100))),
            Attack("9999_native_units","set_object_transforms",poseValue("xMm",JsonPrimitive(999.9)),mustReject=false),
            Attack("empty_object_id","set_object_transforms",poseValue("objectId",JsonPrimitive(""))),
            Attack("empty_transaction_id","center_objects",json("""{"transactionId":"","objectIds":["logo"]}""")),
            Attack("negative_text_height","add_text",json("""{"transactionId":"attack","objectId":"new","text":"A","fontId":"hershey-futural","fontRevision":"bundled-hershey-v1:glyph-extractor-v3","heightMm":-1,"threadColor":"#000000","singleLineBold":false}"""))
        )
    }
    /** Valid viewport, intentionally unsafe needle density, using the explicit oracle profile only. */
    fun densePes():ByteArray=PesWriter.write(EmbPattern(List(40) { i->Stitch(i%2,(i/2)%2,Command.STITCH) },listOf(EmbThread(0))),PesWriteSettings(inkstitchCommands=true))
    fun report(name:String,rows:List<JsonObject>) {
        val dir=Path.of(System.getProperty("myembro.safetyReportDir","/tmp/myembro-safety-audit"));Files.createDirectories(dir)
        Files.write(dir.resolve("$name.json"),Json { prettyPrint=true }.encodeToString(JsonElement.serializer(),JsonArray(rows)).toByteArray(Charsets.UTF_8))
        rows.forEach { println("SAFETY_$name $it") }
    }
    fun row(label:String,result:JsonObject,writes:Int,changed:Boolean=false,bytes:Int=0)=buildJsonObject {
        put("case",label);put("ok",result["ok"] ?: JsonNull);put("code",result["code"] ?: JsonNull);put("writes",writes);put("projectChanged",changed);put("returnedBytes",bytes)
    }
}
class RecordingStore:DocumentStore {
    val files=linkedMapOf<String,ByteArray>();val calls=mutableListOf<String>()
    override fun inspect(destinationId:String,name:String,kind:DocumentKind):DocumentState?=files[documentFileName(name,kind)]?.let { DocumentState(name,documentDigest(it)) }
    override fun write(destinationId:String,name:String,kind:DocumentKind,bytes:ByteArray,expectedVersion:String?):StoredDocument {
        calls+="write:$destinationId:$name";val leaf=documentFileName(name,kind)
        if(files[leaf]?.let(::documentDigest)!=expectedVersion)throw DocumentConflict("changed")
        files[leaf]=bytes.copyOf();return StoredDocument(leaf,"fake:$leaf",documentDigest(bytes))
    }
}
