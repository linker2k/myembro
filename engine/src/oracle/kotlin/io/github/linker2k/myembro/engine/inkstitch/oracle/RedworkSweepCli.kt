// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.oracle

import io.github.linker2k.myembro.engine.inkstitch.svg.image.RedworkPipeline
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/** Synthetic raster replay only. No Android, source algorithms or thresholds are modified. */
object RedworkSweepCli {
    @JvmStatic fun main(args: Array<String>) {
        val output = File(args.single())
        val json = Json { prettyPrint = true }
        val manifest = json.parseToJsonElement(File(output, "inputs.json").readText()).jsonArray
        require(manifest.size == 100)
        val results = manifest.mapIndexed { index, entry ->
            val input = entry.jsonObject
            val id = input.getValue("id").jsonPrimitive.content
            val edge = input.getValue("edgePx").jsonPrimitive.int
            val raw = File(output, "inputs/$id-$edge.rgb").readBytes()
            require(raw.size == edge * edge * 3)
            val rgb = IntArray(edge * edge) { i ->
                ((raw[i*3].toInt() and 255) shl 16) or
                    ((raw[i*3+1].toInt() and 255) shl 8) or (raw[i*3+2].toInt() and 255)
            }
            val result = buildJsonObject {
                input.forEach { (key, value) -> put(key, value) }
                put("inputSha256", MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) })
                try {
                    val generated = RedworkPipeline.generate(edge, edge, rgb, 100.0, 0,
                        EmbThread(0xFFB5492A.toInt(), "Redwork"))
                    // Match the app's default PES v1 export, including writer/readback normalization.
                    val pes = PesWriter.write(generated.pattern)
                    File(output, "pes").mkdirs()
                    File(output, "pes/$id-$edge.pes").writeBytes(pes)
                    val report = SewoutCheck.checkPes(pes, SewoutMachines.SE400, SewoutOptions(
                        placement = Placement.HOOP_COORDINATES, hotspotPenetrations = 16))
                    put("status", if (report.analysisComplete && !report.hasBlockingIssues) "pass" else "blocked")
                    put("analysisComplete", report.analysisComplete)
                    put("stitchCount", report.statistics.stitchCount)
                    put("contours", generated.contourCount)
                    put("suppressedFindings", report.suppressedFindings)
                    putJsonArray("findings") {
                        report.findings.forEach { finding -> add(buildJsonObject {
                            put("code", finding.code.name); put("severity", finding.severity.name)
                            put("message", finding.message)
                            put("measured", finding.measured?.let(::JsonPrimitive) ?: JsonNull)
                            put("threshold", finding.threshold?.let(::JsonPrimitive) ?: JsonNull)
                        }) }
                    }
                    putJsonObject("severityCounts") {
                        Severity.entries.forEach { put(it.name, report.severityCounts[it] ?: 0) }
                    }
                } catch (e: Exception) {
                    put("status", "generation_error")
                    put("errorType", e.javaClass.name)
                    put("error", e.message ?: "No message")
                }
            }
            println("${index+1}/100 $id $edge ${result.getValue("status").jsonPrimitive.content}")
            result
        }
        File(output, "results.json").writeText(json.encodeToString(JsonArray.serializer(), JsonArray(results)) + "\n")
    }
}
