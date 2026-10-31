// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import io.github.linker2k.myembro.engine.model.*
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import kotlin.test.*

class SatinCoverageTest {
    private val f=GeometryFactory()
    private val thread=EmbThread(0xff202020.toInt())
    private fun rect(x0:Double,x1:Double,y0:Double,y1:Double)=f.toGeometry(Envelope(x0,x1,y0,y1))
    private fun row(y:Double)=StitchGroup(thread,listOf(
        Stitch(Units.mmToPixels(0.0),Units.mmToPixels(y)),
        Stitch(Units.mmToPixels(10.0),Units.mmToPixels(y))))

    @Test fun limitIsUpstreamHalfSpacingPlusWorstCasePecRoundingAndInclusive() {
        val limit=SatinParameters().zigzag_spacing_mm/2+Math.hypot(.05,.05)
        assertEquals(limit,SatinCoverage.allowedDistanceMm)
        val line=EmbPattern(listOf(io.github.linker2k.myembro.engine.model.Stitch(0,0,Command.STITCH),
            io.github.linker2k.myembro.engine.model.Stitch(100,0,Command.STITCH)),listOf(thread))
        assertEquals(0.0,SatinCoverage.uncoveredAreaMm2(rect(1.0,9.0,-Math.nextDown(limit),Math.nextDown(limit)),line))
        assertEquals(0.0,SatinCoverage.uncoveredAreaMm2(rect(1.0,9.0,-limit,limit),line))
        assertTrue(SatinCoverage.uncoveredAreaMm2(rect(1.0,9.0,-Math.nextUp(limit),Math.nextUp(limit)),line)>0)
        assertTrue(SatinCoverage.uncoveredAreaMm2(rect(1.0,9.0,-limit-.1,limit+.1),line)>0)
    }
    @Test fun travelDoesNotHideARealGapAndHolesAreNotCounted() {
        val jump=EmbPattern(listOf(io.github.linker2k.myembro.engine.model.Stitch(0,0,Command.JUMP),
            io.github.linker2k.myembro.engine.model.Stitch(100,0,Command.JUMP)),listOf(thread))
        val region=rect(0.0,10.0,-1.0,1.0)
        assertEquals(region.area,SatinCoverage.uncoveredAreaMm2(region,jump))
        val sewn=row(0.0)
        assertEquals(0.0,SatinCoverage.uncoveredAreaMm2(rect(1.0,9.0,-.2,.2),listOf(sewn)))
        val donut=region.difference(rect(1.0,9.0,-.8,.8))
        assertTrue(SatinCoverage.uncoveredAreaMm2(donut,listOf(sewn))<=donut.area)
    }
    @Test fun incompleteAutomaticSatinUsesExistingTatamiAttemptOnlyForThatRegion() {
        val region=rect(0.0,10.0,0.0,4.0)
        val calls=mutableListOf<StitchTypeRule.Type>()
        val tatami=(0..10).map { row(it*.4) }
        val result=StitchTypeRule.generateRegion(StitchTypeRule.Type.SATIN,
            validate={true},satinRegionMm=region) { type ->
            calls+=type
            if(type==StitchTypeRule.Type.SATIN) listOf(row(2.0)) else tatami
        }
        assertEquals(listOf(StitchTypeRule.Type.SATIN,StitchTypeRule.Type.TATAMI),calls)
        assertEquals(StitchTypeRule.Type.TATAMI,result.type)
        assertEquals(tatami,result.groups)
        assertTrue(result.coverage!!.fellBack)
        assertTrue(result.coverage!!.beforeUncoveredMm2>30.0)
        assertEquals(0.0,SatinCoverage.uncoveredAreaMm2(region,result.groups))
    }
    @Test fun completeAutomaticSatinGroupsAreUnchangedAndNonSatinHasNoAudit() {
        val source=listOf(row(0.0))
        val region=rect(1.0,9.0,-.2,.2)
        val kept=StitchTypeRule.generateRegion(StitchTypeRule.Type.SATIN,
            validate={true},satinRegionMm=region) { source }
        assertEquals(StitchTypeRule.Type.SATIN,kept.type)
        assertEquals(source,kept.groups)
        assertFalse(kept.coverage!!.fellBack)
        val fill=StitchTypeRule.generateRegion(StitchTypeRule.Type.TATAMI,
            validate={true},satinRegionMm=region) { source }
        assertEquals(source,fill.groups)
        assertNull(fill.coverage)
    }
    @Test fun automaticVectorCornerIsFilledByExistingTatamiAndPassesEncodedGate() {
        val art=VectorArt.Art(listOf(0.0,0.0,20.0,12.0),listOf(
            VectorArt.Shape("M 0 0 L 10 12 L 20 0",null,0x202020,2.0,lineJoin="round")))
        val result=VectorArt.render(art,22.0,captureRegions=true)
        val part=result.parts.single()
        assertEquals(StitchTypeRule.Type.TATAMI,part.method)
        assertTrue(part.satinCoverage!!.fellBack)
        val region=io.github.linker2k.myembro.engine.geometry.PolygonRegion.fromPaths(part.paths,
            io.github.linker2k.myembro.engine.geometry.FillRule.EVEN_ODD).geometry
        assertTrue(part.satinCoverage!!.beforeUncoveredMm2>SatinCoverage.uncoveredAreaMm2(region,result.regionPatterns.getValue(part.regionId)))
        val checked=io.github.linker2k.myembro.engine.io.PesWriter.writeChecked(result.pattern,
            origin=io.github.linker2k.myembro.engine.sewout.DesignOrigin.APP_GENERATED)
        assertFalse(checked.report.hasBlockingIssues)
    }

    @Test fun existingRingTopologyAndClosedStrokeAreBorderSourcesWithoutThresholds() {
        val solid=rect(0.0,20.0,0.0,20.0)
        val ring=solid.difference(rect(2.0,18.0,2.0,18.0))
        assertEquals(SatinCoverage.Border.NONE,SatinCoverage.border(solid))
        assertEquals(SatinCoverage.Border.POLYGON_RING,SatinCoverage.border(ring))
        assertEquals(SatinCoverage.Border.CLOSED_STROKE,SatinCoverage.border(solid,true))
        // Even a deliberately incomplete ring candidate is not a coverage-triggered fill fallback.
        val calls=mutableListOf<StitchTypeRule.Type>()
        val source=listOf(row(0.0))
        val kept=StitchTypeRule.generateRegion(StitchTypeRule.Type.SATIN,validate={true},
            satinRegionMm=ring,satinBorder=true) { calls+=it;source }
        assertEquals(listOf(StitchTypeRule.Type.SATIN),calls)
        assertEquals(StitchTypeRule.Type.SATIN,kept.type)
        assertEquals(source,kept.groups)
        assertNull(kept.coverage)
        assertTrue(SatinCoverage.uncoveredAreaMm2(ring,source)>0)
    }

}
