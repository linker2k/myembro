// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.FillGeometry
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/** Replays the independent matrix's prepared SVGs. Never runs Ink/Stitch or changes gate inputs. */
object FillOracleMatrix {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size%2==0)
        val options=args.toList().chunked(2).associate { it[0] to it[1] }
        fun required(key:String)=options[key] ?: error("Missing $key")
        val matrix=File(required("--matrix")); val source=File(required("--oracle-input-dir"))
        val output=File(required("--output-dir")).canonicalFile.apply { mkdirs() }
        val results=File(required("--results-json")).absoluteFile.apply { parentFile.mkdirs() }
        val commit=required("--commit")
        require(commit.matches(Regex("[0-9a-f]{40}"))) { "Full source commit is required" }
        val owner=required("--owner"); require(owner=="embro-model")
        val filter=Regex(options["--case-filter"] ?: ".*")
        val root=Json.parseToJsonElement(matrix.readText()).jsonObject
        val assigned=root.getValue("cases").jsonArray.map { it.jsonObject }.filter { it["owner"]?.jsonPrimitive?.content==owner &&
            it["feature"]?.jsonPrimitive?.content=="fill_stitch" && filter.matches(it.getValue("id").jsonPrimitive.content) }
        require(assigned.isNotEmpty())
        val records=mutableListOf<JsonObject>()
        val dependencies=options["--dependency-manifest"]?.let { Json.parseToJsonElement(File(it).readText()) }
        fun save() {
            results.writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),buildJsonObject {
                put("owner",owner); put("branch","codex/model"); put("commit",commit)
                put("matrix_sha256",sha256(matrix.readBytes())); put("oracle_version","v3.3.0")
                put("oracle_inputs",source.absolutePath); put("output_dir",output.path)
                put("case_filter",filter.pattern); put("assigned_cases",assigned.size)
                put("writer","PES v1 (matches official oracle palette policy)")
                put("preview",true); if(dependencies!=null) put("shared_planner_dependencies",dependencies)
                put("cases",JsonArray(records))
            }))
        }
        save()
        for ((index,case) in assigned.withIndex()) {
            val id=case.getValue("id").jsonPrimitive.content
            require(id.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid matrix ID" }
            val svg=File(source,"$id.svg"); val pes=File(output,case.getValue("output_basename").jsonPrimitive.content).canonicalFile
            require(pes.parentFile==output && pes.extension=="pes") { "Output must stay within output directory" }
            val start=System.nanoTime()
            val record=try {
                val original=File(case.getValue("input").jsonPrimitive.content)
                require(sha256(original.readBytes())==case.getValue("input_sha256").jsonPrimitive.content) { "Frozen matrix input hash mismatch" }
                val bytes=svg.readBytes(); val document=FillMatrixInput.read(bytes)
                verifyParameters(case.getValue("parameters").jsonObject,document)
                val groups=mutableListOf<StitchGroup>()
                for (region in document.regions) {
                    val element=FillStitch(region.geometry,region.thread,region.parameters)
                    groups+=element.toStitchGroups(groups.lastOrNull()?.stitches?.lastOrNull())
                }
                val plan=StitchPlanBuilder.fromGroups(groups,collapse_len=document.collapseMm,min_stitch_len=document.minStitchMm)
                val pattern=EmbPatternAdapter.toEmbPattern(plan)
                val encoded=PesWriter.writeV1(pattern)
                val decoded=PesReader.read(encoded)
                require(decoded.stitches.any { it.command==Command.STITCH }) { "No needle stitches generated" }
                pes.writeBytes(encoded)
                buildJsonObject {
                    put("id",id); put("status","GENERATED"); put("path",pes.path); put("sha256",sha256(encoded))
                    put("input",original.absolutePath); put("input_sha256",sha256(original.readBytes()))
                    put("prepared_svg",svg.absolutePath); put("prepared_svg_sha256",sha256(bytes))
                    put("expected",case.getValue("expected")); put("parameters",case.getValue("parameters"))
                    put("groups",groups.size); put("raw_group_stitches",groups.sumOf { it.stitches.size })
                    put("needle_count",decoded.stitches.count { it.command==Command.STITCH })
                    put("holes",document.regions.sumOf { r -> FillGeometry.polygons(r.geometry).sumOf { it.numInteriorRing } })
                    put("row_spacing_effective_mm",document.regions.first().parameters.rowSpacingPx/(96.0/25.4))
                    put("min_stitch_len_mm",document.minStitchMm); put("collapse_len_mm",document.collapseMm)
                    put("elapsed_ms",(System.nanoTime()-start)/1_000_000)
                }
            } catch (error:Exception) {
                pes.delete()
                File(output,"$id.error.txt").writeText(error.stackTraceToString())
                buildJsonObject {
                    put("id",id); put("status","ERROR"); put("reason_code","GENERATION_FAILED")
                    put("reason","${error.javaClass.simpleName}: ${error.message}")
                    put("elapsed_ms",(System.nanoTime()-start)/1_000_000)
                }
            }
            records+=record; save()
            println("${index+1}/${assigned.size} $id ${record.getValue("status").jsonPrimitive.content}")
        }
        println("Wrote ${records.size} case records: ${results.path}")
        check(records.all { it["status"]?.jsonPrimitive?.content=="GENERATED" }) { "Some matrix cases failed; see complete manifest" }
    }

    private fun verifyParameters(parameters:JsonObject,document:FillMatrixInput.Document) {
        val names=mapOf("angle_deg" to "angle","row_spacing_mm" to "row_spacing_mm",
            "max_stitch_length_mm" to "max_stitch_length_mm","staggers" to "staggers","underlay" to "fill_underlay")
        require(parameters.keys.all { it in names }) { "Unhandled matrix parameter: ${parameters.keys-names.keys}" }
        for(region in document.regions) for((key,value) in parameters) {
            val actual=region.attributes["inkstitch:"+names.getValue(key)] ?: error("Missing prepared SVG parameter $key")
            if(key=="underlay") require(actual.toBooleanStrict()==value.jsonPrimitive.boolean)
            else require(kotlin.math.abs(actual.toDouble()-value.jsonPrimitive.double)<1e-10) { "Prepared SVG parameter mismatch: $key" }
        }
    }

    internal fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
