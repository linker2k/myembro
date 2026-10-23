// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch

import io.github.linker2k.myembro.engine.inkstitch.elements.InkSatin
import io.github.linker2k.myembro.engine.inkstitch.stitches.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.geometry.Polyline
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.edit.*
import kotlin.math.*
import kotlin.test.*

/** Analytic goldens transcribed from the pinned upstream formulas, never from executing Python. */
class InkStitchPortTest {
    @Test fun evenRunningSleeveAndCornerGoldens() {
        val line = InkRunning.even(listOf(Point(0.0, 0.0), Point(10.0, 0.0)), 2.5, 0.05)
        assertEquals(5, line.size)
        line.forEachIndexed { i, p -> assertEquals(i * 2.5, p.x, 1e-5) }
        val corner = InkRunning.even(listOf(Point(0.0, 0.0), Point(4.0, 0.0), Point(4.0, 4.0)), 2.5, 0.05)
        assertTrue(corner.any { it.distance(Point(4.0, 0.0)) < 1e-5 })
        assertTrue(corner.zipWithNext().all { (a,b) -> a.distance(b) <= 2.5 + 1e-5 })
        val curve = (0..100).map { val a = it * PI / 200; Point(10 * cos(a), 10 * sin(a)) }
        val sampled = InkRunning.even(curve, 2.5, 0.05)
        assertTrue(sampled.size < curve.size / 2)
        assertTrue(sampled.all { abs(hypot(it.x, it.y) - 10.0) <= 0.051 })
    }
    @Test fun railSpacingRungsAndSawtoothGoldens() {
        val a = listOf(Point(0.0,0.0), Point(0.0,10.0)); val b = listOf(Point(2.0,0.0), Point(2.0,10.0))
        val pairs = InkSatin.pairs(a,b,emptyList(),0.4)
        assertEquals(26,pairs.size)
        pairs.forEachIndexed { i,(p,q) -> assertEquals(i*0.4,p.y,1e-6); assertEquals(p.y,q.y,1e-6) }
        val backwards = InkSatin.pairs(a,b.asReversed(),listOf(InkSatin.Rung(Point(0.0,5.0),Point(2.0,5.0))),0.4)
        assertEquals(pairs.size,backwards.size)
        assertFailsWith<IllegalArgumentException> {
            InkSatin.pairs(a,b,listOf(InkSatin.Rung(Point(0.0,3.0),Point(2.0,8.0)),
                InkSatin.Rung(Point(0.0,7.0),Point(2.0,2.0))),0.4)
        }
        val short = InkSatin.shorten(listOf(Point(0.0,0.0) to Point(2.0,0.0), Point(0.0,0.1) to Point(2.0,0.5)))
        assertEquals(0.0,short.first().first.x)
        assertTrue(short.last().first.x > 0.0) // 15% inward on the crowded rail; the other rail stays full width.
        assertEquals(2.0,short.last().second.x)
    }
    @Test fun allThreeSatinUnderlaysAndCompensationAreAvailable() {
        val a = listOf(Point(0.0,0.0),Point(0.0,10.0));val b=listOf(Point(2.0,0.0),Point(2.0,10.0))
        val plain = InkSatin.paths(a,b,SatinOptions())
        val layers = InkSatin.paths(a,b,SatinOptions(centerUnderlay=true,contourUnderlay=true,zigzagUnderlay=true,pullCompensationMm=0.2))
        assertEquals(1,plain.size);assertEquals(5,layers.size)
        assertTrue(layers.last().minOf { it.x } < -0.19)
        assertTrue(layers.last().maxOf { it.x } > 2.19)
    }
    @Test fun placedTextRebuildsLocksAtMachineScaleInsteadOfScalingTheirLengths() {
        val text=TextObject("scaled",text="I",stitchStyle=StitchStyle.RUNNING,transform=Transform(scaleX=0.5,scaleY=0.5))
        val placed=text.placedPattern()
        val first=placed.stitches.first { it.command==Command.STITCH }
        val jump=placed.stitches.takeWhile{it!=first}.last()
        assertTrue(hypot((first.x-jump.x).toDouble(),(first.y-jump.y).toDouble()) in 3.0..5.0)
        val pes=PesReader.read(PesWriter.write(placed))
        assertEquals(placed.stitchCount,pes.stitchCount)
    }
}
