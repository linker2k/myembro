// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.geometry.Polyline
import io.github.linker2k.myembro.engine.inkstitch.elements.FillParameters
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import java.util.Collections

/** Typed upstream parameters, without renamed attributes or invented defaults.
 * Missing overrides preserve the legacy renderer; explicit overrides use the selected upstream backend. */
data class BlockStitch(
    val type: Type = Type.FILL,
    val fill: FillParameters = FillParameters(),
    val satin: SatinParameters? = null,
    val automatic_angle: Boolean = true,
    val meander_pattern: String = io.github.linker2k.myembro.engine.inkstitch.stitches.MeanderFillParameters().meander_pattern,
    val meander_scale_percent: List<Double> = listOf(100.0, 100.0),
) {
    enum class Type { FILL, SATIN, RUNNING }
}

/** One actually emitted region, after occlusion and the existing safety fallback.
 * Paths use centered local millimeters, retain holes, and are not hoop coordinates. */
class ShapeRegion internal constructor(
    val id: String,
    val sourceShape: Int,
    val role: String,
    paths: List<Polyline>,
    val color: EmbThread,
    val stitch: BlockStitch,
    val defaultStitch: BlockStitch = stitch,
) {
    val paths: List<Polyline> = frozen(paths.map { it.copy(points = frozen(it.points)) })
    internal val capabilityProbe: RegionCapabilityProbe by lazy { BlockStitchCapabilities.probe(this) }
}

/** Editable-vector model draft. Source paint geometry is retained, never reconstructed from stitches.
 * Rendering reuses VectorArt unchanged, including shared classification, occlusion, routing and finish.
 * Region changes are applied atomically through ProjectCommand.SetBlockStitch.
 * No codec/UI installation is implied by constructing this object. */
class ShapeObject internal constructor(
    override val id: String,
    override val name: String,
    val sourceArt: VectorArt.Art,
    val widthMm: Double,
    override val transform: Transform,
    override val visible: Boolean,
    regions: List<ShapeRegion>,
    val overrides: Map<String, BlockStitch> = emptyMap(),
    val regionOrder: List<String> = frozen(regions.map { it.id }),
    internal val photoSource: PhotoShapeSource? = null,
    private val rendered: Lazy<VectorArt.Result> = lazy {
        val result = if(photoSource!=null) PhotoShapeRenderer.render(photoSource,regions,overrides,widthMm) else VectorArt.render(sourceArt, widthMm, name = name,
            regionOverride = { input -> overrides[input.id]?.let { ShapeBlockRenderer.generate(input, it) } }, captureRegions = true)
        require(overrides.isEmpty() || result.parts.map { it.regionId }.toSet() == regions.map { it.id }.toSet()) {
            "REGION_SET_CHANGED: override must not silently exclude another region"
        }
        result
    },
) : DesignObject {
    val regions: List<ShapeRegion> = frozen(regions)
    private val orderedPattern by lazy {
        val base=rendered.value
        if(regionOrder==base.parts.map { it.regionId }) base.pattern else
            io.github.linker2k.myembro.engine.inkstitch.svg.RegionOrderPatterns.compose(regionOrder,base.regionPatterns,base.pattern)
    }
    override fun localPattern(): EmbPattern = orderedPattern
    /** Finished source-order region needles, unaffected by sewing-order changes. */
    fun regionPattern(id: String): EmbPattern = rendered.value.regionPatterns.getValue(id)
    internal fun withRegionOrder(order: List<String>): ShapeObject {
        require(order.size==regions.size && order.toSet()==regions.map { it.id }.toSet()) { "INVALID_REGION_ORDER" }
        require(order==regionOrder || photoSource==null || photoSource.fragments.keys.containsAll(order)) {
            "PHOTO_SOURCE_PROVENANCE_MISSING: reimport this picture"
        }
        return if(order==regionOrder) this else ShapeObject(id,name,sourceArt,widthMm,transform,visible,regions,overrides,frozen(order),photoSource,rendered)
    }
    override fun withId(id: String) = if (this.id == id) this else copy(id = id)
    override fun withTransform(t: Transform) = if (transform == t) this else copy(transform = t)
    override fun withVisible(v: Boolean) = if (visible == v) this else copy(visible = v)

    // Regions are derived exclusively from immutable source/defaults in this draft.
    override fun equals(other: Any?): Boolean = other is ShapeObject && id == other.id &&
        name == other.name && sourceArt == other.sourceArt && widthMm == other.widthMm &&
        transform == other.transform && visible == other.visible && overrides == other.overrides && regionOrder == other.regionOrder
    override fun hashCode(): Int = listOf(id, name, sourceArt, widthMm, transform, visible, overrides, regionOrder).hashCode()

    private fun copy(id: String = this.id, transform: Transform = this.transform, visible: Boolean = this.visible) =
        ShapeObject(id, name, sourceArt, widthMm, transform, visible, regions, overrides, regionOrder, photoSource, rendered)

    internal fun withOverrides(values: Map<String, BlockStitch>): ShapeObject {
        require(values.keys.all { key -> regions.any { it.id == key } }) { "UNKNOWN_REGION" }
        values.values.forEach(ShapeBlockRenderer::validate)
        val normalized = values.filter { (key,value) -> value != regions.first { it.id == key }.defaultStitch }
        if (normalized == overrides) return this
        val updated = regions.map { r -> ShapeRegion(r.id, r.sourceShape, r.role, r.paths, r.color,
            normalized[r.id] ?: r.defaultStitch, r.defaultStitch) }
        return ShapeObject(id, name, sourceArt, widthMm, transform, visible, updated,
            java.util.Collections.unmodifiableMap(normalized.mapValues { (_,value) -> value.copy(meander_scale_percent=frozen(value.meander_scale_percent)) }), regionOrder, photoSource)
    }

    companion object {
        fun fromSvg(id: String, name: String, svg: String, widthMm: Double): ShapeObject =
            fromArt(id, name, VectorArt.parseSvg(svg), widthMm)

        fun fromArt(id: String, name: String, art: VectorArt.Art, widthMm: Double): ShapeObject {
            require(id.isNotBlank()) { "Shape object ID must not be blank" }
            val source = VectorArt.Art(frozen(art.viewBox), frozen(art.shapes))
            val rendered = VectorArt.render(source, widthMm, name = name, captureRegions = true)
            val regions = rendered.parts.mapIndexed { i, part ->
                val type = when (part.method) {
                    StitchTypeRule.Type.TATAMI -> BlockStitch.Type.FILL
                    StitchTypeRule.Type.SATIN -> BlockStitch.Type.SATIN
                    StitchTypeRule.Type.RUNNING -> BlockStitch.Type.RUNNING
                }
                ShapeRegion(part.regionId, part.shape, part.role, part.paths, part.thread,
                    BlockStitch(type, satin = if (type == BlockStitch.Type.SATIN) SatinParameters() else null))
            }
            return ShapeObject(id, name, source, widthMm, Transform(), true, regions, rendered=lazyOf(rendered))
        }
    }
}

private fun <T> frozen(items: List<T>): List<T> = Collections.unmodifiableList(ArrayList(items))
