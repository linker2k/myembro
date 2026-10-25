// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.agent.*
import java.nio.file.Files
import kotlin.test.*

class AndroidDocumentStoreTest {
    @Test fun libraryAdapterUsesExistingNamingAndNeverReplacesWithoutExpectedVersion() {
        val folder=Files.createTempDirectory("library-adapter-").toFile()
        val recorded=mutableListOf<ByteArray>();val store=AndroidDocumentStore(folder) { recorded+=it.copyOf() }
        val first=store.write("library","Example",DocumentKind.PROJECT,"first".toByteArray(),null)
        assertEquals("Example.myembro",first.name)
        assertEquals(first.version,store.inspect("library","example",DocumentKind.PROJECT)!!.version)
        assertFailsWith<DocumentConflict> { store.write("library","example",DocumentKind.PROJECT,"blocked".toByteArray(),null) }
        assertEquals("first",folder.resolve(first.name).readText())
        store.write("library","EXAMPLE",DocumentKind.PROJECT,"second".toByteArray(),first.version)
        assertEquals("second",folder.resolve(first.name).readText())
        val pes=byteArrayOf(1,2,3);store.write("library","export",DocumentKind.PES,pes,null)
        assertTrue(recorded.isEmpty(),"Imported origin must not be automatically tagged generated")
        store.recordGeneratedPes(pes);assertContentEquals(pes,recorded.single())
        assertFailsWith<IllegalArgumentException> { store.inspect("unknown","test",DocumentKind.PES) }
        assertFailsWith<IllegalArgumentException> { store.inspect("library","../test",DocumentKind.PES) }
    }
}
