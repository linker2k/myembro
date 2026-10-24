// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from AutoTrace src/{fit,vector,spline}.c@1a232b27818a46a05cd25edec09e2f77c4e9ac01
// (GPL-2.0-or-later), Copyright (C) 1992 Free Software Foundation, Inc.
package io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** AutoTrace 0.31.10 centerline fitting defaults; coordinates remain Cartesian pixels.
 * preserve_width=false: the caller supplies satin width separately. Float operations intentionally
 * mirror gfloat, including upstream corner/closed-curve and tangent traversal behavior.
 */
object AutoTraceFit {
    data class Point(val x: Float, val y: Float, val z: Float = 0f) {
        internal operator fun plus(p: Point) = Point(x+p.x,y+p.y,z+p.z)
        internal operator fun minus(p: Point) = Point(x-p.x,y-p.y,z-p.z)
        internal operator fun times(k: Float) = Point(x*k,y*k,z*k)
        internal fun dot(p: Point) = x*p.x+y*p.y+z*p.z
        internal fun length() = sqrt(dot(this).toDouble()).toFloat()
        internal fun normalized(): Point { val m=length(); return if(m>0f) Point(x/m,y/m,z/m) else this }
    }
    data class Segment(val degree: Int, val v: List<Point>)
    data class Path(val open: Boolean, val clockwise: Boolean, val colorRgb: Int, val segments: List<Segment>)
    private class Curve(var points: List<Point>, val cyclic: Boolean = false) {
        var previous: Curve? = null
        var next: Curve? = null
        var start: Point? = null
        var end: Point? = null
        fun prev(i: Int) = if(i-1<0) if(cyclic) points.size+i-1 else -1 else i-1
        fun next(i: Int) = if(i+1>=points.size) if(cyclic) (i+1)%points.size else points.size else i+1
    }
    private data class Spline(var degree: Int, val v: List<Point>, var linearity: Float = 0f) {
        fun evaluate(t: Float): Point {
            var p=v.take(degree+1)
            repeat(degree) { p=(0 until p.size-1).map { p[it]*(1f-t)+p[it+1]*t } }
            return p[0]
        }
    }
    private val zero=Point(0f,0f)
    private fun equal(a: Float,b: Float) = a==b || abs(a-b).toDouble()<=0.00001

    fun fit(paths: List<AutoTraceCenterline.Path>): List<Path> = paths.map { path ->
        require(path.points.isNotEmpty()) { "Empty pixel outline" }
        require(path.points.all { it.x in 0..65535 && it.y in 0..65535 }) { "Pixel coordinate out of range" }
        val curves=split(path)
        curves.forEach { removeKnees(it,path.clockwise) }
        curves.forEach(::filter)
        if(curves[0].cyclic) curves[0].points=curves[0].points+curves[0].points[0]
        val fitted=curves.flatMap { curve ->
            val splines=fitCurve(curve)
            // fit.c:change_bad_lines applies to each original corner-to-corner curve.
            if(splines.any { it.degree==3 }) splines.forEach { if(it.degree==1 && it.linearity>0.01f) it.degree=3 }
            splines
        }
        Path(path.open,path.clockwise,path.colorRgb,fitted.map { Segment(it.degree,it.v) })
    }

    // fit.c:split_at_corners / find_corners / find_vectors / remove_adjacent_corners
    private fun split(path: AutoTraceCenterline.Path): List<Curve> {
        val points=path.points.map { Point(it.x.toFloat(),it.y.toFloat()) }
        val surround=if(points.size>10) 4 else (points.size-3)/2
        val corners=if(surround>=2) corners(points,path.open,surround) else emptyList()
        val curves=mutableListOf<Curve>()
        if(corners.isEmpty()) curves+=Curve(points,!path.open) else {
            for(i in 0 until corners.size-1) curves+=Curve(points.subList(corners[i],corners[i+1]+1))
            curves+=Curve(points.drop(corners.last())+if(path.open) emptyList() else points.take(corners.first()+1))
        }
        for(i in 0 until curves.size-1) { curves[i].next=curves[i+1]; curves[i+1].previous=curves[i] }
        if(path.open && corners.isNotEmpty() && curves.size>1) curves[curves.size-2].next=null
        curves.last().next=curves.first(); curves.first().previous=curves.last()
        return curves
    }
    private fun corners(points: List<Point>,open: Boolean,surround: Int): List<Int> {
        val n=points.size
        fun angle(index: Int): Float {
            var incoming=zero; var outgoing=zero
            for(k in 1..surround) {
                incoming+=points[(index-k+n)%n]-points[index]
                outgoing+=points[(index+k)%n]-points[index]
            }
            var v=outgoing.normalized().dot(incoming.normalized())
            if(equal(v,1f)) v=1f else if(equal(v,-1f)) v=-1f
            require(v in -1f..1f) { "AutoTrace corner angle domain" }
            return acos(v.toDouble()).toFloat()*180f/Math.PI.toFloat()
        }
        val result=mutableListOf<Int>()
        if(open && n-1<=surround*2) return result
        if(open) result+=0
        var p=if(open) surround else 0
        val end=if(open) n-1-surround else n-1
        while(p<=end) {
            var a=angle(p)
            if(abs(a)<=100f) {
                var bestAngle=a; var bestIndex=p; val equally=mutableListOf<Int>()
                var q=p; var i=p+1
                while(true) {
                    if(a<=60f && q>=p) result+=q
                    if(i>=bestIndex+surround || i>=n) break
                    q=i%n; a=angle(q)
                    if(equal(a,bestAngle)) equally+=q else if(a<bestAngle) {
                        bestAngle=a; bestIndex=q; i=q; equally.clear()
                    }
                    i++
                }
                if(bestAngle>60f && bestIndex>=p) { result+=bestIndex; result.addAll(equally) }
                p=if(q<p) n else q
            }
            p++
        }
        if(result.isEmpty()) return result
        result.sort()
        // remove_adjacent_corners=false still checks the final corner in the C source.
        val sorted=result.dropLast(1).toMutableList()
        val last=result.last()
        if(sorted.isEmpty() || !(last==sorted.last()+1 || (last==n-(if(open) 2 else 1) && result[0]==0))) sorted+=last
        return sorted
    }
    // fit.c:remove_knee_points (integer input, before any filtering).
    private fun removeKnees(curve: Curve,clockwise: Boolean) {
        val points=curve.points; val offset=if(curve.cyclic) 0 else 1
        var previous=points[curve.prev(offset)]
        val trimmed=mutableListOf<Point>()
        if(!curve.cyclic) trimmed+=points[0]
        fun oneZero(p: Point) = (p.x==0f && p.y!=0f)||(p.y==0f && p.x!=0f)
        for(i in offset until points.size-offset) {
            val current=points[i]; val before=previous-current; val after=points[curve.next(i)]-current
            val knee=if(clockwise) {
                (before.x==-1f && after.y==1f)||(before.y==1f && after.x==1f)||
                    (before.x==1f && after.y==-1f)||(before.y==-1f && after.x==-1f)
            } else {
                (before.y==1f && after.x==-1f)||(before.x==1f && after.y==1f)||
                    (before.y==-1f && after.x==1f)||(before.x==-1f && after.y==-1f)
            }
            if(!(oneZero(before)&&oneZero(after)&&knee)) { previous=current; trimmed+=current }
        }
        if(!curve.cyclic) trimmed+=points.last()
        curve.points=trimmed
    }
    // fit.c:filter; preserve prev_new_point across iterations, including rejected collapses.
    private fun filter(curve: Curve) {
        if(curve.points.size<5) return
        val offset=if(curve.cyclic) 0 else 1
        var previous=Point(Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE)
        repeat(4) {
            val points=curve.points; val filtered=mutableListOf<Point>(); var collapsed=false
            if(offset!=0) filtered+=points[0]
            for(i in offset until points.size-offset) {
                val candidate=points[i]; val prev=curve.prev(i); val next=curve.next(i)
                val prevprev=curve.prev(prev); val nextnext=curve.next(next)
                var incoming=zero+(points[prev]-candidate)
                if(prevprev>=0) incoming+=points[prevprev]-candidate
                var outgoing=zero+(points[next]-candidate)
                if(nextnext<points.size) outgoing+=points[nextnext]-candidate
                val sum=incoming+outgoing
                val p=Point(candidate.x+sum.x/6f,candidate.y+sum.y/6f,candidate.z+sum.z/6f)
                if(abs(previous.x-p.x).toDouble()<0.3 && abs(previous.y-p.y).toDouble()<0.3 && abs(previous.z-p.z).toDouble()<0.3) {
                    collapsed=true; break
                }
                filtered+=p; previous=p
            }
            if(!collapsed) {
                if(offset!=0) filtered+=points.last()
                curve.points=filtered
            }
        }
    }
    // fit.c:find_tangent / find_half_tangent; tangents are averaged, NOT normalized.
    private fun tangent(curve: Curve,toStart: Boolean,cross: Boolean) {
        if((if(toStart) curve.start else curve.end)!=null) return
        var count=0; var surround=3; var t: Point
        fun half(c: Curve,start: Boolean): Point {
            val factor=if(start) 1 else -1; val index=if(start) 0 else c.points.lastIndex
            var sum=zero
            for(p in 1..minOf(c.points.size/2,surround)) {
                val i=p*factor+index
                if(i !in c.points.indices) break
                sum+=(c.points[i]-c.points[index])*factor.toFloat(); count++
            }
            return sum
        }
        do {
            t=half(curve,toStart)
            if(cross || curve.cyclic) t+=half(requireNotNull(if(toStart) curve.previous else curve.next),true)
            surround--
            check(surround>=0 || t.x!=0f || t.y!=0f) { "AutoTrace degenerate tangent" }
        } while(t.x==0f && t.y==0f)
        check(count>0)
        t=t*(1.0/count).toFloat()
        if(toStart) curve.start=t else curve.end=t
        if(curve.cyclic) { if(curve.start!=null) curve.start=t; if(curve.end!=null) curve.end=t }
    }
    private fun fitCurve(curve: Curve): List<Spline> {
        val points=curve.points
        if(points.size<2) return emptyList()
        if(points.size<4) return listOf(Spline(1,listOf(points.first(),points.first(),points.last(),points.last())))
        tangent(curve,true,false); tangent(curve,false,false)
        // fit.c:set_initial_parameter_values
        val t=FloatArray(points.size)
        for(i in 1 until points.size) t[i]=t[i-1]+(points[i]-points[i-1]).length()
        if(t.last()==0f) t[t.lastIndex]=1f
        for(i in 1 until points.size) t[i]=t[i]/t.last()
        val spline=fitOne(curve,t)
        var error=java.lang.Float.MIN_NORMAL; var worst=points.size+1
        for(i in points.indices) {
            val e=(points[i]-spline.evaluate(t[i])).length()
            if(e>=error) { error=e; worst=i }
        }
        if(error<2f && !curve.cyclic) {
            if(linearEnough(spline,curve,t)) spline.degree=1
            return listOf(spline)
        }
        // fit.c:fit_with_least_squares subdivision preserves shared endpoint/tangents.
        check(worst in 1 until points.lastIndex) { "AutoTrace unsplittable curve" }
        val left=Curve(points.take(worst+1)); val right=Curve(points.drop(worst))
        right.next=curve.next; right.previous=left; left.next=right; left.previous=curve; curve.next=left
        left.start=curve.start; right.end=curve.end
        tangent(left,false,true); right.start=left.end
        return fitCurve(left)+fitCurve(right)
    }
    private fun square(t: Float)=t*t
    private fun cube(t: Float)=t*t*t
    // fit.c:fit_one_spline; same Bernstein polynomial order and determinant behavior.
    private fun fitOne(curve: Curve,t: FloatArray): Spline {
        val start=requireNotNull(curve.start); val end=requireNotNull(curve.end)
        val first=curve.points.first(); val last=curve.points.last()
        var c00=0f; var c01=0f; var c11=0f; var x0=0f; var x1=0f
        for(i in curve.points.indices) {
            val u=t[i]; val b0=cube(1f-u); val b1=3f*u*square(1f-u); val b2=3f*square(u)*(1f-u); val b3=cube(u)
            val a0=start*b1; val a1=end*b2
            c00+=a0.dot(a0); c01+=a0.dot(a1); c11+=a1.dot(a1)
            val temp=curve.points[i]-(first*b0+(first*b1+(last*b2+last*b3)))
            x0+=temp.dot(a0); x1+=temp.dot(a1)
        }
        val determinant=c00*c11-c01*c01
        val alpha1=if(determinant==0f) 0f else (x0*c11-x1*c01)/determinant
        val alpha2=if(determinant==0f) 0f else (c00*x1-c01*x0)/determinant
        return Spline(3,listOf(first,first+start*alpha1,last+end*alpha2,last))
    }
    // fit.c:spline_linear_enough
    private fun linearEnough(spline: Spline,curve: Curve,t: FloatArray): Boolean {
        val a=spline.v.last()-spline.v.first(); val distance=a.dot(a)
        var total=0f
        for(i in curve.points.indices) {
            val p=spline.evaluate(t[i])-spline.v.first(); val w=a.dot(p)/distance
            total+=(p-a*w).length()
        }
        total/=(curve.points.size-1).toFloat()
        spline.linearity=total
        return total<minOf(1f,distance*0.5f)
    }
}
