// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Inkex inkex/paths/arc.py and inkex/paths/path.py@2044255c7a79a0d0b5fb20cb8b0f573ed1ae7892 (GPL-2.0-or-later), Copyright (C) 2018 Martin Owens, 2023 Jonathan Neuhauser.
// Flattening: inkex/bezier.py, Copyright (C) 2005 Aaron Spike, 2010 Nick Drobchenko;
// line distance: inkex/transforms.py, Copyright (C) 2006 Jean-Francois Barraud, 2010 Alvin Penner, same commit/license.
// This derivative is distributed under GPL-3.0-or-later; see THIRD_PARTY_NOTICES.md.
package io.github.linker2k.myembro.engine.inkstitch.oracle

import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import kotlin.math.*

/** The pinned Inkex cubic-superpath boundary, distinct from custom lock Path.end_points.
 * Manual returns only CSP nodes; running flattens the SAME cubic controls after transformation.
 */
object ManualCspPath {
    private const val MAX_NODES = 200_000
    private val token = Regex("[A-Za-z]|[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")
    private data class Node(val before: Point, val point: Point, var after: Point)

    fun parse(data: String, transform: Affine2D = Affine2D.IDENTITY): List<List<Point>> =
        parseCsp(data).map { path -> path.map { apply(transform,it.point) } }

    /** Ink/Stitch elements/element.py: transform CSP first, then bezier.cspsubdiv(path, 0.1). */
    fun flatten(data: String, transform: Affine2D = Affine2D.IDENTITY, tolerance: Double = 0.1): List<List<Point>> {
        require(tolerance.isFinite() && tolerance > 0)
        var count=0
        return parseCsp(data).map { source ->
            val nodes=source.map { Node(apply(transform,it.before),apply(transform,it.point),apply(transform,it.after)) }
            val output=mutableListOf<Point>()
            fun append(point:Point) { require(++count<=MAX_NODES) { "Flattened CSP node limit exceeded" };output+=point }
            fun split(p0:Point,p1:Point,p2:Point,p3:Point,depth:Int) {
                if(max(distanceToSegment(p0,p3,p1),distanceToSegment(p0,p3,p2))<=tolerance) { append(p3);return }
                require(depth<40) { "CSP flattening depth exceeded" }
                // Inkex beziersplitatt(bez, .5), preserving its arithmetic and left-to-right order.
                fun mid(a:Point,b:Point)=a+(b-a)*0.5
                val m1=mid(p0,p1);val m2=mid(p1,p2);val m3=mid(p2,p3)
                val m4=mid(m1,m2);val m5=mid(m2,m3);val m=mid(m4,m5)
                split(p0,m1,m4,m,depth+1)
                split(m,m5,m3,p3,depth+1)
            }
            if(nodes.isNotEmpty()) append(nodes.first().point)
            for(i in 1 until nodes.size) split(nodes[i-1].point,nodes[i-1].after,nodes[i].before,nodes[i].point,0)
            output
        }
    }

    private fun parseCsp(data:String):List<List<Node>> {
        val matches=token.findAll(data).toList()
        var offset=0
        for(match in matches) {
            require(data.substring(offset,match.range.first).all { it.isWhitespace()||it==',' }) { "Invalid CSP SVG path" }
            offset=match.range.last+1
        }
        require(data.substring(offset).all { it.isWhitespace()||it==',' }) { "Invalid CSP SVG path" }
        val tokens=matches.map { it.value }.toMutableList()
        var index=0
        var command:Char?=null
        var previous=' '
        var current=Point(0.0,0.0)
        var first=current
        var quadratic:Point?=null
        var nodes=mutableListOf<Node>()
        val paths=mutableListOf<List<Node>>()
        var count=0
        fun add(node:Node) { require(++count<=MAX_NODES) { "CSP node limit exceeded" };nodes+=node }
        fun flush() { if(nodes.isNotEmpty()) paths+=nodes.toList();nodes=mutableListOf() }
        fun number(flag:Boolean=false):Double {
            require(index<tokens.size) { "Incomplete CSP SVG command" }
            val text=tokens[index]
            if(flag) {
                require(text.first() in "01") { "Invalid SVG arc flag" }
                if(text.length==1) index++ else tokens[index]=text.drop(1)
                return text.first().digitToInt().toDouble()
            }
            index++
            return text.toDoubleOrNull()?.takeIf { it.isFinite() } ?:throw IllegalArgumentException("Invalid CSP SVG number")
        }
        while(index<tokens.size) {
            if(tokens[index].length==1&&tokens[index][0].isLetter()) {
                command=tokens[index++][0]
                if(command.uppercaseChar()=='Z') {
                    require(nodes.isNotEmpty()) { "Close without moveto" }
                    add(nodes.first().copy()) // Source CSP duplicates the first node, including its handles.
                    current=first;command=null;previous='Z';quadratic=null
                    continue
                }
            }
            val op=requireNotNull(command) { "CSP SVG command missing" }
            val upper=op.uppercaseChar()
            val argc=when(upper) { 'M','L','T'->2;'H','V'->1;'S','Q'->4;'C'->6;'A'->7;else->throw IllegalArgumentException("Unsupported CSP command $op") }
            require(nodes.isNotEmpty()||upper=='M') { "CSP SVG must start with moveto" }
            val args=DoubleArray(argc) { number(upper=='A'&&it in 3..4) }
            val relative=op.isLowerCase()
            fun point(at:Int)=Point(args[at]+if(relative)current.x else 0.0,args[at+1]+if(relative)current.y else 0.0)
            val end=when(upper) { 'H'->Point(args[0]+if(relative)current.x else 0.0,current.y);'V'->Point(current.x,args[0]+if(relative)current.y else 0.0);else->point(argc-2) }
            when(upper) {
                'M'->{flush();add(Node(end,end,end));current=end;first=end;command=if(relative)'l' else 'L'}
                'A'->{
                    val arc=arcNodes(current,args[0],args[1],args[2],args[3]!=0.0,args[4]!=0.0,end)
                    nodes.last().after=arc.first().after
                    arc.drop(1).forEach(::add)
                    current=arc.last().point
                }
                'C','S','Q','T'->{
                    val cp1:Point;val cp2:Point
                    if(upper=='C'||upper=='S') {
                        cp1=if(upper=='C')point(0) else if(previous in "CS")current*2.0-nodes.last().before else current
                        cp2=point(argc-4)
                    } else {
                        val q=if(upper=='Q')point(0) else if(previous in "QT")current*2.0-checkNotNull(quadratic) else current
                        cp1=current+(q-current)*(2.0/3.0)
                        cp2=end+(q-end)*(2.0/3.0)
                        quadratic=q
                    }
                    nodes.last().after=cp1
                    add(Node(cp2,end,end));current=end
                }
                else->{nodes.last().after=current;add(Node(end,end,end));current=end}
            }
            if(upper !in "QT") quadratic=null
            previous=upper
        }
        flush()
        return paths
    }

    /** All controls from pinned Inkex arc_to_path; sector count is NOT ceil(angle/(pi/2)). */
    private fun arcNodes(start:Point,rx:Double,ry:Double,degrees:Double,large:Boolean,sweep:Boolean,end:Point):List<Node> {
        if(rx==0.0||ry==0.0||start==end) return listOf(Node(start,start,start),Node(end,end,end))
        val theta=degrees*PI/180.0
        data class Matrix(val a:Double,val b:Double,val c:Double,val d:Double) {
            operator fun times(q:Matrix)=Matrix(a*q.a+b*q.c,a*q.b+b*q.d,c*q.a+d*q.c,c*q.b+d*q.d)
            fun apply(p:Point)=Point(a*p.x+b*p.y,c*p.x+d*p.y)
        }
        fun rotation(t:Double)=Matrix(cos(t),-sin(t),sin(t),cos(t))
        val forward=rotation(theta)*Matrix(1/rx,0.0,0.0,1/ry)*rotation(-theta)
        val inverse=rotation(theta)*Matrix(rx,0.0,0.0,ry)*rotation(-theta)
        val a=forward.apply(start);val b=forward.apply(end)
        var k=Point(-(b.y-a.y),b.x-a.x)
        val chordSquared=k.x*k.x+k.y*k.y
        k/=sqrt(chordSquared)
        var distance=sqrt(max(0.0,1-chordSquared/4))
        if(large==sweep)distance *= -1
        val center=Point((b.x+a.x)/2+distance*k.x,(b.y+a.y)/2+distance*k.y)
        val oa=a-center;val ob=b-center
        fun norm(p:Point)=sqrt(p.x*p.x+p.y*p.y)
        var firstAngle=acos(oa.x/norm(oa));if(oa.y<0)firstAngle *= -1
        var lastAngle=acos(ob.x/norm(ob));if(ob.y<0)lastAngle *= -1
        if(sweep&&firstAngle>lastAngle)lastAngle+=2*PI
        if(!sweep&&firstAngle<lastAngle)lastAngle-=2*PI
        val sectors=(abs(firstAngle-lastAngle)*2/PI).toInt()+1
        require(sectors in 1..MAX_NODES)
        val step=(lastAngle-firstAngle)/sectors
        val v=4*tan(step/4.0)/3.0
        val result=(0..sectors).map { i->
            val angle=firstAngle+i*step
            Node(inverse.apply(Point(center.x+cos(angle)-(-v)*sin(angle),center.y+sin(angle)+(-v)*cos(angle))),
                inverse.apply(Point(center.x+cos(angle),center.y+sin(angle))),
                inverse.apply(Point(center.x+cos(angle)-v*sin(angle),center.y+sin(angle)+v*cos(angle))))
        }.toMutableList()
        result[0]=result[0].copy(before=result[0].point)
        result[result.lastIndex]=result.last().copy(after=result.last().point)
        return result
    }

    /** Pinned DirectedLineSegment.distance_to_point (finite segment, including zero length). */
    private fun distanceToSegment(start:Point,end:Point,point:Point):Double {
        val v=end-start;val w=point-start
        val dot=w.x*v.x+w.y*v.y
        fun norm(p:Point)=sqrt(p.x*p.x+p.y*p.y)
        if(dot<=0)return norm(point-start)
        val squared=v.x*v.x+v.y*v.y
        if(squared<=dot)return norm(point-end)
        return abs(v.x*(start.y-point.y)-(start.x-point.x)*v.y)/sqrt(squared)
    }
    private fun apply(transform:Affine2D,p:Point)=transform.apply(io.github.linker2k.myembro.engine.geometry.Point(p.x,p.y)).let { Point(it.x,it.y) }
}
