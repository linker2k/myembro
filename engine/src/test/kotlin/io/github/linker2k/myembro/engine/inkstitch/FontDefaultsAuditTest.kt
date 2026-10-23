// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch
import io.github.linker2k.myembro.engine.inkstitch.lettering.*
import io.github.linker2k.myembro.engine.io.PesWriter
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.Test

/** Representative decomposition only; no 96-case regeneration. */
class FontDefaultsAuditTest {
    @Test fun generate() {
        val out=System.getProperty("myembro.defaultsAudit")?.let(::File) ?: return
        out.mkdirs()
        val src=File("/tmp/myembro-quality-gate/inkstitch/inputs/upstream-fonts/testfont")
        val font=InkStitchFont.parse(src.resolve("ltr.svg").inputStream(),src.resolve("font.json").readText())
        val cases=listOf(4.0 to "AB",10.0 to "AB",20.0 to "AB",10.0 to "AB\nBA").map { (height,text)->
            val id="fonts--testfont--${height.toInt().toString().padStart(3,'0')}mm--${if('\n' in text) "AB-newline-BA" else text}"
            // Codex corrected the official two-line fixture with reversible=false (no rtl variant exists).
            val effectiveFont=if ('\n' in text) InkStitchFont.parse(src.resolve("ltr.svg").inputStream(),
                File("/tmp/myembro-inkstitch-oracle/inputs/newline-font-data/testfont/font.json").readText()) else font
            val pattern=InkStitchLettering.render(effectiveFont,text,height,forceBoundaryLocks=false)
            val file=out.resolve("$id.pes").apply { writeBytes(PesWriter.write(pattern)) }
            buildJsonObject { put("id",id);put("path",file.absolutePath);put("text",text);put("height_mm",height) }
        }
        out.resolve("manifest.json").writeText(buildJsonObject { put("cases",JsonArray(cases)) }.toString())
    }
}
