// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine

import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.engine.io.PesExport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PesExportTest {
    private fun resource(path: String): ByteArray =
        requireNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readBytes()

    @Test
    fun pesIsCopiedUnchanged() {
        val original = resource("flower.v6.pes")
        val viewport=io.github.linker2k.myembro.engine.sewout.PecViewportCheck.check(original,io.github.linker2k.myembro.engine.edit.HoopSpec.H100)
        if(!viewport.valid) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { PesExport.prepare(original,"flower.v6.pes") }
            return
        }
        val result = PesExport.prepare(original, "flower.v6.pes")
        assertFalse(result.converted)
        assertContentEquals(original, result.bytes)
        assertEquals("flower.v6.pes", result.fileName)
    }

    @Test
    fun dstIsConvertedToReadablePes() {
        val dst = resource("flower.dst")
        // Raw DST fixture includes standalone stationary trims. P4 rejects this conversion.
        kotlin.test.assertFailsWith<IllegalArgumentException> { PesExport.prepare(dst,"flower.dst") }

    }

    @Test
    fun uniqueNameAvoidsExistingFiles() {
        val existing = setOf("rose.pes", "rose (1).pes")
        assertEquals("rose (2).pes", PesExport.uniqueName("rose.pes") { it in existing })
        assertEquals("tulip.pes", PesExport.uniqueName("tulip.pes") { it in existing })
    }
}
