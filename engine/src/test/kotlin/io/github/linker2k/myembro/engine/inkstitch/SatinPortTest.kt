// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch

import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.inkstitch.elements.*
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinColumn as CoreSatin
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.utils.Point as SvgPoint
import io.github.linker2k.myembro.engine.inkstitch.utils.Prng
import kotlin.math.*
import kotlin.test.*

/** v3.3.0 tests/ has no satin tests; these expected coordinates are hand-derived from source formulas. */
class SatinPortTest {
    private data class MmRung(val a:Point,val b:Point)
    private data class MmLayer(val points:List<Point>,val tags:Set<String>)
    private fun Point.svg()=SvgPoint(Units.mmToPixels(x),Units.mmToPixels(y))
    private fun SvgPoint.mm()=Point(round(Units.pixelsToMm(x)*1e12)/1e12,round(Units.pixelsToMm(y)*1e12)/1e12)
    private fun layers(a:List<Point>,b:List<Point>,p:SatinParameters=SatinParameters(),rungs:List<MmRung> = emptyList(),
        startPoint:Point?=null,endPoint:Point?=null,previousStitch:Point?=null):List<MmLayer> =
        CoreSatin.generateLayers(a.map { it.svg() },b.map { it.svg() },p,rungs.map { CoreSatin.Rung(it.a.svg(),it.b.svg()) },
            startPoint?.svg(),endPoint?.svg(),previousStitch?.svg()).map { MmLayer(it.points.map { q->q.mm() },it.tags) }
    private fun pairs(a:List<Point>,b:List<Point>,p:SatinParameters=SatinParameters(),rungs:List<MmRung> = emptyList()) =
        CoreSatin.plotPointsOnRails(a.map { it.svg() },b.map { it.svg() },p,rungs.map { CoreSatin.Rung(it.a.svg(),it.b.svg()) }).map { it.first.mm() to it.second.mm() }
    private fun subpaths(paths:List<List<Point>>) = CoreSatin.generateFromSubpaths(paths.map { path->path.map { it.svg() } }).map { MmLayer(it.points.map { q->q.mm() },it.tags) }
    private fun offset(a:Point,b:Point,mm:Pair<Double,Double>) = CoreSatin.offsetPoints(a.svg(),b.svg(),Units.mmToPixels(mm.first) to Units.mmToPixels(mm.second)).let { it.first.mm() to it.second.mm() }
    private fun assertPoints(expected:List<Point>,actual:List<Point>) {
        assertEquals(expected.size,actual.size)
        expected.zip(actual).forEach { (a,b)->assertTrue(a.distance(b)<3e-6,"$a != $b (SVG precision grid)") }
    }
    private val a=listOf(Point(0.0,0.0),Point(0.0,10.0))
    private val b=listOf(Point(4.0,0.0),Point(4.0,10.0))
    private fun top(p:SatinParameters=SatinParameters())=layers(a,b,p).last().points
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
        assertPoints(listOf(Point(0.0,0.0),Point(4.0,0.0),Point(0.0,.4),Point(4.0,.4)),points.take(4))
        points.chunked(2).zipWithNext().dropLast(1).forEach { (x,y)->assertEquals(.4,y[0].y-x[0].y,3e-6) }
    }
    @Test fun reverseSwapAndRungsKeepTheirExplicitDirectionConstraints() {
        assertEquals(top(),layers(a,b.reversed()).last().points)
        assertEquals(top(),layers(a.reversed(),b.reversed(),SatinParameters(reverse_rails="both")).last().points)
        assertEquals(4.0,top(SatinParameters(swap_satin_rails=true)).first().x,3e-6)
        val rails=pairs(a,b,rungs=listOf(MmRung(Point(-1.0,5.0),Point(5.0,7.0))))
        assertTrue(rails.any { abs(it.first.y-it.second.y)>1.0 })
        // Source flattened_sections sorts cuts independently, even for crossing rung input.
        val crossing=layers(a,b,rungs=listOf(MmRung(Point(0.0,3.0),Point(4.0,7.0)),MmRung(Point(0.0,7.0),Point(4.0,3.0))))
        val sorted=layers(a,b,rungs=listOf(MmRung(Point(0.0,3.0),Point(4.0,3.0)),MmRung(Point(0.0,7.0),Point(4.0,7.0))))
        assertPoints(sorted.last().points,crossing.last().points)
    }
    @Test fun subpathRailDetectionAndSynthesizedNodeRungsUseTheSameLadder() {
        val rung=MmRung(Point(-1.0,5.0),Point(5.0,7.0))
        val detected=subpaths(listOf(listOf(rung.a,rung.b),a,b))
        assertEquals(layers(a,b,rungs=listOf(rung)),detected)
        val noRungs=subpaths(listOf(a,b))
        assertEquals(top().size,noRungs.last().points.size)
        val swapped=layers(a,b.reversed(),SatinParameters(swap_satin_rails=true,reverse_rails="first"))
        assertEquals(4.0,swapped.last().points.first().x,3e-6)
        assertEquals(0.0,swapped.last().points.first().y)
    }
    @Test fun centerRepeatParityAndVeryShortColumnsDoNotCrash() {
        val p=SatinParameters(center_walk_underlay=true,center_walk_underlay_repeats=1)
        val layers=layers(a,b,p)
        assertEquals(top().reversed(),layers.last().points)
        assertTrue(layers(listOf(Point(0.0,0.0),Point(0.0,.05)),
            listOf(Point(1.0,0.0),Point(1.0,.05)),startPoint=Point(.5,0.0)).isNotEmpty())
    }
    @Test fun asymmetricMillimeterAndPercentageCompensationAndInsetClamp() {
        val pairs=pairs(a,b,SatinParameters(pull_compensation_mm=.2 to .3,pull_compensation_percent=10.0 to 20.0))
        assertEquals(-.6,pairs.first().first.x,3e-6);assertEquals(5.1,pairs.first().second.x,3e-6)
        val (x,y)=offset(Point(0.0,0.0),Point(1.0,0.0),-2.0 to -2.0)
        assertEquals(Point(.5,0.0),x);assertEquals(x,y)
        val pushed=pairs(a,b,SatinParameters(push_compensation_mm=1.0 to 2.0))
        assertEquals(1.0,pushed.first().first.y,3e-6);assertEquals(8.0,pushed.last().first.y,3e-6)
    }
    @Test fun eSAndZigzagMatchTheirSourceCoordinateSequences() {
        assertPoints(listOf(Point(0.0,0.0),Point(4.0,0.0),Point(0.0,0.0),Point(0.0,.4)),top(SatinParameters(satin_method="e_stitch")).take(4))
        assertPoints(listOf(Point(4.0,0.0),Point(0.0,0.0),Point(0.0,.4),Point(4.0,.4)),top(SatinParameters(satin_method="s_stitch")).take(4))
        val zigzag=top(SatinParameters(satin_method="zigzag"))
        assertPoints(listOf(Point(0.0,0.0),Point(4.0,.2),Point(0.0,.4),Point(4.0,.6)),zigzag.take(4).map{Point(it.x,(it.y*10).roundToInt()/10.0)})
    }
    @Test fun underlayRepeatsPositionContourAndZigzagRemainSeparateTaggedLayers() {
        val p=SatinParameters(center_walk_underlay=true,center_walk_underlay_repeats=3,center_walk_underlay_position=25.0,
            contour_underlay=true,zigzag_underlay=true,zigzag_underlay_max_stitch_length_mm=2.0)
        val layers=layers(a,b,p)
        assertEquals(5,layers.size)
        val center=layers.first().points
        assertTrue(center.all { abs(it.x-1.0)<3e-6 });assertEquals(10.0,center.last().y,3e-6)
        assertTrue("satin_center_walk" in layers.first().tags)
        layers.filter { "satin_zigzag_underlay" in it.tags }.forEach { layer->layer.points.zipWithNext().forEach { (x,y)->assertTrue(x.distance(y)<=2.0+3e-6) } }
        assertEquals(top().reversed(),layers.last().points)
    }
    @Test fun splitMethodsAndSeededRandomnessAreDeterministicAndDoNotShareGlobalState() {
        val p=SatinParameters(max_stitch_length_mm=1.5)
        val basic=top(p)
        basic.take(4).map { it.x }.zip(listOf(0.0,4.0/3,8.0/3,4.0)).forEach { (x,y)->assertEquals(y,x,3e-6) }
        assertNotEquals(basic,top(p.copy(split_method="staggered")))
        assertNotEquals(basic,top(p.copy(split_method="simple")))
        val random=p.copy(random_seed="fixed",random_split_phase=true,random_split_jitter_percent=20.0,
            random_width_decrease_percent=5.0 to 15.0,random_width_increase_percent=10.0 to 20.0,random_zigzag_spacing_percent=20.0)
        val one=top(random);top(random.copy(random_seed="other"))
        assertEquals(one,top(random));assertNotEquals(one,top(random.copy(random_seed="other")))
        assertTrue(one.all { it.x.isFinite() && it.y.isFinite() })
    }
    @Test fun explicitStartEndAndPreviousNextConnectionsAreBoundedWithinTheColumn() {
        val layers=layers(a,b,SatinParameters(center_walk_underlay=true),startPoint=Point(2.0,3.0),endPoint=Point(2.0,5.0))
        assertEquals(Point(2.0,3.0),layers.first().points.first());assertEquals(Point(2.0,5.0),layers.last().points.last())
        assertTrue(layers.flatMap { it.points }.all { it.x in -3e-6..4.000003 && it.y in -3e-6..10.000003 })
        val nearest=layers(a,b,previousStitch=Point(2.0,7.0))
        assertEquals(7.0,nearest.first().points.first().y,3e-6)
    }
}
