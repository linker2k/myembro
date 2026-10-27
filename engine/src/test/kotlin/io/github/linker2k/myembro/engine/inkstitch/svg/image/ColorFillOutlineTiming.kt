// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.sewout.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Optional comparison harness. Historical implementations are read from git into temporary test
 * sources for measurement only; this class has no compile-time dependency on those sources.
 * BACKEND_SUFFIX OUTPUT_DIRECTORY WIDTH_MM INPUT_PNG. Empty suffix selects the current engine.
 */
object ColorFillOutlineTiming {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==4)
        val suffix=args[0];val out=File(args[1]).apply { mkdirs() };val widthMm=args[2].toDouble()
        val source=ImageIO.read(File(args[3]))
        val edge=PhotoPreparationPolicy.photoRasterSize(1,widthMm,widthMm)
        val height=(source.height.toDouble()*edge/source.width).toInt().coerceAtLeast(1)
        val image=BufferedImage(edge,height,BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply { drawImage(source,0,0,edge,height,null);dispose() }
        val pixels=image.getRGB(0,0,edge,height,null,0,edge)
        val clazz=Class.forName("${ColorFillOutlineTiming::class.java.packageName}.ColorDrawingPipeline$suffix")
        val instance=clazz.getField("INSTANCE").get(null)
        val trace=clazz.methods.single { it.name=="trace" }
        val generate=clazz.methods.single { it.name=="generate" && it.parameterCount==3 }
        val checkpoint:(String)->Unit={}
        val start=System.nanoTime()
        val preview=trace.invoke(instance,edge,height,pixels,widthMm,4,ColorDrawingPipeline.MINIMUM_REGION_AREA_MM2,128,false,MyThreads(),checkpoint)
        val traced=System.nanoTime()
        val result=generate.invoke(instance,preview,HoopSpec.H100,checkpoint)
        val finished=System.nanoTime()
        val pattern=result.javaClass.getMethod("getPattern").invoke(result) as EmbPattern
        val skipped=result.javaClass.methods.firstOrNull { it.name=="getSkippedOutlineCount" }?.invoke(result) ?: 0
        val regions=(result.javaClass.getMethod("getRegions").invoke(result) as List<*>).size
        val bytes=PesWriter.write(pattern)
        val report=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
        val viewport=PecViewportCheck.check(bytes,HoopSpec.H100)
        check(!report.hasBlockingIssues && viewport.valid)
        val name=File(args[3]).nameWithoutExtension+"-"+suffix.ifEmpty { "New" }
        File(out,"$name.pes").writeBytes(bytes)
        val row=listOf(name,widthMm,edge,regions,pattern.stitchCount,skipped,pattern.metadata["myembro.fill_outline_checks"] ?: "n/a",
            (traced-start)/1e6,(finished-traced)/1e6,(finished-start)/1e6,"PASS").joinToString("\t")
        File(out,"$name.tsv").writeText(row+"\n");println(row)
    }
}
