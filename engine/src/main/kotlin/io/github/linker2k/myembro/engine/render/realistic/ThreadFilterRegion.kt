// SPDX-License-Identifier: GPL-3.0-or-later
// Ported coordinate rules from Inkscape src/display/{drawing-item.cpp,nr-filter-slot.cpp,nr-filter-units.cpp}
// @ebf0e940d050842d608d57f6a04e6daaa5c65f37 (GPL-2.0-or-later), Inkscape contributors.
package io.github.linker2k.myembro.engine.render.realistic

import kotlin.math.*

/** Object-bounding-box filter x=0,y=0,width=1,height=1, rounded OUTWARDS in device space.
 * drawing-item.cpp:577–581,734–748,826; FilterSlot:31–56. No blur padding outside _drawbox.
 */
internal data class ThreadFilterRegion(val left: Int, val top: Int, val width: Int, val height: Int) {
    companion object {
        fun bounds(path: List<ThreadPathCommand>): ThreadFilterRegion {
            var minX=Double.POSITIVE_INFINITY;var minY=Double.POSITIVE_INFINITY
            var maxX=Double.NEGATIVE_INFINITY;var maxY=Double.NEGATIVE_INFINITY
            fun include(p:RenderPoint) { minX=min(minX,p.x);minY=min(minY,p.y);maxX=max(maxX,p.x);maxY=max(maxY,p.y) }
            var current=RenderPoint(0.0,0.0);var start=current
            for(command in path) when(command) {
                is ThreadPathCommand.Move -> { current=command.p;start=current;include(current) }
                is ThreadPathCommand.Line -> { include(command.p);current=command.p }
                is ThreadPathCommand.Cubic -> {
                    val p0=current;val p1=command.a;val p2=command.b;val p3=command.end
                    include(p3)
                    // Exact cubic derivative roots; same geometric bounds operation as
                    // bounds_exact_transformed, with no tessellation or fitted tolerance.
                    fun roots(a:Double,b:Double,c:Double,d:Double):List<Double> {
                        val aa=-a+3*b-3*c+d;val bb=2*(a-2*b+c);val cc=b-a
                        if(aa==0.0)return if(bb==0.0)emptyList() else listOf(-cc/bb)
                        val disc=bb*bb-4*aa*cc
                        if(disc<0)return emptyList()
                        val r=sqrt(disc)
                        return listOf((-bb+r)/(2*aa),(-bb-r)/(2*aa))
                    }
                    for(t in roots(p0.x,p1.x,p2.x,p3.x)+roots(p0.y,p1.y,p2.y,p3.y)) if(t>0&&t<1) {
                        val u=1-t
                        include(RenderPoint(u*u*u*p0.x+3*u*u*t*p1.x+3*u*t*t*p2.x+t*t*t*p3.x,
                            u*u*u*p0.y+3*u*u*t*p1.y+3*u*t*t*p2.y+t*t*t*p3.y))
                    }
                    current=p3
                }
                ThreadPathCommand.Close -> current=start
            }
            require(minX.isFinite()&&minY.isFinite()&&maxX.isFinite()&&maxY.isFinite())
            val left=floor(minX).toInt();val top=floor(minY).toInt()
            return ThreadFilterRegion(left,top,ceil(maxX).toInt()-left,ceil(maxY).toInt()-top)
        }
    }
    fun local(path:List<ThreadPathCommand>):List<ThreadPathCommand> {
        fun p(p:RenderPoint)=RenderPoint(p.x-left,p.y-top)
        return path.map { when(it) {
            is ThreadPathCommand.Move->ThreadPathCommand.Move(p(it.p))
            is ThreadPathCommand.Line->ThreadPathCommand.Line(p(it.p))
            is ThreadPathCommand.Cubic->ThreadPathCommand.Cubic(p(it.a),p(it.b),p(it.end))
            ThreadPathCommand.Close->ThreadPathCommand.Close
        } }
    }
}

/** Thread-confined, explicit caller-owned pixel-payload budget; no invented application memory policy/default.
 * Exact geometry in the key prevents angle/phase quantization and rotations of prelit sprites.
 * Key/object overhead is additional and measured by the benchmark's heap/RSS sampler.
 */
class ThreadShapeCache(val maxPixelBytes: Long) {
    init { require(maxPixelBytes>=0) }
    internal data class Key(val length:Double,val angle:Double,val dpi:Int,val pixelScale:Double,
        val phaseX:Double,val phaseY:Double,val rgb:Int,val width:Int,val height:Int,
        val path:List<ThreadPathCommand>)
    private val entries=linkedMapOf<Key,IntArray>()
    var hits=0L;private set
    var misses=0L;private set
    var cachedPixelBytes=0L;private set
    internal fun getOrBake(key:Key,bake:()->IntArray):IntArray {
        entries.remove(key)?.let { entries[key]=it;hits++;return it }
        misses++
        val value=bake();val bytes=value.size.toLong()*Int.SIZE_BYTES
        if(bytes<=maxPixelBytes) {
            while(cachedPixelBytes+bytes>maxPixelBytes&&entries.isNotEmpty()) {
                val first=entries.entries.first();cachedPixelBytes-=first.value.size.toLong()*Int.SIZE_BYTES;entries.remove(first.key)
            }
            entries[key]=value;cachedPixelBytes+=bytes
        }
        return value
    }
}
