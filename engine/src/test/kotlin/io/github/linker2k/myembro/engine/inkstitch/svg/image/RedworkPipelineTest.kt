// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.sewout.*
import java.io.File
import kotlin.test.*

/** Two original synthetic masks, no private designs or photographs. */
class RedworkPipelineTest {
    @Test fun silhouetteKeepsWholeRegionFillThenExistingOutlineRouteAndHoles() {
        val region=io.github.linker2k.myembro.engine.geometry.PolygonRegion(
            org.locationtech.jts.io.WKTReader().read("MULTIPOLYGON (((0 0,200 0,200 200,0 200,0 0),(60 60,60 140,140 140,140 60,60 60)),((600 0,700 0,700 100,600 100,600 0)),((300 0,400 0,400 100,300 100,300 0)))"))
        val thread=EmbThread(0xFFB5492A.toInt())
        val selected=RedworkPipeline.silhouetteGroups(region,thread,HoopSpec.H100)
        assertTrue(selected.skipped.isEmpty())
        assertEquals(1,selected.checks)
        val fill=io.github.linker2k.myembro.engine.inkstitch.elements.DefaultTatami.stitchGroups(region,thread)
        assertEquals(fill.map { it.stitches },selected.groups.take(fill.size).map { it.stitches },
            "whole-region routing and propagated fill start must stay unchanged")
        val outlines=(0 until region.geometry.numGeometries).map { index ->
            ColorFillOutline.stitchGroups(region.geometry.getGeometryN(index) as org.locationtech.jts.geom.Polygon,thread)
        }
        assertEquals(2,outlines.first().size,"exterior and hole")
        val anchors=outlines.map { groups -> groups.first().stitches.map {
            io.github.linker2k.myembro.engine.inkstitch.utils.Point(it.x,it.y)
        } }
        val route=PhotoOutline.route(anchors)
        assertTrue(route.after<route.before)
        val expected=route.paths.flatMap { outlines[anchors.indexOf(it)] }
        assertEquals(expected.map { it.stitches },selected.groups.drop(fill.size).map { it.stitches })
        assertTrue(selected.groups.all { it.color==thread })
        assertTrue(ColorFillOutline.safe(selected.groups,HoopSpec.H100))
    }

    @Test fun largerFrameRetainsOutlineAndWritesPesBeyondOneHundredMm() {
        val hoop=HoopSpec("large",2000,2000)
        val region=io.github.linker2k.myembro.engine.geometry.PolygonRegion(
            org.locationtech.jts.io.WKTReader().read("POLYGON ((0 0,1200 0,1200 200,0 200,0 0))"))
        val thread=EmbThread(0xFFB5492A.toInt())
        assertTrue(RedworkPipeline.silhouetteGroups(region,thread,hoop).skipped.isEmpty())
        assertEquals(setOf(0),RedworkPipeline.silhouetteGroups(region,thread,HoopSpec.H100).skipped)
        val rgb=IntArray(64*64) { if(it%64 in 3..60 && it/64 in 24..39) 0 else 0xffffff }
        val traced=RedworkPipeline.trace(64,64,rgb,149.9,0,thread)
        val result=RedworkPipeline.generate(traced,thread,photoMode=true,hoop=hoop)
        assertEquals("0",result.pattern.metadata["myembro.skipped_fill_outlines"])
        val bytes=result.copyPesBytes()
        assertTrue(PecViewportCheck.check(bytes,hoop).valid)
        assertTrue(PesReader.read(bytes).bounds!!.width>999)
    }

    @Test fun silhouetteOutlineAndHolePassP1ThroughP8AtMaximumPhotoWidth() {
        val rgb=IntArray(64*64) { i ->
            val x=i%64;val y=i/64
            if(x in 1..62 && y in 1..62 && !(x in 22..41 && y in 22..41)) 0 else 0xffffff
        }
        val thread=EmbThread(0xFFB5492A.toInt())
        val traced=RedworkPipeline.trace(64,64,rgb,99.9,0,thread)
        val result=RedworkPipeline.generate(traced,thread,photoMode=true)
        assertEquals("0",result.pattern.metadata["myembro.skipped_fill_outlines"])
        assertEquals("1",result.pattern.metadata["myembro.fill_outline_checks"])
        val bytes=result.copyPesBytes()
        assertTrue(io.github.linker2k.myembro.engine.sewout.PesInvariants.violations(bytes,HoopSpec.H100).isEmpty())
        val report=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
        assertTrue(report.analysisComplete)
        assertFalse(report.hasBlockingIssues,report.findings.toString())
        assertEquals(0,report.statistics.missingStartLocks)
        assertEquals(0,report.statistics.missingEndLocks)
        val decoded=PesReader.read(bytes)
        assertTrue(decoded.stitches.filter { it.command==Command.STITCH }.none { it.x in 440..500 && it.y in 440..500 })
    }

    @Test fun outlineBeanRepeatsEachSourceRunningSegmentThreeTimesAndKeepsLocks() {
        val thread=EmbThread(0xFF000000.toInt())
        val points=listOf(io.github.linker2k.myembro.engine.inkstitch.utils.Point(0.0,0.0),
            io.github.linker2k.myembro.engine.inkstitch.utils.Point(100.0,0.0))
        val plain=io.github.linker2k.myembro.engine.inkstitch.elements.Stroke(listOf(points),thread)
            .toStitchGroups(null).single()
        val bean=PhotoOutline.groups(PhotoOutline.Route(listOf(points),0.0,0.0),thread).single()
        assertEquals(1+3*(plain.stitches.size-1),bean.stitches.size)
        assertEquals(io.github.linker2k.myembro.engine.inkstitch.stitches.RunningStitch.beanStitch(plain.stitches,listOf(1)),bean.stitches)
        assertNotNull(bean.lockStart); assertNotNull(bean.lockEnd); assertTrue(bean.trimAfter)
    }
    @Test fun libraryRouteNeverIncreasesOpenJumpDistanceAndKeepsEveryClosedRing() {
        fun ring(x:Double)=listOf(io.github.linker2k.myembro.engine.inkstitch.utils.Point(x,0.0),
            io.github.linker2k.myembro.engine.inkstitch.utils.Point(x+1,0.0),io.github.linker2k.myembro.engine.inkstitch.utils.Point(x,0.0))
        val paths=listOf(ring(0.0),ring(100.0),ring(5.0),ring(90.0))
        val route=PhotoOutline.route(paths)
        assertEquals(paths.toSet(),route.paths.toSet())
        assertTrue(route.after<route.before)
        assertEquals(route,PhotoOutline.route(paths))
        println("PHOTO_ROUTE before_px=${route.before} after_px=${route.after}")
    }
    @Test fun beanOutlinePreservesHoleAndPassesActualPesInvariants() {
        val rgb=IntArray(64*64) { i ->
            val x=i%64;val y=i/64
            if(x in 6..57 && y in 6..57 && !(x in 22..41 && y in 22..41)) 0 else 0xFFFFFF
        }
        val thread=EmbThread(0xFF000000.toInt())
        val outline=RedworkPipeline.trace(64,64,rgb,40.0,0,thread)
        val result=RedworkPipeline.generate(outline,thread,photoMode=true,style=PhotoStyle.OUTLINE)
        assertEquals(2,result.contourCount)
        assertEquals("inkstitch-bean-outline",result.pattern.metadata["myembro.fill.backend"])
        assertEquals(PhotoStyle.OUTLINE.name,result.pattern.metadata["myembro.photo.style"])
        assertEncodedViewport(result.copyPesBytes())
        val report=SewoutCheck.checkPes(result.copyPesBytes(),options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
        assertTrue(report.analysisComplete)
        assertFalse(report.hasBlockingIssues,report.findings.joinToString())
        val target=java.io.File("/tmp/myembro-port-out/editor/photo-outline").apply { mkdirs() }
        java.io.File(target,"ring.pes").writeBytes(result.copyPesBytes())
        println("PHOTO_OUTLINE_PES stitches=${result.pattern.stitchCount} blockers=0")
    }
    @Test fun photoSilhouetteRetainsTatamiAndFinishedBoundsFitExclusiveHoop() {
        val rgb=IntArray(64*64) { if(it%64 in 2..61 && it/64 in 2..61) 0 else 0xFFFFFF }
        val thread=EmbThread(0xFF000000.toInt())
        val outline=RedworkPipeline.trace(64,64,rgb,99.9,0,thread)
        val result=RedworkPipeline.generate(outline,thread,photoMode=true)
        assertEquals("inkstitch-default-tatami-underlay",result.pattern.metadata["myembro.fill.backend"])
        assertEncodedViewport(result.copyPesBytes())
        val report=SewoutCheck.checkPes(result.copyPesBytes(),options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
        assertTrue(report.analysisComplete)
        assertFalse(report.hasBlockingIssues,report.findings.joinToString())
    }
    @Test fun standardMedianRemovesIsolatedNoiseAndPreservesAnEdge() {
        val rgb=IntArray(25) { if(it%5>=3) 0 else 0xFFFFFF };rgb[11]=0
        val filtered=PhotoRasterFilters.median3(5,5,rgb)
        assertEquals(0xFFFFFF,filtered[11])
        assertEquals(0,filtered[14])
        assertEquals(0xFFFFFF,filtered[0])
    }
    @Test fun rejectedUniformImageStillPublishesTwoColorPreviewBeforeAnyStitchWork() {
        var preview:IntArray?=null
        val phases=mutableListOf<String>()
        assertFailsWith<IllegalArgumentException> {
            RedworkPipeline.trace(32,32,IntArray(1024) { 0x777777 },20.0,0,EmbThread(0xFF000000.toInt()),
                onQuantized={ preview=it },checkpoint={ phases.add(it) })
        }
        assertContentEquals(IntArray(1024) { 0xFFFFFF },preview)
        assertEquals(listOf("quantization"),phases)
    }
    @Test fun previewNeverGeneratesStitchesAndSplitOutputMatchesExistingApi() {
        val rgb=IntArray(32*32) { if(it%32 in 8..23 && it/32 in 8..23) 0 else 0xFFFFFF }
        val thread=EmbThread(0xFF000000.toInt())
        val phases=mutableListOf<String>()
        val outline=RedworkPipeline.trace(32,32,rgb,20.0,0,thread,checkpoint={ phases.add(it) })
        assertTrue("preview_ready" in phases)
        assertFalse("stitches" in phases || "pes" in phases)
        assertContentEquals(RedworkPipeline.generate(32,32,rgb,20.0,0,thread).copyPesBytes(),
            RedworkPipeline.generate(outline,thread).copyPesBytes())
    }
    @Test fun photoAreaFilterAndComplexityLimitRunBeforeCurveAndFillWork() {
        val rgb=IntArray(64*64) { i ->
            val x=i%64; val y=i/64
            if((x in 4..20 && y in 4..20) || (x in 40..42 && y in 40..42)) 0 else 0xFFFFFF
        }
        val thread=EmbThread(0xFF000000.toInt())
        assertEquals(2,RedworkPipeline.trace(64,64,rgb,64.0,0,thread).contourCount)
        assertEquals(1,RedworkPipeline.trace(64,64,rgb,64.0,0,thread,minAreaMm2=10.0).contourCount)
        val phases=mutableListOf<String>()
        assertFailsWith<IllegalArgumentException> {
            RedworkPipeline.trace(64,64,rgb,64.0,0,thread,maxContours=1,checkpoint={phases.add(it)})
        }
        assertFalse("preview_ready" in phases || "stitches" in phases)
        assertFailsWith<InterruptedException> {
            RedworkPipeline.trace(64,64,rgb,64.0,0,thread,checkpoint={ throw InterruptedException() })
        }
    }
    @Test fun rectangleBitmapToFinishedSingleColorPes() = fixture("rectangle")
    @Test fun roundBitmapToFinishedSingleColorPes() = fixture("round")

    @Test fun filledRingPreservesBackgroundHoleAndUsesDefaultTatamiBackend() {
        val rgb = IntArray(64*64) { i ->
            val x=i%64; val y=i/64
            if (x in 10..53 && y in 10..53 && !(x in 24..39 && y in 24..39)) 0 else 0xFFFFFF
        }
        val result=RedworkPipeline.generate(64,64,rgb,40.0,0,EmbThread(0xFF000000.toInt(), "Black"))
        assertEquals("inkstitch-default-tatami-underlay",result.pattern.metadata["myembro.fill.backend"])
        val bytes=PesWriter.write(result.pattern)
        assertEncodedViewport(bytes)
        // The model remains centered; terminal PES translates the full design to top-left.
        val shiftX=-result.pattern.stitches.minOf { it.x }
        val shiftY=-result.pattern.stitches.minOf { it.y }
        val needles=PesReader.read(bytes).stitches.filter { it.command==Command.STITCH }
        assertTrue(needles.isNotEmpty())
        assertFalse(needles.any { it.x in shiftX-39..shiftX+39 && it.y in shiftY-39..shiftY+39 }, "White center must remain unsewn")
        // Interior needles demonstrate area fill instead of another pair of running outlines.
        assertTrue(needles.any { it.x in shiftX+65..shiftX+110 && it.y in shiftY-20..shiftY+20 })
    }

    /** Check actual encoded destinations/header, never recentered reader geometry. */
    private fun assertEncodedViewport(bytes: ByteArray) {
        val hoop=HoopSpec.H100
        val viewport=PecViewportCheck.check(bytes,hoop)
        assertTrue(viewport.valid,viewport.errors.joinToString())
        assertEquals(0,viewport.minX)
        assertEquals(0,viewport.minY)
        assertTrue(viewport.maxX in 0 until hoop.width)
        assertTrue(viewport.maxY in 0 until hoop.height)
        assertEquals(viewport.maxX,viewport.headerWidth)
        assertEquals(viewport.maxY,viewport.headerHeight)
        println("PEC_VIEWPORT min=(${viewport.minX},${viewport.minY}) max=(${viewport.maxX},${viewport.maxY}) header=(${viewport.headerWidth},${viewport.headerHeight})")
    }

    private fun fixture(name: String) {
        val width = 64
        val height = 64
        val tokens = requireNotNull(javaClass.getResource("/redwork/$name.pbm")).readText().trim().split(Regex("\\s+"))
        assertEquals(listOf("P1", "64", "64"), tokens.take(3))
        val rgb = tokens.drop(3).map { if (it == "1") 0 else 0xFFFFFF }.toIntArray()
        val source = rgb.copyOf()
        val result = RedworkPipeline.generate(width, height, rgb, 60.0, 0, EmbThread(0xFFB5492A.toInt(), "Redwork"))
        assertContentEquals(source, rgb)
        assertEquals(1, result.contourCount)
        assertTrue(result.pattern.stitchCount > 10)
        assertEquals(1, result.pattern.colorBlocks.size)
        val bytes = result.copyPesBytes()
        assertEncodedViewport(bytes)
        val parsed = PesReader.read(bytes)
        assertEquals(1, parsed.colorBlocks.size)
        assertTrue(parsed.stitches.none { it.command == Command.COLOR_CHANGE })
        assertEquals(Command.END, parsed.stitches.last().command)
        val report = SewoutCheck.checkPes(bytes, options = SewoutOptions(
            // Generated-output threshold from /tmp/myembro-quality-gate/POLICY.md: >15 / 0.5mm.
            origin = DesignOrigin.APP_GENERATED,
        ))
        val output = File("/tmp/myembro-port-out/editor/redwork").apply { mkdirs() }
        File(output, "$name.pes").writeBytes(bytes)
        File(output, "$name.svg").writeText(result.outlineSvg)
        File(output, "$name.pbm").writeText(requireNotNull(javaClass.getResource("/redwork/$name.pbm")).readText())
        File(output, "$name.sewout.txt").writeText(report.findings.joinToString("\n") { "${it.severity}: ${it.code}: ${it.message}" })
        // PES does not preserve generated metadata: explicitly check the app's default v1
        // bytes with the same generated-output policy as PhotoViewModel.
        val appBytes=PesWriter.write(result.pattern)
        assertEncodedViewport(appBytes)
        val appReport = SewoutCheck.checkPes(appBytes, options = SewoutOptions(origin = DesignOrigin.APP_GENERATED))
        assertTrue(appReport.analysisComplete)
        assertFalse(appReport.hasBlockingIssues, appReport.findings.filter { it.severity == Severity.BLOCKER }.joinToString())
        assertTrue(report.analysisComplete)
        assertFalse(report.hasBlockingIssues, report.findings.filter { it.severity == Severity.BLOCKER }.joinToString())
        assertContentEquals(bytes, RedworkPipeline.generate(width, height, rgb, 60.0, 0,
            EmbThread(0xFFB5492A.toInt(), "Redwork")).copyPesBytes())
    }
}
