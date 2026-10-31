// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.render.realistic

import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class PatternThreadSegmentsTest {
    @Test fun originalDisplayColorsAndNeedleOnlySegments() {
        assertEquals(0xA2A2A2,PatternThreadSegments.displayRgb(-1))
        assertEquals(0x000000,PatternThreadSegments.displayRgb(0xFF000000.toInt()))
        assertEquals(0x184687,PatternThreadSegments.displayRgb(0xFF205EB5.toInt()))
        val p=EmbPattern(listOf(Stitch(0,0,Command.STITCH),Stitch(10,0,Command.STITCH),
            Stitch(10,0,Command.TRIM),Stitch(100,100,Command.JUMP),Stitch(100,100,Command.STITCH),Stitch(110,100,Command.STITCH)),listOf(EmbThread(-1)))
        assertEquals(2,PatternThreadSegments.from(p,0.0,0.0).count())
    }
    @Test fun streamingTilesMatchesFrameAndCancellationPublishesNoTile() {
        val s=listOf(RenderThreadSegment(RenderPoint(3.0,3.0),RenderPoint(8.0,3.0),0x184687))
        val raster=RealisticThreadRendererTest().rasterizer
        val expected=RealisticThreadRenderer.render(100,100,300,s,raster)
        val actual=IntArray(100*100) { -1 }
        RealisticThreadRenderer.renderTiles(300,s,raster) { x,y,w,h,tile->
            for(yy in 0 until h)for(xx in 0 until w) {
                val tx=x+xx;val ty=y+yy
                if(tx in 0..99&&ty in 0..99)actual[ty*100+tx]=RealisticThreadRenderer.compositeOpaque(actual[ty*100+tx],tile[yy*w+xx])
            }
        }
        assertContentEquals(expected,actual)
        var tiles=0
        assertFailsWith<java.util.concurrent.CancellationException> {
            RealisticThreadRenderer.renderTiles(300,s,raster,isCancelled={true}) { _,_,_,_,_->tiles++ }
        }
        assertEquals(0,tiles)
    }
}
