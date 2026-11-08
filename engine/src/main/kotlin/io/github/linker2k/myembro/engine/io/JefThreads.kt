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
    private val descriptions = arrayOf("","Black","White","Yellow","Orange","Olive Green","Green","Sky","Purple","Pink","Red","Brown","Blue","Gold","Dark Brown","Pale Violet","Pale Yellow","Pale Pink","Peach","Beige","Wine Red","Pale Sky","Yellow Green","Silver Gray","Gray","Pale Aqua","Baby Blue","Powder Blue","Bright Blue","Slate Blue","Navy Blue","Salmon Pink","Coral","Burnt Orange","Cinnamon","Umber","Blond","Sunflower","Orchid Pink","Peony Purple","Burgundy","Royal Purple","Cardinal Red","Opal Green","Moss Green","Meadow Green","Dark Green","Aquamarine","Emerald Green","Peacock Green","Dark Gray","Ivory White","Hazel","Toast","Salmon","Cocoa Brown","Sienna","Sepia","Dark Sepia","Violet Blue","Blue Ink","Sola Blue","Green Dust","Crimson","Floral Pink","Wine","Olive Drab","Meadow","Mustard","Yellow Ocher","Old Gold","Honey Dew","Tangerine","Canary Yellow","Vermilion","Bright Green","Ocean Blue","Beige Gray","Bamboo")
    private val catalogs = arrayOf("","002","001","204","203","219","226","217","208","201","225","214","207","003","205","209","210","211","212","213","215","216","218","220","221","227","228","229","230","231","232","233","234","235","236","237","238","239","240","241","242","243","244","245","246","247","248","249","250","251","252","253","254","255","256","257","258","259","260","261","262","263","264","265","266","267","268","269","270","271","272","273","274","275","202","206","222","223","224")
    val size: Int get() = colors.size
    fun thread(index: Int) = if (index == 0) io.github.linker2k.myembro.engine.model.EmbThread(0xff000000.toInt()) else io.github.linker2k.myembro.engine.model.EmbThread(0xff000000.toInt() or colors[index], description = descriptions[index], catalogNumber = catalogs[index], brand = "Jef", chart = "Jef")
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
