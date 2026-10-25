// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.test.*

/** Own low-contrast gradient, no personal input. A witness, not a rule for modifying filter output. */
class PhotoCorrectionDifferenceTest {
    @Test fun lowContrastChromaticRegionsExposeThreeDistinctSeparationsAndPes() {
        val input=IntArray(128*128) { i ->
            val x=i%128;val y=i/128
            if(x<4 && y<4) 0xff988c88.toInt()
            else 0xff000000.toInt() or ((128+x*24/127) shl 16) or
                ((128+y*12/127) shl 8) or (128+(x+y)*8/254)
        }
        val masks=mutableListOf<List<Int>>();val outputs=mutableListOf<List<Byte>>()
        val thread=EmbThread(0xff000000.toInt())
        for(filter in listOf(PhotoCorrection.ORIGINAL,PhotoCorrection.CONTRAST,PhotoCorrection.OTSU)) {
            val corrected=PhotoCorrections.apply(128,128,input,true,filter)
            var mask=IntArray(0)
            val outline=if(corrected.mask!=null) RedworkPipeline.traceMask(128,128,corrected.mask!!,30.0,thread,onQuantized={mask=it})
                else RedworkPipeline.trace(128,128,corrected.pixels,30.0,0,thread,onQuantized={mask=it})
            val pes=RedworkPipeline.generate(outline,thread,true,PhotoStyle.OUTLINE).copyPesBytes()
            masks+=mask.toList();outputs+=pes.toList()
            println("LOW_CONTRAST $filter ink=${mask.count { it==0 }} contours=${outline.contourCount} pesBytes=${pes.size}")
        }
        println("LOW_CONTRAST uniqueMasks=${masks.toSet().size} uniquePes=${outputs.toSet().size}")
        assertEquals(3,masks.toSet().size)
        assertEquals(3,outputs.toSet().size)
    }
    @Test fun highContrastBinaryControlNaturallyKeepsTheSameSeparation() {
        val input=IntArray(128*128) { if(it%128 in 25..102 && it/128 in 20..105) 0xff000000.toInt() else -1 }
        val masks=mutableListOf<List<Int>>()
        for(filter in listOf(PhotoCorrection.ORIGINAL,PhotoCorrection.CONTRAST,PhotoCorrection.OTSU)) {
            val corrected=PhotoCorrections.apply(128,128,input,true,filter)
            var mask=IntArray(0)
            val thread=EmbThread(0xff000000.toInt())
            if(corrected.mask!=null) RedworkPipeline.traceMask(128,128,corrected.mask!!,30.0,thread,onQuantized={mask=it})
            else RedworkPipeline.trace(128,128,corrected.pixels,30.0,0,thread,onQuantized={mask=it})
            masks+=mask.toList()
        }
        assertEquals(1,masks.toSet().size)
        println("HIGH_CONTRAST_CONTROL uniqueMasks=1 expected=true")
    }

}
