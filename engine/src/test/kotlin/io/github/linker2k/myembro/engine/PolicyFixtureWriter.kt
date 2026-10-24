// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine

import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.sewout.PecViewportCheck
import kotlin.test.assertFailsWith

/** Wire-format fixtures include intentionally out-of-policy files. Assert rejection FIRST,
 * then construct bytes below the public export boundary to continue decoder/format tests.
 * Production code has no access to this test helper; valid cases use the real writer. */
object PolicyFixtureWriter {
    fun prepareForOutput(p:EmbPattern)=PesWriter.prepareForOutput(p)
    fun write(p:EmbPattern)=write(p,PesWriteSettings())
    fun write(p:EmbPattern,v:PesVersion)=write(p,PesWriteSettings(v))
    fun writeV1(p:EmbPattern)=write(p,PesWriteSettings(PesVersion.V1))
    fun writeV6(p:EmbPattern)=write(p,PesWriteSettings(PesVersion.V6))
    fun write(p:EmbPattern,s:PesWriteSettings):ByteArray {
        val prepared=if(s.inkstitchCommands)p else PesWriter.prepareForOutput(p)
        val candidate=if(s.version==PesVersion.V6)PesV6Writer.write(prepared,s.inkstitchCommands) else {
            val header=ByteArray(22);"#PES0001".toByteArray().copyInto(header);header[8]=22
            header+PecWriter.writePayload(prepared,s.inkstitchCommands,topLeftViewport=!s.inkstitchCommands)
        }
        return if(PecViewportCheck.check(candidate,s.hoop).valid)PesWriter.write(p,s) else {
            assertFailsWith<IllegalArgumentException> { PesWriter.write(p,s) }
            candidate
        }
    }
}
