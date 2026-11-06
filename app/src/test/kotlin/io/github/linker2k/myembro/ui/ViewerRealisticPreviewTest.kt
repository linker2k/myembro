// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import android.graphics.Color
import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.security.MessageDigest
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerRealisticPreviewTest {
    private val plan=ViewerRealisticPlan(600,400,.368f,0f,0f,1000f,1000f,Color.WHITE,Color.WHITE)
    private fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).toList()
    @Test fun resolvedViewportPreservesFabricOutsideNativeDesignBounds() {
        val fabric=Color.rgb(240,220,190);val outside=Color.rgb(100,110,120)
        val pattern=EmbPattern(listOf(Stitch(-20,-20,Command.STITCH),Stitch(20,20,Command.STITCH)),listOf(EmbThread(Color.RED)))
        for(scale in listOf(.5f,1.6f,3.2f)) {
            val p=plan.copy(width=200,height=200,unitsToPixels=scale,hoopWidth=200f,hoopHeight=200f,background=outside,fabric=fabric)
            val image=renderViewerRealistic(pattern,p){false}
            try {
                if(image.nativeCrop==null) {
                    assertEquals(fabric,image.bitmap.getPixel(100,65))
                    assertEquals(fabric,image.bitmap.getPixel(100,80))
                    assertEquals(outside,image.bitmap.getPixel(0,0))
                }
            } finally {image.close()}
        }
    }

    @Test fun bundledPesAndModelAreByteIdenticalAfterRendering() {
        val bytes=RuntimeEnvironment.getApplication().assets.open("samples/sample_02_lightning.pes").use { it.readBytes() }
        val source=bytes.copyOf();val pattern=PatternReader.read(bytes,"sample_02_lightning.pes")
        val stitches=pattern.stitches.toList();val threads=pattern.threads.toList();val metadata=pattern.metadata.toMap()
        val pes=sha(PesWriter.write(pattern))
        val image=renderViewerRealistic(pattern,plan) { false }
        try {
            assertEquals(600,image.bitmap.width);assertEquals(400,image.bitmap.height)
            assertEquals(600L*400*4,plan.outputBytes);assertEquals(0L,plan.cachePixelBudgetBytes)
            assertEquals(stitches,pattern.stitches);assertEquals(threads,pattern.threads);assertEquals(metadata,pattern.metadata)
            assertContentEquals(source,bytes);assertEquals(pes,sha(PesWriter.write(pattern)))
        } finally { image.bitmap.recycle() }
    }
    @Test fun jumpAndTrimAreNotSewnAndRenderingCanBeCancelled() {
        val pattern=EmbPattern(listOf(Stitch(0,0,Command.STITCH),Stitch(80,0,Command.STITCH),
            Stitch(80,0,Command.TRIM),Stitch(0,80,Command.JUMP),Stitch(0,80,Command.STITCH),Stitch(80,80,Command.STITCH)),
            listOf(EmbThread(Color.RED)))
        val p=ViewerRealisticPlan(200,200,1f,50f,50f,1000f,1000f,Color.WHITE,Color.WHITE)
        val image=renderViewerRealistic(pattern,p) { false }
        try { assertEquals(Color.WHITE,image.bitmap.getPixel(90,90)) } finally { image.bitmap.recycle() }
        assertFailsWith<java.util.concurrent.CancellationException> { renderViewerRealistic(pattern,p) { true } }
    }

    @Test fun nativeAndAboveUseSourceCropAndReleaseOnlyOnce() {
        val pattern=EmbPattern(listOf(Stitch(0,0,Command.STITCH),Stitch(10,0,Command.STITCH)),listOf(EmbThread(Color.RED)))
        for(multiplier in listOf(1,2)) {
            val native=io.github.linker2k.myembro.engine.render.realistic.FixedRealisticPreview.PIXELS_PER_MM
            val p=ViewerRealisticPlan(128,128,(native*multiplier/10).toFloat(),0f,0f,1000f,1000f,Color.WHITE,Color.WHITE)
            val image=renderViewerRealistic(pattern,p){false}
            assertNotNull(image.nativeCrop)
            assertEquals((pattern.bounds!!.width*io.github.linker2k.myembro.engine.render.realistic.FixedRealisticPreview.PIXELS_PER_MM/10).toInt(),image.bitmap.width)
            image.close();image.close()
            assertTrue(image.bitmap.isRecycled)
        }
    }

    @Test fun sourcePlanIsDesignIntersectHoopIndependentOfPortraitCanvas() {
        val huge=EmbPattern(listOf(Stitch(-1000,-2000,Command.STITCH),Stitch(2000,3000,Command.STITCH)),listOf(EmbThread(Color.RED)))
        val portrait=ViewerRealisticPlan(1080,2400,.92f,0f,0f,1000f,1000f,Color.WHITE,Color.WHITE)
        val native=nativePlan(huge,portrait)
        assertEquals(1600,native.width);assertEquals(1600,native.height)
        assertEquals(-500.0,native.leftUnits);assertEquals(-500.0,native.topUnits)
        assertEquals(10_240_000L,native.outputBytes)
        assertEquals(native.width,nativePlan(huge,portrait.copy(width=2400,height=1080)).width)
        val small=EmbPattern(listOf(Stitch(-250,-250,Command.STITCH),Stitch(250,250,Command.STITCH)),listOf(EmbThread(Color.RED)))
        assertEquals(800,nativePlan(small,portrait).width)
        val outside=EmbPattern(listOf(Stitch(900,900,Command.STITCH),Stitch(1000,1000,Command.STITCH)),listOf(EmbThread(Color.RED)))
        assertFalse(nativePlan(outside,portrait).renderContent)
    }
    @Test fun completedImageRemainsDisplayableWithAnOlderCameraPlan() {
        val bitmap=android.graphics.Bitmap.createBitmap(3,3,android.graphics.Bitmap.Config.ARGB_8888)
        val image=ViewerRealisticImage(bitmap,plan,0.0,0)
        val newer=plan.copy(unitsToPixels=plan.unitsToPixels*2,centerX=20f)
        assertNotEquals(newer,image.plan)
        assertSame(image,displayedRealisticImage(true,image))
        assertFalse(image.bitmap.isRecycled)
        assertNull(displayedRealisticImage(false,image))
        image.close()
        assertNull(displayedRealisticImage(true,image))
    }
}
