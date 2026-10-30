// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.inkstitch.svg.*
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorDrawingPipeline
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.sewout.*
import kotlin.test.*

class PhotoOutlineNotesTest {
    private fun fixture(): Pair<List<ShapeRegion>,Map<String,EmbPattern>> {
        val shape=ShapeObject.fromSvg("synthetic","Two squares",
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 30 10"><rect width="10" height="10" fill="#224488"/><rect x="20" width="10" height="10" fill="#cc4422"/></svg>""",30.0)
        val fragments=shape.regions.associate { it.id to fill(it) }
        assertEquals(2,fragments.size)
        return shape.regions to fragments
    }
    private fun fill(r:ShapeRegion)=GeneratedPatternProcessor.fromGroups(
        ShapeBlockRenderer.generate(VectorArt.RegionInput(r.id,r.paths,r.color,null),r.stitch).groups,
        pecJumpTrims=true,lockAtThreadBoundaries=true,omitZeroLengthMotions=true)
    private fun base(regions:List<ShapeRegion>,fragments:Map<String,EmbPattern>,skipped:Int?)=
        RegionOrderPatterns.compose(regions.map { it.id },fragments,EmbPattern(emptyList(),emptyList(),
            skipped?.let { mapOf(PhotoShapeRenderer.SKIPPED_OUTLINES_KEY to it.toString()) } ?: emptyMap()))

    @Test fun alreadyBlockedFillIsNotBlamedOnAnOutlineAndOutputStaysFillOnly() {
        val (regions,fragments)=fixture()
        val bad=fragments.getValue(regions.last().id)
        val first=bad.stitches.first { it.command==Command.STITCH }
        val blocked=EmbPattern(List(40) { first }+bad.stitches,bad.threads,bad.metadata)
        val inputs=fragments+(regions.last().id to blocked)
        val baseline=base(regions,inputs,0)
        assertTrue(SewoutCheck.check(baseline,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED)).hasBlockingIssues)
        val actual=PhotoShapeRenderer.render(PhotoShapeSource(baseline,inputs),regions,
            mapOf(regions.first().id to regions.first().stitch),30.0)
        assertEquals("0",actual.pattern.metadata[PhotoShapeRenderer.SKIPPED_OUTLINES_KEY])
        assertEquals(baseline.stitches,actual.pattern.stitches)
        assertEquals(baseline.threads,actual.pattern.threads)
        println("OUTLINE_NOTE baseline_blocked=true skipped=0 needles=IDENTICAL")
    }

    @Test fun countsOnlyOriginalSkippedRegionsThatAreNotOverriddenAndSurvivesCodec() {
        val (regions,fragments)=fixture()
        val pattern=base(regions,fragments,2)
        val result=ColorDrawingPipeline.Result(pattern,regions.map {
            ColorDrawingPipeline.Region(it.color,StitchTypeRule.Widths(10.0,10.0),StitchTypeRule.Type.TATAMI,1,
                paths=it.paths,outlineSkipped=true)
        },fragments)
        val photo=assertNotNull(ShapeObjectFactory.fromPhoto("photo","Synthetic",result,30.0))
        assertEquals(pattern.stitches,photo.localPattern().stitches)
        assertEquals(pattern.threads,photo.localPattern().threads)
        val patch=mapOf(photo.regions.first().id to BlockStitch(type=BlockStitch.Type.RUNNING))
        val edited=photo.withOverrides(patch)
        assertEquals(1,edited.skippedOutlineCount)
        assertFalse(edited.skippedOutlineCountUnconfirmed)
        val restored=ProjectJson.decode(ProjectJson.encode(Project(objects=listOf(edited)))).objects.single() as ShapeObject
        assertEquals(1,restored.skippedOutlineCount)
        assertFalse(restored.skippedOutlineCountUnconfirmed)
        assertEquals(edited.localPattern().stitches,restored.localPattern().stitches)
        assertEquals(0,photo.withOverrides(photo.regions.associate { it.id to BlockStitch(type=BlockStitch.Type.RUNNING) }).skippedOutlineCount)
        println("OUTLINE_NOTE original_skipped=2 replaced=1 total=1 codec=IDENTICAL")
    }

    @Test fun legacyAggregateIsNotAddedOrAssignedToArbitraryRegions() {
        val (regions,fragments)=fixture()
        val source=PhotoShapeSource(base(regions,fragments,2),fragments)
        val overrides=mapOf(regions.first().id to BlockStitch(type=BlockStitch.Type.RUNNING))
        val result=PhotoShapeRenderer.render(source,regions,overrides,30.0)
        assertEquals("0",result.pattern.metadata[PhotoShapeRenderer.SKIPPED_OUTLINES_KEY])
        assertEquals("true",result.pattern.metadata[PhotoShapeRenderer.UNCONFIRMED_OUTLINES_KEY])
        val all=PhotoShapeRenderer.render(source,regions,regions.associate { it.id to BlockStitch(type=BlockStitch.Type.RUNNING) },30.0)
        assertEquals("false",all.pattern.metadata[PhotoShapeRenderer.UNCONFIRMED_OUTLINES_KEY])
        val noneSkipped=PhotoShapeRenderer.render(PhotoShapeSource(base(regions,fragments,0),fragments),regions,overrides,30.0)
        assertEquals("false",noneSkipped.pattern.metadata[PhotoShapeRenderer.UNCONFIRMED_OUTLINES_KEY])
    }

    @Test fun photoFromBeforeFillOutlinesShowsNoUnconfirmedNote() {
        val (regions,fragments)=fixture()
        val source=PhotoShapeSource(base(regions,fragments,null),fragments)
        val result=PhotoShapeRenderer.render(source,regions,mapOf(regions.first().id to BlockStitch(type=BlockStitch.Type.RUNNING)),30.0)
        assertEquals("0",result.pattern.metadata[PhotoShapeRenderer.SKIPPED_OUTLINES_KEY])
        assertEquals("false",result.pattern.metadata[PhotoShapeRenderer.UNCONFIRMED_OUTLINES_KEY])
    }
}
