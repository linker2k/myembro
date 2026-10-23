// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.oracle

import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import kotlin.math.sqrt
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchPlan
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.ColorBlock
import io.github.linker2k.myembro.engine.inkstitch.svg.Units

/** Analytic fixtures for the source-derived manual boundary; no upstream code executes. */
object WorkerBoundaryChecks {
    @JvmStatic fun main(args: Array<String>) {
        val circle="M100 50 A50 50 0 1 1 0 50 A50 50 0 1 1 100 50 Z"
        val nodes=ManualCspPath.parse(circle).single()
        val expected=listOf(Point(100.0,50.0),Point(75.0,50+25*sqrt(3.0)),Point(25.0,50+25*sqrt(3.0)),Point(0.0,50.0),
            Point(25.0,50-25*sqrt(3.0)),Point(75.0,50-25*sqrt(3.0)),Point(100.0,50.0),Point(100.0,50.0))
        check(nodes.size==8) { "Circle must retain six 60-degree CSP sectors and explicit close node" }
        fun same(a:Point,b:Point) { check(a.distance(b)<1e-9) { "Expected $a but got $b" } }
        expected.zip(nodes).forEach { (a,b)->same(a,b) }
        val transform=Affine2D.translation(5.0,9.0)*Affine2D.scale(0.25,0.5)
        val transformed=ManualCspPath.parse(circle,transform).single()
        nodes.zip(transformed).forEach { (a,b)->val p=transform.apply(io.github.linker2k.myembro.engine.geometry.Point(a.x,a.y));same(Point(p.x,p.y),b) }
        check(ManualCspPath.parse("M1 2 l3 4 z m10 20 h5 v6")==listOf(
            listOf(Point(1.0,2.0),Point(4.0,6.0),Point(1.0,2.0)),listOf(Point(11.0,22.0),Point(16.0,22.0),Point(16.0,28.0))))
        check(ManualCspPath.parse("M0 0 C2 5 8 5 10 0 Q15 10 20 0 T30 0").single()==listOf(Point(0.0,0.0),Point(10.0,0.0),Point(20.0,0.0),Point(30.0,0.0)))
        check(ManualCspPath.parse("M0 0 A0 10 0 0 1 10 20").single()==listOf(Point(0.0,0.0),Point(10.0,20.0)))
        check(ManualCspPath.parse("M0 0 A10 10 0 0 1 0 0").single()==listOf(Point(0.0,0.0),Point(0.0,0.0)))
        val compact=ManualCspPath.parse("M100 50 A50 50 0 010 50").single()
        check(compact.size==4)
        same(Point(0.0,50.0),compact.last())
        val cubic=ManualCspPath.flatten("M0 0 C0 4 4 4 4 0",tolerance=1.0).single()
        val cubicExpected=listOf(Point(0.0,0.0),Point(0.625,2.25),Point(2.0,3.0),Point(3.375,2.25),Point(4.0,0.0))
        check(cubic.size==cubicExpected.size)
        cubicExpected.zip(cubic).forEach { (a,b)->same(a,b) }
        val flatCircle=ManualCspPath.flatten(circle).single()
        check(flatCircle.size>nodes.size)
        nodes.dropLast(1).forEach { anchor->check(flatCircle.any { it.distance(anchor)<1e-9 }) }
        check(SourceOutputBoundary.roundNative(2.5)==2)
        check(SourceOutputBoundary.roundNative(3.5)==4)
        check(SourceOutputBoundary.roundNative(-2.5)==-2)
        check(SourceOutputBoundary.roundNative(-3.5)==-4)
        fun native(x:Int,y:Int,jump:Boolean=false)=Stitch(Units.nativeToPixels(x),Units.nativeToPixels(y),jump=jump)
        val output=SourceOutputBoundary.convert(StitchPlan(listOf(ColorBlock(stitches=listOf(native(10,0,true),native(0,0),native(3,1))))))
        same(Point(Units.nativeToPixels(10)/2,Units.nativeToPixels(1)/2),output.origin)
        check(output.pattern.stitches.first().x==5) // Includes the jump in source bbox, not only needles.
        println("WorkerBoundaryChecks PASS: six-sector circle, transforms, subpaths, curve endpoints, degenerate arcs, compact flags, source cubic flatten, all-stitch output origin, bankers rounding")
    }
}
