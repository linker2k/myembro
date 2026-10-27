// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.StitchObject
import io.github.linker2k.myembro.engine.inkstitch.svg.image.*
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.io.PesWriteSettings
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.sewout.*
import io.github.linker2k.myembro.engine.texture.TextureRasterPolicy
import kotlin.test.*

class ThreadTexturePreviewTest {
    @Test fun blockerMessagesUseCodesWithStitchLimitPriority() {
        val dense=FindingCode.DENSITY_HOTSPOT
        val limit=FindingCode.STITCH_LIMIT_EXCEEDED
        val blocker=Severity.BLOCKER
        val warning=Severity.WARNING
        val densityText=io.github.linker2k.myembro.R.string.photo_texture_dense
        val limitText=io.github.linker2k.myembro.R.string.photo_texture_stitch_limit
        val generic=io.github.linker2k.myembro.R.string.photo_texture_blocked
        assertEquals(densityText,ThreadTextureOutputGate.blockerMessage(mapOf(dense to blocker)))
        assertEquals(limitText,ThreadTextureOutputGate.blockerMessage(mapOf(limit to blocker)))
        assertEquals(limitText,ThreadTextureOutputGate.blockerMessage(mapOf(dense to blocker,limit to blocker)))
        assertEquals(limitText,ThreadTextureOutputGate.blockerMessage(mapOf(limit to blocker,dense to blocker)))
        assertEquals(densityText,ThreadTextureOutputGate.blockerMessage(mapOf(limit to warning,dense to blocker)))
        assertEquals(generic,ThreadTextureOutputGate.blockerMessage(mapOf(dense to warning,limit to warning)))
        assertEquals(generic,ThreadTextureOutputGate.blockerMessage(emptyMap()))
    }

    private val source = IntArray(32*32) { i -> when {
        i%32 in 5..14 && i/32 in 6..25 -> 0xffd03030.toInt()
        i%32 in 18..26 && i/32 in 8..23 -> 0xff3060c0.toInt()
        else -> 0xffffffff.toInt()
    } }
    @Test fun defaultOffKeepsExistingPatternAndPesBytesAndNeverCallsTexture() {
        val options=ThreadTextureOptions()
        assertFalse(options.enabled);assertEquals(TextureDetail.FINE,options.detail)
        val preview=ColorDrawingPipeline.trace(32,32,source,30.0,2)
        val pending=ThreadTexturePreview.from(preview,source,32,32,false)
        val legacy=ColorDrawingPipeline.generate(preview,HoopSpec.H100).pattern
        val actual=pending.generate(options,ThreadTextureGenerator { _,_,_,_,_ -> error("Texture called while off") }) { legacy }
        assertSame(legacy,actual)
        assertContentEquals(PesWriter.write(legacy),PesWriter.write(actual))
    }
    @Test fun sharedNoiseContrastAndGrayscaleArePassedUnchangedToGenerator() {
        for(gray in listOf(false,true)) {
            val corrected=PhotoCorrections.adjust(32,32,source,false,3,1.5f).pixels
            val expected=if(gray) IntArray(corrected.size) { Bt709Grayscale.argb(corrected[it]) } else corrected
            val pending=ThreadTexturePreview.prepare(corrected,32,32,2,gray)
            assertContentEquals(expected,pending.previewPixels())
            var calls=0
            for(detail in TextureDetail.entries) {
                pending.generate(ThreadTextureOptions(true,detail),ThreadTextureGenerator { pixels,w,h,palette,d ->
                    calls++;assertEquals(32,w);assertEquals(32,h);assertEquals(detail,d)
                    assertContentEquals(expected,pixels);assertTrue(palette.size in 1..2)
                    if(gray) assertTrue(palette.all { it in Bt709Grayscale.threads })
                    pixels.fill(0)
                    EmbPattern(emptyList(),palette)
                }) { error("No fill fallback") }
                assertContentEquals(expected,pending.previewPixels())
            }
            assertEquals(3,calls)
        }
    }
    @Test fun approvedDetailMappingsUseEngineConstants() {
        assertEquals(TextureRasterPolicy.fineMinimumMmPerPixel,TextureDetail.FINE.pitchMmPerPixel)
        assertEquals(TextureRasterPolicy.coarseMmPerPixel.first(),TextureDetail.NORMAL.pitchMmPerPixel)
        assertEquals(TextureRasterPolicy.coarseMmPerPixel.last(),TextureDetail.COARSE.pitchMmPerPixel)
        assertEquals(listOf(.45,.50,.55),listOf(TextureDetail.FINE,TextureDetail.NORMAL,TextureDetail.COARSE).map { it.pitchMmPerPixel })
    }
    @Test fun actualEngineRunsAllThreePresetsChecksPesAndYieldsStitchObject() {
        for(detail in TextureDetail.entries) {
            val edge=TextureRasterPolicy.edge(40.0,detail.pitchMmPerPixel)
            val pixels=IntArray(edge*edge) { i ->
                val v=48+(i%edge)*180/(edge-1)
                0xff000000.toInt() or (v*0x010101)
            }
            val pending=ThreadTexturePreview.prepare(pixels,edge,edge,2,false)
            var progressCalls=0
            val actual=pending.generate(ThreadTextureOptions(true,detail),
                EngineThreadTextureGenerator(40.0,HoopSpec.H100,onProgress={p -> assertTrue(p in 0.0..1.0);progressCalls++})) { error("No fill fallback") }
            assertTrue(progressCalls>0);assertTrue(actual.stitchCount>0)
            assertEquals("drawingbot-free-default-v1",actual.metadata["myembro.texture"])
            val gated=ThreadTextureOutputGate.check(actual,PesWriteSettings(),SewoutMachines.SE400)
            assertTrue(gated.canUse,gated.report.findingSeverities.toString());assertNotNull(gated.bytes)
            assertTrue(ThreadTextureOutputGate.canEdit(true,true,false,gated.report,true))
            assertSame(actual,StitchObject("texture","Thread texture",actual).source)
            println("TEXTURE_UI_ENGINE detail=$detail pitch=${detail.pitchMmPerPixel} stitches=${actual.stitchCount} blockers=${gated.report.hasBlockingIssues}")
        }
    }
    @Test fun densityAndOtherBlockersDisableBytesAndEditorWithoutBypass() {
        val dense=EmbPattern(List(25) { Stitch(0,0,Command.STITCH) }+Stitch(0,0,Command.END),listOf(EmbThread(0xff000000.toInt())))
        val gated=ThreadTextureOutputGate.check(dense,PesWriteSettings(),SewoutMachines.SE400)
        assertTrue(gated.densityBlocked);assertFalse(gated.canUse);assertNull(gated.bytes)
        assertFalse(ThreadTextureOutputGate.canEdit(true,true,false,gated.report,false))
        assertFalse(ThreadTextureOutputGate.canEdit(true,true,false,gated.report,true))
        assertFalse(ThreadTextureOutputGate.canEdit(true,true,false,null,true))
        assertFalse(ThreadTextureOutputGate.canEdit(true,true,true,gated.report,true))
        // Ordinary editor behavior stays independent of the new texture gate.
        assertTrue(ThreadTextureOutputGate.canEdit(false,true,false,gated.report,false))
        val outside=EmbPattern(listOf(Stitch(0,0,Command.STITCH),Stitch(1001,10,Command.STITCH),Stitch(1001,10,Command.END)),dense.threads)
        val blocked=ThreadTextureOutputGate.check(outside,PesWriteSettings(),SewoutMachines.SE400)
        assertFalse(blocked.canUse);assertNull(blocked.bytes)
    }
    @Test fun actualEngineCancellationDoesNotReturnPartialPattern() {
        val pixels=IntArray(16*16) { 0xff444444.toInt() }
        val pending=ThreadTexturePreview.prepare(pixels,16,16,2,false)
        assertFailsWith<java.util.concurrent.CancellationException> {
            pending.generate(ThreadTextureOptions(true),EngineThreadTextureGenerator(40.0,HoopSpec.H100,isCancelled={true})) { error("No fallback") }
        }
    }
}
