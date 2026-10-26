// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorDrawingPipeline
import io.github.linker2k.myembro.engine.model.EmbPattern
import java.util.Collections

/** Retain Potrace areas and the already checked photo stitches; never reroute them via VectorArt. */
object ShapeObjectFactory {
    const val PHOTO_FILL = "photo-fill"
    fun isPhoto(obj: ShapeObject) = obj.regions.isNotEmpty() && obj.regions.all { it.role == PHOTO_FILL }

    /** Mixed AutoTrace centerline/satin/running results remain StitchObject in this integration unit. */
    fun fromPhoto(id: String, name: String, result: ColorDrawingPipeline.Result, widthMm: Double): ShapeObject? {
        val sewn = result.regions.filter { !it.excluded && it.groups > 0 }
        if (sewn.isEmpty() || sewn.any { it.method != StitchTypeRule.Type.TATAMI || it.paths.isEmpty() }) return null
        val regions = sewn.mapIndexed { i, r -> ShapeRegion("region-$i", i, PHOTO_FILL, r.paths, r.thread, BlockStitch()) }
        val points = regions.flatMap { it.paths }.flatMap { it.points }
        val x = points.minOf { it.x }; val y = points.minOf { it.y }
        val art = VectorArt.Art(frozen(listOf(x,y,points.maxOf { it.x }-x,points.maxOf { it.y }-y)),
            frozen(regions.map { region -> VectorArt.Shape(pathData(region.paths), region.color.color and 0xffffff, null, fillRule=FillRule.EVEN_ODD) }))
        return retained(id,name,art,widthMm,regions,result.pattern)
    }
    /** Codec and rename retain the same backend/default pattern, including thread identity and metadata. */
    internal fun retained(id: String, name: String, art: VectorArt.Art, widthMm: Double,
        regions: List<ShapeRegion>, pattern: EmbPattern): ShapeObject {
        require(id.isNotBlank() && widthMm.isFinite() && widthMm > 0)
        require(regions.isNotEmpty() && regions.all { it.role == PHOTO_FILL && it.paths.isNotEmpty() })
        val source = VectorArt.Art(frozen(art.viewBox), frozen(art.shapes))
        val cached = EmbPattern(frozen(pattern.stitches), frozen(pattern.threads),
            Collections.unmodifiableMap(LinkedHashMap(pattern.metadata)), frozen(pattern.warnings))
        return ShapeObject(id=id,name=name,sourceArt=source,widthMm=widthMm,transform=Transform(),
            visible=true,regions=regions,rendered=lazyOf(cached))
    }
    fun renamed(obj: ShapeObject, id: String, name: String): ShapeObject = if(isPhoto(obj))
        retained(id,name,obj.sourceArt,obj.widthMm,obj.regions,obj.localPattern())
            .withTransform(obj.transform).withVisible(obj.visible)
        else ShapeObject.fromArt(id,name,obj.sourceArt,obj.widthMm)

    private fun pathData(paths: List<Polyline>) = paths.joinToString(" ") { path ->
        buildString { path.points.forEachIndexed { i,p -> append(if(i==0) "M " else "L ");append(p.x);append(' ');append(p.y);append(' ') };if(path.closed) append('Z') }
    }
    private fun <T> frozen(list: List<T>): List<T> = Collections.unmodifiableList(ArrayList(list))
}
