// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OutlineNoteLoaderTest {
    @Test fun firstLazyRenderRunsOffTheCallerThreadAndReturnsItsNote() = runBlocking {
        val caller=Thread.currentThread()
        var renders=0
        val note by lazy {
            assertNotSame(caller,Thread.currentThread())
            renders++
            3 to true
        }
        assertEquals(0,renders)
        assertEquals(3 to true,loadOutlineNote { note })
        assertEquals(1,renders)
        assertEquals(3 to true,loadOutlineNote { note })
        assertEquals(1,renders)
    }
}
