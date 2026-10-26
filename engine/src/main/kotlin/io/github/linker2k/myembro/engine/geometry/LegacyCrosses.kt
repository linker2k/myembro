// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.geometry

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.operation.relate.RelateOp

/** OGC crosses via JTS's classic relation implementation, independent of GeometryRelate configuration.
 * See docs/research/no-relateng.md for the bytecode call path and mobile verification incident. */
internal object LegacyCrosses {
    fun test(a: Geometry, b: Geometry): Boolean =
        a.envelopeInternal.intersects(b.envelopeInternal) &&
            RelateOp.relate(a, b).isCrosses(a.dimension, b.dimension)
}
