// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.tools

import io.github.linker2k.myembro.engine.digitizing.*
import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.serialization.json.*

/** Three controlled public fixtures; no production stitching or writer behavior is changed. */
object AbSewTest {
    @JvmStatic fun main(args: Array<String>) {
        val out=File(args.single()).apply { mkdirs() }
        val black=EmbThread(0xff000000.toInt(),"Black 40wt")
        // Common origin is (1,1)mm for all cases; 10x10mm outline, 1mm running stitches.
        // The initial JUMP is deliberately nonzero in every case, avoiding an origin confound.
        val corners=listOf(Point(10.0,10.0),Point(110.0,10.0),Point(110.0,110.0),Point(10.0,110.0))
        val path=Polyline(corners,true)
        val generated=RunningStitch.generate(listOf(path),RunningSettings(stitchLength=10.0),black)
        check(generated.trimCount==0)
        check(generated.stitches.first().command==Command.JUMP)
        // Same redundant initial JUMP as SamplerCli's prefix + original first JUMP.
        val ours=EmbPattern(listOf(generated.stitches.first())+generated.stitches,generated.threads)
        fun removeZeroMoves(pattern:EmbPattern):EmbPattern {
            var x=0;var y=0
            return EmbPattern(pattern.stitches.filter { s ->
                val keep=s.command !in setOf(Command.JUMP,Command.STITCH) || s.x!=x || s.y!=y
                x=s.x;y=s.y;keep
            },pattern.threads)
        }
        val noZero=removeZeroMoves(ours)
        fun s(x:Int,y:Int,c:Command=Command.STITCH)=Stitch(x,y,c)
        val body=RunningStitch.sample(path,RunningSettings(stitchLength=10.0)).points
            .map { s(it.x.toInt(),it.y.toInt()) }
        check(body.size==41 && body.first()==body.last())
        // Two 0.3mm out-and-return stitches at each boundary, no zero move/needle.
        val ped=EmbPattern(listOf(s(10,10,Command.JUMP),s(13,10),s(10,10))+
            body.drop(1)+listOf(s(10,13),s(10,10),s(10,10,Command.END)),listOf(black))
        check(removeZeroMoves(ped).stitches==ped.stitches)
        check(removeZeroMoves(noZero).stitches==noZero.stitches)
        val cases=listOf(Triple("T1PEDS","0.3mm return locks; no zero moves",ped),
            Triple("T2OURS","Current half_stitch + redundant initial zero jump",ours),
            Triple("T3NOZRO","Same half_stitch; remove only zero needle/jump",noZero))
        val rows=cases.map { (id,label,source) ->
            val pattern=EmbPattern(source.stitches,source.threads,mapOf("name" to id))
            val bytes=PesWriter.write(pattern)
            File(out,"$id.PES").writeBytes(bytes)
            val decoded=PesReader.read(bytes)
            check(decoded.stitches==PecWriter.normalize(pattern).stitches)
            check(decoded.threads.size==1 && decoded.trimCount==0 && decoded.colorChangeCount==0)
            check(decoded.bounds!!.width==100 && decoded.bounds!!.height==100)
            check(decoded.stitchCount<=60 && decoded.stitchCount/400.0*60<=10)
            if(id!="T2OURS")check(removeZeroMoves(decoded).stitches==decoded.stitches)
            draw(decoded,id,label,File(out,"$id.png"))
            buildJsonObject {
                put("file","$id.PES");put("label",label);put("stitches",decoded.stitchCount)
                put("bytes",bytes.size);put("trims",0);put("colors",1);put("width_mm",10);put("height_mm",10)
                put("estimate_seconds_400spm",decoded.stitchCount/400.0*60)
            }
        }
        File(out,"manifest.json").writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),
            buildJsonObject { put("generator","Our RunningStitch -> generated half_stitch / controlled 0.3mm locks -> our PesWriter default PES v1")
                put("common_start_mm","1,1");put("thread","one black 40wt");put("cases",JsonArray(rows)) })+"\n")
        println(rows.joinToString("\n"))
    }
    private fun draw(pattern:EmbPattern,id:String,label:String,file:File) {
        val image=BufferedImage(700,760,BufferedImage.TYPE_INT_RGB)
        val g=image.createGraphics();g.color=Color(246,240,230);g.fillRect(0,0,700,760)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
        g.color=Color(43,36,32);g.font=Font("SansSerif",Font.BOLD,28);g.drawString("$id | 10 x 10 mm | black 40wt",30,45)
        g.font=Font("SansSerif",Font.PLAIN,18);g.drawString(label,30,78)
        fun px(x:Int)=100+(x-10)*5
        fun py(y:Int)=140+(y-10)*5
        var old:Stitch?=null
        g.stroke=BasicStroke(1.4f)
        pattern.stitches.forEach { s ->
            if(s.command==Command.STITCH) {
                old?.let { g.drawLine(px(it.x),py(it.y),px(s.x),py(s.y)) }
                g.fillOval(px(s.x)-3,py(s.y)-3,6,6)
            };old=s
        }
        g.color=Color(181,73,42);g.drawOval(85,125,60,60)
        g.drawString("START / END",110,115)
        g.color=Color(43,36,32);g.drawString("${pattern.stitchCount} stitches | no trim | ${"%.2f".format(pattern.stitchCount/400.0*60)} sec at 400 spm",30,705)
        g.drawString("Dots = needle positions. Labels are preview only.",30,735)
        g.dispose();ImageIO.write(image,"PNG",file)
    }
}
