// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.inkstitch.elements.FillParameters
import io.github.linker2k.myembro.engine.inkstitch.stitches.FillTrace
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.*

/** Synthetic mobile fixtures, exact pre-optimization port coordinates (not an upstream oracle). */
class CircularBlockProfileTest {
    @Test fun square() = profile("square")
    @Test fun circle() = profile("circle")
    private fun profile(shape: String) {
        val path = if(shape=="square") "M0 0H40V40H0Z" else "M40 20A20 20 0 1 0 0 20A20 20 0 1 0 40 20Z"
        val base = ShapeObject.fromSvg("timing-$shape","Synthetic $shape",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 40 40\"><path fill=\"#224488\" d=\"$path\"/></svg>",40.0)
        val settings = BlockStitch(fill=FillParameters(fill_method="circular_fill"),automatic_angle=false)
        val runs = mutableListOf<Double>()
        repeat(3) { run ->
            val obj=base.withOverrides(base.regions.associate { it.id to settings })
            val stages=linkedMapOf<String,Long>()
            var marked=false;var stage="geometry";var tick=System.nanoTime()
            val start=tick
            val result=FillTrace.observe({ key,_ ->
                if(key.startsWith("timing:")) {
                    marked=true
                    val now=System.nanoTime();stages[stage]=(stages[stage] ?: 0)+(now-tick)
                    stage=key.substringAfter(':');tick=now
                }
            }) { obj.localPattern() }
            val end=System.nanoTime();stages[stage]=(stages[stage] ?: 0)+(end-tick)
            val millis=(end-start)/1e6;runs+=millis
            val text=result.stitches.joinToString("\n",postfix="\n") { "${it.x},${it.y},${it.command.name}" }
            val name="circular-$shape.csv.gz"
            val record=System.getProperty("circular.record")
            if(record!=null) {
                val file=File(record,name);file.parentFile.mkdirs()
                if(file.exists()) assertEquals(GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() },text)
                else GZIPOutputStream(file.outputStream()).use { it.write(text.toByteArray()) }
            } else {
                val stream=javaClass.getResourceAsStream("/circular-block/$name") ?: error("Missing frozen golden $name")
                assertEquals(GZIPInputStream(stream).bufferedReader().use { it.readText() },text,"All coordinates and commands must match")
            }
            println("CIRCULAR_PROFILE shape=$shape run=${run+1} total_ms=$millis stitches=${result.stitchCount} stages_ms=${if(marked) stages.mapValues { it.value/1e6 }.toString() else "unprofiled"} golden=${if(record==null) "IDENTICAL" else "RECORDED"}")
        }
        println("CIRCULAR_MEDIAN shape=$shape ms=${runs.sorted()[1]}")
    }
}
