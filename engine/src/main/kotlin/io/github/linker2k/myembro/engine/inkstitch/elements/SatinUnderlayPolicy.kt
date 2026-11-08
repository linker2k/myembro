// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.elements

/**
 * Default underlay for satins the app generates (user decision 2026-11-08: satin without
 * underlay is rare; it limits pull distortion). Only the on/off choice depends on width; every
 * underlay parameter stays the Ink/Stitch v3.3.0 default in [SatinParameters].
 * Pre-digitized Ink/Stitch font satins never pass through here: their SVG settings are kept.
 *
 * Basis (docs/satin-underlay.md): Ink/Stitch satin-column docs order center-walk (thin),
 * contour (small/medium), contour+zigzag "German" (wide) without numbers. Wilcom
 * EmbroideryStudio 28 gives center run for "narrow columns – e.g. 2-3 mm wide"; Embroidery
 * Legacy and ABDigitizing give zigzag/double underlay from 4 mm. The PE-Design corpus survey
 * (heuristic) finds center walk the most common underlay in every band below 4 mm.
 * No source agrees on a separate contour-only band, so none is used.
 */
object SatinUnderlayPolicy {
    /** Column width (mm, inclusive) from which contour + zigzag replaces center walk. */
    const val GERMAN_FROM_MM = 4.0

    /** Explicit underlay choices are kept; with none chosen, choose by [widthMm]. */
    fun withDefault(parameters: SatinParameters, widthMm: Double): SatinParameters {
        require(widthMm.isFinite() && widthMm >= 0)
        if (parameters.center_walk_underlay || parameters.contour_underlay || parameters.zigzag_underlay) return parameters
        return if (widthMm < GERMAN_FROM_MM) parameters.copy(center_walk_underlay = true)
        else parameters.copy(contour_underlay = true, zigzag_underlay = true)
    }
}
