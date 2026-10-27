// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.texture

import io.github.linker2k.myembro.engine.io.PecThreads

/** Same three mathematical pictures for both the historical 192px test and physical-pitch sweep. */
internal object TextureSyntheticFixtures {
    val names = listOf("ramp", "circle", "two-color")
    fun pixels(image: String, edge: Int) = IntArray(edge * edge) { i ->
        val x=i%edge; val y=i/edge
        when(image) {
            "ramp" -> { val gray=x*255/(edge-1); 0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray }
            "circle" -> { val dx=2*x-(edge-1); val dy=2*y-(edge-1)
                if(dx*dx+dy*dy<=(edge*3/4)*(edge*3/4)) PecThreads.forIndex(20).color else -1 }
            "two-color" -> if(x<edge/2) PecThreads.forIndex(5).color else PecThreads.forIndex(1).color
            else -> error("Unknown synthetic picture: $image")
        }
    }
}
