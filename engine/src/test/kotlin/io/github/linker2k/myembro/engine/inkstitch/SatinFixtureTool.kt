// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch

import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.utils.Point as SvgPoint
import io.github.linker2k.myembro.engine.inkstitch.elements.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.EmbPatternAdapter
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchPlan
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.ColorBlock
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

/** Fixed SVG input -> geometric layers -> existing EmbPattern/PES adapter, pending worker StitchGroup contract. */
class SatinFixtureTool {
    @Test fun fixedSvgProducesStitchesAndOptionalIndependentGateArtifacts() {
        val factory=DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl",true) }
        val doc=factory.newDocumentBuilder().parse(File("src/test/resources/inkstitch/satin-column.svg"))
        val path=doc.getElementsByTagName("path").item(0).attributes.getNamedItem("d").nodeValue
        // This fixture contains M/L only; production SVG parsing belongs to the canvas/font parser.
        val paths=path.split('M').filter(String::isNotBlank).map { segment->
            val values=Regex("-?\\d+(?:\\.\\d+)?").findAll(segment).map { it.value.toDouble() }.toList()
            values.chunked(2).map { Point(it[0],it[1]) }
        }
        val out=System.getenv("MYEMBRO_SATIN_FIXTURES")?.let(::File)?.apply { mkdirs() }
        val cases=linkedMapOf(
            "satin" to SatinParameters(max_stitch_length_mm=7.0),
            "underlays" to SatinParameters(max_stitch_length_mm=7.0,center_walk_underlay=true,contour_underlay=true,zigzag_underlay=true,zigzag_underlay_max_stitch_length_mm=7.0),
            "split-staggered" to SatinParameters(max_stitch_length_mm=2.0,split_method="staggered"),
            "split-random" to SatinParameters(max_stitch_length_mm=2.0,random_split_phase=true,random_split_jitter_percent=10.0,random_seed="fixture"),
            "e-stitch" to SatinParameters(max_stitch_length_mm=7.0,satin_method="e_stitch"),
            "s-stitch" to SatinParameters(max_stitch_length_mm=7.0,satin_method="s_stitch"),
            "zigzag" to SatinParameters(max_stitch_length_mm=7.0,satin_method="zigzag"))
        for((name,p) in cases) {
            fun Point.svg()=SvgPoint(Units.mmToPixels(x),Units.mmToPixels(y))
            val group=SatinColumn.fromSubpaths(paths.map { path->path.map { it.svg() } },p,
                color=EmbThread(0xFF000000.toInt())).toStitchGroups(null).single()
            assertTrue(group.stitches.size>=150)
            val safe=GeneratedPatternProcessor.fromGroups(listOf(group))
            out?.resolve("$name.pes")?.writeBytes(PesWriter.write(safe))
        }
        if(out!=null) {
            fun quote(s:String)="\"${s.replace("\\","\\\\").replace("\"","\\\"")}\""
            val fonts=cases.keys.joinToString(",") { quote("synthetic-$it") }
            val entries=cases.keys.joinToString(",") { "{\"font\":${quote("synthetic-$it")},\"height_mm\":34,\"style\":\"satin\",\"path\":${quote(out.resolve("$it.pes").absolutePath)}}" }
            out.resolve("matrix.json").writeText("{\"axes\":{\"fonts\":[$fonts],\"heights_mm\":[34],\"styles\":[\"satin\"]},\"cases\":[$entries]}")
        }
    }
}
