// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.geometry.LineCrossesTrace
import io.github.linker2k.myembro.engine.inkstitch.elements.FillParameters
import org.locationtech.jts.operation.relate.RelateOp
import java.util.IdentityHashMap
import kotlin.test.*

class LineCrossesActualInputsTest {
    @Test fun actualCircularAndMeanderCandidateDistributionsAndOracle() {
        for(method in listOf("circular_fill","meander_fill")) for(name in listOf("circle","square")) {
            val d=if(name=="square") "M0 0H40V40H0Z" else "M40 20A20 20 0 1 0 0 20A20 20 0 1 0 40 20Z"
            val base=ShapeObject.fromSvg("input","input","""<svg viewBox="0 0 40 40"><path fill="#224488" d="$d"/></svg>""",40.0)
            val settings=BlockStitch(fill=FillParameters(fill_method=method),automatic_angle=false,meander_pattern="NC5-11a")
            val obj=base.withOverrides(base.regions.associate { it.id to settings })
            val rows=IdentityHashMap<org.locationtech.jts.geom.LineString,Boolean>()
            val rowVertices=sortedMapOf<Int,Int>();val candidateVertices=sortedMapOf<Int,Int>()
            val paths=sortedMapOf<String,Int>()
            var pairs=0;var mismatches=0
            LineCrossesTrace.observe({ row,line,actual,path ->
                paths[path]=(paths[path] ?: 0)+1
                pairs++;candidateVertices[line.numPoints]=(candidateVertices[line.numPoints] ?: 0)+1
                if(rows.put(row,true)==null) rowVertices[row.numPoints]=(rowVertices[row.numPoints] ?: 0)+1
                val expected=RelateOp.relate(row,line).isCrosses(1,1)
                if(expected!=actual) { mismatches++;fail("$method $name $row / $line expected=$expected actual=$actual") }
            }) { obj.localPattern() }
            assertTrue(pairs>0)
            println("ACTUAL_CROSSES method=$method shape=$name pairs=$pairs mismatches=$mismatches row_vertices=$rowVertices candidate_vertices=$candidateVertices paths=$paths")
        }
    }
}
