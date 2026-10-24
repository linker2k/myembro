// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.oracle

import java.io.File
import java.io.PrintStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*

/** Explicit public-font calculation only; same pipeline as HangulSatinCli, no app/default change. */
object HangulSatinSweepCli {
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.single()); val output=File(root,"outputs").apply { mkdirs() }
        val cases=Json.parseToJsonElement(File(root,"cases.json").readText()).jsonArray
        val console=System.out; val records=mutableListOf<JsonObject>(); val start=System.nanoTime()
        for((index,item) in cases.withIndex()) {
            val case=item.jsonObject; val id=case.getValue("id").jsonPrimitive.content
            val log=ByteArrayOutputStream(); val before=System.nanoTime()
            var failure: String?=null
            try {
                System.setOut(PrintStream(log))
                HangulSatinCli.main(arrayOf(case.getValue("input").jsonPrimitive.content,File(output,id).path,
                    case.getValue("width_mm").jsonPrimitive.content,"no-preview"))
            } catch(e:Exception) { failure="${e.javaClass.name}: ${e.message}" }
            catch(e:StackOverflowError) { failure="StackOverflowError" }
            finally { System.setOut(console) }
            File(output,"$id.log").writeText(log.toString(Charsets.UTF_8.name()))
            val row=buildJsonObject {
                put("id",id); put("seconds",(System.nanoTime()-before)/1e9)
                put("status",if(failure==null) "GENERATED" else "GENERATION_REJECTED")
                put("error",failure?.let(::JsonPrimitive) ?: JsonNull)
                val report=File(output,"$id.json")
                if(report.exists()) put("checks",Json.parseToJsonElement(report.readText()))
            }
            records+=row
            File(root,"generation.json").writeText(Json { prettyPrint=true }.encodeToString(JsonArray.serializer(),JsonArray(records)))
            if((index+1)%30==0) println("HANGUL_SWEEP ${index+1}/${cases.size} generated=${records.count { it["status"]!!.jsonPrimitive.content=="GENERATED" }} seconds=${(System.nanoTime()-start)/1e9}")
        }
        File(root,"generation-seconds.txt").writeText(((System.nanoTime()-start)/1e9).toString())
    }
}
