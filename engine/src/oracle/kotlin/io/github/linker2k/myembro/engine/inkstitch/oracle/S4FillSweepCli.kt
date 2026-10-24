// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.oracle

import io.github.linker2k.myembro.engine.inkstitch.svg.image.RedworkPipeline
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import java.io.File

object S4FillSweepCli {
    @JvmStatic fun main(args: Array<String>) {
        val out=File(args.single())
        val rows=Json.parseToJsonElement(File(out,"inputs.json").readText()).jsonArray.map { element ->
            val input=element.jsonObject
            val id=input.getValue("id").jsonPrimitive.content
            val edge=input.getValue("edgePx").jsonPrimitive.int
            val raw=File(out,"$id.rgb").readBytes()
            require(raw.size==edge*edge*3)
            val rgb=IntArray(edge*edge) { i -> ((raw[i*3].toInt() and 255) shl 16) or
                ((raw[i*3+1].toInt() and 255) shl 8) or (raw[i*3+2].toInt() and 255) }
            buildJsonObject {
                input.forEach { (key,value)->put(key,value) }
                try {
                    val result=RedworkPipeline.generate(edge,edge,rgb,input.getValue("bitmapWidthMm").jsonPrimitive.double,
                        0,EmbThread(0xFF000000.toInt(),"Black 40wt"))
                    val pes=PesWriter.write(result.pattern)
                    File(out,"$id.pes").writeBytes(pes)
                    val report=SewoutCheck.checkPes(pes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED,
                        placement=Placement.HOOP_COORDINATES))
                    put("status",if(report.analysisComplete && !report.hasBlockingIssues) "pass" else "blocked")
                    put("analysisComplete",report.analysisComplete);put("stitches",report.statistics.stitchCount)
                    putJsonArray("blockerCodes") { report.findings.filter { it.severity==Severity.BLOCKER }.forEach { add(it.code.name) } }
                } catch(e:Exception) { put("status","generation_error");put("error",e.message ?: e.javaClass.name) }
            }.also { println("$id ${it.getValue("status")}") }
        }
        require(rows.size==50)
        File(out,"results.json").writeText(Json { prettyPrint=true }.encodeToString(JsonArray.serializer(),JsonArray(rows))+"\n")
    }
}
