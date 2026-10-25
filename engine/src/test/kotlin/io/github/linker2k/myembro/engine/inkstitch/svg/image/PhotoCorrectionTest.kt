// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.test.*

class PhotoCorrectionTest {
    private val source=IntArray(32*32) { i ->
        val v=if(i%32 in 8..23 && i/32 in 7..24) 40 else 220
        0xff000000.toInt() or (v*0x010101)
    }
    @Test fun originalAndCleanMatchOneLegacyMedianAndOriginalPesBytes() {
        val old=PhotoRasterFilters.median3(32,32,source.map { it and 0xffffff }.toIntArray())
        val original=PhotoCorrections.apply(32,32,source,true)
        assertContentEquals(old,original.pixels)
        assertContentEquals(old,PhotoCorrections.apply(32,32,source,true,PhotoCorrection.CLEAN).pixels)
        val thread=EmbThread(0xff000000.toInt())
        val reference=LegacyPhotoPipelineV029.trace(32,32,old,20.0,0,thread)
        val actual=RedworkPipeline.trace(32,32,original.pixels,20.0,0,thread)
        assertEquals(reference.outlineSvg,actual.outlineSvg)
        for(style in PhotoStyle.entries) assertContentEquals(
            LegacyPhotoPipelineV029.generate(reference,thread,photoMode=true,style=style).copyPesBytes(),
            RedworkPipeline.generate(actual,thread,photoMode=true,style=style).copyPesBytes(),"v0.2.9 $style PES bytes")
    }
    @Test fun cleanNeverRunsASecondMedianOnNonIdempotentNoise() {
        val random=java.util.Random(9)
        val noisy=IntArray(49) { if(random.nextBoolean()) 0xff000000.toInt() else -1 }
        val once=PhotoRasterFilters.median3(7,7,noisy)
        assertFalse(once.contentEquals(PhotoRasterFilters.median3(7,7,once)))
        assertContentEquals(once,PhotoCorrections.apply(7,7,noisy,true,PhotoCorrection.ORIGINAL).pixels)
        assertContentEquals(once,PhotoCorrections.apply(7,7,noisy,true,PhotoCorrection.CLEAN).pixels)
    }
    @Test fun contrastRasterEqualsTheUpstreamEngineFunction() {
        for(mono in listOf(false,true)) {
            val base=if(mono) PhotoRasterFilters.median3(32,32,source) else source
            val bytes=ByteArray(base.size*3) { (base[it/3] ushr (16-it%3*8)).toByte() }
            val expected=Autocontrast.apply(bytes,3)
            val actual=PhotoCorrections.apply(32,32,source,mono,PhotoCorrection.CONTRAST).pixels
            assertContentEquals(expected,ByteArray(actual.size*3) { (actual[it/3] ushr (16-it%3*8)).toByte() })
        }
    }
    @Test fun otsuUsesExactMaskAndDoesNotInferBackgroundFromTopLeft() {
        val base=PhotoRasterFilters.median3(32,32,source)
        val gray=ByteArray(base.size) { Bt709Grayscale.argb(base[it]).toByte() }
        val corrected=PhotoCorrections.apply(32,32,source,true,PhotoCorrection.OTSU)
        assertContentEquals(OtsuThreshold.darkMask(gray),corrected.mask)
        var published:IntArray?=null
        RedworkPipeline.traceMask(32,32,corrected.mask!!,20.0,EmbThread(0xff000000.toInt()),onQuantized={published=it})
        assertContentEquals(corrected.pixels,published)
        val invertedCorner=corrected.mask!!.copyOf().also { it[0]=0 }
        RedworkPipeline.traceMask(32,32,invertedCorner,20.0,EmbThread(0xff000000.toInt()),onQuantized={assertEquals(0,it[0])})
    }
    @Test fun opaqueColorOriginalAndTransparentColorStayByteIdentical() {
        assertContentEquals(source,PhotoCorrections.apply(32,32,source,false).pixels)
        val median=PhotoRasterFilters.median3(32,32,source)
        assertContentEquals(IntArray(median.size) { median[it] or 0xff000000.toInt() },
            PhotoCorrections.apply(32,32,source,false,PhotoCorrection.CLEAN).pixels)
        val alpha=source.copyOf().also { it[0]=0;it[1]=0x80123456.toInt() }
        for(filter in PhotoCorrection.entries) {
            val result=PhotoCorrections.apply(32,32,alpha,false,filter)
            assertTrue(result.alphaBypassed);assertContentEquals(alpha,result.pixels);assertNull(result.mask)
        }
        assertContentEquals(source,PhotoCorrections.apply(32,32,source,false,PhotoCorrection.OTSU).pixels)
    }
    @Test fun uniformOtsuStillPublishesRasterBeforeRejection() {
        var preview:IntArray?=null
        assertFailsWith<IllegalArgumentException> {
            RedworkPipeline.traceMask(4,4,ByteArray(16),20.0,EmbThread(0xff000000.toInt()),onQuantized={preview=it})
        }
        assertContentEquals(IntArray(16),preview)
    }
}
