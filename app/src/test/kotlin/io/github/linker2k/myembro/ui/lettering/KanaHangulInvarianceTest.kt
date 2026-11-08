// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.lettering.AndroidGlyphOutlines
import io.github.linker2k.myembro.lettering.AndroidCenterlineGlyph
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbThread
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.security.MessageDigest
import kotlin.test.*

/**
 * Frozen on untouched origin/main f1db5974, using the bundled OFL Noto Sans KR 400 family.
 * sha256TopOnly = the f1db5974 bytes, reproduced with autoUnderlay=false; sha256 = the default
 * width-based underlay (docs/satin-underlay.md), refrozen on 795d257d.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KanaHangulInvarianceTest {
    @Test fun thirtyRepresentativePesFilesRemainByteIdentical() {
        val face=RuntimeEnvironment.getApplication().resources.getFont(R.font.myembro_sans)
        val points=AndroidGlyphOutlines.extract("자",face).flatten()
        val reference=points.maxOf { it.second }-points.minOf { it.second }
        val root=JSONObject(requireNotNull(javaClass.getResourceAsStream("/lettering/kana-hangul-baseline.json")).bufferedReader().use { it.readText() })
        val rows=root.getJSONArray("rows")
        val prepared=mutableMapOf<String,CenterlineSatinData>()
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);val glyph=row.getString("glyph")
            val data=prepared.getOrPut(glyph) {
                val traced=CenterlineGlyph.extract(AndroidCenterlineGlyph.rasterize(glyph,face))
                CenterlineSatinData(traced.paths,List(traced.paths.size){0},traced.components,
                    mapOf(0 to HangulSatin.measuredWidthSource(AndroidGlyphOutlines.extract(glyph,face),reference)))
            }
            assertTrue(HangulSatin.applies(glyph));assertFalse(KanaSatin.applies(glyph))
            fun sha(autoUnderlay:Boolean)=MessageDigest.getInstance("SHA-256").digest(PesWriter.write(HangulSatin.generate(data,row.getDouble("height"),
                reference,EmbThread(0xff000000.toInt(),"Black 40wt"),autoUnderlay=autoUnderlay))).joinToString("") { "%02x".format(it.toInt() and 255) }
            // Without the default underlay the f1db5974 bytes must stay; the default output is frozen separately.
            assertEquals(row.getString("sha256TopOnly"),sha(false),"$glyph ${row.getDouble("height")}mm top only")
            assertEquals(row.getString("sha256"),sha(true),"$glyph ${row.getDouble("height")}mm")
        }
        assertEquals(30,rows.length())
    }
}
