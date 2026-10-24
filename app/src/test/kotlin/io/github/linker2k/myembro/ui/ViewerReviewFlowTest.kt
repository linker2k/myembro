// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class ViewerReviewFlowTest {
    private fun project(x: Double = 250.0) = Project(HoopSpec.H100, listOf(StitchObject("a", "lemon",
        EmbPattern(listOf(Stitch(-100,-50,Command.STITCH), Stitch(100,50,Command.STITCH), Stitch(-100,-50,Command.STITCH), Stitch(-100,-50,Command.END)),
            listOf(EmbThread(0xFF123456.toInt(), "thread"))), Transform(x = x))))
    @Test fun `project preview preserves hoop placement dimensions threads and PES export source`() {
        val p = project()
        val opened = readViewerDesign(ProjectJson.encode(p).toByteArray(), "lemon.MYEMBRO")
        assertEquals(p.hoop, opened.hoop)
        assertEquals(p.flatten().stitches, opened.pattern.stitches)
        assertEquals(150, viewerBounds(opened.pattern, true)!!.minX)
        assertEquals(200, opened.pattern.bounds!!.width)
        assertEquals(1, opened.pattern.colorBlocks.size)
        assertEquals("lemon.pes", opened.sourceName)
        assertEquals(io.github.linker2k.myembro.engine.io.PesWriter.prepareForOutput(opened.pattern).stitchCount, PatternReader.read(opened.source, opened.sourceName).stitchCount)
        assertTrue(viewerFits(opened.pattern, p.hoop, true))
    }
    @Test fun `off-centre design too far outside hoop cannot pass by dimensions alone`() {
        val p = project(600.0)
        assertEquals(200, p.flatten().bounds!!.width)
        assertFalse(viewerFits(p.flatten(), p.hoop, true))
        assertTrue(viewerFits(p.flatten(), p.hoop, false))
    }
    @Test fun `ordinary PES preview remains centred and preserves bytes for export`() {
        val p = project()
        val projectOpened = readViewerDesign(ProjectJson.encode(p).toByteArray(), "lemon.myembro")
        val opened = readViewerDesign(projectOpened.source, "lemon.pes")
        assertNull(opened.hoop)
        assertSame(projectOpened.source, opened.source)
        val b = viewerBounds(opened.pattern, false)!!
        assertTrue(kotlin.math.abs(b.minX.toLong() + b.maxX) <= 1)
    }
}
