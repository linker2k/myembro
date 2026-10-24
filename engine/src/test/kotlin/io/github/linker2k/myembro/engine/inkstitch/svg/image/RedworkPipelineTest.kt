// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.sewout.*
import java.io.File
import kotlin.test.*

/** Two original synthetic masks, no private designs or photographs. */
class RedworkPipelineTest {
    @Test fun rectangleBitmapToFinishedSingleColorPes() = fixture("rectangle")
    @Test fun roundBitmapToFinishedSingleColorPes() = fixture("round")

    @Test fun filledRingPreservesBackgroundHoleAndUsesDefaultTatamiBackend() {
        val rgb = IntArray(64*64) { i ->
            val x=i%64; val y=i/64
            if (x in 10..53 && y in 10..53 && !(x in 24..39 && y in 24..39)) 0 else 0xFFFFFF
        }
        val result=RedworkPipeline.generate(64,64,rgb,40.0,0,EmbThread(0xFF000000.toInt(), "Black"))
        assertEquals("inkstitch-default-tatami-underlay",result.pattern.metadata["myembro.fill.backend"])
        val bytes=PesWriter.write(result.pattern)
        assertEncodedViewport(bytes)
        // The model remains centered; terminal PES translates the full design to top-left.
        val shiftX=-result.pattern.stitches.minOf { it.x }
        val shiftY=-result.pattern.stitches.minOf { it.y }
        val needles=PesReader.read(bytes).stitches.filter { it.command==Command.STITCH }
        assertTrue(needles.isNotEmpty())
        assertFalse(needles.any { it.x in shiftX-39..shiftX+39 && it.y in shiftY-39..shiftY+39 }, "White center must remain unsewn")
        // Interior needles demonstrate area fill instead of another pair of running outlines.
        assertTrue(needles.any { it.x in shiftX+65..shiftX+110 && it.y in shiftY-20..shiftY+20 })
    }

    /** Check actual encoded destinations/header, never recentered reader geometry. */
    private fun assertEncodedViewport(bytes: ByteArray) {
        val hoop=HoopSpec.H100
        val viewport=PecViewportCheck.check(bytes,hoop)
        assertTrue(viewport.valid,viewport.errors.joinToString())
        assertEquals(0,viewport.minX)
        assertEquals(0,viewport.minY)
        assertTrue(viewport.maxX in 0 until hoop.width)
        assertTrue(viewport.maxY in 0 until hoop.height)
        assertEquals(viewport.maxX,viewport.headerWidth)
        assertEquals(viewport.maxY,viewport.headerHeight)
        println("PEC_VIEWPORT min=(${viewport.minX},${viewport.minY}) max=(${viewport.maxX},${viewport.maxY}) header=(${viewport.headerWidth},${viewport.headerHeight})")
    }

    private fun fixture(name: String) {
        val width = 64
        val height = 64
        val tokens = requireNotNull(javaClass.getResource("/redwork/$name.pbm")).readText().trim().split(Regex("\\s+"))
        assertEquals(listOf("P1", "64", "64"), tokens.take(3))
        val rgb = tokens.drop(3).map { if (it == "1") 0 else 0xFFFFFF }.toIntArray()
        val source = rgb.copyOf()
        val result = RedworkPipeline.generate(width, height, rgb, 60.0, 0, EmbThread(0xFFB5492A.toInt(), "Redwork"))
        assertContentEquals(source, rgb)
        assertEquals(1, result.contourCount)
        assertTrue(result.pattern.stitchCount > 10)
        assertEquals(1, result.pattern.colorBlocks.size)
        val bytes = result.copyPesBytes()
        assertEncodedViewport(bytes)
        val parsed = PesReader.read(bytes)
        assertEquals(1, parsed.colorBlocks.size)
        assertTrue(parsed.stitches.none { it.command == Command.COLOR_CHANGE })
        assertEquals(Command.END, parsed.stitches.last().command)
        val report = SewoutCheck.checkPes(bytes, options = SewoutOptions(
            // Generated-output threshold from /tmp/myembro-quality-gate/POLICY.md: >15 / 0.5mm.
            origin = DesignOrigin.APP_GENERATED,
        ))
        val output = File("/tmp/myembro-port-out/editor/redwork").apply { mkdirs() }
        File(output, "$name.pes").writeBytes(bytes)
        File(output, "$name.svg").writeText(result.outlineSvg)
        File(output, "$name.pbm").writeText(requireNotNull(javaClass.getResource("/redwork/$name.pbm")).readText())
        File(output, "$name.sewout.txt").writeText(report.findings.joinToString("\n") { "${it.severity}: ${it.code}: ${it.message}" })
        // PES does not preserve generated metadata: explicitly check the app's default v1
        // bytes with the same generated-output policy as PhotoViewModel.
        val appBytes=PesWriter.write(result.pattern)
        assertEncodedViewport(appBytes)
        val appReport = SewoutCheck.checkPes(appBytes, options = SewoutOptions(origin = DesignOrigin.APP_GENERATED))
        assertTrue(appReport.analysisComplete)
        assertFalse(appReport.hasBlockingIssues, appReport.findings.filter { it.severity == Severity.BLOCKER }.joinToString())
        assertTrue(report.analysisComplete)
        assertFalse(report.hasBlockingIssues, report.findings.filter { it.severity == Severity.BLOCKER }.joinToString())
        assertContentEquals(bytes, RedworkPipeline.generate(width, height, rgb, 60.0, 0,
            EmbThread(0xFFB5492A.toInt(), "Redwork")).copyPesBytes())
    }
}
