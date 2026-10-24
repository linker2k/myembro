// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch
import io.github.linker2k.myembro.engine.inkstitch.elements.utils.StrokeToSatin
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import kotlin.test.*

/** Analytic source-formula regressions, NOT an independently executed Ink/Stitch oracle. */
class StrokeToSatinTest {
    @Test fun straightRailsAndCenterRungFollowOriginalFormula() {
        val p=checkNotNull(StrokeToSatin.convertPathToSatin(listOf(Point(0.0,0.0),Point(40.0,0.0)),4.0))
        assertEquals(listOf(Point(0.0,-2.0),Point(40.0,-2.0)),p.left)
        assertEquals(listOf(Point(0.0,2.0),Point(40.0,2.0)),p.right)
        assertEquals(listOf(Point(20.0,2.4) to Point(20.0,-2.4)),p.rungs)
        assertTrue(p.column().toStitchGroups(null).sumOf { it.stitches.size }>0)
    }
    @Test fun originalCornerScoreAndMiterRails() {
        val input=listOf(Point(0.0,0.0),Point(40.0,0.0),Point(40.0,40.0))
        val scores=StrokeToSatin.scores(input)
        assertEquals(8100,scores[50]);assertEquals(8100,scores.sum())
        val p=checkNotNull(StrokeToSatin.convertPathToSatin(input,4.0))
        assertEquals(listOf(Point(0.0,-2.0),Point(42.0,-2.0),Point(42.0,40.0)),p.left)
        assertEquals(listOf(Point(0.0,2.0),Point(38.0,2.0),Point(38.0,40.0)),p.right)
        assertTrue(p.rungs.isNotEmpty())
    }
    @Test fun loopFixAndRecursiveMergeDoNotInventACenterCap() {
        val path=listOf(Point(0.0,0.0),Point(40.0,0.0),Point(40.0,40.0),Point(0.0,40.0),Point(0.0,0.0))
        val fixed=StrokeToSatin.fixLoop(path)
        assertEquals(Point(20.0,0.0),fixed.first());assertEquals(fixed.first(),fixed.last())
        val p=checkNotNull(StrokeToSatin.convertPathToSatin(path,4.0))
        assertTrue(p.left.size>3);assertTrue(p.right.size>3);assertTrue(p.rungs.isNotEmpty())
    }
    @Test fun nodeRungsAndDegeneratePathsUseSourceRules() {
        assertNull(StrokeToSatin.convertPathToSatin(listOf(Point(0.0,0.0)),4.0))
        assertNull(StrokeToSatin.convertPathToSatin(listOf(Point(0.0,0.0),Point(0.5,0.0)),4.0))
        val p=checkNotNull(StrokeToSatin.convertPathToSatin(listOf(Point(0.0,0.0),Point(20.0,0.0),Point(40.0,0.0)),4.0,rungsAtNodes=true))
        assertEquals(1,p.rungs.size)
        assertEquals(Point(20.0,2.4),p.rungs.single().first)
    }
    @Test fun closedExtensionStartsAtFirstSafeSegmentCenter() {
        val path=listOf(Point(0.0,0.0),Point(40.0,0.0),Point(40.0,40.0),Point(0.0,40.0),Point(0.0,0.0))
        val rolled=StrokeToSatin.setFirstNode(path,4.0)
        assertEquals(Point(20.0,0.0),rolled.first());assertEquals(rolled.first(),rolled.last())
        assertEquals(listOf(Point(40.0,0.0),Point(40.0,40.0),Point(0.0,40.0),Point(0.0,0.0)),rolled.drop(1).dropLast(1))
    }
    @Test fun extensionFilterRejectsRungsThatMissEitherRail() {
        val p=checkNotNull(StrokeToSatin.convertPathToSatin(listOf(Point(0.0,0.0),Point(40.0,0.0)),4.0))
        val bad=Point(10.0,10.0) to Point(20.0,10.0)
        assertEquals(p,StrokeToSatin.filteredRungs(p.copy(rungs=p.rungs+bad)))
    }

    @Test fun explicitAndAbsentSvgMiterStylePreserveDifferentDefaults() {
        assertEquals(5.0,StrokeToSatin.Style.fromSvg().mitreLimit)
        assertEquals(4.0,StrokeToSatin.Style.fromSvg("miter").mitreLimit)
        assertEquals(16,StrokeToSatin.Style().quadSegments)
    }
}
