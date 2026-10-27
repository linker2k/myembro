// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import org.locationtech.jts.io.WKTReader
import org.locationtech.jts.geom.LineString
import io.github.linker2k.myembro.engine.geometry.LegacyCrosses
import kotlin.test.*

class CircularPredicateParityTest {
    @Test fun explicitLegacyCrossesMatchesGeometryForBoundaryOverlapAndSelfCrossingLines() {
        val reader=WKTReader()
        val lines=listOf(
            "LINESTRING(0 0,10 10)", "LINESTRING(0 10,10 0)",
            "LINESTRING(10 10,20 0)", "LINESTRING(2 2,8 8)",
            "LINESTRING(0 0,10 10,0 10,10 0,0 0)",
            "LINESTRING(0 5,10 5)", "LINESTRING(0 0,0 0,5 5,10 0)",
            "LINESTRING(0 0,10 0.000000001)", "LINESTRING(5 -1,5 1)").map(reader::read)
        for(a in lines) {
            val prepared=LegacyCrosses.prepareLine(a as LineString)
            for(b in lines) {
                val expected=LegacyCrosses.test(a,b)
                assertEquals(a.crosses(b),expected,"$a / $b classic")
                assertEquals(expected,prepared.test(b as LineString),"$a / $b two-stage")
            }
        }
        println("CIRCULAR_PREDICATE_PARITY pairs=${lines.size*lines.size} identical=true")
    }
}
