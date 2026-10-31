// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.render.realistic

import kotlinx.serialization.json.*
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.test.*

/** Java2D is TEST ONLY: shape-AA differences are measured separately from filter differences. */
class RealisticThreadRendererTest {
    private val fixtures = Json.parseToJsonElement(javaClass.getResource("/realistic-preview/scenes.json")!!.readText())
        .jsonObject.getValue("cases").jsonObject
    private val directory = File("build/realistic-preview-cpu")

    internal fun awtPath(path: List<ThreadPathCommand>) = Path2D.Double().apply {
        path.forEach { command -> when (command) {
            is ThreadPathCommand.Move -> moveTo(command.p.x, command.p.y)
            is ThreadPathCommand.Line -> lineTo(command.p.x, command.p.y)
            is ThreadPathCommand.Cubic -> curveTo(command.a.x,command.a.y,command.b.x,command.b.y,command.end.x,command.end.y)
            ThreadPathCommand.Close -> closePath()
        } }
    }
    internal val rasterizer = ThreadAlphaRasterizer { path, width, height ->
        val image = BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
            color=Color.WHITE
            fill(awtPath(path)); dispose()
        }
        image.getRGB(0,0,width,height,null,0,width).also { pixels ->
            for(i in pixels.indices)pixels[i]=pixels[i] ushr 24
        }
    }
    internal fun segments(name: String): List<RenderThreadSegment> = fixtures.getValue(name).jsonArray.flatMap { run ->
        val rgb=run.jsonObject.getValue("displayRgb").jsonPrimitive.content.removePrefix("#").toInt(16)
        val points=run.jsonObject.getValue("pointsMm").jsonArray.map { value ->
            val xy=value.jsonArray
            RenderPoint(xy[0].jsonPrimitive.double*ThreadShape.SVG_PIXELS_PER_MM,
                xy[1].jsonPrimitive.double*ThreadShape.SVG_PIXELS_PER_MM)
        }
        points.zipWithNext().map { (start,end)->RenderThreadSegment(start,end,rgb) }
    }
    private fun render(name: String) {
        val segments=segments(name)
        for(dpi in listOf(300,600)) {
            val edge=floor(16/25.4*dpi+.5).toInt()
            val started=System.nanoTime()
            val actualScale=edge/(16*ThreadShape.SVG_PIXELS_PER_MM)
            val pixels=RealisticThreadRenderer.render(edge,edge,dpi,segments,rasterizer,pixelsPerSvgUnit=actualScale)
            val elapsed=(System.nanoTime()-started)/1_000_000.0
            val budget=segments.sumOf { val b=ThreadFilterRegion.bounds(ThreadShape.path(it,actualScale));b.width.toLong()*b.height*4 }
            val cache=ThreadShapeCache(budget)
            val cached=RealisticThreadRenderer.render(edge,edge,dpi,segments,rasterizer,cache,actualScale)
            val cachedAgain=RealisticThreadRenderer.render(edge,edge,dpi,segments,rasterizer,cache,actualScale)
            assertContentEquals(pixels,cached,"cache cold $name/$dpi")
            assertContentEquals(pixels,cachedAgain,"cache warm $name/$dpi")
            assertTrue(cache.hits>=segments.size,"all exact repeats must hit")
            assertTrue(cache.cachedPixelBytes<=budget)
            assertEquals(edge*edge,pixels.size)
            assertTrue(pixels.any { it != -1 },name)
            assertTrue(pixels.all { it ushr 24 == 255 })
            val output=File(directory,"png/$dpi/$name.png").apply { parentFile.mkdirs() }
            val image=BufferedImage(edge,edge,BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0,0,edge,edge,pixels,0,edge)
            assertTrue(ImageIO.write(image,"png",output))
            // Flat-control isolates the Java2D/Inkex shape-AA difference. It is
            // the original display color/path without the blur/lighting chain.
            val flat=BufferedImage(edge,edge,BufferedImage.TYPE_INT_ARGB)
            flat.createGraphics().apply {
                color=Color.WHITE;fillRect(0,0,edge,edge)
                setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
                segments.forEach { s-> color=Color(s.displayRgb);fill(awtPath(ThreadShape.path(s,actualScale))) }
                dispose()
            }
            val control=File(directory,"flat-control/png/$dpi/$name.png").apply { parentFile.mkdirs() }
            assertTrue(ImageIO.write(flat,"png",control))
            File(directory,"$name-$dpi.json").writeText(buildJsonObject {
                put("case",name);put("dpi",dpi);put("segments",segments.size);put("renderMs",elapsed)
                put("width",edge);put("height",edge);put("pixelsPerSvgUnit",actualScale);
                put("cacheHits",cache.hits);put("cacheMisses",cache.misses);put("cachePixelBytes",cache.cachedPixelBytes);
                put("cachedRendersEqual",true);put("jvm",System.getProperty("java.version"))
                put("rasterizer","Java2D test-only, antialiased filled original cubic path")
            }.toString()+"\n")
            println("CPU_PREVIEW $name dpi=$dpi segments=${segments.size} renderMs=$elapsed")
        }
    }
    @Test fun dumpHorizontalIntermediateMasks() {
        val segment=segments("horizontal").single()
        for(dpi in listOf(300,600)) {
            val edge=floor(16/25.4*dpi+.5).toInt()
            val alpha=rasterizer.rasterize(ThreadShape.path(segment,dpi/96.0),edge,edge)
            val blurred=InkscapeGaussian.blur(alpha,edge,edge,ThreadFilterMath.SIGMA*dpi/96.0)
            for((name,mask) in listOf("alpha" to alpha,"blur" to blurred)) {
                val image=BufferedImage(edge,edge,BufferedImage.TYPE_INT_ARGB)
                image.setRGB(0,0,edge,edge,mask.map { it shl 24 }.toIntArray(),0,edge)
                val file=File(directory,"debug/$dpi/$name.png").apply { parentFile.mkdirs() }
                assertTrue(ImageIO.write(image,"png",file))
            }
        }
    }
    @Test fun horizontal()=render("horizontal")
    @Test fun vertical()=render("vertical")
    @Test fun diagonal()=render("diagonal")
    @Test fun satinColumn()=render("satin-column")
    @Test fun tatamiPatch()=render("tatami-patch")
    @Test fun crossing()=render("crossing")
    @Test fun whiteThread()=render("white-thread")
    @Test fun blackThread()=render("black-thread")

    @Test fun noAndroidOrAwtDependencyInCalculationSource() {
        val root=File("src/main/kotlin/io/github/linker2k/myembro/engine/render/realistic")
        assertTrue(root.isDirectory)
        root.walkTopDown().filter { it.extension=="kt" }.forEach {
            assertFalse(Regex("(?m)^import (android\\.|java\\.awt|javax\\.imageio)").containsMatchIn(it.readText()),it.name)
        }
    }
    @Test fun noOpAndZeroLengthDoNotCreateDisplayMarks() {
        val p=RenderPoint(5.0,5.0)
        val empty=RealisticThreadRenderer.render(20,20,300,emptyList(),rasterizer)
        val zero=RealisticThreadRenderer.render(20,20,300,listOf(RenderThreadSegment(p,p,0)),rasterizer)
        assertContentEquals(empty,zero)
        assertTrue(empty.all { it == -1 })
    }
    @Test fun renderIsDeterministicAndDoesNotMutateSegments() {
        val s=segments("crossing");val before=s.toList()
        assertContentEquals(RealisticThreadRenderer.render(189,189,300,s,rasterizer),
            RealisticThreadRenderer.render(189,189,300,s,rasterizer))
        assertEquals(before,s)
    }
    @Test fun blurZeroAndImpulseRemainSymmetricAndInputsUnchanged() {
        val zeros=IntArray(21*21)
        assertContentEquals(zeros,InkscapeGaussian.blur(zeros,21,21,2.8125))
        val impulse=zeros.copyOf().apply { this[10*21+10]=255 }
        val before=impulse.copyOf()
        val result=InkscapeGaussian.blur(impulse,21,21,2.8125)
        assertContentEquals(before,impulse)
        assertTrue(result.all { it in 0..255 })
        for(y in 0..20) for(x in 0..20) assertEquals(result[y*21+x],result[(20-y)*21+20-x])
    }
    @Test fun bothGaussianBranchesPreserveConstantAlphaAndAcceptZeroSigma() {
        val a=IntArray(21*21) { 255 }
        for(sigma in listOf(0.0,2.8125,5.625)) {
            assertContentEquals(a,InkscapeGaussian.blur(a,21,21,sigma),"sigma=$sigma")
        }
        assertFailsWith<IllegalArgumentException> { InkscapeGaussian.blur(a,21,21,Double.NaN) }
    }
    @Test fun noBudgetCacheAndSubpixelDirectionChangesRemainExact() {
        val shifts=listOf(0.0,0.125,0.5,0.875)
        val s=shifts.flatMap { phase -> listOf(
            RenderThreadSegment(RenderPoint(4+phase,4+phase),RenderPoint(12+phase,4+phase),0x184687),
            RenderThreadSegment(RenderPoint(4+phase,4+phase),RenderPoint(4+phase,12+phase),0x8E1D22),
            RenderThreadSegment(RenderPoint(4+phase,4+phase),RenderPoint(12+phase,12+phase),0x184687)) }
        for(dpi in listOf(300,600)) {
            val cache=ThreadShapeCache(0)
            val a=RealisticThreadRenderer.render(100,100,dpi,s,rasterizer)
            val b=RealisticThreadRenderer.render(100,100,dpi,s,rasterizer,cache)
            assertContentEquals(a,b)
            assertEquals(0L,cache.cachedPixelBytes)
            assertEquals(0L,cache.hits)
            assertEquals(s.size.toLong(),cache.misses)
        }
    }
    @Test fun tinyCacheEvictionAndRecolorDoNotChangeCompositing() {
        val base=segments("crossing")
        val s=(0..15).flatMap { pass->base.map {
            it.copy(start=RenderPoint(it.start.x+pass/16.0,it.start.y),
                end=RenderPoint(it.end.x+pass/16.0,it.end.y),displayRgb=if(pass%2==0)it.displayRgb else 0xA2A2A2)
        } }
        val region=ThreadFilterRegion.bounds(ThreadShape.path(s.first(),300/96.0))
        val budget=region.width.toLong()*region.height*4
        val cache=ThreadShapeCache(budget)
        val expected=RealisticThreadRenderer.render(189,189,300,s,rasterizer)
        repeat(2) { assertContentEquals(expected,RealisticThreadRenderer.render(189,189,300,s,rasterizer,cache)) }
        assertTrue(cache.cachedPixelBytes<=budget)
        assertTrue(cache.misses>s.size)
    }
    @Test fun originalOutwardBoundsAndIntegerViewportScaleArePreserved() {
        val s=segments("horizontal").single()
        val nominal=ThreadFilterRegion.bounds(ThreadShape.path(s,300/96.0))
        val actual=ThreadFilterRegion.bounds(ThreadShape.path(s,189/(16*ThreadShape.SVG_PIXELS_PER_MM)))
        assertEquals(91,nominal.top)
        assertEquals(92,actual.top)
        assertEquals(6,actual.height)
    }
    @Test fun originalCppFirGoldenAtActualFilterBounds() {
        // Extracted ORIGINAL Inkscape nr-filter-gaussian.cpp FIR functions @ebf0e940,
        // g++ -O2, 97x6 input, sigma=2.8125: [188,185,173,152,125,94].
        // This is also the horizontal reference PNG's center-column blurred alpha.
        val rows=intArrayOf(170,255,255,255,170,0)
        val alpha=IntArray(97*6) { rows[it/97] }
        val result=InkscapeGaussian.blur(alpha,97,6,2.8125)
        assertContentEquals(intArrayOf(188,185,173,152,125,94),IntArray(6) { result[it*97+48] })
    }
    @Test fun invalidInputsRejected() {
        assertFailsWith<IllegalArgumentException> { RealisticThreadRenderer.render(20,20,0,emptyList(),rasterizer) }
        assertFailsWith<IllegalArgumentException> { RenderPoint(Double.NaN,0.0) }
        val segment=segments("horizontal")
        assertFailsWith<IllegalArgumentException> { RealisticThreadRenderer.render(20,20,300,segment,ThreadAlphaRasterizer { _,_,_->intArrayOf(-1) }) }
    }
}
