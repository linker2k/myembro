// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.oracle

import io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace.*
import io.github.linker2k.myembro.engine.inkstitch.elements.utils.StrokeToSatin
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.geometry.Affine2D
import java.util.Locale
import java.security.MessageDigest
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.sewout.PecViewportCheck
import kotlinx.serialization.json.*
import java.io.File
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.*

/** Explicit sample runner, never an app/default style choice. Width must be provided by the caller. */
object HangulSatinCli {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size in 3..4) { "Usage: INPUT_DIRECTORY OUTPUT_STEM STROKE_WIDTH_MM|trace [no-preview]" }
        val input=File(args[0]);val stem=File(args[1]);stem.parentFile.mkdirs()
        val metadata=Json.parseToJsonElement(File(input,"input.json").readText()).jsonObject
        val pixelsPerMm=metadata.getValue("pixels_per_mm").jsonPrimitive.double
        val tokens=File(input,"input.pgm").readText().trim().split(Regex("\\s+"))
        require(tokens[0]=="P2" && tokens[3]=="255")
        val w=tokens[1].toInt();val h=tokens[2].toInt()
        val pixels=tokens.drop(4).map { it.toInt().toByte() }.toByteArray()
        val thin=AutoTraceThinning.thin(w,h,pixels)
        val paths=AutoTraceCenterline.trace(w,h,thin)
        check(paths.isNotEmpty())
        val fitFile=File(input,"fit.json")
        val originalFit=fitFile.takeIf { it.exists() }?.let { Json.parseToJsonElement(it.readText()).jsonObject }
        val kotlinFit=AutoTraceFit.fit(paths)
        val fit=buildJsonObject {
            put("schema",1); put("width",w); put("height",h)
            put("paths",buildJsonArray { kotlinFit.forEach { path -> add(buildJsonObject {
                put("open",path.open); put("clockwise",path.clockwise)
                put("segments",buildJsonArray { path.segments.forEach { segment -> add(buildJsonObject {
                    put("degree",segment.degree)
                    put("v",buildJsonArray { segment.v.forEach { p -> add(buildJsonArray { add(p.x);add(p.y);add(p.z) }) } })
                }) } })
            }) } })
        }
        // Optional native reference only verifies the port; never supplies the production coordinates.
        if(originalFit!=null) {
            val expected=originalFit.getValue("paths").jsonArray
            check(expected.size==kotlinFit.size) { "Original fit path count mismatch" }
            var controls=0
            expected.forEachIndexed { i,element ->
                val path=element.jsonObject; val actual=kotlinFit[i]
                check(path.getValue("open").jsonPrimitive.boolean==actual.open)
                check(path.getValue("clockwise").jsonPrimitive.boolean==actual.clockwise)
                val segments=path.getValue("segments").jsonArray
                check(segments.size==actual.segments.size) { "Original fit segment count mismatch at path $i" }
                segments.forEachIndexed { j,value ->
                    val segment=value.jsonObject; val output=actual.segments[j]
                    check(segment.getValue("degree").jsonPrimitive.int==output.degree)
                    segment.getValue("v").jsonArray.forEachIndexed { k,coords ->
                        val e=coords.jsonArray.map { it.jsonPrimitive.float }
                        val a=output.v[k]
                        check(e[0].toBits()==a.x.toBits() && e[1].toBits()==a.y.toBits() && e[2].toBits()==a.z.toBits()) {
                            "Original fit control mismatch path=$i segment=$j control=$k expected=$e actual=$a"
                        }
                        controls++
                    }
                }
            }
            println("AUTOTRACE_LOCAL_FIT_EXACT paths=${kotlinFit.size} segments=${kotlinFit.sumOf { it.segments.size }} controls=$controls floatBits=exact")
        }
        val fitProvenance=if(originalFit!=null) {
            fun sha(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val manifest=Json.parseToJsonElement(File(input,"fit-provenance.json").readText()).jsonObject
            check(manifest.getValue("commit").jsonPrimitive.content=="1a232b27818a46a05cd25edec09e2f77c4e9ac01")
            check(manifest.getValue("input_sha256").jsonPrimitive.content==sha(File(input,"input.pgm"))) { "Stale fit input" }
            check(manifest.getValue("output_sha256").jsonPrimitive.content==sha(fitFile)) { "Changed fit output" }
            check(manifest.getValue("repeat_exact").jsonPrimitive.boolean)
            manifest
        } else null
        val fitPaths=fit.getValue("paths").jsonArray
        val normalized=fitPaths.map { element ->
            val segments=element.jsonObject.getValue("segments").jsonArray.map { it.jsonObject }
            fun value(segment:JsonObject,index:Int,axis:Int):String {
                val coord=segment.getValue("v").jsonArray[index].jsonArray[axis].jsonPrimitive.float
                // output-svg.c writes y=height-y and C %g (six significant digits).
                val number=if(axis==1) (h.toFloat()-coord).toFloat() else coord
                return String.format(Locale.ROOT,"%.6g",number)
            }
            val d=buildString {
                val first=segments.first()
                append("M ${value(first,0,0)} ${value(first,0,1)} ")
                for(segment in segments) when(segment.getValue("degree").jsonPrimitive.int) {
                    1 -> append("L ${value(segment,3,0)} ${value(segment,3,1)} ")
                    3 -> append("C ${value(segment,1,0)} ${value(segment,1,1)} ${value(segment,2,0)} ${value(segment,2,1)} ${value(segment,3,0)} ${value(segment,3,1)} ")
                    else -> error("Unsupported original AutoTrace spline degree")
                }
            }
            val scale=PIXELS_PER_MM/pixelsPerMm
            SvgPath.flatten(d,Affine2D.scale(scale,scale),tolerance=0.1).single().points.map { Point(it.x,it.y) }
        }
        val curveContract="Kotlin AutoTrace 0.31.10 fit defaults; original output-svg %g serialization; existing SvgPath flatten 0.1 SVG px"
        val pathReport=buildJsonObject {
            put("input",metadata);put("path_count",paths.size)
            put("curve_contract",curveContract)
            put("converter_paths_svg_px",buildJsonArray { normalized.forEach { path -> add(buildJsonArray {
                path.forEach { q -> add(buildJsonArray { add(q.x);add(q.y) }) }
            }) } })
            put("original_pixel_paths",buildJsonArray { paths.forEach { p -> add(buildJsonObject {
                put("open",p.open);put("points",buildJsonArray { p.points.forEach { q -> add(buildJsonArray { add(q.x);add(q.y) }) } })
            }) } })
        }
        File(stem.path+"-centerline.json").writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),pathReport))
        fun preview(pattern:EmbPattern?,label:String,file:File,offsetX:Double=0.0,offsetY:Double=0.0) {
            if(args.getOrNull(3)=="no-preview") return
            val image=BufferedImage(1300,800,BufferedImage.TYPE_INT_RGB)
            val g=image.createGraphics();g.color=Color.WHITE;g.fillRect(0,0,image.width,image.height)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
            g.color=Color.BLACK;g.drawString("Noto Sans KR 400 / 15mm / $label",40,35)
            val physicalScale=30.0
            fun screen(x:Double)=70+x*physicalScale
            fun sy(y:Double)=100+y*physicalScale
            g.stroke=BasicStroke(1f);g.color=Color.GRAY
            for(path in normalized) for((a,b) in path.zipWithNext())
                g.drawLine(screen(a.x/PIXELS_PER_MM).roundToInt(),sy(a.y/PIXELS_PER_MM).roundToInt(),screen(b.x/PIXELS_PER_MM).roundToInt(),sy(b.y/PIXELS_PER_MM).roundToInt())
            if(pattern!=null) {
                g.color=Color(40,70,120);g.stroke=BasicStroke(1.4f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND)
                for((a,b) in pattern.stitches.zipWithNext()) if(b.command==Command.STITCH)
                    g.drawLine(screen(a.x/10.0+offsetX).roundToInt(),sy(a.y/10.0+offsetY).roundToInt(),screen(b.x/10.0+offsetX).roundToInt(),sy(b.y/10.0+offsetY).roundToInt())
            }
            g.dispose();ImageIO.write(image,"png",file)
        }
        preview(null,"Kotlin AutoTrace fitted centerline",File(stem.path+"-centerline.png"))
        if(args[2]=="trace") { println("TRACE_ONLY fitted=Kotlin paths=${paths.size}; no PES export requested");return }
        val widthMm=args[2].toDouble();require(widthMm.isFinite() && widthMm>0)
        val groups=mutableListOf<StitchGroup>();val black=EmbThread(0xff000000.toInt(),"Black 40wt")
        for(path in normalized) {
            val closed=path.first()==path.last()
            val started=if(closed) StrokeToSatin.setFirstNode(path,widthMm*PIXELS_PER_MM) else path
            val section=checkNotNull(StrokeToSatin.convertPathToSatin(started,widthMm*PIXELS_PER_MM)) { "No satin section for fitted path" }
            val filtered=StrokeToSatin.filteredRungs(section)
            groups+=filtered.column(color=black).toStitchGroups(groups.lastOrNull()?.stitches?.lastOrNull())
        }
        val generated=GeneratedPatternProcessor.fromGroups(groups,pecJumpTrims=true,
            lockAtThreadBoundaries=true,omitZeroLengthMotions=true)
        val candidate=EmbPattern(generated.stitches,generated.threads,generated.metadata+mapOf("name" to stem.name))
        // Default writer applies mandatory P1-P8. Never weaken the gate to manufacture a trial file.
        val bytes=PesWriter.write(candidate,PesWriteSettings(hoop=HoopSpec.H100))
        val decoded=PesReader.read(bytes);val viewport=PecViewportCheck.check(bytes,HoopSpec.H100)
        check(viewport.valid && viewport.minX==0 && viewport.minY==0)
        check(decoded.threads.size==1 && decoded.colorChangeCount==0)
        check(viewport.maxX<=600 && viewport.maxY<=600) { "Trial exceeds 60x60mm" }
        var x=0;var y=0;var zeroNeedles=0;var zeroJumps=0;var maxLength=0.0
        for(s in decoded.stitches) if(s.command in setOf(Command.STITCH,Command.JUMP)) {
            val length=hypot((s.x-x).toDouble(),(s.y-y).toDouble())/10
            if(length==0.0) { if(s.command==Command.STITCH)zeroNeedles++ else zeroJumps++ }
            if(s.command==Command.STITCH)maxLength=max(maxLength,length)
            x=s.x;y=s.y
        }
        check(zeroNeedles==0 && zeroJumps==0)
        val destination=File(stem.path+".PES");check(!destination.exists()) { "Use a new sewout filename: $destination" }
        destination.writeBytes(bytes)
        preview(decoded,"satin width ${widthMm}mm (decoded PES)",File(stem.path+".png"),
            candidate.bounds!!.minX/10.0,candidate.bounds!!.minY/10.0)
        val report=buildJsonObject {
            put("input",metadata);put("fit_provenance",fitProvenance ?: buildJsonObject { put("implementation","Kotlin"); put("commit","1a232b27818a46a05cd25edec09e2f77c4e9ac01") });put("stroke_width_mm",widthMm);put("curve_contract",pathReport.getValue("curve_contract"))
            put("paths",paths.size);put("stitches",decoded.stitchCount);put("trims",decoded.trimCount);put("colors",decoded.threads.size)
            put("min_x",viewport.minX);put("min_y",viewport.minY);put("max_x",viewport.maxX);put("max_y",viewport.maxY)
            put("header_width",viewport.headerWidth);put("header_height",viewport.headerHeight)
            put("zero_needles",zeroNeedles);put("zero_jumps",zeroJumps);put("max_stitch_mm",maxLength)
            put("default_writer_P1_P8","PASS");put("independent_gate","PENDING")
        }
        File(stem.path+".json").writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report)+"\n")
        println(report)
    }
}
