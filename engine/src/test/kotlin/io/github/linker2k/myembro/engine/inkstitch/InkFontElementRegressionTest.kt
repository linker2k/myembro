// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch

import io.github.linker2k.myembro.engine.inkstitch.lettering.*
import io.github.linker2k.myembro.engine.io.*
import java.io.File
import kotlin.test.*

class InkFontElementRegressionTest {
    @Test fun malformedSvgNumbersUseSourceDefaultsWhileProjectCodecStaysStrict() {
        val codec=io.github.linker2k.myembro.engine.inkstitch.elements.SatinParametersCodec
        assertEquals(codec.decode(emptyMap()),codec.decodeSvg(mapOf("zigzag_underlay_spacing_mm" to ".&", "pull_compensation_mm" to "oops")))
        assertFailsWith<IllegalArgumentException> { codec.decode(mapOf("pull_compensation_mm" to "1 2 3")) }
    }

    private fun font(attributes: String, style: String = "fill:none;stroke:#000000", d: String = "M 0,0 L 0,40") =
        InkStitchFont.parse("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" xmlns:inkstitch="http://inkstitch.org/namespace" width="40mm" height="40mm" viewBox="0 0 40 40"><g inkscape:label="GlyphLayer-A"><path $attributes style="$style" d="$d"/></g></svg>""".byteInputStream(),"""{"name":"regression","reversible":false}""")
    @Test fun emptyParametersUseUpstreamDefaultsRatherThanThrowing() {
        val a=InkStitchLettering.render(font("inkstitch:running_stitch_length_mm=\" \" inkstitch:ties=\"\""),"A",10.0)
        val b=InkStitchLettering.render(font(""),"A",10.0)
        assertEquals(b.stitches,a.stitches)
    }
    @Test fun beanRunsUseWorkerStrokePort() {
        val plain=InkStitchLettering.render(font(""),"A",10.0,forceBoundaryLocks=false)
        val bean=InkStitchLettering.render(font("inkstitch:bean_stitch_repeats=\"1\""),"A",10.0,forceBoundaryLocks=false)
        assertTrue(bean.stitchCount>plain.stitchCount)
        assertEquals(bean.stitchCount,PesReader.read(PesWriter.write(bean)).stitchCount)
    }
    @Test fun fillWithHoleUsesModelTatamiPort() {
        val f=font("inkstitch:fill_underlay=\"false\"", "fill:#000000;stroke:none;fill-rule:evenodd",
            "M0,0 L40,0 L40,40 L0,40 Z M12,12 L28,12 L28,28 L12,28 Z")
        val pattern=InkStitchLettering.render(f,"A",10.0,forceBoundaryLocks=false)
        assertTrue(pattern.stitchCount>30)
        assertFalse(pattern.stitches.filter { it.command==io.github.linker2k.myembro.engine.model.Command.STITCH }
            .any { it.x in 35..65 && it.y in 35..65 }, "The even-odd hole must stay empty")
        assertEquals(pattern.stitchCount,PesReader.read(PesWriter.write(pattern)).stitchCount)
    }
    /** Local released font data are read only and are never bundled as fixtures. */
    private fun actual(name:String) {
        val root=System.getenv("INKSTITCH_REGRESSION_ROOT")?.let(::File) ?: return
        val dir=File(root,name)
        val f=File(dir,"ltr.svg").inputStream().use { InkStitchFont.parse(it,File(dir,"font.json").readText()) }
        val text="Hamburg"
        val height=requireNotNull(f.minHeightMm)
        val pattern=InkStitchLettering.render(f,text,height)
        assertTrue(pattern.stitchCount>0,"$name rendered no stitches")
        assertEquals(pattern.stitchCount,PesReader.read(PesWriter.write(pattern)).stitchCount)
        println("FONT_REGRESSION PASS $name text=$text height=$height stitches=${pattern.stitchCount}")
    }
    @Test fun chickenScratch()=actual("chicken_scratch")
    @Test fun digoryDoodlesBean()=actual("digory_doodles_bean")
    @Test fun emilio20Applique()=actual("emilio_20_applique")
    @Test fun emilio20Bold()=actual("emilio_20_bold")
    @Test fun mangaImpact()=actual("manga_impact")
    @Test fun precious()=actual("precious")
    @Test fun roaringTwentiesKorSmall()=actual("roaring_twenties_KOR_small")
}
