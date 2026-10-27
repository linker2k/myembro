// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.geometry

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.operation.relate.RelateOp

/** OGC crosses via JTS's classic relation implementation, independent of GeometryRelate configuration.
 * See docs/research/no-relateng.md for the bytecode call path and mobile verification incident. */
internal object LegacyCrosses {
    /** Reusable exact segment-index predicate; the direct relation implementation remains the test oracle. */
    fun prepareLine(row: LineString) = PreparedLine(row)

    class PreparedLine internal constructor(private val row: LineString) {
        private val predicate = IndexedLineCrosses(row)
        fun test(candidate: LineString): Boolean {
            return predicate.test(candidate)
        }
    }

    fun test(a: Geometry, b: Geometry): Boolean =
        a.envelopeInternal.intersects(b.envelopeInternal) &&
            RelateOp.relate(a, b).isCrosses(a.dimension, b.dimension)
}
