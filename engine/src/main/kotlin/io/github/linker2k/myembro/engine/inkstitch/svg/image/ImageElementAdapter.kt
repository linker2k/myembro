// SPDX-License-Identifier: GPL-3.0-or-later
// Original myembro image-to-element integration; not an Ink/Stitch source port.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.inkstitch.elements.EmbroideryElement
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.model.EmbThread

/** Coordinates are SVG px (96dpi). Filled paths use even-odd winding, including all holes. */
data class ImageSvgPath(val points: List<Point>, val closed: Boolean)

/** Ready for the shared elements factory: fill outlines, two satin rails, or a running centerline. */
data class ImageSvgElement(
    val regionId: Int,
    val type: ImageStitchType,
    val color: EmbThread,
    val paths: List<ImageSvgPath>,
    val parameters: Map<String, String>,
    val fillRule: FillRule = FillRule.EVEN_ODD,
)

/** Explicit 0.1mm -> SVG px boundary. No rounding before the shared plan-to-pattern adapter. */
fun ImageElement.toSvgElement(): ImageSvgElement {
    val paths = when (type) {
        ImageStitchType.FILL -> region.geometry.boundaries()
        ImageStitchType.SATIN -> listOf(rails!!.first, rails.second)
        ImageStitchType.RUNNING -> listOf(centerLine!!)
    }
    return ImageSvgElement(region.id, type, region.color.thread, paths.map { path ->
        ImageSvgPath(path.points.map { p -> Point(Units.mmToPixels(p.x / 10), Units.mmToPixels(p.y / 10)) }, path.closed)
    }, parameters.toMap())
}

/** Passive handoff: concrete Fill/Satin/Stroke implementations and plan finishing remain owned
 * by their porting agents. The factory receives actual shared px coordinates, not native units.
 */
fun ImageEmbroideryDraft.toEmbroideryElements(factory: (ImageSvgElement) -> EmbroideryElement): List<EmbroideryElement> =
    elements.map { factory(it.toSvgElement()) }
