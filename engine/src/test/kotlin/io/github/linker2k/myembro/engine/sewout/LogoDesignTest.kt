// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.io.PecThreads
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * First check of the shared vector-art path (docs/emoji-to-design.md B) with our own launcher
 * logo: VectorDrawable → SVG → width-classified Ink/Stitch stitches → Brother threads → PES.
 * LOGO_OUT=<dir> also writes LOGO60.PES, logo.svg and a logo-vs-stitches preview PNG.
 */
class LogoDesignTest {
    private val xml = File("../app/src/main/res/drawable/ic_launcher_foreground.xml").readText()
    private val hoop = HoopSpec.H100

    @Test fun logoAt60mmPassesInvariantsAndGate() {
        val svg = VectorArt.vectorDrawableToSvg(xml)
        val art = VectorArt.parseSvg(svg)
        val result = VectorArt.render(art, 60.0, name = "myembro")
        val pes = PesWriter.write(result.pattern)
        val report = SewoutCheck.checkPes(pes, options = SewoutOptions(hoop = hoop, origin = DesignOrigin.APP_GENERATED))
        val blockers = report.findings.filter { it.severity == Severity.BLOCKER }.map { "${it.code}: ${it.message}" }
        result.parts.forEach { println("LOGO part ${it.shape} ${it.role} ${it.method} ${"%.2f".format(it.widthMm)}mm " +
            "#${"%06X".format(it.thread.color and 0xFFFFFF)} ${it.thread.catalogNumber} ${it.thread.description}") }
        val moves = boundedPecStitches(pes).filter { it.command == Command.STITCH || it.command == Command.JUMP }
        println("LOGO size ${(moves.maxOf { it.x } - moves.minOf { it.x }) / 10.0}x${(moves.maxOf { it.y } - moves.minOf { it.y }) / 10.0}mm " +
            "stitches ${report.statistics.stitchCount} colors ${result.pattern.threads.size} blockers ${blockers.size} " +
            "warnings ${report.findings.filter { it.severity == Severity.WARNING }.map { it.code }.toSet()}")
        assertEquals(emptyList(), PesInvariants.violations(pes, hoop))
        assertEquals(emptyList(), blockers)
        assertTrue(report.analysisComplete)
        // The width rule on our own logo: clamp bar 3.2mm and hoop ring 5.3mm → satin, arches 6.8mm > 6 → tatami.
        // The clamp body is covered by the bar and the ring except slivers under 0.9mm, so it is not sewn.
        assertEquals(listOf("1 stroke SATIN", "2 stroke SATIN", "3 stroke TATAMI"), result.parts.map { "${it.shape} ${it.role} ${it.method}" })
        System.getenv("LOGO_OUT")?.let { File(it) }?.let { out ->
            out.mkdirs()
            File(out, "LOGO60.PES").writeBytes(pes)
            File(out, "logo.svg").writeText(svg)
            ImageIO.write(DesignPreview.sideBySide(art, pes), "png", File(out, "LOGO60.png"))
            File(out, "THREADS.md").writeText(threads(result))
            File(out, "README.md").writeText(readme(result, report.statistics.stitchCount))
        }
        // Save regeneration artifacts even when the bundled asset needs updating.
        // The app's bundled default design is exactly this output (regenerate with LOGO_OUT when it changes).
        assertContentEquals(File("../app/src/main/assets/designs/myembro_logo.pes").readBytes(), pes)
    }

    private fun hex(rgb: Int) = "#%06X".format(rgb and 0xFFFFFF)

    /** Purchase list: one Brother thread per distinct logo color, nearest by PecThreads red-mean distance. */
    private fun threads(result: VectorArt.Result): String {
        val byThread = result.parts.groupBy { it.thread }
        val rows = byThread.entries.mapIndexed { i, (thread, parts) ->
            val source = parts.first().sourceRgb
            val alternatives = VectorArt.threadCandidates(source, 3).drop(1)
                .joinToString(", ") { (t, d) -> "${t.catalogNumber} ${t.description} ${hex(t.color)} (거리 $d)" }
            val use = parts.joinToString(", ") { p -> "${listOf("집게 몸통", "집게 막대", "후프 링", "M 물결")[p.shape]}(${p.method.name.lowercase()} ${"%.1f".format(p.widthMm)}mm)" }
            "| ${i + 1} | **${thread.catalogNumber}** | **${thread.description}** | ${hex(thread.color)} | ${hex(source)} | " +
                "${PecThreads.distance(thread.color, source or (0xFF shl 24))} | $use | $alternatives |"
        }
        return """# myembro 로고 — 자수실 구입 목록

${byThread.size}색 (로고 핵심 색만: 후프 갈색, M 빨강). 근거: 로고 색 → Brother/PEC 64색표(`PecThreads`)에서
pyembroidery red-mean 거리(`PecThreads.distance`, 0 = 같은 색)가 가장 작은 실. 실물 색은 화면과 다를 수 있으니
매장에서 차선 후보와 비교해 고르세요.

번호는 Brother 기계가 화면에 보여주는 **64색 팔레트(PEC) 번호·이름**입니다. Brother 자수실 제품 번호(실패 라벨 번호)와의
대응표는 저장소에 없어 **미확인**이니, 구입 시 이름과 색으로 확인하세요. 거리가 수천대로 크면 64색표에 비슷한 색이
없다는 뜻입니다(1번 갈색은 노란 기가 도는 Light Brown으로 바뀝니다).

| 순서 | 팔레트 번호 | 이름 | 실 색 | 로고 색 | 거리 | 용도 | 차선 후보 (번호 이름 색 거리) |
|---|---|---|---|---|---|---|---|
${rows.joinToString("\n")}
"""
    }

    private fun readme(result: VectorArt.Result, stitches: Int) = """# LOGO60 — myembro 로고 기본 도안

- `LOGO60.PES`: 약 60mm 폭, 바늘 $stitches 땀, ${result.pattern.threads.size}색. 원점 왼쪽 위(P1), 0길이 0개, 게이트(SewoutCheck) 차단 0건.
- `LOGO60.png`: 왼쪽 원본 로고, 오른쪽 PES 땀(같은 배율).
- `THREADS.md`: 구입할 Brother 자수실 번호·이름·용도.
- `logo.svg`: 앱 아이콘 벡터(ic_launcher_foreground.xml)를 변환한 원본.
- `LOGO60-gate.txt`: 외부 품질 게이트(`/tmp/myembro-quality-gate/quality-gate check`) 출력.

## 실이 한 가지뿐일 때

색이 바뀌는 지점에서 기계가 멈추고 다음 실을 요청합니다. 실을 바꾸지 않고 **같은 실 그대로 시작 버튼을 눌러 계속**해도
됩니다. 색만 한 가지로 나올 뿐 모양·크기·땀 밀도는 그대로라, 모양 확인용 시험 봉제로 충분합니다.

## 땀 구성 (폭 기준 자동 결정, docs/emoji-to-design.md)

${result.parts.joinToString("\n") { "- ${listOf("집게 몸통", "집게 막대", "후프 링", "M 물결")[it.shape]}: ${it.method.name.lowercase()} ${"%.1f".format(it.widthMm)}mm → ${it.thread.catalogNumber} ${it.thread.description}" }}
- 집게 몸통은 막대·링에 덮이고 0.9mm 미만 조각만 남아 봉제하지 않습니다.
"""
}
