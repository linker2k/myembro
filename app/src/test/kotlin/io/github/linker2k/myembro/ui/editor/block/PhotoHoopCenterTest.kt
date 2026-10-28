// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.inkstitch.svg.image.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.sewout.*
import java.io.File
import org.junit.Test
import org.junit.Assert.*

class PhotoHoopCenterTest {
    @Test fun central640PixelPhotoAt999HasSafePesAndSafeBlockBadge() {
        // Android's compile bootclasspath omits java.desktop. These local JVM tests can use
        // the installed JDK's ImageIO/AWT through reflection without adding Android dependencies.
        val imageIO=Class.forName("javax.imageio.ImageIO")
        val buffered=Class.forName("java.awt.image.BufferedImage")
        val source=imageIO.getMethod("read",File::class.java).invoke(null,File("../docs/images/launcher-icon-artwork.png"))
        val int=Int::class.javaPrimitiveType!!
        val raster=buffered.getConstructor(int,int,int).newInstance(192,192,buffered.getField("TYPE_INT_ARGB").getInt(null))
        val graphics=buffered.getMethod("createGraphics").invoke(raster)
        val graphics2D=Class.forName("java.awt.Graphics2D")
        val hints=Class.forName("java.awt.RenderingHints")
        graphics2D.getMethod("setRenderingHint",Class.forName("java.awt.RenderingHints\$Key"),Any::class.java)
            .invoke(graphics,hints.getField("KEY_INTERPOLATION").get(null),hints.getField("VALUE_INTERPOLATION_BILINEAR").get(null))
        val drawTypes=arrayOf(Class.forName("java.awt.Image"))+Array(8) { int }+Class.forName("java.awt.image.ImageObserver")
        val graphicsClass=Class.forName("java.awt.Graphics")
        graphicsClass.getMethod("drawImage",*drawTypes).invoke(graphics,source,0,0,192,192,192,192,832,832,null)
        graphicsClass.getMethod("dispose").invoke(graphics)
        val pixels=buffered.getMethod("getRGB",int,int,int,int,IntArray::class.java,int,int)
            .invoke(raster,0,0,192,192,null,0,192) as IntArray
        val corrected=PhotoCorrections.adjust(192,192,pixels,false,5,1f)
        val result=ColorDrawingPipeline.generate(ColorDrawingPipeline.trace(192,192,corrected.pixels,99.9,4))
        val obj=checkNotNull(ShapeObjectFactory.fromPhoto("photo","photo",result,99.9))
        // MainActivity's same insertion/rename branch must not double the factory's offset.
        val inserted=ShapeObjectFactory.centeredForImport(ShapeObjectFactory.renamed(obj,"editor","picture"))
        val project=Project(objects=listOf(inserted))
        assertEquals(result.pattern.stitches,inserted.localPattern().stitches)
        assertEquals(result.pattern.stitchCount,inserted.placedPattern().stitchCount)
        assertEquals(result.pattern.stitchCount,project.flatten().stitchCount)
        assertTrue(SewoutLimits.placedFits(project.hoop,inserted.placedBounds()!!))
        val sourcePes=PesWriter.write(result.pattern)
        val editorPes=PesWriter.write(project.flatten())
        assertArrayEquals(sourcePes,editorPes)
        assertTrue(PecViewportCheck.check(sourcePes,project.hoop).valid)
        assertTrue(PecViewportCheck.check(editorPes,project.hoop).valid)
        val report=SewoutCheck.checkPes(editorPes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
        assertFalse(report.findings.toString(),report.hasBlockingIssues)
        assertNull(BlockApply.check(project))
        assertEquals(io.github.linker2k.myembro.engine.model.Bounds(-500,-500,499,499),inserted.placedBounds())
        println("HOOP_CENTER_REPRO crop=640 width=99.9 source=${result.pattern.bounds} placed=${inserted.placedBounds()} transform=${inserted.transform} stitches=${result.pattern.stitchCount}/${project.flatten().stitchCount} result_PES=PASS editor_PES=PASS BlockApply=PASS")
        System.getenv("HOOP_CENTER_REPRO_OUT")?.let { path ->
            File(path).mkdirs();File(path,"result.pes").writeBytes(sourcePes);File(path,"editor.pes").writeBytes(editorPes)
        }
    }
}
