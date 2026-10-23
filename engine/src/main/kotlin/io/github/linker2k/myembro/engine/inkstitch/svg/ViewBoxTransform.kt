// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/svg/units.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.geometry.Affine2D
import java.util.Locale

/** get_viewbox_transform: source formats to six decimals and applies translate @ scale. */
object ViewBoxTransform {
    fun fromDimensions(widthPx:Double,heightPx:Double,viewBox:List<Double>,preserveAspectRatio:String="xMidYMid meet"):Affine2D {
        require(viewBox.size==4 && viewBox.all(Double::isFinite) && widthPx.isFinite() && heightPx.isFinite())
        require(viewBox[2]>0 && viewBox[3]>0)
        fun sourceDecimal(value:Double)=String.format(Locale.ROOT,"%.6f",value).toDouble()
        var sx=widthPx/viewBox[2];var sy=heightPx/viewBox[3]
        if (preserveAspectRatio!="none") {
            val scale=if("slice" in preserveAspectRatio) maxOf(sx,sy) else minOf(sx,sy)
            sx=scale;sy=scale
        }
        // Deliberately retain v3.3.0 translation order (not the general SVG viewport algorithm).
        return Affine2D(sourceDecimal(sx),0.0,0.0,sourceDecimal(sy),sourceDecimal(-viewBox[0]),sourceDecimal(-viewBox[1]))
    }
}
