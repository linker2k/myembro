// SPDX-License-Identifier: GPL-3.0-or-later
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorDrawingPipeline
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.sewout.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Args: repository root, output directory, optional local-only proxy image. */
fun main(args:Array<String>) {
    val root=File(args[0]);val out=File(args[1]).apply { mkdirs() }
    val pesDir=File(out,"pes").apply { mkdirs() }
    File(out,"generation.tsv").printWriter().use { rows ->
        rows.println("input\texpand_mm\tstitches\tsewout\texcluded\tmethods\tfile\terror")
        val inputs=listOf("face","robot").map { Triple(it,File(root,"engine/src/test/resources/image-to-design/$it.png"),30.0) }+
            args.getOrNull(2)?.let { listOf(Triple("proxy",File(it),99.8)) }.orEmpty()
        for((name,path,widthMm) in inputs) {
            val source=ImageIO.read(path)
            val h=if(name=="proxy") source.height*192/source.width else 192
            val image=BufferedImage(192,h,BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(source,0,0,192,h,null);dispose()
            }
            if(name!="proxy") for(y in 105..108) for(x in 92..95) image.setRGB(x,y,0xfffefefe.toInt())
            val preview=ColorDrawingPipeline.trace(192,h,image.getRGB(0,0,192,h,null,0,192),widthMm,4)
            for(expand in listOf(0.0,0.1,0.2,0.3,0.4)) {
                val file=File(pesDir,"$name-$expand.pes")
                try {
                    val result=ColorDrawingPipeline.generate(preview,expandMm=expand)
                    val bytes=PesWriter.write(result.pattern)
                    val report=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
                    file.writeBytes(bytes)
                    rows.println(listOf(name,expand,result.pattern.stitchCount,report.verdict,result.excludedCount,
                        result.regions.groupingBy { it.method }.eachCount(),file.name,report.findings.filter { it.severity==Severity.BLOCKER }.map { it.code }).joinToString("\t"))
                } catch(e:Exception) {
                    rows.println(listOf(name,expand,"","BLOCK","","",file.name,"${e.javaClass.simpleName}: ${e.message}".replace('\n',' ')).joinToString("\t"))
                }
                rows.flush();println("GENERATED $name expand=$expand")
            }
        }
    }
}
