// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ImportDecisionsTest {
    private val items = (0..2).map { DuplicateDesign(it, "$it.pes", File("$it.pes")) }
    @Test fun `apply to remaining keeps earlier per-file decisions`() = runBlocking {
        val gate = ImportDecisions()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { gate.choose(items) }
        gate.answer(DuplicateAction.SKIP, false)
        assertEquals(1, gate.prompt.value!!.index)
        assertFalse(answer.isCompleted)
        gate.answer(DuplicateAction.COPY, true)
        assertEquals(mapOf(0 to DuplicateAction.SKIP, 1 to DuplicateAction.COPY, 2 to DuplicateAction.COPY), answer.await())
        assertNull(gate.prompt.value)
    }
    @Test fun `cancel discards earlier choices rather than partially importing`() = runBlocking {
        val gate = ImportDecisions()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { gate.choose(items) }
        gate.answer(DuplicateAction.REPLACE, false)
        gate.cancel()
        assertNull(answer.await())
        assertNull(gate.prompt.value)
    }
}
