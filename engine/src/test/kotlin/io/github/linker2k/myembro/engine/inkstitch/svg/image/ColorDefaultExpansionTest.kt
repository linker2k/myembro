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
        // Synthetic-only goldens from original main 2007a4f algorithms under approved P7 (2026-10-28).
        // P7 can now retain optional outlines previously rejected at 16–30 penetrations.
        // ColorCleanupSweep 30mm/192px inputs with identical bilinear resize + 4×4 white speck.
        val goldens=mapOf(
            "face" to "0c365deb402a3085b656569ed9798dcfbb1937b473cf5bb5fee5aa4d4c3687aa",
            "robot" to "85178d50200dc3dcb4a374f322a290deda5ef50dabf104f6004aeea3ec8dc00d")
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
            assertEquals(golden,hash,"$name explicit zero matches main 2007a4f bytes")
            assertFalse(SewoutCheck.checkPes(actual,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED)).hasBlockingIssues)
            output?.resolve("$name-default.pes")?.writeBytes(actual)
            output?.resolve("$name-zero.pes")?.writeBytes(zero)
        }
    }
}
