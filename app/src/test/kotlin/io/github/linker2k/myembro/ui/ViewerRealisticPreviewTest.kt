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
    @Test fun bundledPesAndModelAreByteIdenticalAfterRendering() {
        val bytes=RuntimeEnvironment.getApplication().assets.open("samples/flower.pes").use { it.readBytes() }
        val source=bytes.copyOf();val pattern=PatternReader.read(bytes,"flower.pes")
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
}
