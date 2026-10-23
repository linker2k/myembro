// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.library.DesignSourceResolver
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.*

class EditorSourceRecoveryTest {
    private val gold = EmbThread(0xFFFFD911.toInt(), "Gold", "206", "Brother")
    private fun bytes() = PesWriter.writeV6(EmbPattern(listOf(Stitch(0, 0, Command.STITCH),
        Stitch(100, 100, Command.STITCH), Stitch(100, 100, Command.END)), listOf(gold)))
    private fun oldProject(embedded: Boolean = false): Project {
        val obj = StitchObject.fromBytes("old", "test", bytes(), "synthetic.pes")
        return Project(objects = listOf(obj.copy(sourceBytes = bytes().takeIf { embedded }, source = EmbPattern(
            obj.source.stitches, listOf(EmbThread(0xFF650073.toInt(), "���085���", "085", "EMBROIDERY���")), obj.source.metadata))))
    }
    private fun stitch(project: Project) = project.objects.single() as StitchObject
    private fun <T> withDirectory(block: (File) -> T): T {
        val dir = Files.createTempDirectory("editor-source-recovery").toFile()
        try { return block(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun projectFileAndAutomaticRecoveryBothResolveMissingBytesFromDesignLibrary() = withDirectory { root ->
        val designs = root.resolve("designs").apply { mkdirs() }
        designs.resolve("synthetic.pes").writeBytes(bytes())
        val resolver = DesignSourceResolver(designs)
        val projectFile = root.resolve("old.myembro").apply { writeText(oldProject().toJson()) }
        val restored = readEditorProject(projectFile.readText(), resolver)
        assertEquals(listOf(gold), stitch(restored).source.threads)
        assertContentEquals(bytes(), stitch(restored).sourceBytes)
        // SAF provides the same project text to this reader; it never resolves relative to a URI.
        assertEquals(listOf(gold), stitch(readEditorProject(projectFile.readText(), resolver)).source.threads)
        val drafts = EditorDraftStore(root.resolve("drafts"), resolver)
        drafts.write(oldProject())
        val draftText = root.resolve("drafts/editor.myembro").readText()
        val recovered = drafts.read()!!
        assertEquals(listOf(gold), stitch(recovered).source.threads)
        assertEquals(stitch(oldProject()).source.stitches, stitch(recovered).source.stitches)
        assertEquals(draftText, root.resolve("drafts/editor.myembro").readText()) // read does not rewrite user's snapshot
        assertTrue(projectFile.readText().contains("���085���"))
    }

    @Test fun embeddedOriginalIsRereadForBothOpeningAndRecoveryWithoutLibrary() = withDirectory { root ->
        var lookups = 0
        val resolver = ProjectSourceResolver { lookups++; bytes() }
        assertEquals(listOf(gold), stitch(readEditorProject(oldProject(true).toJson(), resolver)).source.threads)
        val drafts = EditorDraftStore(root, resolver)
        drafts.write(oldProject(true))
        assertEquals(listOf(gold), stitch(drafts.read()!!).source.threads)
        assertEquals(0, lookups)
    }

    @Test fun userRecoloringSurvivesProjectAndDraftReloadEvenWhenLibraryOriginalExists() = withDirectory { root ->
        val designs = root.resolve("designs").apply { mkdirs() }
        designs.resolve("synthetic.pes").writeBytes(bytes())
        val resolver = DesignSourceResolver(designs)
        val chosen = EmbThread(0xFF654321.toInt(), "User color")
        val obj = StitchObject.fromBytes("test", "test", bytes(), "synthetic.pes").recolored(0, chosen) as StitchObject
        val project = Project(objects = listOf(obj))
        assertTrue(obj.sourceThreadsEdited)
        assertEquals(listOf(chosen), stitch(readEditorProject(project.toJson(), resolver)).source.threads)
        val drafts = EditorDraftStore(root.resolve("drafts"), resolver)
        drafts.write(project)
        assertEquals(listOf(chosen), stitch(drafts.read()!!).source.threads)
    }

    @Test fun wrongOrMissingLibraryOriginalLeavesSnapshotAvailableWithReadableLabelFallback() = withDirectory { root ->
        val designs = root.resolve("designs").apply { mkdirs() }
        val resolver = DesignSourceResolver(designs)
        val old = oldProject()
        val missing = stitch(readEditorProject(old.toJson(), resolver))
        assertEquals(stitch(old).source.threads, missing.source.threads)
        assertEquals("085", missing.source.threads.single().displayName)
        val other = EmbPattern(listOf(Stitch(0, 0, Command.STITCH), Stitch(101, 100, Command.STITCH),
            Stitch(101, 100, Command.END)), listOf(gold))
        designs.resolve("synthetic.pes").writeBytes(PesWriter.writeV6(other))
        assertEquals(stitch(old).source.threads, stitch(readEditorProject(old.toJson(), resolver)).source.threads)
        assertNull(stitch(readEditorProject(old.toJson(), resolver)).sourceBytes)
    }

    @Test fun resolverUsesOnlyLibraryBasenamesAndRejectsSymlinksOutsideAndOversizeFiles() = withDirectory { root ->
        val designs = root.resolve("designs").apply { mkdirs() }
        val source = designs.resolve("Synthetic.PES").apply { writeBytes(bytes()) }
        val resolver = DesignSourceResolver(designs)
        assertContentEquals(bytes(), resolver.resolve("/old/device/files/designs/Synthetic.PES"))
        assertContentEquals(bytes(), resolver.resolve("C:\\old\\synthetic.pes"))
        assertNull(resolver.resolve(".."))
        assertNull(resolver.resolve("missing.pes"))
        val outside = root.resolve("private.pes").apply { writeBytes(bytes()) }
        assertNull(resolver.resolve("../private.pes"))
        Files.createSymbolicLink(designs.resolve("link.pes").toPath(), outside.toPath())
        assertNull(resolver.resolve("link.pes"))
        source.delete()
        RandomAccessFile(designs.resolve("huge.pes"), "rw").use { it.setLength(32L*1024*1024+1) }
        assertNull(resolver.resolve("huge.pes"))
    }
}
