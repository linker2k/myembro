// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.oracle

import java.nio.file.Files
import java.io.File
import kotlinx.serialization.json.*
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ImageQuantizer
import kotlin.test.*

class ImageToDesignProbeTest {
    @Test fun twoOriginalEmojiProduceValidMultiColorPesWithoutBlockingFindings() {
        val root=Files.createTempDirectory("image-to-design-test").toFile()
        try {
            val input=File(root,"inputs").apply { mkdir() }
            listOf("face","robot").forEach { name ->
                File(input,"$name.png").writeBytes(javaClass.getResourceAsStream("/image-to-design/$name.png")!!.use { it.readBytes() })
            }
            val output=File(root,"out")
            ImageToDesignProbeCli.main(arrayOf(input.path,output.path,"4"))
            val rows=Json.parseToJsonElement(File(output,"results.json").readText()).jsonArray
            assertEquals(2,rows.size)
            for(row in rows.map { it.jsonObject }) {
                assertTrue(row.getValue("viewport_valid").jsonPrimitive.boolean)
                assertFalse(row.getValue("blocking").jsonPrimitive.boolean,row.toString())
                assertTrue(row.getValue("stitch_count").jsonPrimitive.int>0)
                assertEquals(3,row.getValue("palette_colors").jsonPrimitive.int)
            }
            val styles=rows.flatMap { it.jsonObject.getValue("regions").jsonArray }
                .map { it.jsonObject.getValue("style").jsonPrimitive.content }.toSet()
            assertEquals(setOf("FILL"),styles)
            // Width diagnostics are retained even though painted regions now default to fill.
            val robot=rows.single { it.jsonObject.getValue("input").jsonPrimitive.content=="robot.png" }.jsonObject
            val blue=robot.getValue("regions").jsonArray.single { it.jsonObject.getValue("thread").jsonPrimitive.content=="#2584bb" }.jsonObject
            assertTrue(blue.getValue("median_width_mm").jsonPrimitive.double<6.0)
            assertTrue(blue.getValue("maximum_width_mm").jsonPrimitive.double>6.0)
            assertEquals("FILL",blue.getValue("style").jsonPrimitive.content)
        } finally { root.deleteRecursively() }
    }

    @Test fun existingWuSupportsEveryRequestedColorBudgetFromTwoToSix() {
        val rgb=IntArray(256) { i -> ((i*37%256) shl 16) or ((i*59%256) shl 8) or (i*83%256) }
        for(count in 2..6) {
            val actual=ImageQuantizer.quantize(rgb,count)
            assertEquals(count,actual.palette.size)
            assertTrue(actual.copyIndices().all { it in actual.palette.indices })
            assertEquals(actual.palette,ImageQuantizer.quantize(rgb,count).palette)
        }
    }
}
