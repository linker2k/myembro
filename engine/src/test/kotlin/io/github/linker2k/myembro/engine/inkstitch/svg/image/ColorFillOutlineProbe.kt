// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.sewout.*
import java.io.File
import javax.imageio.ImageIO
import java.awt.Color
import java.awt.image.BufferedImage

/** Explicit local probe; accepts external images but never copies them into the repository. */
object ColorFillOutlineProbe {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size>=2) { "OUTPUT_DIRECTORY INPUT_PNG..." }
        val out=File(args[0]).apply { mkdirs() }
        val rows=mutableListOf("input\twidth_mm\tbefore\tafter\tincrease_percent\tskipped_outlines\texcluded_regions\tsewout\tviewport")
        val sheet=BufferedImage(650*(args.size-1),650,BufferedImage.TYPE_INT_RGB)
        val g=sheet.createGraphics();g.color=Color.WHITE;g.fillRect(0,0,sheet.width,sheet.height)
        args.drop(1).forEachIndexed { index,path ->
            val source=ImageIO.read(File(path))
            val edge=192;val h=(source.height.toDouble()*edge/source.width).toInt().coerceAtLeast(1)
            require(h<=512)
            val image=BufferedImage(edge,h,BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().apply { drawImage(source,0,0,edge,h,null);dispose() }
            val preview=ColorDrawingPipeline.trace(edge,h,image.getRGB(0,0,edge,h,null,0,edge),60.0,4)
            val before=ColorDrawingPipeline.generate(preview,HoopSpec.H100,true,{},false)
            val after=ColorDrawingPipeline.generate(preview)
            val bytes=PesWriter.write(after.pattern)
            val name=if(index==0) "repository" else "local-proxy-$index"
            File(out,"$name.pes").writeBytes(bytes)
            val report=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
            val viewport=PecViewportCheck.check(bytes,HoopSpec.H100)
            val increase=100.0*(after.pattern.stitchCount-before.pattern.stitchCount)/before.pattern.stitchCount
            val row=listOf(name,60.0,before.pattern.stitchCount,after.pattern.stitchCount,"%.2f".format(java.util.Locale.ROOT,increase),after.skippedOutlineCount,after.excludedCount,
                if(report.hasBlockingIssues) "BLOCK" else "PASS",viewport.valid).joinToString("\t")
            rows+=row;println(row)
            val pattern=PesReader.read(bytes)
            val bounds=pattern.bounds!!;val scale=540.0/maxOf(bounds.width,bounds.height,1)
            for(block in pattern.colorBlocks) {
                g.color=Color(block.thread.color)
                for(i in maxOf(1,block.start) until block.end) {
                    val a=pattern.stitches[i-1];val b=pattern.stitches[i]
                    if(b.command==Command.STITCH) g.drawLine(index*650+50+(a.x*scale).toInt(),60+(a.y*scale).toInt(),index*650+50+(b.x*scale).toInt(),60+(b.y*scale).toInt())
                }
            }
            g.color=Color.BLACK;g.drawString("$name: ${after.pattern.stitchCount} stitches, skipped ${after.skippedOutlineCount}",index*650+30,30)
        }
        g.dispose();ImageIO.write(sheet,"png",File(out,"overview.png"))
        File(out,"results.tsv").writeText(rows.joinToString("\n")+"\n")
    }
}
