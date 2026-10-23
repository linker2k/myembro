// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch

import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinColumn
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParametersCodec
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.PathEndPoints
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.svg.ViewBoxTransform
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlinx.serialization.json.*
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.min
import kotlin.test.Test

/** Frozen public SVG data only. The official release executable is never invoked by this task. */
class SatinOracleReproductionTest {
    private val ns="http://inkstitch.org/namespace"
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){ "%02x".format(it.toInt() and 255) }
    @Test fun reproduce() {
        val root=System.getProperty("myembro.oracleRoot")?.let(::File) ?: return
        val out=File(System.getProperty("myembro.portOutput")).apply { mkdirs() }
        val matrixFile=System.getProperty("myembro.matrixFile")?.takeIf(String::isNotBlank)?.let(::File)
        val legacy=matrixFile==null
        val document=if(legacy) Json.parseToJsonElement(root.resolve("out/oracle-manifest.json").readText()).jsonObject
            else Json.parseToJsonElement(matrixFile!!.readText()).jsonObject
        val matrixHash=if(legacy) document.getValue("matrix_sha256").jsonPrimitive.content else sha(matrixFile!!.readBytes())
        if(legacy) {
            val frozen=Json.parseToJsonElement(root.resolve("oracle-freeze.json").readText()).jsonObject
            check(matrixHash==frozen.getValue("matrix_sha256").jsonPrimitive.content)
        }
        val records=document.getValue("cases").jsonArray.map { it.jsonObject }.filter { it.getValue("feature").jsonPrimitive.content=="satin_column" }
        fun input(case:JsonObject)=if(legacy) root.resolve("inputs/${case.getValue("id").jsonPrimitive.content}.svg")
            else File(case.getValue("input").jsonPrimitive.content)
        fun expectedHash(case:JsonObject)=if(legacy) case.getValue("mapping").jsonObject.getValue("materialized_sha256").jsonPrimitive.content
            else case.getValue("input_sha256").jsonPrimitive.content
        val commit=ProcessBuilder("git","rev-parse","HEAD").start().inputStream.bufferedReader().readText().trim()
        val dirty=ProcessBuilder("git","status","--porcelain").start().inputStream.bufferedReader().readText().isNotBlank()
        val results=records.map { case ->
            val id=case.getValue("id").jsonPrimitive.content
            val input=input(case)
            val digest=sha(input.readBytes())
            check(digest==expectedHash(case)) { "Input changed: $id" }
            buildJsonObject {
                put("id",id);put("feature","satin_column");put("input",input.absolutePath);put("input_sha256",digest)
                try {
                    val bytes=PesWriter.write(generate(input))
                    val output=out.resolve("$id.pes").apply { writeBytes(bytes) }
                    put("status","GENERATED");put("path",output.absolutePath);put("sha256",sha(bytes))
                } catch(error:Exception) {
                    put("status",if(case["expected"]?.jsonPrimitive?.content in setOf("REJECT","REJECTED"))"REJECTED" else "ERROR")
                    put("error",error.toString())
                }
            }
        }
        out.resolve("inputs.txt").writeText(records.joinToString("\n",postfix="\n") { input(it).absolutePath })
        out.resolve("manifest.json").writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("matrix_sha256",matrixHash);put("commit",commit);put("working_tree_dirty",dirty)
            put("branch","codex/letter");put("stage","S2");put("source",if(legacy)"frozen-legacy-oracle" else matrixFile!!.absolutePath)
            put("upstream_commit","b0edd96311ece82ee48816dc93466274b12c2a9c")
            put("owner","embro-letter");put("cases",JsonArray(results))
        }))
        println("S2 outputs: ${out.absolutePath}; ${results.groupingBy { it.getValue("status").jsonPrimitive.content }.eachCount()}")
    }
    private fun generate(input:File):io.github.linker2k.myembro.engine.model.EmbPattern {
        val factory=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true;setFeature("http://apache.org/xml/features/disallow-doctype-decl",true) }
        val doc=factory.newDocumentBuilder().parse(input)
        val root=doc.documentElement
        fun lengthPx(value:String):Double {
            val v=Regex("([-+0-9.eE]+)([a-z]*)").matchEntire(value.trim()) ?: error("Unsupported length $value")
            val n=v.groupValues[1].toDouble()
            return when(v.groupValues[2]) { "mm"->Units.mmToPixels(n);"cm"->Units.mmToPixels(n*10);"in"->n*96;"","px"->n;else->error("Unsupported SVG unit") }
        }
        val box=root.getAttribute("viewBox").trim().split(Regex("[\\s,]+")).map(String::toDouble)
        require(box.size==4 && box[2]>0 && box[3]>0)
        val width=lengthPx(root.getAttribute("width"));val height=lengthPx(root.getAttribute("height"))
        val viewport=ViewBoxTransform.fromDimensions(width,height,box,root.getAttribute("preserveAspectRatio").ifBlank { "xMidYMid meet" })
        val paths=doc.getElementsByTagNameNS("http://www.w3.org/2000/svg","path")
        require(paths.length==1) { "Satin matrix fixture must contain one path" }
        val element=paths.item(0) as Element
        val transform=viewport*Affine2D.parse(element.getAttribute("transform"))
        val d=element.getAttribute("d")
        val flattened=SvgPath.flatten(d,transform,tolerance=.1).map { line->line.points.map { Point(it.x,it.y) } }
        val rawNodes=d.split(Regex("(?=[Mm])")).filter(String::isNotBlank).map { part ->
            PathEndPoints.parse(part).map { p->transform.apply(io.github.linker2k.myembro.engine.geometry.Point(p.x,p.y)).let { Point(it.x,it.y) } }
        }
        val attributes=(0 until element.attributes.length).map { element.attributes.item(it) }.filter { it.namespaceURI==ns }.associate { it.localName to it.nodeValue }
        val parameters=SatinParametersCodec.decode(attributes + ("random_seed" to (attributes["random_seed"] ?: element.getAttribute("id"))))
        val rgb=element.getAttribute("stroke").removePrefix("#").let { if(it.length==3)it.flatMap { c->listOf(c,c) }.joinToString("") else it }.toLong(16).toInt() or 0xFF000000.toInt()
        val defaults=LockStitches.fromParameters(LockStitchParameters())
        val groups=SatinColumn.fromSubpaths(flattened,parameters,rawNodes,EmbThread(rgb),lockStart=defaults.start,lockEnd=defaults.end).toStitchGroups(null)
        return GeneratedPatternProcessor.fromGroups(groups,collapse_len=3.0,min_stitch_len=.1)
    }
}
