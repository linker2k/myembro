// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.inkstitch.lettering.*
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinColumn
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParametersCodec
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.utils.Point as SvgPoint
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.test.*

class InkFontSatinBridgeTest {
    @Test fun forceFlagWithTiesNeitherKeepsUpstreamEndOnlySemantics() {
        val locks=io.github.linker2k.myembro.engine.inkstitch.stitch_plan.LockStitches.fromParameters(
            io.github.linker2k.myembro.engine.inkstitch.stitch_plan.LockStitchParameters(ties=3,force_lock_stitches=true))
        assertNull(locks.start)
        assertNotNull(locks.end)
    }

    @Test fun sourceSvgBooleansUseInkStitchSpellings() {
        val p=SatinParametersCodec.decode(mapOf("center_walk_underlay" to " False ","zigzag_underlay" to "True", "swap_satin_rails" to "yes"))
        assertFalse(p.center_walk_underlay);assertTrue(p.zigzag_underlay);assertTrue(p.swap_satin_rails)
    }
    @Test fun glyphSatinRetainsItsParametersAndUsesOneS1Pass() {
        val info=InkStitchFontInfo.parse("""{"name":"test","size":10,"min_scale":1,"max_scale":1}""")
        val paths=listOf(listOf(Point(0.0,0.0),Point(0.0,96/2.54)),listOf(Point(8.0,0.0),Point(8.0,96/2.54)))
        val attrs=mapOf("zigzag_spacing_mm" to "0.65","center_walk_underlay" to "True","pull_compensation_mm" to "0.1 0.3")
        val element=InkSatin(paths,paths,0xFF000000.toInt(),InkStitchParams(attrs))
        val glyph=InkStitchGlyph("H",listOf(element),0.0,8.0,0.0,96/2.54)
        val font=InkStitchFont(info,mapOf("H" to glyph),0.0,emptyMap())
        val actual=InkStitchLettering.render(font,"H",10.0)
        val nativePaths=paths.map { it.map { q->SvgPoint(q.x,q.y) } }
        val groups=SatinColumn.fromSubpaths(nativePaths,SatinParametersCodec.decode(attrs),nativePaths,EmbThread(0xFF000000.toInt())).toStitchGroups(null)
        val expected=GeneratedPatternProcessor.fromGroups(groups)
        assertEquals(expected.stitches,actual.stitches)
        assertEquals(expected.metadata[GeneratedPatternProcessor.LOCK_INDICES_KEY],actual.metadata[GeneratedPatternProcessor.LOCK_INDICES_KEY])
    }
}
