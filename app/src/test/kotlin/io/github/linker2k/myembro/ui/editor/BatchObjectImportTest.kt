// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class BatchObjectImportTest {
    @Test fun `multiple selected files remain separate editable objects and undo together`() {
        val dir = Files.createTempDirectory("editor-batch-").toFile()
        try {
            val pattern = EmbPattern(listOf(Stitch(0, 0, Command.STITCH), Stitch(40, 20, Command.STITCH),
                Stitch(0, 20, Command.STITCH), Stitch(40, 0, Command.STITCH), Stitch(40, 20, Command.END)), listOf(EmbThread(0xFF112233.toInt())))
            val pes = PesWriter.write(pattern)
            val files = (1..3).map { File(dir, "part-$it.pes").apply { writeBytes(pes) } }
            val objects = files.map { assertIs<StitchObject>(importDesignObject(it)) }
            assertEquals(3, objects.map { it.id }.distinct().size)
            val session = EditorSession()
            session.commit(session.project.copy(objects = objects))
            assertEquals(3, session.project.objects.size)
            assertTrue(objects.all { it.sourceBytes!!.contentEquals(pes) })
            val first = objects.first()
            val changed = session.project.update(first.id) { it.withTransform(Transform(x = 100.0, rotationDeg = 45.0)) }
            assertEquals(objects[1].transform, changed.objects[1].transform)
            assertEquals(100.0, changed.objects[0].transform.x)
            val restored = ProjectJson.decode(ProjectJson.encode(changed))
            assertEquals(3, restored.objects.size)
            assertEquals(changed.objects.map { it.transform }, restored.objects.map { it.transform })
            session.undo()
            assertTrue(session.project.objects.isEmpty())
            session.redo()
            assertEquals(3, session.project.objects.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `adding a project yields one independent object without replacing the current hoop`() {
        val dir = Files.createTempDirectory("editor-project-import-").toFile()
        try {
            val source = EmbPattern(listOf(Stitch(0, 0, Command.STITCH), Stitch(10, 10, Command.STITCH)), emptyList())
            val project = Project(HoopSpec.H130x180, listOf(StitchObject("source", "source", source)))
            val file = File(dir, "saved.myembro").apply { writeText(ProjectJson.encode(project)) }
            val obj = assertIs<StitchObject>(importDesignObject(file))
            val target = Project(HoopSpec.H100).add(obj)
            assertEquals(HoopSpec.H100, target.hoop)
            assertEquals(1, target.objects.size)
            assertNotEquals("source", obj.id)
            assertEquals(project.flatten().stitchCount, obj.source.stitchCount)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `adding a one-shape project keeps its blocks, order and placement`() {
        val dir = Files.createTempDirectory("editor-shape-import-").toFile()
        try {
            val svg = "<svg viewBox='0 0 100 100'><rect x='5' y='5' width='40' height='40' fill='#224488'/>" +
                "<rect x='55' y='55' width='40' height='40' fill='#cc4422'/></svg>"
            val base = ShapeObject.fromSvg("source", "Blocks", svg, 40.0)
            val first = base.regionOrder.first()
            val edited = (io.github.linker2k.myembro.engine.edit.commands.apply(Project(HoopSpec.H100, listOf(base)),
                io.github.linker2k.myembro.engine.edit.commands.ProjectCommand.SetBlockOrder("source", setOf(first), BlockOrderMove.LAST)) as io.github.linker2k.myembro.engine.edit.commands.CommandResult.Applied).project
                .update("source") { it.withTransform(Transform(x = 30.0, y = -20.0)) }
            val saved = assertIs<ShapeObject>(edited.objects.single())
            val file = File(dir, "blocks.myembro").apply { writeText(ProjectJson.encode(edited)) }
            val obj = assertIs<ShapeObject>(importDesignObject(file))
            assertNotEquals("source", obj.id)
            assertEquals(saved.regionOrder, obj.regionOrder)
            assertEquals(saved.overrides, obj.overrides)
            assertEquals(saved.transform, obj.transform)
            assertEquals(edited.flatten().stitches, Project(HoopSpec.H100, listOf(obj)).flatten().stitches)
            // Two objects stay one flattened design, as before.
            val two = Project(HoopSpec.H100, listOf(saved, saved.withId("copy")))
            File(dir, "two.myembro").writeText(ProjectJson.encode(two))
            assertIs<StitchObject>(importDesignObject(File(dir, "two.myembro")))
        } finally { dir.deleteRecursively() }
    }
}
