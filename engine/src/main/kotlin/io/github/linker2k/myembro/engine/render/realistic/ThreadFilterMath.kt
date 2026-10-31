// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from resvg crates/resvg/src/filter/{lighting,composite}.rs
// @4ba2462da4ecf1f93731e957fc65d4ccb7c51bed (MIT option), Copyright 2020 the Resvg Authors.
// Also ported from Inkscape src/display/{nr-filter-specularlighting.cpp,cairo-utils.h,nr-filter-composite.cpp}
// @ebf0e940d050842d608d57f6a04e6daaa5c65f37 (GPL-2.0-or-later), Copyright (C) 2007 Inkscape authors.
// Ink/Stitch filter parameters: lib/svg/rendering.py@b0edd96311ece82ee48816dc93466274b12c2a9c
// (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors.
package io.github.linker2k.myembro.engine.render.realistic

import kotlin.math.*

/** Internal numeric prototype, no bitmap/Android/Java2D dependencies. */
internal object ThreadFilterMath {
    // Ink/Stitch rendering.py:69–97: sRGB SourceAlpha blur, distant specular, atop, arithmetic.
    const val SIGMA = 0.9
    const val SURFACE_SCALE = 4.29f
    const val SPECULAR_CONSTANT = 0.65f
    const val SPECULAR_EXPONENT = 1.6f
    const val AZIMUTH_DEG = 154.0
    const val ELEVATION_DEG = 112.0
    const val K2 = 0.8f
    const val K3 = 1.2f
    fun specular(blurred: IntArray, width: Int, height: Int, pixelScale: Double): IntArray {
        // lighting.rs:172–200 and 229–237: distant-light halfway vector (view vector 0,0,1).
        val az = Math.toRadians(AZIMUTH_DEG).toFloat()
        val el = Math.toRadians(ELEVATION_DEG).toFloat()
        val hx = cos(az) * cos(el); val hy = sin(az) * cos(el); val hz = sin(el) + 1f
        val hLength = sqrt(hx*hx + hy*hy + hz*hz)
        // Inkscape 1.4.2 nr-filter-specularlighting.cpp:187 scales the bump height
        // into device pixels; unlike resvg's direct fe.surface_scale(), match the oracle.
        val surfaceScale = SURFACE_SCALE * pixelScale.toFloat()
        val result = IntArray(blurred.size)
        for (y in 0 until height) for (x in 0 until width) {
            val n = normal(blurred, width, height, x, y)
            val nx = -n[0] * n[2] * surfaceScale / 255f
            val ny = -n[1] * n[3] * surfaceScale / 255f
            val dot = (nx*hx + ny*hy + hz) / sqrt(nx*nx + ny*ny + 1f) / hLength
            val light = if (dot <= 0f) 0f else SPECULAR_CONSTANT * dot.pow(SPECULAR_EXPONENT)
            // Inkscape nr-filter-specularlighting.cpp:59–66 explicitly premultiplies
            // RGB by alpha=max(R,G,B). resvg returns RGB=alpha without that extra multiply.
            val intensity = ((255f * light).coerceIn(0f, 255f) + .5f).toInt()
            result[y*width+x] = premultiply(intensity, intensity)
        }
        return result
    }

    /** Sobel derivatives and SVG edge/corner factors, lighting.rs:318–481. */
    private fun normal(a: IntArray, w: Int, h: Int, x: Int, y: Int): FloatArray {
        fun p(dx: Int, dy: Int) = a[(y+dy).coerceIn(0, h-1)*w+(x+dx).coerceIn(0,w-1)].toFloat()
        val tl=p(-1,-1); val t=p(0,-1); val tr=p(1,-1)
        val l=p(-1,0); val c=p(0,0); val r=p(1,0)
        val bl=p(-1,1); val b=p(0,1); val br=p(1,1)
        return when {
            x==0 && y==0 -> floatArrayOf(-2*c+2*r-b+br, -2*c-r+2*b+br, 2f/3, 2f/3)
            x==w-1 && y==0 -> floatArrayOf(-2*l+2*c-bl+b, -l-2*c+bl+2*b, 2f/3, 2f/3)
            x==0 && y==h-1 -> floatArrayOf(-t+tr-2*c+2*r, -2*t-tr+2*c+r, 2f/3, 2f/3)
            x==w-1 && y==h-1 -> floatArrayOf(-tl+t-2*l+2*c, -tl-2*t+l+2*c, 2f/3, 2f/3)
            y==0 -> floatArrayOf(-2*l+2*r-bl+br, -l-2*c-r+bl+2*b+br, 1f/3, 1f/2)
            y==h-1 -> floatArrayOf(-tl+tr-2*l+2*r, -tl-2*t-tr+l+2*c+r, 1f/3, 1f/2)
            x==0 -> floatArrayOf(-t+tr-2*c+2*r-b+br, -2*t-tr+2*b+br, 1f/2, 1f/3)
            x==w-1 -> floatArrayOf(-tl+t-2*l+2*c-bl+b, -tl-2*t+bl+2*b, 1f/2, 1f/3)
            else -> floatArrayOf(-tl+tr-2*l+2*r-bl+br, -tl-2*t-tr+bl+2*b+br, 1f/4, 1f/4)
        }
    }

    // Inkscape cairo-utils.h:149–153, also Pixman/Cairo's 8-bit UN8 multiplication.
    private fun premultiply(color: Int, alpha: Int): Int {
        val temp = color * alpha + 128
        return (temp + (temp ushr 8)) ushr 8
    }

    /** Original Inkscape fixed-point arithmetic composite, then Cairo source-over. */
    fun composite(alpha: IntArray, specular: IntArray, rgb: Int, destination: IntArray) {
        val colors = intArrayOf((rgb ushr 16) and 255, (rgb ushr 8) and 255, rgb and 255)
        // nr-filter-composite.cpp:36–57 uses coefficients scaled by 255^2.
        val fixedOne = 255*255
        val k2 = (K2.toDouble()*fixedOne).roundToInt()
        val k3 = (K3.toDouble()*fixedOne).roundToInt()
        for (i in alpha.indices) {
            if (alpha[i] == 0) continue
            val a = alpha[i]
            val lightingAtop = premultiply(specular[i], a)
            val outA = ((k2+k3)*a).coerceIn(0, 255*fixedOne)
            val quantizedAlpha = (outA + fixedOne/2)/fixedOne
            var pixel = -0x1000000
            for (channel in 0..2) {
                val source = premultiply(colors[channel], a)
                val weighted = (k2*lightingAtop+k3*source).coerceIn(0, outA)
                val premultiplied = (weighted + fixedOne/2)/fixedOne
                val background = (destination[i] ushr (16-channel*8)) and 255
                val result = (premultiplied + premultiply(background,255-quantizedAlpha)).coerceIn(0,255)
                pixel = pixel or (result shl (16-channel*8))
            }
            destination[i] = pixel
        }
    }
}
