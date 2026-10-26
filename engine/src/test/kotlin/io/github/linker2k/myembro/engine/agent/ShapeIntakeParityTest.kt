// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.io.PesWriter
import java.io.File
import kotlin.test.*

/** Public/synthetic inputs only. Compare full composed coordinates, not only stitch counts. */
class ShapeIntakeParityTest {
    private fun parity(prepared: PreparedEmoji, expectedName: String, oldPattern: io.github.linker2k.myembro.engine.model.EmbPattern) {
        val actual = assertIs<ShapeObject>(prepared.objectFor("picture", expectedName))
        val legacy = StitchObject("picture", expectedName, oldPattern)
        assertEquals(oldPattern.stitches, prepared.pattern.stitches)
        assertEquals(oldPattern.threads, prepared.pattern.threads)
        assertEquals(legacy.placedPattern().stitches, actual.placedPattern().stitches)
        val before = Project(objects = listOf(legacy)).flatten()
        val after = Project(objects = listOf(actual)).flatten()
        assertEquals(before.stitches, after.stitches)
        assertEquals(before.threads, after.threads)
        assertContentEquals(PesWriter.writeV1(before), PesWriter.writeV1(after))
        val decoded = ProjectJson.decode(ProjectJson.encode(Project(objects = listOf(actual))))
        val restored = assertIs<ShapeObject>(decoded.objects.single())
        assertEquals(actual.sourceArt, restored.sourceArt)
        assertEquals(after.stitches, decoded.flatten().stitches)
        assertContentEquals(PesWriter.writeV1(after), PesWriter.writeV1(decoded.flatten()))
        println("INTAKE_PARITY $expectedName shape=true stitches=${after.stitchCount} coordinates=IDENTICAL pes=IDENTICAL codec=IDENTICAL")
    }
    @Test fun emojiPreparationAndEditorPlacementPreserveStitches() {
        val resolver = BundledEmojiResolver()
        val emoji = resolver.catalog.emoji.single { it.id == "red-heart" }
        val svg = resolver.localSvg(emoji)!!.toString(Charsets.UTF_8)
        val before = VectorArt.render(VectorArt.parseSvg(svg), 30.0, name = emoji.name).pattern
        parity(EmojiPreparation(resolver).prepare(emoji.id, HoopSpec.H100, 30.0), emoji.name, before)
    }
    @Test fun svgPreparationAndAiImportPreserveStitchesHolesAndPlacement() {
        val svg = File("src/test/resources/inkstitch/fill/hole.svg").readText()
        val before = VectorArt.render(VectorArt.parseSvg(svg), 30.0, name = "SVG").pattern
        parity(SvgPicturePreparation.prepare(svg.toByteArray(), HoopSpec.H100, 30.0, "SVG"), "SVG", before)
    }
}
