// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.oracle

import io.github.linker2k.myembro.engine.inkstitch.svg.image.RedworkPipeline
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/** Replays original script-generated RGB inputs, never personal photos. */
object S4SamplesCli {
    @JvmStatic fun main(args: Array<String>) {
        val output = File(args.single())
        val items = listOf("FLOWER", "HOUSE").map { name ->
            val bytes = File(output, "$name.rgb").readBytes()
            require(bytes.size == 192 * 192 * 3)
            val rgb = IntArray(192*192) { i -> ((bytes[3*i].toInt() and 255) shl 16) or
                ((bytes[3*i+1].toInt() and 255) shl 8) or (bytes[3*i+2].toInt() and 255) }
            val thread = EmbThread(0xFF000000.toInt(), "Black 40wt")
            // Calibrate input physical width, then regenerate; never scale finished stitches.
            var widthMm = 30.0
            var result = RedworkPipeline.generate(192,192,rgb,widthMm,0,thread)
            repeat(3) {
                val bounds = requireNotNull(result.pattern.bounds)
                widthMm *= 300.0 / maxOf(bounds.width, bounds.height)
                result = RedworkPipeline.generate(192,192,rgb,widthMm,0,thread)
            }
            val pes = PesWriter.write(result.pattern)
            File(output,"$name.PES").writeBytes(pes)
            File(output,"$name.outline.svg").writeText(result.outlineSvg)
            val bounds = requireNotNull(result.pattern.bounds)
            check(maxOf(bounds.width,bounds.height) in 298..302) { "30mm target: $bounds" }
            val report = SewoutCheck.checkPes(pes, options = SewoutOptions(
                origin = DesignOrigin.APP_GENERATED, placement = Placement.HOOP_COORDINATES))
            File(output,"$name.sewout.txt").writeText(report.findings.joinToString("\n") { "${it.severity} ${it.code}: ${it.message}" })
            check(report.analysisComplete && !report.hasBlockingIssues) { "$name blocked: ${report.findings}" }
            buildJsonObject {
                put("name",name);put("rasterEdgePx",192);put("bitmapWidthMm",widthMm)
                put("widthMm",bounds.width/10.0);put("heightMm",bounds.height/10.0)
                put("stitches",report.statistics.stitchCount);put("contours",result.contourCount)
                put("analysisComplete",report.analysisComplete);put("blockers",report.severityCounts[Severity.BLOCKER] ?: 0)
                put("inputSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            }
        }
        val json = Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(items))
        File(output,"inputs-report.json").writeText(json+"\n")
        println(json)
    }
}
