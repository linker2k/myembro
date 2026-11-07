// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine
import io.github.linker2k.myembro.engine.io.JefWriter
import io.github.linker2k.myembro.engine.io.JefWriteSettings
import kotlin.test.*
class JefWriterTest {
    @Test fun threeBundledCc0Representatives(){FormatWriterSamples.compare("jef"){JefWriter.write(it,"20000102030405")}}
    @Test fun originalBytesWithTestOnlyFixedDate(){
        for((id,p)in FormatWriterFixtures.patterns)if(id!="empty")FormatWriterFixtures.compare("$id.jef",JefWriter.write(p,"20000102030405"))
    }
    @Test fun explicitTrimOptionMatchesOriginal(){
        val p=FormatWriterFixtures.patterns.first{it.first=="multicolor"}.second
        FormatWriterFixtures.compare("multicolor.trims.jef",JefWriter.write(p,JefWriteSettings(trims=true,date="20000102030405")))
    }
    @Test fun emptyDesignIsRejectedAsInUpstream(){assertFailsWith<IndexOutOfBoundsException>{JefWriter.write(FormatWriterFixtures.patterns.first{it.first=="empty"}.second,"20000102030405")}}
    @Test fun hoopBoundaryDefaults(){
        assertEquals(1,JefWriter.hoopSize(499,499));assertEquals(3,JefWriter.hoopSize(500,500))
        assertEquals(3,JefWriter.hoopSize(1259,1099));assertEquals(2,JefWriter.hoopSize(1260,1100))
        assertEquals(2,JefWriter.hoopSize(1399,1999));assertEquals(4,JefWriter.hoopSize(1400,1999));assertEquals(0,JefWriter.hoopSize(2000,2000))
    }
}
