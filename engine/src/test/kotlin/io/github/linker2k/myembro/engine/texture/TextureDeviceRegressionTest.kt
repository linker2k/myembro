// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.texture

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorRegionCleanup
import io.github.linker2k.myembro.engine.io.PecThreads
import io.github.linker2k.myembro.engine.sewout.PecViewportCheck
import java.io.File
import kotlin.test.*

class TextureDeviceRegressionTest {
    private val black=PecThreads.forIndex(20)
    private val lavender=PecThreads.forIndex(45)
    private fun picture(edge:Int, background:Int):IntArray = TextureSyntheticFixtures.pixels("circle",edge)
        .map { if(it == -1) background else it }.toIntArray()

    @Test fun widthAndHeightUseNative9989991000Boundaries() {
        for (n in listOf(998,999)) {
            assertEquals(n,ThreadTextureAdapter.frameUnits(n/10.0,222,222,HoopSpec.H100).first)
            assertEquals(n,ThreadTextureAdapter.frameUnits(99.9,999,n,HoopSpec.H100).second)
        }
        assertFailsWith<IllegalArgumentException> { ThreadTextureAdapter.frameUnits(100.0,222,222,HoopSpec.H100) }
        assertFailsWith<IllegalArgumentException> { ThreadTextureAdapter.frameUnits(99.9,999,1000,HoopSpec.H100) }
        val noisy=99.9*222/222
        assertTrue(noisy*10>999.0) // Reproduces the device's spurious decimal comparison failure.
        assertEquals(999 to 999,ThreadTextureAdapter.frameUnits(noisy,222,222,HoopSpec.H100))
    }
    @Test fun fine999FrameGeneratesAndExportsWithinIntegerHoop() {
        val mm=99.9
        val edge=TextureRasterPolicy.edge(mm,TextureRasterPolicy.pitchForDetail(2))
        assertEquals(222,edge)
        val pixels=picture(edge,lavender.color)
        val palette=ThreadTextureAdapter.brotherPalette(pixels,2)
        val result=ThreadTextureAdapter.generate(edge,edge,pixels,mm,palette,detail=2)
        assertEquals(SketchLines.render(edge,edge,pixels).lines,result.sketch.lines)
        assertEquals(lavender,result.backgroundThread)
        assertFalse(result.sewout.hasBlockingIssues,result.sewout.findings.toString())
        assertTrue(PecViewportCheck.check(result.pes(),HoopSpec.H100).valid)
    }
    @Test fun sharedBorderModeExcludesOpaqueBackgroundButTransparencyDisablesIt() {
        val edge=88;val pixels=picture(edge,lavender.color)
        val opaque=ColorRegionCleanup.selectBackground(edge,edge,pixels,2)
        assertEquals(lavender.color and 0xffffff,opaque.backgroundRgb)
        assertTrue(opaque.foreground.isNotEmpty())
        assertTrue(opaque.foreground.all { pixels[it]==black.color })
        val transparent=picture(edge,lavender.color and 0xffffff)
        val selection=ColorRegionCleanup.selectBackground(edge,edge,transparent,2)
        assertNull(selection.backgroundRgb)
        assertEquals(transparent.indices.filter { transparent[it] ushr 24 != 0 },selection.foreground)
    }
    @Test fun removedBackgroundNeverConnectsSeparateForegroundPieces() {
        val p=listOf(PixelPoint(0,0),PixelPoint(3,0),PixelPoint(6,0),PixelPoint(9,0))
        val plot=SketchResult(listOf(SketchLine(p[0],p[1],black.color,0),
            SketchLine(p[1],p[2],lavender.color,0),SketchLine(p[2],p[3],black.color,0)),
            SketchTermination.LUMINANCE,SketchProgress(0.0,1.0,1.0))
        val runs=ThreadTextureAdapter.runs(plot,listOf(black,lavender),lavender)
        assertEquals(listOf(listOf(p[0],p[1]),listOf(p[2],p[3])),runs.map { it.pixels })
        assertEquals(listOf(listOf(0),listOf(2)),runs.map { it.sourceLineIndices })
    }
    @Test fun syntheticBeforeAfterStitchTableKeepsOriginalPlotIntact() {
        val rows=mutableListOf("background\tframe_mm\tplot_lines\tbefore_stitches\tafter_stitches\texcluded_lines\tafter_peak\tsewout")
        for (background in listOf("lavender","white","transparent")) {
            val edge=TextureRasterPolicy.edge(40.0)
            val color=when(background) { "lavender"->lavender.color;"white"->-1;else->lavender.color and 0xffffff }
            val pixels=picture(edge,color)
            val palette=ThreadTextureAdapter.brotherPalette(pixels,2)
            val after=ThreadTextureAdapter.generate(edge,edge,pixels,40.0,palette)
            val before=ThreadTextureAdapter.fromSketch(after.sketch,edge,edge,40.0,palette,HoopSpec.H100)
            assertEquals(before.sketch,after.sketch)
            val excluded=after.sketch.lines.size-after.runs.sumOf { it.sourceLineIndices.size }
            if(background=="transparent") {
                assertNull(after.backgroundThread)
                assertEquals(before.pattern.stitchCount,after.pattern.stitchCount)
                assertEquals(0,excluded)
            } else {
                if(background=="lavender") {
                    assertTrue(excluded>0,"Lavender background must remove assigned lines")
                    assertTrue(after.pattern.stitchCount<before.pattern.stitchCount)
                } else assertTrue(after.pattern.stitchCount<=before.pattern.stitchCount)
                assertTrue(after.runs.none { it.thread.sameColor(requireNotNull(after.backgroundThread)) })
            }
            assertFalse(after.sewout.hasBlockingIssues,after.sewout.findings.toString())
            assertTrue(PecViewportCheck.check(after.pes(),HoopSpec.H100).valid)
            rows+="$background\t40\t${after.sketch.lines.size}\t${before.pattern.stitchCount}\t${after.pattern.stitchCount}\t$excluded\t${after.sewout.statistics.peakCellPenetrations}\t${after.sewout.verdict}"
        }
        val out=File("build/thread-texture-background").apply { mkdirs() }
        File(out,"before-after.tsv").writeText(rows.joinToString("\n",postfix="\n"))
        println(rows.joinToString("\n"))
    }
}
