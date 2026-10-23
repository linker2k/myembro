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
                Stitch(40, 20, Command.END)), listOf(EmbThread(0xFF112233.toInt())))
            val pes = PesWriter.write(pattern)
            val files = (1..3).map { File(dir, "part-$it.pes").apply { writeBytes(pes) } }
            val objects = files.map(::importDesignObject)
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
            val obj = importDesignObject(file)
            val target = Project(HoopSpec.H100).add(obj)
            assertEquals(HoopSpec.H100, target.hoop)
            assertEquals(1, target.objects.size)
            assertNotEquals("source", obj.id)
            assertEquals(project.flatten().stitchCount, obj.source.stitchCount)
        } finally { dir.deleteRecursively() }
    }
}
