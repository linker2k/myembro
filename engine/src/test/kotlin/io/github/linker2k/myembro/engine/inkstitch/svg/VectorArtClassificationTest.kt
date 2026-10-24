// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg

import kotlin.test.*

class VectorArtClassificationTest {
    @Test fun maximumOverridesNarrowMedianAndBoundsAreInclusive() {
        fun c(median:Double,maximum:Double)=StitchTypeRule.classify(StitchTypeRule.Widths(maximum,median))
        assertEquals(StitchTypeRule.Type.TATAMI,c(4.121,7.778))
        assertEquals(StitchTypeRule.Type.TATAMI,c(0.5,8.0))
        assertEquals(StitchTypeRule.Type.SATIN,c(1.0,6.0)) // minimum 1.0mm: Ink/Stitch min_satin_stroke_width
        assertEquals(StitchTypeRule.Type.RUNNING,c(Math.nextDown(1.0),6.0))
        assertEquals(StitchTypeRule.Type.TATAMI,c(1.0,Math.nextUp(6.0)))
    }

    @Test fun logoStrokeRendererUsesTheSameClassifierAsBitmapProbe() {
        for(width in listOf(0.6,1.2,7.0)) {
            // Flat caps make the 10-unit path exactly 10mm long at this render width.
            val art=VectorArt.Art(listOf(0.0,0.0,10.0,10.0),listOf(
                VectorArt.Shape("M 0 0 L 10 0",null,0x2589ad,width)))
            val result=VectorArt.render(art,10.0)
            assertEquals(StitchTypeRule.classify(StitchTypeRule.Widths(width,width)),result.parts.single().method)
            assertEquals(width,result.parts.single().widths!!.maxMm,0.01)
        }
    }
    @Test fun canvasLogoStillPassesEncodedPesChecks() {
        val xml=java.io.File("../app/src/main/res/drawable/ic_launcher_foreground.xml").readText()
        val result=VectorArt.render(VectorArt.parseSvg(VectorArt.vectorDrawableToSvg(xml)),60.0)
        val pes=io.github.linker2k.myembro.engine.io.PesWriter.write(result.pattern)
        val table=buildString {
            appendLine("shape,role,median_mm,maximum_mm,type")
            result.parts.forEach { p -> appendLine("${p.shape},${p.role},${p.widths!!.medianMm},${p.widths!!.maxMm},${p.method}") }
        }
        println("LOGO_WIDTHS\n$table")
        System.getenv("IMAGE_PROBE_LOGO_OUT")?.let { directory ->
            val out=java.io.File(directory).apply { mkdirs() }
            java.io.File(out,"logo.pes").writeBytes(pes)
            java.io.File(out,"logo-widths.csv").writeText(table)
        }
        val viewport=io.github.linker2k.myembro.engine.sewout.PecViewportCheck.check(pes,
            io.github.linker2k.myembro.engine.edit.HoopSpec.H100)
        val report=io.github.linker2k.myembro.engine.sewout.SewoutCheck.checkPes(pes,
            options=io.github.linker2k.myembro.engine.sewout.SewoutOptions(
                origin=io.github.linker2k.myembro.engine.sewout.DesignOrigin.APP_GENERATED))
        assertTrue(viewport.valid,viewport.errors.toString())
        assertFalse(report.hasBlockingIssues,report.findings.toString())
        assertEquals(listOf(StitchTypeRule.Type.SATIN,StitchTypeRule.Type.SATIN,StitchTypeRule.Type.TATAMI),
            result.parts.map { it.method })
    }

}
