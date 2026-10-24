// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace

/** User decision 2026-10-24: complete ink-connected components by character, then user-defined 50% row overlap.
 * Standard 8-neighbor flood fill, matching AutoTrace's diagonal pixel adjacency. Not jamo inference.
 * Glyph ownership is supplied by font layout, never guessed from a text-wide centerline scan.
 */
object HangulComponentOrder {
    data class GlyphBounds(val characterIndex:Int,val left:Int,val top:Int,val right:Int,val bottom:Int)
    data class Component(val id:Int,val characterIndex:Int,val left:Int,val top:Int,val right:Int,val bottom:Int,val pixelCount:Int)
    class Labeling internal constructor(val width:Int,val height:Int,private val labels:IntArray,val components:List<Component>) {
        fun component(path:AutoTraceCenterline.Path):Component {
            val ids=path.points.map { p ->
                require(p.x in 0 until width && p.y in 0 until height)
                labels[(height-1-p.y)*width+p.x]
            }.distinct()
            require(ids.size==1 && ids[0]>=0) { "Centerline crosses ink components or background" }
            return components[ids[0]]
        }
        val ordered:List<Component> get()=order(components)
    }
    /** User rule: vertical intersection >= 50% of the smaller height means one row.
     * Integer cross multiplication includes exactly 50%, without a floating tolerance.
     */
    fun sameRow(a:Component,b:Component):Boolean {
        val overlap=maxOf(0,minOf(a.bottom,b.bottom)-maxOf(a.top,b.top))
        return overlap.toLong()*2>=minOf(a.bottom-a.top,b.bottom-b.top)
    }
    /** Pairwise row overlap is not transitive. Apply its precedence constraints with a DAG,
     * rather than passing a potentially non-transitive comparator to the JVM sort.
     * An inconsistent layout is rejected explicitly; no invented row threshold breaks ties.
     */
    fun order(components:List<Component>):List<Component> {
        require(components.map { it.id }.distinct().size==components.size)
        require(components.all { it.characterIndex>=0 && it.left<it.right && it.top<it.bottom })
        val result=mutableListOf<Component>()
        for(group in components.groupBy { it.characterIndex }.toSortedMap().values) {
            val outgoing=Array(group.size) { mutableListOf<Int>() };val degree=IntArray(group.size)
            for(i in group.indices)for(j in i+1 until group.size) {
                val a=group[i];val b=group[j]
                val comparison=if(sameRow(a,b)) compareValuesBy(a,b,{ it.left },{ it.top },{ it.id })
                    else compareValuesBy(a,b,{ it.top },{ it.left },{ it.id })
                val from=if(comparison<0)i else j;val to=if(comparison<0)j else i
                outgoing[from]+=to;degree[to]++
            }
            val ready=java.util.PriorityQueue<Int>(compareBy<Int> { group[it].top }.thenBy { group[it].left }.thenBy { group[it].id })
            group.indices.filterTo(mutableListOf()) { degree[it]==0 }.forEach { ready.add(it) }
            var emitted=0
            while(ready.isNotEmpty()) {
                val index=ready.remove();result+=group[index];emitted++
                for(next in outgoing[index]) { degree[next]--;if(degree[next]==0)ready.add(next) }
            }
            require(emitted==group.size) { "Conflicting component row-order constraints" }
        }
        return result
    }
    fun label(width:Int,height:Int,pixels:ByteArray,glyphs:List<GlyphBounds>):Labeling {
        require(width>0 && height>0 && width.toLong()*height<=1_048_576 && pixels.size==width*height)
        require(pixels.all { it==0.toByte() || it==255.toByte() })
        require(glyphs.isNotEmpty() && glyphs.map { it.characterIndex }.distinct().size==glyphs.size)
        require(glyphs.all { it.characterIndex>=0 && it.left<it.right && it.top<it.bottom })
        val labels=IntArray(pixels.size) { -1 };val queue=IntArray(pixels.size);val components=mutableListOf<Component>()
        for(seed in pixels.indices) {
            if(pixels[seed]!=0.toByte() || labels[seed]>=0) continue
            val id=components.size;var read=0;var write=1;queue[0]=seed;labels[seed]=id
            var left=width;var right=0;var top=height;var bottom=0
            while(read<write) {
                val index=queue[read++];val x=index%width;val y=index/width
                left=minOf(left,x);right=maxOf(right,x+1);top=minOf(top,y);bottom=maxOf(bottom,y+1)
                for(dy in -1..1) for(dx in -1..1) {
                    if(dx==0 && dy==0)continue
                    val nx=x+dx;val ny=y+dy
                    if(nx !in 0 until width || ny !in 0 until height)continue
                    val next=ny*width+nx
                    if(pixels[next]==0.toByte() && labels[next]<0) { labels[next]=id;queue[write++]=next }
                }
            }
            val owners=glyphs.filter { left>=it.left && right<=it.right && top>=it.top && bottom<=it.bottom }
            require(owners.size==1) { "Ink component needs unambiguous font-layout character ownership" }
            components+=Component(id,owners.single().characterIndex,left,top,right,bottom,write)
        }
        return Labeling(width,height,labels,components)
    }
}
