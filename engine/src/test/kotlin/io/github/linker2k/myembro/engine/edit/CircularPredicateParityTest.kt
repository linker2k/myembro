// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import org.locationtech.jts.io.WKTReader
import org.locationtech.jts.operation.relateng.RelateNG
import org.locationtech.jts.operation.relateng.RelatePredicate
import kotlin.test.*

class CircularPredicateParityTest {
    @Test fun preparedCrossesMatchesLegacyForBoundaryOverlapAndSelfCrossingLines() {
        val reader=WKTReader()
        val lines=listOf(
            "LINESTRING(0 0,10 10)", "LINESTRING(0 10,10 0)",
            "LINESTRING(10 10,20 0)", "LINESTRING(2 2,8 8)",
            "LINESTRING(0 0,10 10,0 10,10 0,0 0)",
            "LINESTRING(0 5,10 5)", "LINESTRING(0 0,0 0,5 5,10 0)",
            "LINESTRING(0 0,10 0.000000001)", "LINESTRING(5 -1,5 1)").map(reader::read)
        for(a in lines) {
            val prepared=RelateNG.prepare(a)
            for(b in lines) assertEquals(a.crosses(b),prepared.evaluate(b,RelatePredicate.crosses()),"$a / $b")
        }
        println("CIRCULAR_PREDICATE_PARITY pairs=${lines.size*lines.size} identical=true")
    }
}
