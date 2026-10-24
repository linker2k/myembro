// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.oracle

import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorDrawingPipeline
import io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import java.io.File
import javax.imageio.ImageIO

/** Generates PES through the same two-phase API used by the Android photo/drawing screen. */
object ImageToDesignProbeCli {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size in 2..3) { "INPUT_DIRECTORY OUTPUT_DIRECTORY [COLORS=4]" }
        val colorCount=args.getOrNull(2)?.toInt() ?: 4
        val out=File(args[1]).apply { mkdirs() }
        val rows=File(args[0]).listFiles()!!.filter { it.extension=="png" }.sortedBy { it.name }.map { file ->
            val start=System.nanoTime()
            val image=ImageIO.read(file); val w=image.width; val h=image.height
            val argb=image.getRGB(0,0,w,h,null,0,w)
            val preview=ColorDrawingPipeline.trace(w,h,argb,30.0,colorCount)
            val traced=System.nanoTime()
            val result=ColorDrawingPipeline.generate(preview)
            val pattern=result.pattern
            val generated=System.nanoTime()
            val bytes=PesWriter.write(pattern,PesVersion.V6)
            File(out,file.nameWithoutExtension+".pes").writeBytes(bytes)
            val viewport=PecViewportCheck.check(bytes,HoopSpec.H100)
            val report=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
            buildJsonObject {
                put("input",file.name);put("requested_colors",colorCount);put("palette_colors",preview.palette.size)
                put("regions",buildJsonArray { result.regions.forEach { r -> add(buildJsonObject {
                    put("thread",r.thread.hex);put("median_width_mm",r.widths.medianMm)
                    put("maximum_width_mm",r.widths.maxMm)
                    put("style",if(r.method==StitchTypeRule.Type.TATAMI) "FILL" else r.method.name);put("groups",r.groups)
                }) } })
                put("region_count",result.regions.size);put("stitch_count",pattern.stitchCount)
                put("trace_ms",(traced-start)/1e6);put("stitch_ms",(generated-traced)/1e6)
                put("total_ms",(System.nanoTime()-start)/1e6);put("viewport_valid",viewport.valid)
                put("gate",report.verdict.name);put("blocking",report.hasBlockingIssues)
                put("findings",buildJsonObject { report.findingCounts.forEach { (code,count) -> put(code.name,count) } })
            }.also { println(it) }
        }
        File(out,"results.json").writeText(Json { prettyPrint=true }.encodeToString(JsonArray.serializer(),JsonArray(rows)))
    }
}
