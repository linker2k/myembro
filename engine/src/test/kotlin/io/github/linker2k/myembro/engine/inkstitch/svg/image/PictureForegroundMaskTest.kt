// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.elements.DefaultTatami
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.math.round
import kotlin.test.*

class PictureForegroundMaskTest {
    @Test fun exactCellsRetainSinglePixelHoleAndDisconnectedInk() {
        val w=8;val h=6
        val pixels=BooleanArray(w*h) { i -> i%w in 1..6 && i/w in 1..4 }
        pixels[2*w+3]=false;pixels[0]=true
        val region=PictureForegroundMask.polygonize(w,h,pixels,10.0)
        assertEquals(pixels.count { it }*100.0,region.area,1e-8)
        for(y in 0 until h) for(x in 0 until w)
            assertEquals(pixels[y*w+x],region.covers(Point((x+.5-w/2.0)*10,(y+.5-h/2.0)*10)))
    }

    @Test fun expandedTopCrossesColorBoundaryButNeverMaskHoleOrExterior() {
        val foreground=BooleanArray(20*16) { i -> i%20 in 1..18 && i/20 in 1..14 }
        for(y in 6..9) for(x in 12..15) foreground[y*20+x]=false
        val mask=PictureForegroundMask.polygonize(20,16,foreground,10.0)
        val color=SvgPath.polygons("M-90 -70 H0 V70 H-90 Z",FillRule.EVEN_ODD)
        val thread=EmbThread(0xff000000.toInt(),"Black")
        val base=DefaultTatami.stitchGroups(color,thread)
        val expanded=DefaultTatami.stitchGroups(color,thread,expandMm=.3,clipRegion=mask)
        fun points(groups:List<io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup>,tag:String)=
            groups.flatMap { it.stitches }.filter { tag in it.tags }.map { Point(it.x*10/PIXELS_PER_MM,it.y*10/PIXELS_PER_MM) }
        assertEquals(points(base,"fill_underlay"),points(expanded,"fill_underlay"))
        val top=points(expanded,"fill_top")
        assertTrue(top.any { it.x>2.9 })
        // Judge the actual native integer needle coordinates (PES), not sub-unit SVG rounding.
        val outside=top.filterNot { mask.covers(Point(round(it.x),round(it.y))) }
        assertTrue(outside.isEmpty(),outside.take(8).toString())
        val zero=DefaultTatami.stitchGroups(color,thread,expandMm=0.0,clipRegion=mask)
        assertTrue(points(zero,"fill_top").all { mask.covers(Point(round(it.x),round(it.y))) })
    }
}
