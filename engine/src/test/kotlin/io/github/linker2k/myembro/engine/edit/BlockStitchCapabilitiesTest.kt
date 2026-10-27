// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.edit.commands.*
import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.inkstitch.svg.RegionSatin
import io.github.linker2k.myembro.engine.inkstitch.utils.Point as InkPoint
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.sewout.SewoutLimits
import java.io.File
import kotlin.test.*

class BlockStitchCapabilitiesTest {
    private fun fixture(name: String) = ShapeObject.fromSvg(name, name,
        File("src/test/resources/emoji/fluent/${name}_flat.svg").readText(), 30.0)

    @Test fun fixtureTableAgreesWithActualRegionSatinAndCachesProbe() {
        val rows = mutableListOf("fixture | region | SATIN | RUNNING | FILL | satin max mm | reason")
        for (name in listOf("red_heart", "grinning_face")) {
            val shape = fixture(name)
            assertTrue(shape.regions.isNotEmpty())
            val before = shape.localPattern()
            for (region in shape.regions) {
                val actual = BlockStitchCapabilities.forRegion(region)
                val probe = region.capabilityProbe
                val direct = runCatching { RegionSatin.groups(PolygonRegion.fromPaths(region.paths, FillRule.EVEN_ODD).geometry,
                    { x,y -> InkPoint(x*PIXELS_PER_MM,y*PIXELS_PER_MM) }, region.color, region.stitch.satin ?: SatinParameters()) }
                val expected = if (direct.isFailure) BlockStitchAvailability(false,"SATIN_GENERATION_FAILED")
                    else if (direct.getOrNull().isNullOrEmpty()) BlockStitchAvailability(false,"SATIN_GEOMETRY_UNSUPPORTED")
                    else BlockStitchCapabilities.satinLengths(direct.getOrThrow()!!, Transform())
                assertEquals(expected, actual.satin, "$name ${region.id}")
                assertTrue(actual.running.possible); assertTrue(actual.fill.possible)
                assertEquals(actual, BlockStitchCapabilities.forRegion(region))
                actual.satin.maxStitchLengthMm?.takeIf { it > 0.0 }?.let { max ->
                    val scale=(SewoutLimits.MAX_STITCH_LENGTH_UNITS_0P1MM / 10.0) / max + 1.0
                    val scaled=BlockStitchCapabilities.forRegion(region,Transform(scaleX=scale,scaleY=scale))
                    assertFalse(scaled.satin.possible)
                    assertEquals("SATIN_STITCH_TOO_LONG",scaled.satin.reason)
                }
                assertSame(probe, region.capabilityProbe)
                assertSame(probe, shape.withTransform(Transform(x=1.0)).regions.first { it.id==region.id }.capabilityProbe)
                rows += "$name | ${region.id} | ${actual.satin.possible} | ${actual.running.possible} | ${actual.fill.possible} | ${actual.satin.maxStitchLengthMm} | ${actual.satin.reason ?: "-"}"
            }
            assertSame(before, shape.localPattern())
        }
        println(rows.joinToString("\n"))
        System.getProperty("blockCapabilityReport")?.let { File(it).writeText(rows.joinToString("\n")+"\n") }
    }
    @Test fun p8BoundaryAndScaleMeasureSewnSegmentsNotTravelBetweenGroups() {
        fun group(mm: Double, x: Double=0.0)=StitchGroup(stitches=listOf(Stitch(x*PIXELS_PER_MM,0.0), Stitch((x+mm)*PIXELS_PER_MM,0.0)))
        val limit = SewoutLimits.MAX_STITCH_LENGTH_UNITS_0P1MM / 10.0
        assertTrue(BlockStitchCapabilities.satinLengths(listOf(group(limit)), Transform()).possible)
        val long = BlockStitchCapabilities.satinLengths(listOf(group(limit+0.1)), Transform())
        assertFalse(long.possible); assertEquals("SATIN_STITCH_TOO_LONG", long.reason)
        assertEquals(limit+0.1,long.maxStitchLengthMm!!,1e-10)
        assertTrue(BlockStitchCapabilities.satinLengths(listOf(group(1.0),group(1.0,90.0)), Transform()).possible)
        val groups=listOf(group(6.1))
        assertFalse(BlockStitchCapabilities.satinLengths(groups, Transform(scaleX=2.0)).possible)
        assertTrue(BlockStitchCapabilities.satinLengths(groups, Transform(scaleX=-1.0,rotationDeg=135.0,x=100.0)).possible)
        val jump=StitchGroup(stitches=listOf(Stitch(0.0,0.0),Stitch(90*PIXELS_PER_MM,0.0,jump=true),Stitch(91*PIXELS_PER_MM,0.0)))
        assertTrue(BlockStitchCapabilities.satinLengths(listOf(jump), Transform()).possible)
    }
    @Test fun missingGeometryAndOpenRunningPathHaveDifferentCapabilities() {
        val empty=ShapeRegion("empty",0,"fill",emptyList(),EmbThread(0),BlockStitch())
        val caps=BlockStitchCapabilities.forRegion(empty)
        assertFalse(caps.satin.possible);assertFalse(caps.running.possible);assertFalse(caps.fill.possible)
        val line=ShapeRegion("line",0,"stroke",listOf(Polyline(listOf(Point(0.0,0.0),Point(2.0,0.0)),false)),EmbThread(0),BlockStitch())
        val lineCaps=BlockStitchCapabilities.forRegion(line)
        assertTrue(lineCaps.running.possible);assertFalse(lineCaps.fill.possible);assertFalse(lineCaps.satin.possible)
    }
    @Test fun gradientOverrideExplicitlyRejectsWithoutChangingProjectOrHistory() {
        val p=Project(objects=listOf(fixture("red_heart")))
        val c=ProjectCommand.SetBlockStitch("red_heart",setOf("region-0"),BlockStitchPatch(fill_method="linear_gradient_fill"))
        val result=assertIs<CommandResult.Rejected>(apply(p,c))
        assertEquals(CommandError.INVALID_BLOCK_SETTINGS,result.code)
        assertContains(result.message,"LINEAR_GRADIENT_REQUIRES_COLOR_STOPS")
        assertContains(result.message,"at least 2 color stops")
        assertSame(p,result.project)
        val s=EditSession(p)
        s.executeTurn("gradient",0,listOf(c))
        assertSame(p,s.project);assertEquals(0,s.snapshot().undoCount)
        assertFalse("linear_gradient_fill" in BlockStitchCapabilities.fillMethods)
    }
}
