// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.StitchObject
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorDrawingPipeline
import io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoCorrections
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class ThreadTexturePreviewTest {
    private val white = 0xffffffff.toInt()
    private val source = IntArray(32*32) { i -> when {
        i%32 in 5..14 && i/32 in 6..25 -> 0xffd03030.toInt()
        i%32 in 18..26 && i/32 in 8..23 -> 0xff3060c0.toInt()
        else -> white
    } }
    private fun sharedPreview(gray: Boolean = false, noise: Int = 0, contrast: Float = 1f): ColorDrawingPipeline.Preview {
        val corrected = PhotoCorrections.adjust(32,32,source,false,noise,contrast)
        return ColorDrawingPipeline.trace(32,32,corrected.pixels,30.0,2,grayscale=gray)
    }
    private val fakePattern = EmbPattern(listOf(Stitch(0,0,Command.JUMP),Stitch(10,10,Command.STITCH),
        Stitch(20,0,Command.STITCH),Stitch(20,0,Command.END)), listOf(EmbThread(0xff101010.toInt())))

    @Test fun defaultOffKeepsExistingPatternAndPesBytesAndNeverCallsGenerator() {
        val options = ThreadTextureOptions()
        assertFalse(options.enabled)
        val preview = sharedPreview()
        val pending = ThreadTexturePreview.from(preview,source,32,32,false)
        val legacy = ColorDrawingPipeline.generate(preview,HoopSpec.H100).pattern
        var ordinaryCalls = 0
        val actual = pending.generate(options,ThreadTextureGenerator { _,_,_,_,_,_ -> error("Must not call texture generator") }) {
            ordinaryCalls++; legacy
        }
        assertEquals(1, ordinaryCalls)
        assertSame(legacy, actual)
        assertContentEquals(PesWriter.write(legacy), PesWriter.write(actual))
    }

    @Test fun sharedColorNoiseContrastAndGrayscaleReachFakeAndDisplayPreviewIsPreserved() {
        for(gray in listOf(false,true)) {
            val shared = sharedPreview(gray,3,1.5f)
            val corrected = PhotoCorrections.adjust(32,32,source,false,3,1.5f).pixels
            val pending = ThreadTexturePreview.from(shared,corrected,32,32,gray)
            assertContentEquals(shared.previewPixels(), pending.previewPixels())
            assertEquals(32,pending.width);assertEquals(32,pending.height)
            val expectedInput = if(gray) IntArray(corrected.size) {
                io.github.linker2k.myembro.engine.inkstitch.svg.image.Bt709Grayscale.argb(corrected[it])
            } else corrected
            pending.generate(ThreadTextureOptions(enabled=true), ThreadTextureGenerator { pixels,_,_,palette,_,_ ->
                assertContentEquals(expectedInput,pixels);assertEquals(shared.palette,palette)
                fakePattern
            }) { error("No fallback") }
            if(gray) assertTrue(shared.palette.all { (it ushr 16 and 255)==(it ushr 8 and 255) && (it and 255)==(it ushr 8 and 255) })
            val mutated = pending.previewPixels().also { it.fill(0) }
            assertFalse(mutated.contentEquals(pending.previewPixels()))
        }
    }

    @Test fun fakeReceivesAllEnumChoicesOnlyWhenGenerationIsRequestedAndReturnsStitchObjectSource() {
        val shared = sharedPreview()
        val pending = ThreadTexturePreview.from(shared,source,32,32,false)
        var calls = 0
        for(detail in TextureDetail.entries) for(length in TextureRunLength.entries) {
            val generator = ThreadTextureGenerator { pixels,w,h,palette,d,r ->
                calls++
                assertEquals(32,w);assertEquals(32,h)
                assertContentEquals(source,pixels);assertEquals(shared.palette,palette)
                assertEquals(detail,d);assertEquals(length,r)
                pixels.fill(0) // The generator cannot mutate a cached preview for later requests.
                fakePattern
            }
            val pattern = pending.generate(ThreadTextureOptions(true,detail,length),generator) { error("No fill fallback") }
            assertSame(fakePattern, pattern)
            val obj = StitchObject("texture","Texture",pattern)
            assertSame(pattern,obj.source)
            assertContentEquals(shared.previewPixels(),pending.previewPixels())
        }
        assertEquals(9,calls)
    }

    @Test fun missingOrFailingEngineNeverSilentlyGeneratesOrdinaryFill() {
        val pending = ThreadTexturePreview.from(sharedPreview(),source,32,32,false)
        val options = ThreadTextureOptions(enabled=true)
        assertFailsWith<IllegalArgumentException> { pending.generate(options,null) { error("No fallback") } }
        assertFailsWith<UnsupportedOperationException> {
            pending.generate(options,ThreadTextureGenerator { _,_,_,_,_,_ -> throw UnsupportedOperationException() }) { error("No fallback") }
        }
    }
}
