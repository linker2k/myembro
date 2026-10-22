// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

internal fun outlineText(id: String = "outline", thread: EmbThread = blue): TextObject = TextObject(
    id = id, name = "윤곽 글자", text = "자수", fontId = "offline-font", fontKind = FontKind.OUTLINE,
    contours = listOf(
        listOf(-50.25 to -100.5, 50.5 to -100.5, 50.5 to 0.25, -50.25 to -100.5),
        listOf(-10.5 to -80.25, 10.25 to -30.5), emptyList(),
    ),
    heightMm = 12.5, spacing = 2.25, arcDeg = -35.0, thread = thread,
    contourBaseline = 4.75, contourSourceHeight = 110.25,
)

class TextObjectJsonTest {
    @Test fun hersheyFieldsAndGeneratedStitchesRoundTripWithoutRegistration() {
        val obj = TextObject(
            id = "text", name = "한글 \"이름\"", text = "A\n자수 😀\tB", heightMm = 13.25,
            spacing = 2.5, arcDeg = 37.5, thread = blue.copy(color = 0x33123456),
            transform = Transform(100.25, -50.5, 27.0, -1.2, 0.85), visible = false,
        )
        val project = Project(HoopSpec.H130x180, listOf(obj))
        val json = project.toJson()
        assertTrue(json.contains("\"text\""))
        val back = ProjectJson.decode(json)
        assertEquals(project, back)
        val restored = assertIs<TextObject>(back.objects.single())
        assertEquals(obj.localPattern().stitches, restored.localPattern().stitches)
        assertEquals(obj.localPattern().warnings, restored.localPattern().warnings)
        assertEquals(obj.placedPattern().stitches, restored.placedPattern().stitches)
        assertEquals(json, back.toJson())
    }

    @Test fun outlineGeometryAndSourceMetricsSurviveWithoutTheOriginalFont() {
        for (sourceHeight in listOf(null, 110.25)) {
            val obj = outlineText().copy(contourSourceHeight = sourceHeight, transform = Transform(-100.5, 45.0, -20.0, 1.1, -1.0))
            val project = Project(objects = listOf(obj))
            val back = ProjectJson.decode(project.toJson())
            val restored = assertIs<TextObject>(back.objects.single())
            assertEquals(obj, restored)
            assertEquals(obj.localPattern().stitches, restored.localPattern().stitches)
            assertEquals(project.flatten().stitches, back.flatten().stitches)
            assertEquals(project.flatten().threads, back.flatten().threads)
        }
    }

    @Test fun mixedProjectsPreserveTypesOrderVisibilityAndFlattenOutput() {
        val text = TextObject("text", text = "AB", thread = blue, transform = Transform(200.0, 50.0))
        val outline = outlineText().copy(visible = false)
        val p = Project(objects = listOf(rectangle(), text, outline, TextObject("empty")))
        val back = ProjectJson.decode(p.toJson())
        assertEquals(p.objects.map { it.id }, back.objects.map { it.id })
        assertIs<StitchObject>(back.objects[0])
        assertEquals(text, back.objects[1])
        assertEquals(outline, back.objects[2])
        assertEquals(p.objects[3], back.objects[3])
        assertEquals(p.flatten().stitches, back.flatten().stitches)
        assertEquals(p.flatten().threads, back.flatten().threads)
        assertEquals(p.statistics(), back.statistics())
    }

    @Test fun builtInCodecAlsoWorksExplicitlyAndAfterScopedRegistration() {
        val project = Project(objects = listOf(outlineText()))
        val json = project.toJson()
        assertEquals(json, project.toJson(listOf(TextObjectCodec)))
        ProjectJson.registerCodec(TextObjectCodec)
        try {
            assertEquals(json, project.toJson())
            assertEquals(project, ProjectJson.decode(json))
        } finally {
            ProjectJson.unregisterCodec(TextObjectCodec)
        }
        assertEquals(project, ProjectJson.decode(json))
    }

    @Test fun invalidFontKindsAndMalformedContourPairsFailExplicitly() {
        val data = TextObjectCodec.encode(outlineText())
        assertFailsWith<IllegalArgumentException> {
            TextObjectCodec.decode(JsonObject(data + ("fontKind" to JsonPrimitive("UNKNOWN"))))
        }
        assertFailsWith<IllegalArgumentException> {
            TextObjectCodec.decode(JsonObject(data + ("contours" to buildJsonArray {
                add(buildJsonArray { add(buildJsonArray { add(1.0); add(2.0); add(3.0) }) })
            })))
        }
    }
}
