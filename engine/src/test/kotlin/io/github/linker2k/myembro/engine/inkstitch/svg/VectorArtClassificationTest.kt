// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.lettering.GlyphStrokeWidths
import org.locationtech.jts.io.WKTReader
import kotlin.test.*

class VectorArtClassificationTest {
    @Test fun exactCoordinatorBoundariesUseOneClassifier() {
        assertEquals(VectorArt.Method.RUNNING,VectorArt.classifyWidth(Math.nextDown(0.9)))
        assertEquals(VectorArt.Method.SATIN,VectorArt.classifyWidth(0.9))
        assertEquals(VectorArt.Method.SATIN,VectorArt.classifyWidth(6.0))
        assertEquals(VectorArt.Method.TATAMI,VectorArt.classifyWidth(Math.nextUp(6.0)))
        for(value in listOf(-1.0,Double.NaN,Double.POSITIVE_INFINITY))
            assertFailsWith<IllegalArgumentException> { VectorArt.classifyWidth(value) }
    }

    @Test fun geometryMeasurementIsExactlyModelGlyphMedianIncludingHoles() {
        val outer=listOf(0.0 to 0.0,20.0 to 0.0,20.0 to 20.0,0.0 to 20.0,0.0 to 0.0)
        val hole=listOf(8.0 to 8.0,12.0 to 8.0,12.0 to 12.0,8.0 to 12.0,8.0 to 8.0)
        val model=GlyphStrokeWidths.measure(GlyphStrokeWidths.FontOutlines("fixture",1.0,
            listOf(GlyphStrokeWidths.Glyph(0,0,listOf(outer,hole)))),1.0).rows.single()
        val shape=WKTReader().read("POLYGON((0 0,20 0,20 20,0 20,0 0),(8 8,12 8,12 12,8 12,8 8))")
        assertEquals(model.medianWidthMm,VectorArt.medianWidthMm(shape))
        assertTrue(VectorArt.medianWidthMm(WKTReader().read("POLYGON((0 0,20 0,20 20,0 20,0 0))"))>6.0)
    }

    @Test fun logoStrokeRendererUsesTheSameClassifierAsBitmapProbe() {
        for(width in listOf(0.6,1.2,7.0)) {
            // Flat caps make the 10-unit path exactly 10mm long at this render width.
            val art=VectorArt.Art(listOf(0.0,0.0,10.0,10.0),listOf(
                VectorArt.Shape("M 0 0 L 10 0",null,0x2589ad,width)))
            val result=VectorArt.render(art,10.0)
            assertEquals(VectorArt.classifyWidth(width),result.parts.single().method)
            assertEquals(width,result.parts.single().widthMm,1e-9)
        }
    }
    @Test fun canvasLogoStillPassesEncodedPesChecks() {
        val xml=java.io.File("../app/src/main/res/drawable/ic_launcher_foreground.xml").readText()
        val result=VectorArt.render(VectorArt.parseSvg(VectorArt.vectorDrawableToSvg(xml)),60.0)
        val pes=io.github.linker2k.myembro.engine.io.PesWriter.write(result.pattern)
        val viewport=io.github.linker2k.myembro.engine.sewout.PecViewportCheck.check(pes,
            io.github.linker2k.myembro.engine.edit.HoopSpec.H100)
        val report=io.github.linker2k.myembro.engine.sewout.SewoutCheck.checkPes(pes,
            options=io.github.linker2k.myembro.engine.sewout.SewoutOptions(
                origin=io.github.linker2k.myembro.engine.sewout.DesignOrigin.APP_GENERATED))
        assertTrue(viewport.valid,viewport.errors.toString())
        assertFalse(report.hasBlockingIssues,report.findings.toString())
        assertEquals(listOf(VectorArt.Method.SATIN,VectorArt.Method.SATIN,VectorArt.Method.TATAMI),
            result.parts.map { it.method })
    }

}
