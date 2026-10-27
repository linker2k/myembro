// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import kotlin.math.ceil

/** Standard border-label mode and four-connected small-region merging. No stitch heuristics. */
internal object ColorRegionCleanup {
    data class BackgroundSelection(val visible: List<Int>, val labels: IntArray,
        val backgroundIndex: Int, val backgroundRgb: Int?) {
        val foreground: List<Int> get() = visible.filter { backgroundIndex < 0 || labels[it] != backgroundIndex }
    }

    /** Shared ColorDrawing rule: N+1 Wu labels, opaque border mode; any transparency disables border removal. */
    fun selectBackground(width: Int, height: Int, pixels: IntArray, colorCount: Int): BackgroundSelection {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
        val visible = pixels.indices.filter { pixels[it] ushr 24 != 0 }
        val labels = IntArray(pixels.size) { -1 }
        if (visible.isEmpty()) return BackgroundSelection(visible, labels, -1, null)
        val preliminary = ImageQuantizer.quantize(visible.map { pixels[it] and 0xffffff }.toIntArray(), colorCount + 1)
        val indices = preliminary.copyIndices()
        visible.forEachIndexed { i, p -> labels[p] = indices[i] }
        val background = if (visible.size < pixels.size) -1 else borderMode(width, height, labels)
        return BackgroundSelection(visible, labels, background, preliminary.palette.getOrNull(background))
    }

    fun borderMode(width:Int,height:Int,labels:IntArray):Int {
        val counts=linkedMapOf<Int,Int>()
        for(y in 0 until height) for(x in 0 until width)
            if(x==0 || y==0 || x==width-1 || y==height-1) {
                val label=labels[y*width+x];counts[label]=(counts[label] ?: 0)+1
            }
        return counts.maxBy { it.value }.key
    }

    /** Components smaller than [minimumAreaMm2] merge into the largest adjacent retained region.
     * Background is an eligible destination, but never a source: transparent holes stay transparent.
     * No nonadjacent color is invented. Ties follow stable raster order.
     */
    fun mergeSmall(width:Int,height:Int,labels:IntArray,pixelAreaMm2:Double,minimumAreaMm2:Double):IntArray {
        require(pixelAreaMm2.isFinite() && pixelAreaMm2>0 && minimumAreaMm2.isFinite() && minimumAreaMm2>=0)
        val minimum=ceil(minimumAreaMm2/pixelAreaMm2).toInt()
        if(minimum<=1) return labels.copyOf()
        val owner=IntArray(labels.size) { -1 };val queue=IntArray(labels.size)
        val colors=mutableListOf<Int>();val sizes=mutableListOf<Int>()
        fun neighbors(p:Int,visit:(Int)->Unit) {
            if(p%width>0) visit(p-1);if(p%width+1<width) visit(p+1)
            if(p>=width) visit(p-width);if(p+width<labels.size) visit(p+width)
        }
        for(p in labels.indices) if(owner[p]<0) {
            val id=colors.size;colors+=labels[p];var head=0;var tail=1;queue[0]=p;owner[p]=id
            while(head<tail) {
                val current=queue[head++]
                neighbors(current) { q -> if(owner[q]<0 && labels[q]==labels[p]) { owner[q]=id;queue[tail++]=q } }
            }
            sizes+=tail
        }
        val adjacent=Array(colors.size) { sortedSetOf<Int>() }
        for(p in labels.indices) neighbors(p) { q -> if(owner[p]!=owner[q]) adjacent[owner[p]]+=owner[q] }
        val destination=IntArray(colors.size) { it }
        for(id in colors.indices) if(colors[id]>=0 && sizes[id]<minimum) {
            val target=adjacent[id].filter { sizes[it]>=minimum }.maxByOrNull { sizes[it] }
            if(target!=null) destination[id]=target
        }
        return IntArray(labels.size) { colors[destination[owner[it]]] }
    }
}
