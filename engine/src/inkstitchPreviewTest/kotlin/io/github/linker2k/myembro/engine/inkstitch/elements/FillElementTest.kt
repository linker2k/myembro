// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/fill_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.digitizing.TatamiSettings
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.*
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbThread
import org.locationtech.jts.geom.Coordinate
import kotlin.test.*

class FillElementTest {
    private val thread = EmbThread(0xFFff3020.toInt(),"Fixture red","R1","myembro")
    private fun rectangle(x: Double=0.0, width: Double=60.0) = FillGeometry.factory.createPolygon(arrayOf(
        Coordinate(x,0.0),Coordinate(x+width,0.0),Coordinate(x+width,40.0),Coordinate(x,40.0),Coordinate(x,0.0)))

    @Test fun underlayPrecedesTopAndSharedMetadataLocksAndFinalCommandsArePreserved() {
        val options = FillParameters(fill_underlay_angle="45 -45",trim_after=true,stop_after=true,
            min_stitch_length_mm=0.2,min_jump_stitch_length_mm=1.5,force_lock_stitches=true)
        val groups = FillStitch(rectangle(),thread,options).toStitchGroups(null)
        assertEquals(3,groups.size)
        assertTrue(groups[0].stitches.all { "fill_underlay" in it.tags })
        assertTrue(groups[1].stitches.all { "fill_underlay" in it.tags })
        assertTrue(groups.last().stitches.all { "fill_top" in it.tags })
        assertTrue(groups.all { it.color == thread && it.lockStart != null && it.lockEnd != null })
        assertTrue(groups.all { it.minStitchLengthMm == 0.2 && it.minJumpLengthMm == 1.5 })
        assertTrue(groups.dropLast(1).none { it.trimAfter || it.stopAfter || it.forceLockStitches })
        assertTrue(groups.last().trimAfter && groups.last().stopAfter && groups.last().forceLockStitches)
    }

    @Test fun disabledFillAndTiesAreExplicitAndPreviousNeedleOnlyChoosesEntry() {
        assertTrue(FillStitch(rectangle(),parameters=FillParameters(fill=false)).toStitchGroups(null).isEmpty())
        val p = FillParameters(fill_underlay=false,ties=3)
        val before = rectangle().copy()
        val source = rectangle()
        val groups = FillStitch(source,parameters=p).toStitchGroups(Stitch(70.0,23.0))
        assertTrue(groups.all { it.lockStart == null && it.lockEnd == null })
        assertEquals(60.0,groups.first().stitches.first().x,1e-6)
        assertEquals(23.0,groups.first().stitches.first().y,1e-6)
        assertTrue(source.equalsExact(before))
    }

    @Test fun explicitStartEndTakePrecedenceOverPreviousStitchAndSurviveThePlan() {
        val groups = FillStitch(rectangle(),parameters=FillParameters(fill_underlay=false,ties=3),
            starting_point=FillPoint(-10.0,7.0),ending_point=FillPoint(70.0,31.0)).toStitchGroups(Stitch(70.0,23.0))
        assertEquals(0.0,groups.first().stitches.first().x,1e-6)
        assertEquals(7.0,groups.first().stitches.first().y,1e-6)
        assertEquals(60.0,groups.last().stitches.last().x,1e-6)
        assertEquals(31.0,groups.last().stitches.last().y,1e-6)
    }

    @Test fun disjointPiecesBecomeSeparateGroupsAndAnExplicitNeedleUpTransition() {
        val shape = rectangle(width=20.0).union(rectangle(25.0,width=20.0))
        val groups = FillStitch(shape,parameters=FillParameters(fill_underlay=false,ties=3)).toStitchGroups(null)
        assertEquals(2,groups.size)
        assertTrue(groups.first().forceLockStitches)
        assertTrue(groups.all { group -> group.stitches.none { it.isJump } })
        val machine = EmbPatternAdapter.toEmbPattern(StitchPlanBuilder.fromGroups(groups))
        assertEquals(2,machine.stitches.count { it.command == Command.JUMP })
    }

    @Test fun typedMethodSelectionUsesSharedContractAndDefaultGettersClampMillimeters() {
        val common = FillParameters(fill_underlay=false,row_spacing_mm=0.8)
        val methods = listOf(FillMethod.Tatami,FillMethod.Legacy,FillMethod.Contour(),FillMethod.Circular(),
            FillMethod.Guided(FillGeometry.line(listOf(FillPoint(-10.0,20.0),FillPoint(70.0,20.0)))))
        for (method in methods) {
            val element = FillStitch(rectangle(),thread,common,method=method)
            val groups = element.toStitchGroups(null)
            assertTrue(groups.isNotEmpty(),method.id)
            assertTrue(groups.all { it.color == thread },method.id)
        }
        assertTrue(FillStitch(rectangle(),parameters=common.copy(row_spacing_mm=0.0,max_stitch_length_mm=0.0))
            .toStitchGroups(null).isNotEmpty())
        assertEquals(FillMethod.Tatami,FillMethod.fromId("unknown-upstream-method"))
        assertFailsWith<IllegalArgumentException> { FillMethod.fromId("cross_stitch") }
    }

    @Test fun gradientAndTartanColorChangesGoThroughTheSharedPlanAndPesRoundTrip() {
        val gradient = FillMethod.Gradient(LinearGradientFill.Gradient(FillPoint(0.0,0.0),FillPoint(0.0,40.0),
            listOf(LinearGradientFill.Stop(0.0,"#ff0000"),LinearGradientFill.Stop(1.0,"#0000ff"))))
        val element = FillStitch(rectangle(),parameters=FillParameters(fill_underlay=false,row_spacing_mm=0.5),method=gradient)
        val groups = element.toStitchGroups(null)
        assertEquals(listOf(0xff0000,0x0000ff),groups.map { it.color!!.color and 0xffffff }.distinct())
        val pattern = EmbPatternAdapter.toEmbPattern(StitchPlanBuilder.fromGroups(groups))
        val read = PesReader.read(PesWriter.writeV6(pattern))
        assertEquals(pattern.stitches.count { it.command == Command.COLOR_CHANGE },read.stitches.count { it.command == Command.COLOR_CHANGE })
        assertEquals(pattern.stitches.count { it.command == Command.STITCH },read.stitches.count { it.command == Command.STITCH })
    }

    @Test fun legacyFlipReverseStaySeparateFromTheUnchangedOldImplementation() {
        val p = FillParameters(fill_method="legacy_fill",fill_underlay=true)
        val a = FillStitch(rectangle(),parameters=p).toStitchGroups(null)
        val b = FillStitch(rectangle(),parameters=p.copy(reverse=true)).toStitchGroups(null)
        assertEquals(a.map { it.stitches }.reversed().map { it.reversed() },b.map { it.stitches })
        assertTrue(a.none { group -> group.stitches.any { "fill_underlay" in it.tags } })
    }

    @Test fun migrationAdapterConvertsNativeGeometryAndClockwiseAnglesWithoutChangingOldCallSites() {
        val region = SvgPath.polygons("M0 0H100V100H0Z")
        val element = FillAdapters.tatamiElement(region,TatamiSettings(angleDegrees=30.0,underlay=false),thread)
        assertEquals(-30.0,element.parameters.angle)
        assertEquals(0.4,element.parameters.row_spacing_mm)
        assertEquals(4.0,element.parameters.max_stitch_length_mm)
        val groups = element.toStitchGroups(null)
        assertTrue(groups.flatMap { it.stitches }.all { it.x in -1e-5..(10*PIXELS_PER_MM+1e-5) })
        assertFailsWith<IllegalArgumentException> { FillAdapters.tatamiElement(region,TatamiSettings(rowOffsets=listOf(0.1,0.4))) }
    }
}
