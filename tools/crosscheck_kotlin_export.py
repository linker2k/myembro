#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Call the fixed Kotlin worker public API in its /tmp archive; no Python verdict input."""
import json,pathlib,subprocess,sys
manifest,output,checkout=map(pathlib.Path,sys.argv[1:]);test=checkout/'engine/src/test/kotlin/io/github/linker2k/myembro/engine/sewout/IndependentCorpusCheckTest.kt'
test.parent.mkdir(parents=True,exist_ok=True)
test.write_text('''package io.github.linker2k.myembro.engine.sewout
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.Test
class IndependentCorpusCheckTest {
 @Test fun export() {
  val input=Json.parseToJsonElement(File(System.getProperty("crosscheck.input")).readText()).jsonObject
  fun level(report:SewoutReport,codes:Set<String>):String {
   val f=report.findingSeverities.filterKeys { it.name in codes }.values
   return if(Severity.BLOCKER in f) "BLOCK" else if(Severity.WARNING in f) "WARNING" else "PASS"
  }
  val mapping=mapOf("density" to setOf("DENSITY_HOTSPOT"),"repetition" to setOf("REPEATED_PENETRATION"),"zero_motion" to setOf("ZERO_LENGTH_MOTION"),"locks" to setOf("MISSING_LOCK_STITCH"),"length" to setOf("LONG_STITCH"),"file" to setOf("INVALID_PES","EMPTY_DESIGN","PEC_ORIGIN","PEC_DIMENSIONS"),"hoop" to setOf("HOOP_EXCEEDED","MACHINE_AREA_EXCEEDED"))
  val rows=input.getValue("cases").jsonArray.map { entry ->
   val c=entry.jsonObject;val app=c["origin"]?.jsonPrimitive?.content=="APP_GENERATED"
   val report=SewoutCheck.checkPes(File(c.getValue("path").jsonPrimitive.content).readBytes(),options=SewoutOptions(origin=if(app) DesignOrigin.APP_GENERATED else DesignOrigin.IMPORTED, applyHoop=app))
   val known=mapping.values.flatten().toSet()
   buildJsonObject {
    put("id",c.getValue("id"));put("sha256",c.getValue("sha256"));put("verdict",when(report.verdict.name){"CAUTION"->"WARNING";else->report.verdict.name})
    put("conditions",buildJsonObject {for((key,codes) in mapping) put(key,if(key=="hoop" && !app) "NOT_APPLICABLE" else level(report,codes))})
    put("file_messages",JsonArray(report.findings.filter {it.code==FindingCode.INVALID_PES}.map {JsonPrimitive(it.message)}))
    put("suppressed_findings",report.suppressedFindings)
    put("other_blockers",JsonArray(report.findingSeverities.filter { (code,severity) -> severity==Severity.BLOCKER && code.name !in known }.keys.map {JsonPrimitive(it.name)}))
   }
  }
  File(System.getProperty("crosscheck.output")).writeText(buildJsonObject {put("commit",input.getValue("commit"));put("source","IMPORTED");put("apply_hoop",false);put("default_long_stitch_mm",SewoutOptions().longStitchMm);put("cases",JsonArray(rows))}.toString())
 }
}
''')
(checkout/'local.properties').write_text('sdk.dir=/home/gosh2/Android/Sdk\n')
init=checkout/'crosscheck.init.gradle';init.write_text("gradle.afterProject { p -> if(p.path==':engine') p.tasks.withType(org.gradle.api.tasks.testing.Test).configureEach { systemProperty 'crosscheck.input', '"+str(manifest)+"'; systemProperty 'crosscheck.output', '"+str(output)+"' } }\n")
subprocess.run(['./gradlew','--offline','--no-daemon','-I',str(init),':engine:test','--tests','*IndependentCorpusCheckTest','--console=plain'],cwd=checkout,check=True,timeout=500)
