// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.inkstitch.svg.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.model.*

/** Exact source-order photo output and native fragments, never inferred from finished needles. */
internal class PhotoShapeSource(val pattern: EmbPattern, val fragments: Map<String, EmbPattern>)

internal object PhotoShapeRenderer {
    fun render(source: PhotoShapeSource, regions: List<ShapeRegion>, overrides: Map<String,BlockStitch>, widthMm: Double): VectorArt.Result {
        val parts=regions.map { r -> VectorArt.Part(r.sourceShape,r.role,when(r.stitch.type) {
            BlockStitch.Type.FILL -> StitchTypeRule.Type.TATAMI
            BlockStitch.Type.SATIN -> StitchTypeRule.Type.SATIN
            BlockStitch.Type.RUNNING -> StitchTypeRule.Type.RUNNING
        },r.color,
            widthMm,r.color.color and 0xffffff,paths=r.paths,regionId=r.id) }
        if(overrides.isEmpty()) return VectorArt.Result(source.pattern,parts,1.0,regionPatterns=source.fragments)
        val fragments=regions.associate { r ->
            val settings=overrides[r.id]
            r.id to if(settings==null) source.fragments[r.id]
                ?: error("PHOTO_SOURCE_PROVENANCE_MISSING: reimport this picture")
            else {
                val groups=ShapeBlockRenderer.generate(VectorArt.RegionInput(r.id,r.paths,r.color,null),settings).groups
                GeneratedPatternProcessor.fromGroups(groups,pecJumpTrims=true,lockAtThreadBoundaries=true,omitZeroLengthMotions=true)
            }
        }
        val pattern=RegionOrderPatterns.compose(regions.map { it.id },fragments,source.pattern)
        return VectorArt.Result(pattern,parts,1.0,regionPatterns=fragments)
    }
}
