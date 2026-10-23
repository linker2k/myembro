// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch

import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.inkstitch.elements.*
import io.github.linker2k.myembro.engine.inkstitch.utils.Prng
import kotlin.math.*
import kotlin.test.*

/** v3.3.0 tests/ has no satin tests; these expected coordinates are hand-derived from source formulas. */
class SatinPortTest {
    private val a=listOf(Point(0.0,0.0),Point(0.0,10.0))
    private val b=listOf(Point(4.0,0.0),Point(4.0,10.0))
    private fun top(p:SatinParameters=SatinParameters())=SatinColumn.generateLayers(a,b,p).last().points
    @Test fun blake2sCounterStreamMatchesRfcAndStandardHashlibVectors() {
        fun hash(s:String)=Prng.blake2s(s.toByteArray()).joinToString(""){ "%02x".format(it.toInt() and 255) }
        assertEquals("69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9",hash(""))
        assertEquals("508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982",hash("abc"))
        assertEquals("3ac477e27353f9019b81694afe60c8049403784f91a58288428ea318bfa82809",hash("a".repeat(128)))
        assertEquals("9d37bbe4901e8340ceb484980b6ab828ec6a6dcc1ec154f46d0c45a06ace8335",hash("한글/0"))
        assertEquals(0x7ce7d009L/4294967295.0,Prng.uniformFloats("seed","satin-points",0)[0])
    }
    @Test fun svgDefaultsAndStraightColumnAreExactBeforeMachinePolicy() {
        val p=SatinParameters()
        assertEquals(.25,p.short_stitch_distance_mm);assertNull(p.max_stitch_length_mm)
        assertEquals(3.0,p.center_walk_underlay_stitch_length_mm)
        val points=top()
        assertEquals(listOf(Point(0.0,0.0),Point(4.0,0.0),Point(0.0,.4),Point(4.0,.4)),points.take(4))
        points.chunked(2).zipWithNext().dropLast(1).forEach { (x,y)->assertEquals(.4,y[0].y-x[0].y,1e-9) }
    }
    @Test fun reverseSwapAndRungsKeepTheirExplicitDirectionConstraints() {
        assertEquals(top(),SatinColumn.generateLayers(a,b.reversed()).last().points)
        assertEquals(top(),SatinColumn.generateLayers(a.reversed(),b.reversed(),SatinParameters(reverse_rails="both")).last().points)
        assertEquals(4.0,top(SatinParameters(swap_satin_rails=true)).first().x)
        val rails=SatinColumn.plotPointsOnRails(a,b,rungs=listOf(SatinColumn.Rung(Point(-1.0,5.0),Point(5.0,7.0))))
        assertTrue(rails.any { abs(it.first.y-it.second.y)>1.0 })
        assertFailsWith<IllegalArgumentException> {
            SatinColumn.generateLayers(a,b,rungs=listOf(SatinColumn.Rung(Point(0.0,3.0),Point(4.0,7.0)),SatinColumn.Rung(Point(0.0,7.0),Point(4.0,3.0))))
        }
    }
    @Test fun subpathRailDetectionAndSynthesizedNodeRungsUseTheSameLadder() {
        val rung=SatinColumn.Rung(Point(-1.0,5.0),Point(5.0,7.0))
        val detected=SatinColumn.generateFromSubpaths(listOf(listOf(rung.a,rung.b),a,b))
        assertEquals(SatinColumn.generateLayers(a,b,rungs=listOf(rung)),detected)
        val noRungs=SatinColumn.generateFromSubpaths(listOf(a,b))
        assertEquals(top().size,noRungs.last().points.size)
        val swapped=SatinColumn.generateLayers(a,b.reversed(),SatinParameters(swap_satin_rails=true,reverse_rails="first"))
        assertEquals(4.0,swapped.last().points.first().x)
        assertEquals(0.0,swapped.last().points.first().y)
    }
    @Test fun centerRepeatParityAndVeryShortColumnsDoNotCrash() {
        val p=SatinParameters(center_walk_underlay=true,center_walk_underlay_repeats=1)
        val layers=SatinColumn.generateLayers(a,b,p)
        assertEquals(top().reversed(),layers.last().points)
        assertTrue(SatinColumn.generateLayers(listOf(Point(0.0,0.0),Point(0.0,.05)),
            listOf(Point(1.0,0.0),Point(1.0,.05)),startPoint=Point(.5,0.0)).isNotEmpty())
    }
    @Test fun asymmetricMillimeterAndPercentageCompensationAndInsetClamp() {
        val pairs=SatinColumn.plotPointsOnRails(a,b,SatinParameters(pull_compensation_mm=.2 to .3,pull_compensation_percent=10.0 to 20.0))
        assertEquals(-.6,pairs.first().first.x,1e-9);assertEquals(5.1,pairs.first().second.x,1e-9)
        val (x,y)=SatinColumn.offsetPoints(Point(0.0,0.0),Point(1.0,0.0),-2.0 to -2.0)
        assertEquals(Point(.5,0.0),x);assertEquals(x,y)
        val pushed=SatinColumn.plotPointsOnRails(a,b,SatinParameters(push_compensation_mm=1.0 to 2.0))
        assertEquals(1.0,pushed.first().first.y,1e-8);assertEquals(8.0,pushed.last().first.y,1e-7)
    }
    @Test fun eSAndZigzagMatchTheirSourceCoordinateSequences() {
        assertEquals(listOf(Point(0.0,0.0),Point(4.0,0.0),Point(0.0,0.0),Point(0.0,.4)),top(SatinParameters(satin_method="e_stitch")).take(4))
        assertEquals(listOf(Point(4.0,0.0),Point(0.0,0.0),Point(0.0,.4),Point(4.0,.4)),top(SatinParameters(satin_method="s_stitch")).take(4))
        val zigzag=top(SatinParameters(satin_method="zigzag"))
        assertEquals(listOf(Point(0.0,0.0),Point(4.0,.2),Point(0.0,.4),Point(4.0,.6)),zigzag.take(4).map{Point(it.x,(it.y*10).roundToInt()/10.0)})
    }
    @Test fun underlayRepeatsPositionContourAndZigzagRemainSeparateTaggedLayers() {
        val p=SatinParameters(center_walk_underlay=true,center_walk_underlay_repeats=3,center_walk_underlay_position=25.0,
            contour_underlay=true,zigzag_underlay=true,zigzag_underlay_max_stitch_length_mm=2.0)
        val layers=SatinColumn.generateLayers(a,b,p)
        assertEquals(5,layers.size)
        val center=layers.first().points
        assertTrue(center.all { abs(it.x-1.0)<1e-9 });assertEquals(10.0,center.last().y)
        assertTrue("satin_center_walk" in layers.first().tags)
        layers.filter { "satin_zigzag_underlay" in it.tags }.forEach { layer->layer.points.zipWithNext().forEach { (x,y)->assertTrue(x.distance(y)<=2.0+1e-7) } }
        assertEquals(top().reversed(),layers.last().points)
    }
    @Test fun splitMethodsAndSeededRandomnessAreDeterministicAndDoNotShareGlobalState() {
        val p=SatinParameters(max_stitch_length_mm=1.5)
        val basic=top(p)
        assertEquals(listOf(0.0,4.0/3,8.0/3,4.0),basic.take(4).map { it.x })
        assertNotEquals(basic,top(p.copy(split_method="staggered")))
        assertNotEquals(basic,top(p.copy(split_method="simple")))
        val random=p.copy(random_seed="fixed",random_split_phase=true,random_split_jitter_percent=20.0,
            random_width_decrease_percent=5.0 to 15.0,random_width_increase_percent=10.0 to 20.0,random_zigzag_spacing_percent=20.0)
        val one=top(random);top(random.copy(random_seed="other"))
        assertEquals(one,top(random));assertNotEquals(one,top(random.copy(random_seed="other")))
        assertTrue(one.all { it.x.isFinite() && it.y.isFinite() })
    }
    @Test fun explicitStartEndAndPreviousNextConnectionsAreBoundedWithinTheColumn() {
        val layers=SatinColumn.generateLayers(a,b,SatinParameters(center_walk_underlay=true),startPoint=Point(2.0,3.0),endPoint=Point(2.0,5.0))
        assertEquals(Point(2.0,3.0),layers.first().points.first());assertEquals(Point(2.0,5.0),layers.last().points.last())
        assertTrue(layers.flatMap { it.points }.all { it.x in 0.0..4.0 && it.y in 0.0..10.0 })
        val nearest=SatinColumn.generateLayers(a,b,previousStitch=Point(2.0,7.0))
        assertEquals(7.0,nearest.first().points.first().y,1e-7)
    }
}
