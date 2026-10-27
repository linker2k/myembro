// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.texture

import kotlin.test.*

class TextureRasterPolicyTest {
    @Test fun presetsComeFromAllPassingSweepRowsNotAssumedMonotonicity() {
        val rows=requireNotNull(javaClass.getResourceAsStream("/texture-pitch/results.tsv"))
            .bufferedReader().use { it.readLines() }.drop(1).map { it.split('\t') }
        assertEquals(108,rows.size)
        val passing=rows.groupBy { it[0].toDouble() }.filterValues { group -> group.size==12 && group.all { it[8]=="PASS" } }.keys.sorted()
        assertEquals(passing,TextureRasterPolicy.allPassingPitchesMmPerPixel)
        assertEquals(passing.first(),TextureRasterPolicy.normalMmPerPixel)
        assertEquals(passing.drop(1).take(2),TextureRasterPolicy.coarseMmPerPixel)
        assertEquals(passing.first(),TextureRasterPolicy.fineMinimumMmPerPixel)
        assertTrue(rows.any { it[8]=="BLOCK" && "STITCH_LIMIT_EXCEEDED" in it[9] })
    }
    @Test fun pixelBudgetIsStableAtDecimalAndNativeBoundaries() {
        for (mm in listOf(99.8,99.9,100.0,90.0)) {
            val expected=TextureRasterPolicy.edge(mm)
            assertEquals(expected,TextureRasterPolicy.edge(Math.nextDown(mm)))
            assertEquals(expected,TextureRasterPolicy.edge(Math.nextUp(mm)))
        }
        assertEquals(200,TextureRasterPolicy.edge(90.0,.45))
        assertEquals(222,TextureRasterPolicy.edge(99.9*222/222,.45))
    }
    @Test fun normalAndFineNeverExceedMeasuredPixelDensity() {
        for (mm in listOf(20.0,30.0,40.0,60.0,90.0,99.9)) for (pitch in TextureRasterPolicy.allPassingPitchesMmPerPixel) {
            val edge=TextureRasterPolicy.edge(mm,pitch)
            assertTrue(mm/edge>=pitch,"$mm/$edge < $pitch")
        }
        assertEquals(TextureRasterPolicy.pitchForDetail(1),TextureRasterPolicy.pitchForDetail(2))
        assertTrue(TextureRasterPolicy.pitchForDetail(0)>TextureRasterPolicy.pitchForDetail(1))
        assertFailsWith<IllegalArgumentException> { TextureRasterPolicy.edge(40.0,.4) }
        assertFailsWith<IllegalArgumentException> { TextureRasterPolicy.edge(40.0,.46) }
        assertTrue(TextureRasterPolicy.blockGuidanceKo.contains("크기를 키우거나 디테일을 낮추세요"))
    }
}
