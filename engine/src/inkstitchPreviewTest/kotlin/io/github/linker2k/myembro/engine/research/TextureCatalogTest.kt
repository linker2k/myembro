// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.research

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.elements.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import org.locationtech.jts.geom.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Research audit, NOT an assertion that every existing port/tile is safe or upstream-equivalent.
 * No stitch algorithm or parameter is changed. Run through tools/research/texture-catalog.sh.
 */
class TextureCatalogTest {
    private val factory = GeometryFactory()
    private val size = 40.0 * PIXELS_PER_MM
    private val thread = PecThreads.SET[5]!!
    private val options = SewoutOptions(hoop = HoopSpec.H100, origin = DesignOrigin.APP_GENERATED)

    @Test fun allBundledTilesAndDefaultFillMethodsAreMeasured() {
        val out = File(System.getenv("TEXTURE_CATALOG_OUTPUT") ?: "/tmp/myembro-texture").apply { mkdirs() }
        val tiles = MeanderFill.availablePatterns()
        val files = File("src/main/resources/inkstitch/tiles").listFiles()!!.filter { it.extension == "svg" }.map { it.nameWithoutExtension }
        assertEquals(files.toSet(), tiles.toSet(), "Every real SVG tile must be covered; index/ORIGIN are not tiles")
        assertEquals(75, tiles.size, "Pinned v3.3.0 data inventory")
        val shapes = linkedMapOf("square" to factory.createPolygon(arrayOf(Coordinate(0.0,0.0), Coordinate(size,0.0),
            Coordinate(size,size), Coordinate(0.0,size), Coordinate(0.0,0.0))),
            "circle" to factory.createPoint(Coordinate(size/2,size/2)).buffer(size/2,64) as Polygon)
        val rows = mutableListOf<JsonObject>()
        for ((shapeName, shape) in shapes) {
            File(out, "$shapeName.svg").writeText(svg(shape, "texture-$shapeName"))
            val methods = listOf<Pair<String,FillMethod>>("tatami_fill" to FillMethod.Tatami,
                "contour_fill" to FillMethod.Contour(), "circular_fill" to FillMethod.Circular(),
                "linear_gradient_fill_no_gradient" to FillMethod.Gradient()) +
                tiles.map { "meander_fill__$it" to FillMethod.Meander(MeanderFillParameters(meander_pattern=it)) } +
                listOf("linear_gradient_fill_two_stops" to FillMethod.Gradient(LinearGradientFill.Gradient(
                    FillPoint(0.0,size/2), FillPoint(size,size/2), listOf(
                        LinearGradientFill.Stop(0.0,"#ed171f"),LinearGradientFill.Stop(1.0,"#0e1f7c")))))
            for ((label, method) in methods) {
                val began = System.nanoTime()
                val id = "${shapeName}__$label"
                var report: SewoutReport? = null
                var pattern: EmbPattern? = null
                var error: String? = null
                var saved = false
                try {
                    val groups = FillStitch(shape,thread,FillParameters(fill_method=method.id,random_seed="texture-$shapeName"),method=method).toStitchGroups(null)
                    require(groups.any { it.stitches.isNotEmpty() }) { "No stitches generated" }
                    // Same shared S1 defaults as the app; the writer applies existing P1/P4 output cleanup.
                    val generated = GeneratedPatternProcessor.fromGroups(groups,collapse_len=3.0,
                        pecJumpTrims=true,lockAtThreadBoundaries=true,omitZeroLengthMotions=true)
                    pattern = PesWriter.prepareForOutput(generated)
                    report = SewoutCheck.check(pattern,options=options)
                    val bytes = PesWriter.write(generated)
                    report = SewoutCheck.checkPes(bytes,options=options)
                    pattern = PesReader.read(bytes)
                    check(report.analysisComplete && !report.hasBlockingIssues) { "Incomplete or blocked PES" }
                    File(out,"$id.pes").writeBytes(bytes)
                    saved = true
                } catch (e: Exception) {
                    error = "${e.javaClass.simpleName}: ${e.message}".replace('\n',' ').take(400)
                    File(out,"$id.pes").delete() // Never leave a stale/pass-looking output for a blocked case.
                }
                val blockers = report?.findingSeverities?.filterValues { it == Severity.BLOCKER }?.keys?.map { it.name }.orEmpty()
                val status = when { saved -> report!!.verdict.name; report != null -> "BLOCK"; else -> "ERROR" }
                thumbnail(File(out,"$id.png"),shape,pattern,"$label / $status")
                val row = buildJsonObject {
                    put("id",id); put("shape",shapeName); put("method",method.id)
                    put("resolved_default_seed","texture-$shapeName")
                    put("tile",(method as? FillMethod.Meander)?.options?.meander_pattern ?: "")
                    put("input",if(label.endsWith("two_stops")) "horizontal red/blue gradient; explicit SVG input, default params" else "solid red; default params")
                    put("status",status); put("pes_written",saved)
                    put("stitches",(report?.statistics?.stitchCount ?: pattern?.stitchCount)?.let(::JsonPrimitive) ?: JsonNull)
                    put("peak_cell_0_5mm",report?.statistics?.peakCellPenetrations?.let(::JsonPrimitive) ?: JsonNull)
                    put("analysis_complete",report?.analysisComplete ?: false)
                    put("blockers",JsonArray(blockers.map(::JsonPrimitive)))
                    put("reason",error ?: if(blockers.isNotEmpty()) blockers.joinToString() else "")
                    put("elapsed_ms",(System.nanoTime()-began)/1_000_000)
                    put("thumbnail","$id.png")
                }
                rows += row
                File(out,"results.json").writeText(Json { prettyPrint=true }.encodeToString(JsonArray.serializer(),JsonArray(rows)))
                println("TEXTURE ${rows.size}/160 $id $status stitches=${row["stitches"]} peak=${row["peak_cell_0_5mm"]}")
            }
        }
        assertEquals(160,rows.size)
        assertEquals(150,rows.count { it["method"]?.jsonPrimitive?.content=="meander_fill" })
        assertTrue(rows.filter { it["method"]?.jsonPrimitive?.content=="tatami_fill" }.all { it["analysis_complete"]==JsonPrimitive(true) })
        assertTrue(rows.all { File(out,it.getValue("thumbnail").jsonPrimitive.content).isFile })
        File(out,"results.md").writeText(buildString {
            appendLine("| Shape | Method / tile | SewoutCheck | Stitches | Peak / 0.5mm cell | Failure reason |")
            appendLine("|---|---|---|---:|---:|---|")
            for (r in rows) appendLine("| ${r.text("shape")} | ${r.text("method")}${r.text("tile").takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty()}${if(r.text("id").endsWith("two_stops")) " (2 stops)" else ""} | ${r.text("status")} | ${r.text("stitches")} | ${r.text("peak_cell_0_5mm")} | ${r.text("reason").replace("|","/")} |")
        })
        for (shapeName in shapes.keys) contactSheet(out,shapeName,rows.filter { it.text("shape")==shapeName })
        println("TEXTURE_CATALOG COMPLETE cases=160 tiles=75 shapes=2 statuses="+rows.groupingBy { it.text("status") }.eachCount())
        for (id in listOf("square__meander_fill__NC5-11a", "circle__meander_fill__NC5-11a", "circle__meander_fill__P3-1")) {
            assertEquals("PASS", rows.single { it.text("id") == id }.text("status"),
                "$id must complete with the original 7/10-edge cutoffs and no cumulative budget")
        }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun svg(polygon: Polygon, id: String): String {
        val path=polygon.coordinates.mapIndexed { i,c -> "${if(i==0) "M" else "L"}${c.x},${c.y}" }.joinToString(" ")+" Z"
        return """<svg xmlns="http://www.w3.org/2000/svg" width="40mm" height="40mm" viewBox="0 0 $size $size"><path id="$id" d="$path" fill="#ed171f"/></svg>"""
    }
    private fun contactSheet(out: File,shape: String,rows: List<JsonObject>) {
        val sheet=BufferedImage(1800,((rows.size+9)/10)*210,BufferedImage.TYPE_INT_RGB)
        val g=sheet.createGraphics();g.color=Color.WHITE;g.fillRect(0,0,sheet.width,sheet.height)
        g.font=Font(Font.SANS_SERIF,Font.PLAIN,10)
        for ((i,r) in rows.withIndex()) {
            val x=(i%10)*180;val y=(i/10)*210
            g.drawImage(ImageIO.read(File(out,r.text("thumbnail"))),x,y+28,180,180,null)
            g.color=if(r.text("status")=="ERROR" || r.text("status")=="BLOCK") Color.RED else Color.BLACK
            g.drawString(r.text("tile").ifEmpty {r.text("id").removePrefix("${shape}__")}.take(29),x+2,y+11)
            g.drawString("${r.text("status")} n=${r.text("stitches")} peak=${r.text("peak_cell_0_5mm")}",x+2,y+23)
        }
        g.dispose();ImageIO.write(sheet,"png",File(out,"${shape}-contact.png"))
    }
    private fun thumbnail(file: File,shape: Polygon,pattern: EmbPattern?,label: String) {
        val image=BufferedImage(480,510,BufferedImage.TYPE_INT_RGB); val g=image.createGraphics()
        g.color=Color.WHITE;g.fillRect(0,0,image.width,image.height)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
        g.color=Color(0x555555); g.drawString(label.take(68),8,16)
        val scale=420.0/400.0
        g.stroke=BasicStroke(0.6f);g.color=Color(0xDDDDDD)
        shape.coordinates.toList().zipWithNext().forEach { (a,b) ->g.drawLine((30+a.x/PIXELS_PER_MM*10*scale).toInt(),(48+a.y/PIXELS_PER_MM*10*scale).toInt(),(30+b.x/PIXELS_PER_MM*10*scale).toInt(),(48+b.y/PIXELS_PER_MM*10*scale).toInt()) }
        g.stroke=BasicStroke(0.9f)
        var previous: Stitch?=null;var color=0
        for(s in pattern?.stitches.orEmpty()) {
            if(s.command==Command.COLOR_CHANGE) color++
            g.color=Color(pattern!!.threadAt(color).color)
            if(s.command==Command.STITCH && previous!=null)g.drawLine((30+previous!!.x*scale).toInt(),(48+previous!!.y*scale).toInt(),(30+s.x*scale).toInt(),(48+s.y*scale).toInt())
            previous=s
        }
        g.dispose();ImageIO.write(image,"png",file)
    }
}
