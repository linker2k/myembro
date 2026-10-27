// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.test.*

class DefaultTatamiTest {
    private val thread=EmbThread(0xffb74e35.toInt(),"Terracotta")
    private fun region(path:String)=SvgPath.polygons(path,FillRule.EVEN_ODD)
    @Test fun rectangleUsesDefaultUnderlayThenTopInSharedPixelUnits() {
        val groups=DefaultTatami.stitchGroups(region("M0 0 H200 V120 H0 Z"),thread)
        assertTrue(groups.size>=2)
        val tags=groups.map { it.stitches.first().tags }
        assertTrue("fill_underlay" in tags.first());assertTrue("fill_top" in tags.last())
        assertTrue(groups.all { it.color==thread && it.lockStart!=null && it.lockEnd!=null && !it.forceLockStitches })
        val p=EmbPatternAdapter.toEmbPattern(StitchPlanBuilder.fromGroups(groups))
        val bounds=p.bounds!!
        assertEquals(200,bounds.width);assertEquals(120,bounds.height)
        // The public defaults include 0.25mm top rows, not the backend's older 0.4mm default.
        val rows=groups.filter { "fill_top" in it.stitches.first().tags }.flatMap { it.stitches }
            .map { Math.round(it.y/(96.0/25.4)*1000).toInt() }.toSet()
        assertTrue(rows.contains(250))
    }
    @Test fun holeProducesReadablePesWithoutNeedlesInItsInterior() {
        val groups=DefaultTatami.stitchGroups(region("M0 0 H200 V200 H0 Z M60 60 V140 H140 V60 Z"),thread)
        val decoded=PesReader.read(PesWriter.writeV1(EmbPatternAdapter.toEmbPattern(StitchPlanBuilder.fromGroups(groups))))
        val needles=decoded.stitches.filter { it.command==Command.STITCH }
        assertTrue(needles.isNotEmpty())
        assertFalse(needles.any { it.x in 61..139 && it.y in 61..139 })
    }
    @Test fun separateComponentsRetainUnderlayTopOrderAndEmptyInputProducesNothing() {
        val groups=DefaultTatami.stitchGroups(region("M0 0 H80 V80 H0 Z M140 0 H220 V80 H140 Z"),thread)
        val stages=groups.map { if("fill_underlay" in it.stitches.first().tags) "underlay" else "top" }
            .fold(mutableListOf<String>()) { acc,stage -> acc.apply { if(lastOrNull()!=stage) add(stage) } }
        assertEquals(listOf("underlay","top","underlay","top"),stages)
        assertTrue(DefaultTatami.stitchGroups(PolygonRegion.empty(),thread).isEmpty())
    }
    @Test fun expansionGrowsTopOnlyAndZeroRetainsCoordinates() {
        val polygon=region("M0 0 H200 V120 H0 Z")
        val original=DefaultTatami.stitchGroups(polygon,thread)
        val zero=DefaultTatami.stitchGroups(polygon,thread,expandMm=0.0)
        val expanded=DefaultTatami.stitchGroups(polygon,thread,expandMm=0.3)
        fun coordinates(groups:List<StitchGroup>,tag:String)=groups.flatMap { it.stitches }
            .filter { tag in it.tags }.map { it.x to it.y }
        for(tag in listOf("fill_top","fill_underlay"))
            assertEquals(coordinates(original,tag),coordinates(zero,tag))
        assertEquals(coordinates(original,"fill_underlay"),coordinates(expanded,"fill_underlay"))
        val top=coordinates(expanded,"fill_top")
        val scale=io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
        assertTrue(top.minOf { it.first } < -0.29*scale)
        assertTrue(top.maxOf { it.first } > 20.29*scale)
        assertFailsWith<IllegalArgumentException> { DefaultTatami.stitchGroups(polygon,thread,Double.NaN) }
    }

}
