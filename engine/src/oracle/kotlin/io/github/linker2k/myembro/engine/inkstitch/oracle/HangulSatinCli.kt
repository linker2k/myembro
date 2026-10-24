// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.oracle

import io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace.*
import io.github.linker2k.myembro.engine.inkstitch.elements.utils.StrokeToSatin
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.io.*
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
        require(args.size==3) { "Usage: INPUT_DIRECTORY OUTPUT_STEM STROKE_WIDTH_MM|trace" }
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
        val normalized=paths.map { path ->
            var points=path.points.map { Point(it.x/pixelsPerMm*PIXELS_PER_MM,
                (h-1-it.y)/pixelsPerMm*PIXELS_PER_MM) }
            if(!path.open) points=points+points.first()
            points
        }
        val pathReport=buildJsonObject {
            put("input",metadata);put("path_count",paths.size)
            put("curve_contract","AutoTrace original pixel paths as explicit LineStrings; no spline smoothing or inferred width")
            put("paths",buildJsonArray { paths.forEach { p -> add(buildJsonObject {
                put("open",p.open);put("points",buildJsonArray { p.points.forEach { q -> add(buildJsonArray { add(q.x);add(q.y) }) } })
            }) } })
        }
        File(stem.path+"-centerline.json").writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),pathReport))
        fun preview(pattern:EmbPattern?,label:String,file:File,offsetX:Double=0.0,offsetY:Double=0.0) {
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
        preview(null,"centerline only; width not selected",File(stem.path+"-centerline.png"))
        if(args[2]=="trace") { println("CENTERLINE paths=${paths.size}; width required before satin/PES");return }
        val widthMm=args[2].toDouble();require(widthMm.isFinite() && widthMm>0)
        val groups=mutableListOf<StitchGroup>();val black=EmbThread(0xff000000.toInt(),"Black 40wt")
        for((index,path) in normalized.withIndex()) {
            val started=if(!paths[index].open) StrokeToSatin.setFirstNode(path,widthMm*PIXELS_PER_MM) else path
            val section=checkNotNull(StrokeToSatin.convertPathToSatin(started,widthMm*PIXELS_PER_MM)) { "No satin section for path $index" }
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
            put("input",metadata);put("stroke_width_mm",widthMm);put("curve_contract",pathReport.getValue("curve_contract"))
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
