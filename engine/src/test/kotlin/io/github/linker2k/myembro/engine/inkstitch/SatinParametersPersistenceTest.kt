// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.inkstitch.elements.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.lettering.*
import kotlin.test.*

class SatinParametersPersistenceTest {
    private val parameters = SatinParameters(satin_method="zigzag", zigzag_spacing_mm=.65,
        pull_compensation_mm=.1 to .3, pull_compensation_percent=2.0 to 6.0,
        push_compensation_mm=.1 to -.2, reverse_rails="second", swap_satin_rails=true,
        short_stitch_inset=listOf(10.0,20.0,30.0), short_stitch_distance_mm=.35,
        split_method="staggered", max_stitch_length_mm=4.5, split_staggers=3.0,
        random_split_jitter_percent=20.0, random_split_phase=true, min_random_split_length_mm=.4,
        random_width_decrease_percent=1.0 to 3.0, random_width_increase_percent=2.0 to 5.0,
        random_zigzag_spacing_percent=5.0, random_seed="roundtrip",
        running_stitch_length_mm=2.2, running_stitch_tolerance_mm=.12, running_stitch_position=40.0,
        start_at_nearest_point=false, end_at_nearest_point=false,
        center_walk_underlay=true, center_walk_underlay_stitch_length_mm=2.8,
        center_walk_underlay_stitch_tolerance_mm=.15, center_walk_underlay_repeats=3,
        center_walk_underlay_position=45.0, contour_underlay=true,
        contour_underlay_stitch_length_mm=2.7, contour_underlay_stitch_tolerance_mm=.18,
        contour_underlay_inset_mm=.3 to .5, contour_underlay_inset_percent=3.0 to 5.0,
        zigzag_underlay=true, zigzag_underlay_spacing_mm=2.3,
        zigzag_underlay_inset_mm=.2 to .4, zigzag_underlay_inset_percent=2.0 to 4.0,
        zigzag_underlay_max_stitch_length_mm=4.2, min_stitch_length_mm=.15, min_jump_stitch_length_mm=2.8)

    @Test fun viewBoxUsesSourceSixDecimalScaleAndTranslationOrder() {
        val transform=io.github.linker2k.myembro.engine.inkstitch.svg.ViewBoxTransform.fromDimensions(
            io.github.linker2k.myembro.engine.inkstitch.svg.Units.mmToPixels(48.0),
            io.github.linker2k.myembro.engine.inkstitch.svg.Units.mmToPixels(24.0),listOf(-10.0,-10.0,240.0,120.0))
        val start=transform.apply(io.github.linker2k.myembro.engine.geometry.Point(0.0,0.0))
        assertEquals(10.0,start.x);assertEquals(10.0,start.y)
        val end=transform.apply(io.github.linker2k.myembro.engine.geometry.Point(0.0,20.0))
        assertEquals(15.11812,end.y-start.y,1e-10)
        // That source precision places this width just ABOVE a 4mm split threshold.
        assertTrue(end.y-start.y > io.github.linker2k.myembro.engine.inkstitch.svg.Units.mmToPixels(4.0))
    }
    @Test fun all41SourceAttributesRoundTripThroughSvgAndProject() {
        val attributes=SatinParametersCodec.encode(parameters)
        assertEquals(41,attributes.size)
        assertEquals(parameters,SatinParametersCodec.decode(attributes))
        assertEquals(SatinParameters(),SatinParametersCodec.decode(emptyMap()))
        val text=TextObject("satin",text="I",stitchStyle=StitchStyle.SATIN,
            satinOptions=SatinOptions(parameters=parameters,autoDensity=false))
        val project=Project(objects=listOf(text))
        val restored=ProjectJson.decode(project.toJson())
        assertEquals(project,restored)
        assertEquals(project.flatten().stitches,restored.flatten().stitches)
    }
    @Test fun nullableAndEmptySourceValuesStayDistinct() {
        val p=SatinParameters(short_stitch_inset=emptyList(),random_seed="")
        assertEquals(p,SatinParametersCodec.decode(SatinParametersCodec.encode(p)))
        assertNull(SatinParametersCodec.decode(mapOf("max_stitch_length_mm" to "")).max_stitch_length_mm)
        assertEquals(.2 to .2,SatinParametersCodec.decode(mapOf("pull_compensation_mm" to ".2")).pull_compensation_mm)
        assertFailsWith<IllegalArgumentException> { SatinParametersCodec.decode(mapOf("pull_compensation_mm" to "1 2 3")) }
    }
    @Test fun sourceParametersDriveActualLetteringAndSharedFinishing() {
        fun text(p:SatinParameters)=TextObject("test",fontKind=FontKind.OUTLINE,singleLine=true,
            contours=listOf(listOf(0.0 to 0.0,0.0 to 10.0)),contourSourceHeight=10.0,
            stitchStyle=StitchStyle.SATIN,satinOptions=SatinOptions(parameters=p,autoDensity=false))
        val original=text(SatinParameters()).localPattern()
        val sparse=text(SatinParameters(zigzag_spacing_mm=.8)).localPattern()
        val e=text(SatinParameters(satin_method="e_stitch")).localPattern()
        assertTrue(sparse.stitchCount < original.stitchCount)
        assertTrue(e.stitchCount > original.stitchCount)
        assertTrue(original.metadata.containsKey(GeneratedPatternProcessor.FINISHED_KEY))
        val manual=text(SatinParameters(zigzag_spacing_mm=.7))
        assertEquals(.7,manual.satinOptions.densityForHeight(10.0))
        val fine=manual.withThreadWeight(ThreadWeight.WT60)
        assertEquals(.3,fine.satinOptions.densityForHeight(10.0,fine.threadWeight))
        assertTrue(fine.localPattern().stitchCount > manual.localPattern().stitchCount)
    }
}
