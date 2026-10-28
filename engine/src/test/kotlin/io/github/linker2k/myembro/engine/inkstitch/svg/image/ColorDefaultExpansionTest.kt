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
        // Synthetic-only goldens: baseline 46505dc with only the approved outline repeats 1 -> 0.
        // Independently rebuilt baseline + single-parameter overlay; /tmp/myembro-p7-freq/one-pass.
        // Expansion 0 still bypasses buffer/clip; the outline policy intentionally changes PES bytes.
        // ColorCleanupSweep 30mm/192px inputs with identical bilinear resize + 4×4 white speck.
        val goldens=mapOf(
            "face" to "826ead35421936ddd7688a0a4840f3364c4889f550ed2b6e6704bfbf356bdc66",
            "robot" to "77bd7e3116c23835c92861fdd69cac233cd01fe1e1ea8c84560b0d846f45832a")
        val output=System.getenv("COLOR_DEFAULT_EXPAND_OUT")?.let { File(it).apply { mkdirs() } }
        for((name,golden) in goldens) {
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
            assertEquals(golden,hash,"$name explicit zero matches approved one-pass baseline bytes")
            assertFalse(SewoutCheck.checkPes(actual,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED)).hasBlockingIssues)
            output?.resolve("$name-default.pes")?.writeBytes(actual)
            output?.resolve("$name-zero.pes")?.writeBytes(zero)
        }
    }
}
