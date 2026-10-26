// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.geometry.SvgPath
import java.io.File
import kotlin.test.Test

class SvgCorpusMeasureTest {
    @Test fun measure() {
        val rows=File("../app/src/main/assets/emoji/bundled").listFiles()!!.filter { it.extension=="svg" }.map { file ->
            val art=VectorArt.parseSvg(file.readText())
            val points=art.shapes.sumOf { shape -> SvgPath.flatten(shape.d,shape.transform).sumOf { it.points.size } }
            println("SVG_CORPUS ${file.name} bytes=${file.length()} points=$points")
            Triple(file.name,file.length(),points)
        }
        println("SVG_MAX_BYTES ${rows.maxBy { it.second }}")
        println("SVG_MAX_POINTS ${rows.maxBy { it.third }}")
    }
}
