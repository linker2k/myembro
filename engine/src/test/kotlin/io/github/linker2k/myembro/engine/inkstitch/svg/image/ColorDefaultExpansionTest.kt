// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.sewout.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.*

class ColorDefaultExpansionTest {
    @Test fun approvedDefaultMatchesPointTwoAndKeepsExtentAndExplicitZeroGolden() {
        assertEquals(0.2,ColorDrawingPipeline.DEFAULT_EXPAND_MM)
        // Synthetic-only snapshots, measured for the user-approved small-region preservation.
        // Inputs retain the same bilinear resize + 4x4 white speck. Default expansion stays 0.2;
        // explicit zero still bypasses buffer/clip, while narrow paint now uses existing running.
        val goldens=mapOf(
            "face" to "f8bf80ba3179b3f74376d1af267066075b6957cf6941308cbc8fc3c89fe980db",
            "robot" to "110b1eaa7cac72ceae2aeb901bc94692488e51ae1ff95620a4afcb61ae433d2f")
        val output=System.getenv("COLOR_DEFAULT_EXPAND_OUT")?.let { File(it).apply { mkdirs() } }
        val observed=linkedMapOf<String,String>()
        for((name,_) in goldens) {
            val source=ImageIO.read(javaClass.getResourceAsStream("/image-to-design/$name.png"))
            val image=BufferedImage(192,192,BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(source,0,0,192,192,null);dispose()
            }
            for(y in 105..108)for(x in 92..95)image.setRGB(x,y,0xfffefefe.toInt())
            val preview=ColorDrawingPipeline.trace(192,192,image.getRGB(0,0,192,192,null,0,192),30.0,4)
            val actual=PesWriter.write(ColorDrawingPipeline.generate(preview).pattern)
            val explicit=PesWriter.write(ColorDrawingPipeline.generate(preview,expandMm=0.2).pattern)
            val internal=PesWriter.write(ColorDrawingPipeline.generate(preview,HoopSpec.H100,true).pattern)
            val zero=PesWriter.write(ColorDrawingPipeline.generate(preview,expandMm=0.0).pattern)
            assertContentEquals(explicit,actual,"$name public default")
            assertContentEquals(explicit,internal,"$name internal default")
            assertEquals(PesReader.read(zero).bounds,PesReader.read(actual).bounds,"$name integer extent")
            val hash=MessageDigest.getInstance("SHA-256").digest(zero).joinToString("") { "%02x".format(it) }
            observed[name]=hash
            println("SMALL_REGION_ZERO_GOLDEN $name $hash")
            assertFalse(SewoutCheck.checkPes(actual,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED)).hasBlockingIssues)
            output?.resolve("$name-default.pes")?.writeBytes(actual)
            output?.resolve("$name-zero.pes")?.writeBytes(zero)
        }
        assertEquals(goldens,observed,"Approved small-region preservation changes these pipeline bytes, never the fill/outline defaults")
    }
}
