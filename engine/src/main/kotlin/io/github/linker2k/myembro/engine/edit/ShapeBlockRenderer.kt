// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.elements.*
import io.github.linker2k.myembro.engine.inkstitch.lettering.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.*
import org.locationtech.jts.geom.Polygon

/** Source ranges and units: docs/agent/block-stitch.md. No silent clamp/fallback for user overrides. */
data class BlockStitchPatch(
    val type: BlockStitch.Type? = null, val fill_method: String? = null,
    val automatic_angle: Boolean? = null, val angle: Double? = null,
    val row_spacing_mm: Double? = null, val meander_pattern: String? = null,
    val meander_scale_percent: List<Double>? = null, val zigzag_spacing_mm: Double? = null,
) {
    fun applyTo(old: BlockStitch): BlockStitch {
        require(angle == null || angle.isFinite()) { "INVALID_ANGLE" }
        val auto = automatic_angle ?: if (angle != null) false else old.automatic_angle
        val chosen = if (auto) FillParameters().angle else angle ?: old.fill.angle
        return old.copy(type = type ?: old.type, automatic_angle = auto,
            fill = old.fill.copy(fill_method = fill_method ?: old.fill.fill_method, angle = chosen,
                row_spacing_mm = row_spacing_mm ?: old.fill.row_spacing_mm),
            satin = if (old.satin != null || zigzag_spacing_mm != null || (type ?: old.type) == BlockStitch.Type.SATIN) (old.satin ?: SatinParameters()).copy(zigzag_spacing_mm = zigzag_spacing_mm ?: old.satin?.zigzag_spacing_mm ?: SatinParameters().zigzag_spacing_mm) else null,
            meander_pattern = meander_pattern ?: old.meander_pattern,
            meander_scale_percent = (meander_scale_percent ?: old.meander_scale_percent).toList())
    }
}

internal object ShapeBlockRenderer {
    val methods = setOf("tatami_fill", "circular_fill", "contour_fill", "cross_stitch", "guided_fill",
        "linear_gradient_fill", "meander_fill", "tartan_fill", "legacy_fill")
    fun validate(p: BlockStitch) {
        require(p.fill.fill_method != "linear_gradient_fill") { "LINEAR_GRADIENT_REQUIRES_COLOR_STOPS: block gradients need at least 2 color stops and are unavailable in this version" }
        require(p.fill.fill_method in methods) { "INVALID_FILL_METHOD" }
        require(p.fill.angle.isFinite()) { "INVALID_ANGLE" }
        require(!p.automatic_angle || p.fill.angle == FillParameters().angle) { "AUTOMATIC_ANGLE_MUST_MATCH_APP_DEFAULT" }
        // v3.3.0 fill_stitch.py:364–380; element.py:111–123 converts mm to pixels.
        require(p.fill.row_spacing_mm.isFinite() && p.fill.row_spacing_mm >= 0.1) { "ROW_SPACING_BELOW_UPSTREAM_MIN_0.1mm" }
        // fill_stitch.py:239–248, np.maximum(split-percent, (1,1)). No upper bound.
        require(p.meander_scale_percent.size in 1..2 && p.meander_scale_percent.all { it.isFinite() && it >= 1.0 }) { "MEANDER_SCALE_BELOW_UPSTREAM_MIN_1_PERCENT" }
        require(p.meander_pattern in MeanderFill.availablePatterns()) { "UNKNOWN_MEANDER_PATTERN" }
        val satin = p.satin ?: SatinParameters()
        // satin_column.py:263–272: clamp is 0.01 SVG px after mm conversion.
        require(satin.zigzag_spacing_mm.isFinite() && satin.zigzag_spacing_mm >= 0.01 / PIXELS_PER_MM) { "SATIN_SPACING_BELOW_UPSTREAM_MIN_0.01px" }
    }

    fun generate(input: VectorArt.RegionInput, settings: BlockStitch): StitchTypeRule.RegionStitches {
        validate(settings)
        return try { generateValidated(input, settings) }
        catch(e: java.util.concurrent.CancellationException) { throw e }
        catch(e: InterruptedException) { throw e }
        catch(e: BlockGenerationException) { throw e }
        catch(e: MeanderGraphException) { throw BlockGenerationException(BlockGenerationKind.MEANDER_GRAPH,e.message ?: "Meander graph failed",e) }
        catch(e: Exception) {
            val kind=when {
                settings.type==BlockStitch.Type.SATIN -> BlockGenerationKind.SATIN_GENERATION_FAILED
                settings.fill.fill_method=="meander_fill" -> BlockGenerationKind.MEANDER_GENERATION
                else -> BlockGenerationKind.REGION_GENERATION
            }
            throw BlockGenerationException(kind,e.message ?: "Region generation failed",e)
        }
    }

    private fun generateValidated(input: VectorArt.RegionInput, settings: BlockStitch): StitchTypeRule.RegionStitches {
        val paths = input.paths.map { line -> Polyline(line.points.map { Point(it.x * PIXELS_PER_MM, it.y * PIXELS_PER_MM) }, line.closed) }
        val region = PolygonRegion.fromPaths(paths, FillRule.EVEN_ODD)
        val previous = input.previous
        val thread = input.thread
        val p = settings.fill
        val type = when(settings.type) {
            BlockStitch.Type.FILL -> StitchTypeRule.Type.TATAMI
            BlockStitch.Type.SATIN -> StitchTypeRule.Type.SATIN
            BlockStitch.Type.RUNNING -> StitchTypeRule.Type.RUNNING
        }
        val groups = when(settings.type) {
            BlockStitch.Type.RUNNING -> Stroke(paths.map { line -> line.points.map { io.github.linker2k.myembro.engine.inkstitch.utils.Point(it.x,it.y) } }, thread).toStitchGroups(previous)
            BlockStitch.Type.SATIN -> RegionSatin.groups(PolygonRegion.fromPaths(input.paths,FillRule.EVEN_ODD).geometry,
                { x,y -> io.github.linker2k.myembro.engine.inkstitch.utils.Point(x*PIXELS_PER_MM,y*PIXELS_PER_MM) }, thread, settings.satin ?: SatinParameters())
                ?: throw BlockGenerationException(BlockGenerationKind.SATIN_GEOMETRY_UNSUPPORTED,"No complete satin geometry could be generated")
            BlockStitch.Type.FILL -> if (p.fill_method == "tatami_fill") {
                val attrs = mapOf("fill_method" to p.fill_method, "angle" to p.angle.toString(), "row_spacing_mm" to p.row_spacing_mm.toString())
                FontTatami.stitchGroups(InkFill(paths,FillRule.EVEN_ODD,thread.color,InkStitchParams(attrs)),
                    { q -> io.github.linker2k.myembro.engine.inkstitch.utils.Point(q.x,q.y) },attrs,thread,previous)
            } else {
                val result = mutableListOf<StitchGroup>()
                val locks = LockStitches.fromParameters(LockStitchParameters())
                var start = previous?.let { FillPoint(it.x,it.y) }
                fun add(points: List<FillPoint>) {
                    var current = mutableListOf<Stitch>()
                    fun flush() { if(current.isNotEmpty()) { result += StitchGroup(thread,current,lockStart=locks.start,lockEnd=locks.end);current=mutableListOf() } }
                    for(point in points) {
                        if("jump" in point.tags) flush()
                        current += Stitch(point.x,point.y,point.tags - "jump")
                    }
                    flush();start=points.lastOrNull() ?: start
                }
                for (poly in FillGeometry.polygons(FillGeometry.makeValid(region.geometry))) {
                    for(layer in FillUnderlay.generate(poly,p,start)) add(layer.stitches)
                    val points = when(p.fill_method) {
                        "meander_fill" -> MeanderFill.fill(poly,MeanderFillParameters(meander_pattern=settings.meander_pattern,
                            meander_scale_percent=settings.meander_scale_percent),startingPoint=start)
                        "contour_fill" -> if(start==null) ContourFill.fill(poly,ContourFillParameters(row_spacing_mm=p.row_spacing_mm)) else ContourFill.fill(poly,ContourFillParameters(row_spacing_mm=p.row_spacing_mm),start!!)
                        "circular_fill" -> CircularFill.generate(poly,CircularFill.Parameters(angle=p.angle,row_spacing_mm=p.row_spacing_mm),start)
                        else -> throw BlockGenerationException(BlockGenerationKind.UNSUPPORTED_FILL_BACKEND,"Unsupported fill backend: ${p.fill_method}")
                    }
                    add(points)
                }
                result
            }
        }
        if(groups.none { it.stitches.isNotEmpty() }) throw BlockGenerationException(BlockGenerationKind.EMPTY_REGION,"Region generated no stitches")
        return StitchTypeRule.RegionStitches(groups,type)
    }
}
