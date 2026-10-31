// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from scikit-image 0.25.2 skimage/color/{colorconv,delta_e}.py (BSD-3-Clause).
// Copyright (c) the scikit-image team. See THIRD_PARTY_NOTICES.md and licenses/scikit-image.
package io.github.linker2k.myembro.engine.color

import kotlin.math.*

/** sRGB, D65, 2-degree observer. Constants and branch rules match scikit-image 0.25.2. */
object ColorDifference {
    data class Lab(val l: Double, val a: Double, val b: Double)
    fun lab(rgb: Int): Lab {
        fun channel(shift: Int): Double {
            val c = ((rgb ushr shift) and 255) / 255.0
            return if (c > .04045) ((c + .055) / 1.055).pow(2.4) else c / 12.92
        }
        val r = channel(16); val g = channel(8); val b = channel(0)
        fun f(x: Double) = if (x > .008856) Math.cbrt(x) else 7.787 * x + 16.0 / 116
        val x = f((.412453*r + .357580*g + .180423*b) / .95047)
        val y = f(.212671*r + .715160*g + .072169*b)
        val z = f((.019334*r + .119193*g + .950227*b) / 1.08883)
        return Lab(116*y-16, 500*(x-y), 200*(y-z))
    }
    fun ciede2000(a: Int, b: Int): Double = ciede2000(lab(a), lab(b))
    /** CIEDE2000 with the original kL=kC=kH=1 defaults; angles are radians. */
    fun ciede2000(x: Lab, y: Lab): Double {
        val cbar = (hypot(x.a,x.b)+hypot(y.a,y.b))/2
        val c7 = cbar.pow(7)
        val scale = 1 + .5*(1-sqrt(c7/(c7+25.0.pow(7))))
        val c1 = hypot(x.a*scale,x.b); val c2 = hypot(y.a*scale,y.b)
        fun hue(a: Double,b: Double): Double { val h=atan2(b,a);return if(h<0) h+2*PI else h }
        val h1=hue(x.a*scale,x.b);val h2=hue(y.a*scale,y.b)
        val lbar=(x.l+y.l)/2;val tmp=(lbar-50).pow(2)
        val l=(y.l-x.l)/(1+.015*tmp/sqrt(20+tmp))
        val cp=(c1+c2)/2;val c=(c2-c1)/(1+.045*cp)
        val diff=h2-h1;val sum=h1+h2;val cc=c1*c2
        val dh=when { cc==0.0->0.0;diff>PI->diff-2*PI;diff< -PI->diff+2*PI;else->diff }
        var hb=sum
        if(cc!=0.0 && abs(diff)>PI) hb+=if(sum<2*PI) 2*PI else -2*PI
        if(cc==0.0) hb*=2
        hb/=2
        val t=1-.17*cos(hb-Math.toRadians(30.0))+.24*cos(2*hb)+.32*cos(3*hb+Math.toRadians(6.0))-.20*cos(4*hb-Math.toRadians(63.0))
        val h=2*sqrt(cc)*sin(dh/2)/(1+.015*cp*t)
        val cp7=cp.pow(7);val rc=2*sqrt(cp7/(cp7+25.0.pow(7)))
        val theta=Math.toRadians(30.0)*exp(-((Math.toDegrees(hb)-275)/25).pow(2))
        return sqrt(max(0.0,l*l+c*c+h*h-sin(2*theta)*rc*c*h))
    }
}
