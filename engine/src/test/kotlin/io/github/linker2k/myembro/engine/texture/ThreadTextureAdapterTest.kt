// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.texture

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.geometry.PICTURE_MIN_WIDTH_MM
import io.github.linker2k.myembro.engine.inkstitch.svg.image.Bt709Grayscale
import io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoPreparationPolicy
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.sewout.*
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.test.*

class ThreadTextureAdapterTest {
    private val black = PecThreads.forIndex(20)
    private val white = PecThreads.forIndex(29)
    private val red = PecThreads.forIndex(5)
    private fun sketch(lines: List<SketchLine>) = SketchResult(lines, SketchTermination.LUMINANCE, SketchProgress(0.0, 1.0, 1.0))
    @Test fun paletteUsesExistingBrotherThreadsAndSamplingAssignment() {
        val colors = intArrayOf(black.color, red.color, black.color, red.color)
        val palette = ThreadTextureAdapter.brotherPalette(colors, 2)
        assertEquals(setOf(black.color, red.color), palette.map { it.color }.toSet())
        assertEquals(red, ThreadTextureAdapter.threadFor(red.color, palette))
        assertEquals(black, ThreadTextureAdapter.threadFor(black.color, palette))
        assertFailsWith<IllegalArgumentException> { ThreadTextureAdapter.brotherPalette(colors, 7) }
    }
    @Test fun colorChangesAndPenLiftsSplitRunsThenGroupLightToDark() {
        val input = sketch(listOf(
            SketchLine(PixelPoint(0,0), PixelPoint(5,0), black.color, 0),
            SketchLine(PixelPoint(5,0), PixelPoint(9,0), white.color, 0),
            SketchLine(PixelPoint(9,0), PixelPoint(12,0), black.color, 0),
            SketchLine(PixelPoint(12,0), PixelPoint(16,0), black.color, 1),
        ))
        val runs = ThreadTextureAdapter.runs(input, listOf(black, white))
        assertEquals(listOf(white, black, black, black), runs.map { it.thread })
        assertEquals(listOf(listOf(1),listOf(0),listOf(2),listOf(3)), runs.map { it.sourceLineIndices })
        assertTrue(runs.all { it.pixels.size == 2 })
    }
    @Test fun connectedSameThreadSegmentsStayOneOriginalSquiggleRun() {
        val a=PixelPoint(2,3); val b=PixelPoint(5,6); val c=PixelPoint(8,9)
        val runs=ThreadTextureAdapter.runs(sketch(listOf(SketchLine(a,b,black.color,0),SketchLine(b,c,black.color,0))), listOf(black))
        assertEquals(1,runs.size);assertEquals(listOf(a,b,c),runs.single().pixels)
    }
    @Test fun simpleRunUsesExactPhysicalScaleAndExistingSafetyPipeline() {
        val input=sketch(listOf(SketchLine(PixelPoint(10,20),PixelPoint(110,20),black.color,0)))
        val result=ThreadTextureAdapter.fromSketch(input,128,128,40.0,listOf(black),HoopSpec.H100)
        assertEquals(40.0/128,result.mmPerPixel)
        assertFalse(result.sewout.hasBlockingIssues, result.sewout.findings.toString())
        val bytes=result.pes();assertTrue(PecViewportCheck.check(bytes,HoopSpec.H100).valid)
        val back=PesReader.read(bytes)
        val source=PesWriter.prepareForOutput(result.pattern).stitches.filter { it.command==Command.STITCH }
        assertEquals(source, back.stitches.filter { it.command==Command.STITCH })
        val needles=result.pattern.stitches.filter { it.command==Command.STITCH }
        assertEquals(313, needles.maxOf { it.x }-needles.minOf { it.x }) // 100 * 40/128 mm, rounded to native units.
    }
    @Test fun widthAndRasterBoundariesReusePhotoPolicy() {
        val pixels=IntArray(4) { black.color }
        assertFailsWith<IllegalArgumentException> { ThreadTextureAdapter.generate(2,2,pixels,PICTURE_MIN_WIDTH_MM-.1,listOf(black)) }
        assertFailsWith<IllegalArgumentException> { ThreadTextureAdapter.generate(2,2,pixels,PhotoPreparationPolicy.photoMaxWidthMm(HoopSpec.H100)+.1,listOf(black)) }
        val size=PhotoPreparationPolicy.photoRasterSize(1,40.0,40.0)+1
        assertFailsWith<IllegalArgumentException> { ThreadTextureAdapter.generate(size,size,IntArray(size*size),40.0,listOf(black)) }
    }
    @Test fun cancelledPlotNeverProducesPartialEmbroidery() {
        assertFailsWith<CancellationException> { ThreadTextureAdapter.generate(20,20,IntArray(400) { black.color },40.0,listOf(black),isCancelled={true}) }
    }
    @Test fun oldDenseRasterRemainsBlockedWithoutThresholdRelaxation() {
        val pixels=TextureSyntheticFixtures.pixels("ramp",192)
        val palette=ThreadTextureAdapter.brotherPalette(pixels,4)
        assertFailsWith<IllegalArgumentException> { ThreadTextureAdapter.generate(192,192,pixels,40.0,palette) }
        val plot=SketchLines.render(192,192,pixels)
        val result=ThreadTextureAdapter.fromSketch(plot,192,192,40.0,palette,HoopSpec.H100)
        assertTrue(result.sewout.hasBlockingIssues)
        assertEquals(19,result.sewout.statistics.peakCellPenetrations)
        assertFailsWith<IllegalArgumentException> { result.pes() }
    }
    @Test fun threeSyntheticImagesAtThreePhysicalSizes() {
        val directory=File("build/thread-texture-adapter").apply { mkdirs() }
        val rows=mutableListOf("image\twidth_mm\traster\tplot_lines\truns\tstitches\tpeak_0.5mm\tsewout\tblockers\tPEC\troundtrip\tgeneration_ms")
        for (image in listOf("ramp","circle","two-color")) for (mm in listOf(40.0,60.0,90.0)) {
            val edge=TextureRasterPolicy.edge(mm)
            val pixels=TextureSyntheticFixtures.pixels(image,edge)
            val started=System.nanoTime()
            val palette=ThreadTextureAdapter.brotherPalette(pixels,if(image=="ramp")4 else 2)
            val result=ThreadTextureAdapter.generate(edge,edge,pixels,mm,palette)
            val elapsed=(System.nanoTime()-started)/1_000_000.0
            val blockers=result.sewout.findingSeverities.filterValues { it==Severity.BLOCKER }.keys.sortedBy { it.name }
            val expectedBlocked = false // Default pitch is selected only from all-passing measured rows.
            assertEquals(expectedBlocked,result.sewout.hasBlockingIssues,"$image $mm blocker regression")
            assertEquals(if(expectedBlocked)listOf(FindingCode.DENSITY_HOTSPOT) else emptyList(),blockers)
            val lumas=result.runs.map { Bt709Grayscale.argb(it.thread.color) and 255 }
            assertEquals(lumas.sortedDescending(),lumas)
            val sewnIndices=result.runs.flatMap { it.sourceLineIndices }.sorted()
            assertEquals(result.sketch.lines.indices.toList(),sewnIndices)
            var pec="NOT_EMITTED";var roundtrip="NOT_EMITTED"
            if(result.sewout.hasBlockingIssues) {
                assertFailsWith<IllegalArgumentException> { result.pes() }
            } else {
                val bytes=result.pes()
                File(directory,"${image}-${mm.toInt()}.pes").writeBytes(bytes)
                val viewport=PecViewportCheck.check(bytes,HoopSpec.H100)
                assertTrue(viewport.valid,viewport.errors.toString());pec="PASS"
                val read=PesReader.read(bytes)
                val expected=PesWriter.prepareForOutput(result.pattern).stitches.filter { it.command==Command.STITCH }
                assertEquals(expected,read.stitches.filter { it.command==Command.STITCH },"$image $mm")
                assertEquals(result.pattern.threads.map { it.color },read.threads.map { it.color })
                val checked=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
                assertFalse(checked.hasBlockingIssues,checked.findings.toString())
                roundtrip="PASS"
            }
            val status=if(result.sewout.hasBlockingIssues)"BLOCK" else "NO_BLOCK (${result.sewout.verdict})"
            rows += "$image\t$mm\t${edge}x$edge\t${result.sketch.lines.size}\t${result.runs.size}\t${result.pattern.stitchCount}\t${result.sewout.statistics.peakCellPenetrations}\t$status\t${blockers.joinToString(",").ifEmpty { "none" }}\t$pec\t$roundtrip\t${"%.3f".format(java.util.Locale.ROOT,elapsed)}"
            File(directory,"results.tsv").writeText(rows.joinToString("\n",postfix="\n"))
            println(rows.last())
        }
        assertEquals(10,rows.size)
    }
}
