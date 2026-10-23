// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.sewout.*
import java.io.File
import kotlin.test.*

/** Two original synthetic masks, no private designs or photographs. */
class RedworkPipelineTest {
    @Test fun rectangleBitmapToFinishedSingleColorPes() = fixture("rectangle")
    @Test fun roundBitmapToFinishedSingleColorPes() = fixture("round")

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
        val parsed = PesReader.read(bytes)
        assertEquals(1, parsed.colorBlocks.size)
        assertTrue(parsed.stitches.none { it.command == Command.COLOR_CHANGE })
        assertEquals(Command.END, parsed.stitches.last().command)
        val report = SewoutCheck.checkPes(bytes, options = SewoutOptions(
            placement = Placement.HOOP_COORDINATES,
            // Generated-output threshold from /tmp/myembro-quality-gate/POLICY.md: >15 / 0.5mm.
            hotspotPenetrations = 16,
        ))
        val output = File("/tmp/myembro-port-out/editor/redwork").apply { mkdirs() }
        File(output, "$name.pes").writeBytes(bytes)
        File(output, "$name.svg").writeText(result.outlineSvg)
        File(output, "$name.pbm").writeText(requireNotNull(javaClass.getResource("/redwork/$name.pbm")).readText())
        File(output, "$name.sewout.txt").writeText(report.findings.joinToString("\n") { "${it.severity}: ${it.code}: ${it.message}" })
        assertTrue(report.analysisComplete)
        assertFalse(report.hasBlockingIssues, report.findings.filter { it.severity == Severity.BLOCKER }.joinToString())
        assertContentEquals(bytes, RedworkPipeline.generate(width, height, rgb, 60.0, 0,
            EmbThread(0xFFB5492A.toInt(), "Redwork")).copyPesBytes())
    }
}
