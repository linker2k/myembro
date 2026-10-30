// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.svg.*
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorFillOutline
import io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoIslandOrder
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.sewout.*

/** Exact source-order photo output and native fragments, never inferred from finished needles. */
internal class PhotoShapeSource(val pattern: EmbPattern, val fragments: Map<String, EmbPattern>)

internal object PhotoShapeRenderer {
    const val SKIPPED_OUTLINES_KEY = "myembro.skipped_fill_outlines"

    fun render(source: PhotoShapeSource, regions: List<ShapeRegion>, overrides: Map<String,BlockStitch>, widthMm: Double): VectorArt.Result {
        val parts=regions.map { r -> VectorArt.Part(r.sourceShape,r.role,when(r.stitch.type) {
            BlockStitch.Type.FILL -> StitchTypeRule.Type.TATAMI
            BlockStitch.Type.SATIN -> StitchTypeRule.Type.SATIN
            BlockStitch.Type.RUNNING -> StitchTypeRule.Type.RUNNING
        },r.color,
            widthMm,r.color.color and 0xffffff,paths=r.paths,regionId=r.id) }
        if(overrides.isEmpty()) return VectorArt.Result(source.pattern,parts,1.0,regionPatterns=source.fragments)
        // Overridden fills get the same two-pass staggered outline as the original photo route (ColorFillOutline).
        val outlined=mutableMapOf<String,EmbPattern>()
        val fragments=regions.associate { r ->
            val settings=overrides[r.id]
            r.id to if(settings==null) source.fragments[r.id]
                ?: error("PHOTO_SOURCE_PROVENANCE_MISSING: reimport this picture")
            else {
                val groups=ShapeBlockRenderer.generate(VectorArt.RegionInput(r.id,r.paths,r.color,null),settings).groups
                if(settings.type==BlockStitch.Type.FILL) outlined[r.id]=finish(groups.dropLast(1)+groups.last().copy(trimAfter=false)+outlineGroups(r))
                finish(groups)
            }
        }
        // Keep each outline only while the whole object still has no sewout blocker, as the photo route does.
        val selected=fragments.toMutableMap()
        var skipped=0
        for((id,candidate) in outlined) {
            val trial=selected+(id to candidate)
            if(passes(RegionOrderPatterns.compose(regions.map { it.id },trial,source.pattern))) selected[id]=candidate else skipped++
        }
        val pattern=RegionOrderPatterns.compose(regions.map { it.id },selected,source.pattern)
        val skippedTotal=skipped+(source.pattern.metadata[SKIPPED_OUTLINES_KEY]?.toIntOrNull() ?: 0)
        return VectorArt.Result(EmbPattern(pattern.stitches,pattern.threads,pattern.metadata+(SKIPPED_OUTLINES_KEY to skippedTotal.toString()),pattern.warnings),
            parts,1.0,regionPatterns=selected)
    }

    private fun finish(groups: List<StitchGroup>) =
        GeneratedPatternProcessor.fromGroups(groups,pecJumpTrims=true,lockAtThreadBoundaries=true,omitZeroLengthMotions=true)

    /** Region paths are mm; ColorFillOutline takes 0.1 mm polygons, one outline per island. */
    private fun outlineGroups(r: ShapeRegion): List<StitchGroup> {
        val region=PolygonRegion.fromPaths(r.paths.map { line -> Polyline(line.points.map { Point(it.x*10,it.y*10) },true) },FillRule.EVEN_ODD)
        return PhotoIslandOrder.polygons(region).flatMap { ColorFillOutline.stitchGroups(it,r.color) }
    }

    // Object-local check: the hoop here only wraps the object; the project gate still checks the real hoop.
    private fun passes(pattern: EmbPattern): Boolean {
        val bounds=pattern.bounds ?: return true
        val hoop=HoopSpec("object",bounds.width.toInt()+2,bounds.height.toInt()+2)
        val machine=SewoutMachine("output","Selected hoop",Specification(),Specification(),knownHoopsMm=emptySet())
        return !SewoutCheck.check(pattern,machine=machine,options=SewoutOptions(hoop=hoop,origin=DesignOrigin.APP_GENERATED)).hasBlockingIssues
    }
}
