// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.UnsafeStitchPlanException
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import java.awt.font.FontRenderContext
import java.io.File
import java.util.Locale
import kotlin.math.*
import kotlin.test.Test

/** OFL-generated geometry only; never reads the user's PES as a test fixture. */
internal object LetteringAuditGlyphs {
    fun script(text: String = "Jung Min", height: Double = 34.0): TextObject = outline(
        File("src/test/resources/fonts/dancing-script-bold/DancingScript-Bold.ttf"), text, height, StitchStyle.FILL)
    fun outline(file: File, text: String, height: Double, style: StitchStyle): TextObject {
        val font = AwtGlyphs.load(file)
        val ref = AwtGlyphs.contours(font, if (font.canDisplay('자')) "자" else "H").flatten()
            .let { p -> p.maxOf { it.second } - p.minOf { it.second } }
        var x = 0.0
        val contours = arrayListOf<List<Pair<Double, Double>>>()
        val owners = arrayListOf<Int>()
        text.codePoints().toArray().forEachIndexed { index, code ->
            val ch = String(Character.toChars(code))
            AwtGlyphs.contours(font, ch).forEach { path -> contours += path.map { (px, py) -> px + x to py }; owners += index }
            x += font.getStringBounds(ch, FontRenderContext(null, false, true)).width + 1.2 * ref / height
        }
        return TextObject("audit", text = text, fontKind = FontKind.OUTLINE, fontId = file.name,
            heightMm = height, spacing = 1.2, contours = contours, contourSourceHeight = ref,
            contourCharacters = owners, stitchStyle = style)
    }
}

/** Opt-in developer audit. Artifacts stay outside the repository, including optional private input. */
class LetteringAuditTool {
    @Test fun dumpStarts() {
        val out = System.getenv("MYEMBRO_START_AUDIT")?.let(::File) ?: return
        out.mkdirs()
        val summary = arrayListOf("name\tstitches\ttrims\tzeros\tshortLt03\tmaxCell05\tmaxConsecutiveCell05\tmaxSamePoint\tstarts")
        fun dump(name: String, input: EmbPattern) {
            val bytes = PesWriter.write(input)
            File(out, "$name.pes").writeBytes(bytes)
            val p = PesReader.read(bytes)
            val cells = hashMapOf<Pair<Int, Int>, Int>(); val points = hashMapOf<Pair<Int, Int>, Int>()
            var previous = Stitch(0, 0, Command.JUMP)
            var zero = 0; var short = 0; var consecutive = 0; var maxConsecutive = 0
            var lastCell: Pair<Int, Int>? = null
            p.stitches.forEach { s ->
                if (s.command == Command.STITCH) {
                    val length = hypot((s.x - previous.x).toDouble(), (s.y - previous.y).toDouble()) / 10
                    if (length == 0.0) zero++
                    if (length < 0.3 - 1e-9) short++
                    val cell = floor(s.x / 5.0).toInt() to floor(s.y / 5.0).toInt()
                    cells[cell] = (cells[cell] ?: 0) + 1
                    val point = s.x to s.y; points[point] = (points[point] ?: 0) + 1
                    consecutive = if (cell == lastCell) consecutive + 1 else 1
                    lastCell = cell; maxConsecutive = max(maxConsecutive, consecutive)
                }
                previous = s
            }
            val starts = (listOf(0) + p.stitches.indices.filter { p.stitches[it].command in setOf(Command.TRIM, Command.COLOR_CHANGE) }.map { it + 1 })
                .filter { start -> p.stitches.drop(start).takeWhile { it.command !in setOf(Command.TRIM, Command.COLOR_CHANGE, Command.END) }.any { it.command == Command.STITCH } }
            val rows = arrayListOf("start\tindex\tcommand\txMm\tyMm\tlengthMm\tpointHitsFirst20\tcellHitsFirst20")
            starts.forEachIndexed { restart, index ->
                val commands = p.stitches.drop(index).takeWhile { it.command !in setOf(Command.TRIM, Command.COLOR_CHANGE, Command.END) }
                var sewn = 0
                val first = commands.takeWhile { if (it.command == Command.STITCH) sewn++; sewn <= 20 }
                val needles = first.filter { it.command == Command.STITCH }
                first.forEachIndexed { j, s ->
                    val a = p.stitches.getOrNull(index + j - 1) ?: Stitch(0, 0, Command.JUMP)
                    val len = hypot((s.x - a.x).toDouble(), (s.y - a.y).toDouble()) / 10
                    val hits = needles.count { it.x == s.x && it.y == s.y }
                    val cellHits = needles.count { floor(it.x / 5.0) == floor(s.x / 5.0) && floor(it.y / 5.0) == floor(s.y / 5.0) }
                    rows += "$restart\t${index+j}\t${s.command}\t${s.x/10.0}\t${s.y/10.0}\t${String.format(Locale.ROOT, "%.3f", len)}\t$hits\t$cellHits"
                }
            }
            File(out, "$name-starts.tsv").writeText(rows.joinToString("\n") + "\n")
            File(out, "$name.json").writeText(p.stitches.joinToString(",", "[", "]") { "[${it.x/10.0},${it.y/10.0},\"${it.command}\"]" })
            val row = "$name\t${p.stitchCount}\t${p.trimCount}\t$zero\t$short\t${cells.values.maxOrNull() ?: 0}\t$maxConsecutive\t${points.values.maxOrNull() ?: 0}\t${starts.size}"
            summary += row; println(row)
        }
        fun generated(name: String, obj: TextObject) {
            try { dump(name, obj.localPattern()) }
            catch (unsafe: UnsafeStitchPlanException) {
                summary += "$name\tBLOCKED\t0\t0\t0\t${unsafe.penetrations}\t0\t0\t0"
                File(out, "$name-blocked.txt").writeText(unsafe.message.orEmpty())
            }
        }
        System.getenv("MYEMBRO_PRIVATE_PES")?.let { dump("private-input", PesReader.read(File(it).readBytes())) }
        val script = LetteringAuditGlyphs.script()
        generated("script-34", script)
        script.text.filterNot(Char::isWhitespace).toSet().forEach { generated("script-glyph-$it", LetteringAuditGlyphs.script(it.toString())) }
        if (System.getenv("MYEMBRO_AUDIT_FULL") == "1") {
            for (font in listOf("jua_regular", "gowun_batang", "noto_sans_kr")) for (height in listOf(10.0, 18.0, 30.0))
                for ((language, text) in listOf("ko" to "자수", "en" to "TEXT")) for (style in listOf(StitchStyle.RUNNING, StitchStyle.FILL, StitchStyle.SATIN)) {
                    val obj = LetteringAuditGlyphs.outline(File("../app/src/main/res/font/$font.ttf"), text, height, style)
                    generated("$font-$language-${height.toInt()}-$style", obj)
                    text.toSet().forEach { ch -> generated("$font-glyph-${ch.code}-${height.toInt()}-$style",
                        LetteringAuditGlyphs.outline(File("../app/src/main/res/font/$font.ttf"), ch.toString(), height, style)) }
                }
            for (height in listOf(10.0, 18.0, 30.0)) for (style in listOf(StitchStyle.RUNNING, StitchStyle.BEAN)) {
                val obj = TextObject("hershey", text = "Jung Min", heightMm = height, stitchStyle = style)
                generated("hershey-${height.toInt()}-$style", obj)
            }
            dump("two-objects", Project(objects = listOf(script, script.copy(id = "second", transform = Transform(y = 400.0)))).flatten())
        }
        File(out, "summary.tsv").writeText(summary.joinToString("\n") + "\n")
    }
}
