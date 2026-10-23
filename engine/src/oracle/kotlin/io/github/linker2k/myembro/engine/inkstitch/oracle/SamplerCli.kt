// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.oracle

import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.*
import java.io.File
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Reuses actual needle order, one black thread, 5mm row packing and trims between samples. */
object SamplerCli {
    private data class Item(val file: File,val stitches: List<Stitch>) {
        val minX=stitches.minOf { it.x }; val minY=stitches.minOf { it.y }
        val width=stitches.maxOf { it.x }-minX; val height=stitches.maxOf { it.y }-minY
        var x=0;var y=0
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size>=3) { "Usage: SamplerCli OUTPUT_STEM MAX_MM GAP_MM INPUT.pes..." }
        val output=File(args[0]).absoluteFile
        require(output.name.matches(Regex("[A-Za-z0-9_]{1,8}"))) { "Output stem must be an 8.3-safe name (1–8 letters/digits/underscores)" }
        val limit=(args[1].toDouble()*10).roundToInt();val gap=(args[2].toDouble()*10).roundToInt()
        require(limit>0 && gap>=0 && args.size>3)
        val items=args.drop(3).map { path ->
            val file=File(path);val pattern=PatternReader.read(file.readBytes(),file.name)
            val active=pattern.stitches.takeWhile { it.command!=Command.END }
            require(active.any { it.command==Command.STITCH }) { "Empty sample: $path" }
            Item(file,active).also { require(it.width<=limit && it.height<=limit) { "Sample exceeds ${limit/10.0}mm: $path" } }
        }
        val rows=mutableListOf<MutableList<Item>>()
        var row=mutableListOf<Item>().also(rows::add);var width=0
        for (item in items) {
            if (row.isNotEmpty() && width+gap+item.width>limit) { row=mutableListOf<Item>().also(rows::add);width=0 }
            item.x=if (row.isEmpty()) 0 else width+gap
            row+=item;width=item.x+item.width
        }
        val height=rows.sumOf { r -> r.maxOf { it.height } }+gap*(rows.size-1)
        require(height<=limit) { "Packed height ${height/10.0}mm exceeds ${limit/10.0}mm; split the sampler" }
        var y=-height/2
        rows.forEach { r ->
            val rowWidth=r.last().x+r.last().width
            r.forEach { it.x-=rowWidth/2;it.y=y }
            y+=r.maxOf { it.height }+gap
        }
        val stitches=mutableListOf<Stitch>()
        val layout=mutableListOf<JsonObject>()
        items.forEachIndexed { index,item ->
            if (stitches.isNotEmpty()) stitches+=stitches.last().copy(command=Command.TRIM)
            val translated=item.stitches.map { it.copy(x=it.x-item.minX+item.x,y=it.y-item.minY+item.y,
                command=if (it.command in setOf(Command.COLOR_CHANGE,Command.STOP)) Command.TRIM else it.command) }
            stitches+=translated.first().copy(command=Command.JUMP)
            stitches+=translated
            layout+=buildJsonObject {
                put("number",index+1);put("input",item.file.path)
                put("x_mm",item.x/10.0);put("y_mm",item.y/10.0)
                put("width_mm",item.width/10.0);put("height_mm",item.height/10.0)
            }
        }
        stitches+=stitches.last().copy(command=Command.END)
        val pattern=EmbPattern(stitches,listOf(EmbThread(0xFF000000.toInt(),"Black 40wt")))
        val bytes=PesWriter.write(pattern)
        val decoded=PesReader.read(bytes)
        check(decoded.threads.size==1 && decoded.colorChangeCount==0 && decoded.stopCount==0)
        check(decoded.stitches.all { it.x in -limit/2..limit/2 && it.y in -limit/2..limit/2 })
        output.parentFile.mkdirs()
        File(output.path+".PES").writeBytes(bytes)
        val seconds=decoded.stitchCount/400.0*60+decoded.trimCount*10
        val report=buildJsonObject {
            put("schema_version",1);put("sampler",output.name);put("limit_mm",limit/10.0);put("gap_mm",gap/10.0)
            put("stitches",decoded.stitchCount);put("trims",decoded.trimCount);put("colors",decoded.threads.size)
            put("width_mm",decoded.bounds!!.width/10.0);put("height_mm",decoded.bounds!!.height/10.0)
            put("estimated_seconds",seconds)
            put("estimate_basis","stitches / 400 spm * 60 seconds + every encoded TRIM * 10 seconds; excludes setup")
            put("single_thread_conversion","COLOR_CHANGE and STOP become TRIM; needle order and internal trims preserved")
            put("items",JsonArray(layout))
        }
        File(output.path+".json").writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report)+"\n")
        val image=BufferedImage(1100,1180,BufferedImage.TYPE_INT_RGB)
        val g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
        g.color=Color(246,240,230);g.fillRect(0,0,image.width,image.height)
        val scale=900.0/limit
        fun px(x:Int)=100+((x+limit/2)*scale).roundToInt()
        fun py(y:Int)=140+((y+limit/2)*scale).roundToInt()
        g.color=Color(216,203,184);g.stroke=BasicStroke(1f);g.drawRect(100,140,900,900)
        g.color=Color(43,36,32);g.font=Font("SansSerif",Font.PLAIN,26)
        g.drawString("${output.name} | Black 40wt | ${limit/10.0} x ${limit/10.0} mm",45,45)
        g.font=Font("SansSerif",Font.PLAIN,19)
        g.drawString("Numbers are preview labels only. Jumps hidden. Gap ${gap/10.0} mm.",45,80)
        var previous:Stitch?=null
        g.stroke=BasicStroke(1.4f)
        decoded.stitches.forEach { s ->
            if (s.command==Command.STITCH) {
                previous?.let { g.drawLine(px(it.x),py(it.y),px(s.x),py(s.y)) }
                g.fillOval(px(s.x)-1,py(s.y)-1,3,3)
            }
            previous=s
        }
        g.font=Font("SansSerif",Font.BOLD,21)
        items.forEachIndexed { i,item -> g.drawString("${i+1}. ${item.file.nameWithoutExtension}",px(item.x),py(item.y)-16) }
        g.font=Font("SansSerif",Font.PLAIN,20)
        g.drawString("${decoded.stitchCount} stitches | ${decoded.trimCount} trims | estimate ${"%.1f".format(seconds/60)} min",45,1100)
        g.drawString("At 400 spm + 10 sec per encoded trim; setup excluded.",45,1135)
        g.dispose();ImageIO.write(image,"PNG",File(output.path+".png"))
        println(report)
    }
}
