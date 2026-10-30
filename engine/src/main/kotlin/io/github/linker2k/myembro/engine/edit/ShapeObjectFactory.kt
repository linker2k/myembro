// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorDrawingPipeline
import io.github.linker2k.myembro.engine.model.EmbPattern
import java.util.Collections

/** Retain Potrace areas and the already checked photo stitches; never reroute them via VectorArt. */
object ShapeObjectFactory {
    const val PHOTO_FILL = "photo-fill"
    fun isPhoto(obj: ShapeObject) = obj.regions.isNotEmpty() && obj.regions.all { it.role == PHOTO_FILL }

    /** Preserve native photo stitches and the automatic type for every retained painted region. */
    fun photoUnsupportedReason(result: ColorDrawingPipeline.Result): String? {
        val sewn = result.regions.filter { !it.excluded && it.groups > 0 }
        return when {
            sewn.isEmpty() -> "NO_SEWN_REGIONS"
            sewn.any { it.paths.isEmpty() } -> "MISSING_REGION_PATHS"
            else -> null
        }
    }
    fun fromPhoto(id: String, name: String, result: ColorDrawingPipeline.Result, widthMm: Double): ShapeObject? {
        val sewn = result.regions.filter { !it.excluded && it.groups > 0 }
        if (photoUnsupportedReason(result) != null) return null
        val regions = sewn.mapIndexed { i, r ->
            val type = when (r.method) {
                StitchTypeRule.Type.TATAMI -> BlockStitch.Type.FILL
                StitchTypeRule.Type.SATIN -> BlockStitch.Type.SATIN
                StitchTypeRule.Type.RUNNING -> BlockStitch.Type.RUNNING
            }
            ShapeRegion("region-$i", i, PHOTO_FILL, r.paths, r.thread,
                BlockStitch(type, satin = if (type == BlockStitch.Type.SATIN)
                    SatinParameters() else null))
        }
        val points = regions.flatMap { it.paths }.flatMap { it.points }
        val x = points.minOf { it.x }; val y = points.minOf { it.y }
        val art = VectorArt.Art(frozen(listOf(x,y,points.maxOf { it.x }-x,points.maxOf { it.y }-y)),
            frozen(regions.map { region -> VectorArt.Shape(pathData(region.paths), region.color.color and 0xffffff, null, fillRule=FillRule.EVEN_ODD) }))
        val skippedById=regions.mapIndexed { i,r -> r.id to sewn[i].outlineSkipped }.toMap()
        val fragments=result.regionPatterns.mapValues { (id,p) ->
            val skipped=skippedById.getValue(id)
            EmbPattern(p.stitches,p.threads,p.metadata+(PhotoShapeRenderer.REGION_OUTLINE_SKIPPED_KEY to skipped.toString()),p.warnings)
        }
        return centeredForImport(retained(id,name,art,widthMm,regions,result.pattern,fragments))
    }
    /** Fresh picture insertion only. Keep original paths, cached needles and fragments untouched;
     * an integer pose translates every command together without rerendering or adding needles.
     * Set the pose from local bounds, rather than adding it, so factory + UI calls are idempotent.
     */
    fun centeredForImport(obj: ShapeObject): ShapeObject {
        val bounds = obj.localPattern().bounds ?: return obj
        val (cx, cy) = integerHoopCenter(bounds)
        return obj.withTransform(Transform(x = -cx.toDouble(), y = -cy.toDouble()))
    }
    /** Codec and rename retain the same backend/default pattern, including thread identity and metadata. */
    internal fun retained(id: String, name: String, art: VectorArt.Art, widthMm: Double,
        regions: List<ShapeRegion>, pattern: EmbPattern, fragments: Map<String,EmbPattern> = emptyMap()): ShapeObject {
        require(id.isNotBlank() && widthMm.isFinite() && widthMm > 0)
        require(regions.isNotEmpty() && regions.all { it.role == PHOTO_FILL && it.paths.isNotEmpty() })
        val source = VectorArt.Art(frozen(art.viewBox), frozen(art.shapes))
        val cached = EmbPattern(frozen(pattern.stitches), frozen(pattern.threads),
            Collections.unmodifiableMap(LinkedHashMap(pattern.metadata)), frozen(pattern.warnings))
        val native=Collections.unmodifiableMap(fragments.mapValues { (_,p) -> EmbPattern(frozen(p.stitches),frozen(p.threads),
            Collections.unmodifiableMap(LinkedHashMap(p.metadata)),frozen(p.warnings)) })
        return ShapeObject(id=id,name=name,sourceArt=source,widthMm=widthMm,transform=Transform(),
            visible=true,regions=regions,photoSource=PhotoShapeSource(cached,native))
    }
    fun renamed(obj: ShapeObject, id: String, name: String): ShapeObject = if(isPhoto(obj))
        retained(id,name,obj.sourceArt,obj.widthMm,obj.regions,requireNotNull(obj.photoSource).pattern,obj.photoSource.fragments)
            .withOverrides(obj.overrides).withRegionOrder(obj.regionOrder).withTransform(obj.transform).withVisible(obj.visible)
        else ShapeObject.fromArt(id,name,obj.sourceArt,obj.widthMm).withOverrides(obj.overrides).withRegionOrder(obj.regionOrder)

    private fun pathData(paths: List<Polyline>) = paths.joinToString(" ") { path ->
        buildString { path.points.forEachIndexed { i,p -> append(if(i==0) "M " else "L ");append(p.x);append(' ');append(p.y);append(' ') };if(path.closed) append('Z') }
    }
    private fun <T> frozen(list: List<T>): List<T> = Collections.unmodifiableList(ArrayList(list))
}
