// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.hypot
import kotlin.test.*

class BeanStitchTest {
    private val line = listOf(listOf(0.0 to 0.0, 5.0 to 0.0))
    private fun straight(style: StitchStyle) = LetteringEngine.fromContours(
        line, heightMm = 5.0, sourceHeight = 5.0, simplify = false, stitchStyle = style,
    )

    @Test fun everySewnMoveGoesForwardBackForwardWithoutTriplingItsAnchor() {
        val running = straight(StitchStyle.RUNNING)
        val bean = straight(StitchStyle.BEAN)
        val runningBody = generatedBody(running)
        val beanBody = generatedBody(bean)
        val sewn = beanBody.stitches.filter { it.command == Command.STITCH }
        assertEquals(listOf(-25, 0, 25), runningBody.stitches.filter { it.command == Command.STITCH }.map { it.x })
        assertEquals(listOf(-25, 0, -25, 0, 25, 0, 25), sewn.map { it.x })
        assertTrue(sewn.all { it.y == 0 })
        assertEquals(1 + 3 * (runningBody.stitchCount - 1), beanBody.stitchCount)
        assertEquals(running.bounds, bean.bounds)
        assertEquals(running.stitches.last(), bean.stitches.last())
    }

    @Test fun arcsColorsAndSeparateContoursKeepCommandsAndStitchLength() {
        val red = EmbThread(0xFFB5492A.toInt())
        val blue = EmbThread(0xFF0000FF.toInt())
        fun pattern(style: StitchStyle) = LetteringEngine.fromContours(
            line + listOf(listOf(8.0 to -5.0, 8.0 to 0.0)), heightMm = 5.0, sourceHeight = 5.0,
            arcDeg = -1.0, arcRadiusMm = 30.0, contourThreads = listOf(red, blue), stitchStyle = style,
        )
        val running = pattern(StitchStyle.RUNNING)
        val bean = pattern(StitchStyle.BEAN)
        assertEquals(running.stitches.filter { it.command != Command.STITCH }, bean.stitches.filter { it.command != Command.STITCH })
        assertEquals(running.threads, bean.threads)
        assertEquals(running.bounds, bean.bounds)
        val runningBody = generatedBody(running)
        val beanBody = generatedBody(bean)
        assertEquals(2 + 3 * (runningBody.stitchCount - 2), beanBody.stitchCount)
        bean.stitches.zipWithNext().forEach { (a, b) ->
            if (b.command == Command.STITCH) assertTrue(hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) <= 25.0)
        }
        assertEquals(2, bean.jumpCount)
        assertEquals(1, bean.colorChangeCount)
        assertTrue(bean.trimCount > 0)
    }

    /** Locks are added once after bean expansion and must never themselves be tripled. */
    private fun generatedBody(pattern: EmbPattern): EmbPattern {
        assertFalse(pattern.metadata[GeneratedPatternProcessor.LOCK_INDICES_KEY].isNullOrBlank(), "Generated pattern must contain traced locks")
        return GeneratedPatternProcessor.stripGeneratedLocks(pattern)
    }

    @Test fun newSingleLineFontsDefaultToBeanWhileOutlinesKeepRunning() {
        val hershey = TextObject("hershey", text = "Ab")
        assertTrue(hershey.singleLine)
        assertEquals(StitchStyle.BEAN, hershey.stitchStyle)
        assertEquals(LetteringEngine.generate("Ab").stitches, hershey.localPattern().stitches)
        assertTrue(hershey.localPattern().stitchCount > hershey.copy(stitchStyle = StitchStyle.RUNNING).localPattern().stitchCount)
        val outline = TextObject("outline", fontKind = FontKind.OUTLINE, contours = line)
        assertEquals(StitchStyle.RUNNING, outline.stitchStyle)
        val svg = TextObject("svg", fontKind = FontKind.OUTLINE, contours = line, singleLine = true)
        assertEquals(StitchStyle.BEAN, svg.stitchStyle)
        assertEquals(straight(StitchStyle.RUNNING).stitches,
            LetteringEngine.fromContours(line, 5.0, sourceHeight = 5.0).stitches)
    }

    @Test fun bothStylesAndSingleLineSourceSurviveProjectSerialization() {
        for (kind in FontKind.entries) for (style in StitchStyle.entries.filter { it != StitchStyle.FILL }) {
            val obj = TextObject("font", text = "AB", fontKind = kind, contours = line, singleLine = true, stitchStyle = style)
            val project = Project(objects = listOf(obj))
            val restored = ProjectJson.decode(project.toJson())
            assertEquals(project, restored)
            assertEquals(project.flatten().stitches, restored.flatten().stitches)
        }
    }

    @Test fun oldFilesWithoutStyleKeepTheirOriginalRunningStitches() {
        for (kind in FontKind.entries) {
            val original = TextObject("old", text = "AB", fontKind = kind, contours = line, stitchStyle = StitchStyle.RUNNING)
            val payload = JsonObject(TextObjectCodec.encode(original).filterKeys { it != "stitchStyle" && it != "singleLine" })
            val restored = TextObjectCodec.decode(payload) as TextObject
            assertEquals(original, restored)
            assertEquals(original.localPattern().stitches, restored.localPattern().stitches)
        }
        assertFailsWith<IllegalArgumentException> {
            TextObjectCodec.decode(JsonObject(TextObjectCodec.encode(TextObject("bad")) + ("stitchStyle" to JsonPrimitive("UNKNOWN"))))
        }
    }

    @Test fun pesExportPreservesEveryForwardAndBackwardNeedlePosition() {
        val pattern = straight(StitchStyle.BEAN)
        val restored = PesReader.read(PesWriter.write(pattern))
        assertEquals(io.github.linker2k.myembro.engine.io.PesWriter.prepareForOutput(pattern).stitches.filter { it.command == Command.STITCH }, restored.stitches.filter { it.command == Command.STITCH })
        assertEquals(io.github.linker2k.myembro.engine.io.PesWriter.prepareForOutput(pattern).bounds, restored.bounds)
    }
}
