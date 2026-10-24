// SPDX-License-Identifier: GPL-3.0-or-later
// Exact synthetic input from integrator ColorSampleCli.fail.kt (sun face, not personal art).
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.sewout.*
import kotlin.test.*

class ColorSampleRegressionTest {
    @Test fun smallRoundEyesDoNotAbortTheWholeSunFace() {
        val e = 192
        val argb = IntArray(e * e) { i ->
            val x = i % e - 96; val y = i / e - 96; val r2 = x * x + y * y
            when {
                (x + 28) * (x + 28) + (y + 18) * (y + 18) < 100 || (x - 28) * (x - 28) + (y + 18) * (y + 18) < 100 -> 0xFF303030.toInt()
                y in 25..38 && x in -35..35 -> 0xFFC03030.toInt()
                r2 < 80 * 80 -> 0xFFF0A020.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        }
        val preview = ColorDrawingPipeline.trace(e, e, argb, 40.0, 3, ColorDrawingPipeline.MINIMUM_REGION_AREA_MM2)
        val result = ColorDrawingPipeline.generate(preview)

        val bytes=PesWriter.write(result.pattern)
        val report=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
        println("INTEGRATOR_REPRO stitches=${result.pattern.stitchCount} excluded=${result.excludedCount} types=${result.regions.map { it.method }} blockers=${report.hasBlockingIssues}")
        assertTrue(result.pattern.stitchCount>0)
        assertEquals(0,result.excludedCount)
        assertTrue(result.regions.count { it.method==io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule.Type.TATAMI }>=3)
        assertFalse(report.hasBlockingIssues,report.findings.toString())
        System.getenv("REGION_FALLBACK_OUT")?.let { directory -> java.io.File(directory).apply { mkdirs() }.resolve("sun-face.pes").writeBytes(bytes) }
    }
    @Test fun allRegionsCanBeExcludedWithoutAbortingTheSession() {
        val image=javax.imageio.ImageIO.read(javaClass.getResourceAsStream("/image-to-design/face.png"))
        val preview=ColorDrawingPipeline.trace(image.width,image.height,image.getRGB(0,0,image.width,image.height,null,0,image.width),30.0,3)
        val result=ColorDrawingPipeline.generate(preview,io.github.linker2k.myembro.engine.edit.HoopSpec("test tiny",1,1))
        assertTrue(result.excludedCount>0)
        assertEquals(result.regions.size,result.excludedCount)
        assertEquals(0,result.pattern.stitchCount)
        assertEquals(result.excludedCount.toString(),result.pattern.metadata["myembro.excluded_regions"])
    }

}
