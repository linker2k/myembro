// SPDX-License-Identifier: GPL-3.0-or-later
// Color policy ported from Ink/Stitch lib/threads/color.py@b0edd96311ece82ee48816dc93466274b12c2a9c
// (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors.
package io.github.linker2k.myembro.engine.render.realistic

import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import kotlin.math.*

/** Same STITCH->STITCH rule as RenderData. Never renders jumps/trims/color transitions as thread. */
object PatternThreadSegments {
    fun from(pattern:EmbPattern,originXUnits:Double,originYUnits:Double):Sequence<RenderThreadSegment> = sequence {
        var block=0
        var rgb=displayRgb(pattern.threadAt(block).color)
        var previous:io.github.linker2k.myembro.engine.model.Stitch?=null
        for(stitch in pattern.stitches) {
            val a=previous
            if(stitch.command==Command.COLOR_CHANGE) { block++;rgb=displayRgb(pattern.threadAt(block).color) }
            if(stitch.command==Command.STITCH && a?.command==Command.STITCH && (a.x!=stitch.x || a.y!=stitch.y)) {
                val units=ThreadShape.SVG_PIXELS_PER_MM/10
                yield(RenderThreadSegment(RenderPoint((a.x-originXUnits)*units,(a.y-originYUnits)*units),
                    RenderPoint((stitch.x-originXUnits)*units,(stitch.y-originYUnits)*units),rgb))
            }
            previous=stitch
        }
    }

    /** Original visible_on_white (HLS lightness cap .85), then darker (lightness *.75), int truncation. */
    fun displayRgb(argb:Int):Int {
        val r=((argb ushr 16) and 255)/255.0;val g=((argb ushr 8) and 255)/255.0;val b=(argb and 255)/255.0
        val hi=max(r,max(g,b));val lo=min(r,min(g,b));val light=(hi+lo)/2
        if(hi==lo) { val c=(min(light,.85)*.75*255).toInt();return (c shl 16) or (c shl 8) or c }
        val delta=hi-lo
        val saturation=if(light<=.5) delta/(hi+lo) else delta/(2-hi-lo)
        val rc=(hi-r)/delta;val gc=(hi-g)/delta;val bc=(hi-b)/delta
        var hue=when(hi) { r->bc-gc;g->2+rc-bc;else->4+gc-rc }
        hue=(hue/6)%1;if(hue<0)hue+=1
        val l=min(light,.85)*.75
        val m2=if(l<=.5)l*(1+saturation) else l+saturation-l*saturation
        val m1=2*l-m2
        fun value(h:Double):Int {
            var t=h%1;if(t<0)t+=1
            val v=when { t<1.0/6->m1+(m2-m1)*t*6;t<.5->m2;t<2.0/3->m1+(m2-m1)*(2.0/3-t)*6;else->m1 }
            return (v*255).toInt()
        }
        return (value(hue+1.0/3) shl 16) or (value(hue) shl 8) or value(hue-1.0/3)
    }
}
