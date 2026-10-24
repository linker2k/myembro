// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.diagnostics.FontFillProbe
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlinx.serialization.json.*

class FontFillProbeTest {
    @Test fun captureStageByStageWithoutChangingOutput() {
        val input=GZIPInputStream(checkNotNull(javaClass.getResourceAsStream("/font-snapshots/noto-sans-kr-latin.myembro.gz"))).bufferedReader().use { it.readText() }
        val out=File("/tmp/myembro-font-fill-probe").apply { mkdirs() }
        val report=FontFillProbe.run(input)
        File(out,"jvm.json").writeText(report)
        val runs=Json.parseToJsonElement(report).jsonObject.getValue("runs").jsonArray
        val invariant=setOf("input_rings","noded_lines","polygonized_normalized","parameters","row_segments")
        fun values(run:JsonElement)=run.jsonObject.getValue("stages").jsonArray.map { it.jsonObject }
            .filter { it.getValue("stage").jsonPrimitive.content in invariant }
            .map { it.getValue("stage") to it.getValue("sha256") }
        assertTrue(values(runs.first()).isNotEmpty())
        runs.forEach { assertEquals(values(runs.first()),values(it),"Numeric geometry/rows must remain exact") }
        println("FILL_PROBE JVM /tmp/myembro-font-fill-probe/jvm.json")
    }
}
