// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.elements.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.inkstitch.utils.Point as SP
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.sewout.*
import org.locationtech.jts.io.WKTReader
import org.locationtech.jts.geom.Polygon
import javax.imageio.ImageIO
import kotlin.test.*

class ColorFillOutlineTest {
    @Test fun exteriorAndHoleFollowTheRunningPortOnceInSourceDirection() {
        val polygon=WKTReader().read("POLYGON ((0 0,200 0,200 200,0 200,0 0),(50 50,50 150,150 150,150 50,50 50))") as Polygon
        val thread=PecThreads.SET.filterNotNull().first()
        val actual=ColorFillOutline.stitchGroups(polygon,thread)
        val rings=listOf(polygon.exteriorRing,polygon.getInteriorRingN(0))
        val expected=Stroke(rings.map { ring -> ring.coordinates.map { SP(it.x/10*PIXELS_PER_MM,it.y/10*PIXELS_PER_MM) } },thread,
            StrokeParameters(repeats=1,bean_stitch_repeats=listOf(0),running_stitch_length_mm=listOf(1.655))).toStitchGroups(null)
        assertEquals(2,actual.size)
        assertEquals(expected.map { it.stitches },actual.map { it.stitches })
        actual.forEach { group ->
            assertEquals(thread,group.color)
            // A rectangle is traversed in the source direction once, without bean backtracking.
            val points=group.stitches
            val length=points.zipWithNext().sumOf { (a,b) -> kotlin.math.hypot(b.x-a.x,b.y-a.y) }
            val ring=rings[actual.indexOf(group)]
            assertEquals(ring.length/10*PIXELS_PER_MM,length,1e-6)
            assertEquals(points.map { it.x to it.y }.distinct().size,points.size-1)

        }
    }
    @Test fun cumulativeDensityRejectsOnlyOptionalOutline() {
        val polygon=WKTReader().read("POLYGON ((0 0,200 0,200 200,0 200,0 0))") as Polygon
        val groups=ColorFillOutline.stitchGroups(polygon,PecThreads.SET.filterNotNull().first())
        assertTrue(ColorFillOutline.safe(groups,HoopSpec.H100))
        assertFalse(ColorFillOutline.safe(List(40) { groups }.flatten(),HoopSpec.H100))
    }
    @Test fun batchChecksOnceWhenAllOutlinesPassAndResolvesDensityOwners() {
        val thread=PecThreads.SET.filterNotNull().first()
        fun outline(x:Int)=ColorFillOutline.stitchGroups(WKTReader().read(
            "POLYGON (($x 0,${x+200} 0,${x+200} 200,$x 200,$x 0))") as Polygon,thread)
        val first=outline(0);val second=outline(300)
        // The baseline deliberately has no needle in the proposed outlines' cells.
        val base=listOf(StitchGroup(thread,listOf(Stitch(SP(-100.0,-100.0)),Stitch(SP(-90.0,-100.0)))))
        val all=ColorFillOutline.select(base,listOf(ColorFillOutline.Outline(1,0,first)),HoopSpec.H100)
        assertEquals(1,all.checks);assertTrue(all.skipped.isEmpty())
        val base2=base+base.first().copy(stitches=base.first().stitches.map { it.copy(x=it.x+20) })
        val resolved=ColorFillOutline.select(base2,listOf(
            ColorFillOutline.Outline(1,0,List(40) { first }.flatten()),
            ColorFillOutline.Outline(2,1,second)),HoopSpec.H100)
        assertEquals(setOf(0),resolved.skipped)
        assertEquals(2,resolved.checks)
        assertTrue(ColorFillOutline.safe(resolved.groups,HoopSpec.H100))
        val invalidBaseline=List(40) { first }.flatten()
        val fallback=ColorFillOutline.select(invalidBaseline,listOf(
            ColorFillOutline.Outline(invalidBaseline.size,7,second)),HoopSpec.H100)
        assertEquals(invalidBaseline,fallback.groups)
        assertEquals(setOf(7),fallback.skipped)
        assertEquals(1,fallback.checks)
    }
    @Test fun outlinesSharingTheSameFillBoundaryRetainEveryOwnerAndOnlyRemoveBlockedOnes() {
        val thread=PecThreads.SET.filterNotNull().first()
        fun outline(x:Int)=ColorFillOutline.stitchGroups(WKTReader().read(
            "POLYGON (($x 0,${x+200} 0,${x+200} 200,$x 200,$x 0))") as Polygon,thread)
        val first=outline(0);val second=outline(300)
        val base=listOf(StitchGroup(thread,listOf(Stitch(SP(-100.0,-100.0)),Stitch(SP(-90.0,-100.0)))))
        val kept=ColorFillOutline.select(base,listOf(ColorFillOutline.Outline(1,0,first),
            ColorFillOutline.Outline(1,1,second)),HoopSpec.H100)
        assertTrue(kept.skipped.isEmpty())
        assertEquals((base+first+second).map { it.stitches },kept.groups.map { it.stitches })
        val filtered=ColorFillOutline.select(base,listOf(ColorFillOutline.Outline(1,0,List(40) { first }.flatten()),
            ColorFillOutline.Outline(1,1,second)),HoopSpec.H100)
        assertEquals(setOf(0),filtered.skipped)
        assertEquals((base+second).map { it.stitches },filtered.groups.map { it.stitches })
        assertTrue(ColorFillOutline.safe(filtered.groups,HoopSpec.H100))
    }
    @Test fun unscaledColorSelectionStillUsesExact998_999_1000Boundary() {
        val thread=PecThreads.forIndex(5)
        for(units in listOf(998,999,1000)) {
            val polygon=WKTReader().read("POLYGON ((0 0,$units 0,$units 100,0 100,0 0))") as Polygon
            val base=listOf(StitchGroup(thread,listOf(Stitch(SP(0.0,0.0)),Stitch(SP(10.0,0.0)))))
            val outline=ColorFillOutline.stitchGroups(polygon,thread)
            val selected=ColorFillOutline.select(base,listOf(ColorFillOutline.Outline(1,0,outline)),HoopSpec.H100)
            assertEquals(if(units<=999) emptySet() else setOf(0),selected.skipped,"color boundary $units")
        }
    }
    @Test fun facePreservesRegionsColorsAndFragmentsAndPassesEncodedGate() {
        val image=ImageIO.read(javaClass.getResourceAsStream("/image-to-design/face.png"))
        val preview=ColorDrawingPipeline.trace(image.width,image.height,image.getRGB(0,0,image.width,image.height,null,0,image.width),40.0,4)
        val before=ColorDrawingPipeline.generate(preview,HoopSpec.H100,true,{},false)
        val after=ColorDrawingPipeline.generate(preview)
        assertEquals(before.excludedCount,after.excludedCount)
        assertEquals(before.regions.map { it.paths },after.regions.map { it.paths })
        assertEquals(before.pattern.threads,after.pattern.threads)
        assertTrue(after.pattern.stitchCount>before.pattern.stitchCount)
        assertEquals(after.skippedOutlineCount.toString(),after.pattern.metadata["myembro.skipped_fill_outlines"])
        assertEquals(before.regionPatterns.keys,after.regionPatterns.keys)
        val bytes=PesWriter.write(after.pattern)
        assertTrue(PecViewportCheck.check(bytes,HoopSpec.H100).valid)
        assertFalse(SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED)).hasBlockingIssues)
        println("FILL_OUTLINE before=${before.pattern.stitchCount} after=${after.pattern.stitchCount} skipped=${after.skippedOutlineCount} regions=${after.regions.size}")
    }
}
