// SPDX-License-Identifier: GPL-3.0-or-later
// pyembroidery 1.5.1 EmbThreadJef.py / EmbThread.py (MIT).
package io.github.linker2k.myembro.engine.io

internal object JefThreads {
    private val colors=intArrayOf(0,
        0x000000,0xFFFFFF,0xFFFF17,0xFF6600,0x2F5933,0x237336,0x65C2C8,0xAB5A96,
        0xF669A0,0xFF0000,0xB1704E,0x0B2F84,0xE4C35D,0x481A05,0xAC9CC7,0xFCF294,
        0xF999B7,0xFAB381,0xC9A480,0x970533,0xA0B8CC,0x7FC21C,0xE5E5E5,0x889B9B,
        0x98D6BD,0xB2E1E3,0x368BA0,0x4F83AB,0x386A91,0x071650,0xF999A2,0xF9676B,
        0xE3311F,0xE2A188,0xB59474,0xE4CF99,0xFFCB00,0xE1ADD4,0xC3007E,0x80004B,
        0x540571,0xB10525,0xCAE0C0,0x899856,0x5C941A,0x003114,0x5DAE94,0x4CBF8F,
        0x007772,0x595B61,0xFFFFF2,0xB15818,0xCB8A07,0x986C80,0x98692D,0x4D3419,
        0x4C330B,0x33200A,0x523A97,0x0D217E,0x1E77AC,0xB2DD53,0xF33689,0xDE649E,
        0x984161,0x4C5612,0x4C881F,0xE4DE79,0xCB8A1A,0xCBA21C,0xFF9805,0xFCB257,
        0xFFE505,0xF0331F,0x1A842D,0x386CAE,0xE3C4B4,0xE3AC81)
    fun nearest(rgb:Int,exclude:Int=-1):Int {
        val r=rgb ushr 16 and 255;val g=rgb ushr 8 and 255;val b=rgb and 255
        var best=-1;var distance=Int.MAX_VALUE
        for(i in 1 until colors.size){
            if(i==exclude)continue
            val c=colors[i];val rr=c ushr 16 and 255;val gg=c ushr 8 and 255;val bb=c and 255
            val mean=Math.rint((r+rr)/2.0).toInt();val dr=r-rr;val dg=g-gg;val db=b-bb
            val d=(((512+mean)*dr*dr) shr 8)+4*dg*dg+(((767-mean)*db*db) shr 8)
            if(d<=distance){distance=d;best=i} // Upstream chooses the later entry on a tie.
        }
        return best
    }
}
