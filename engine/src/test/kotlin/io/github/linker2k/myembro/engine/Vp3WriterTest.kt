// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine
import io.github.linker2k.myembro.engine.io.Vp3Writer
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.test.Test
class Vp3WriterTest {
    @Test fun threeBundledCc0Representatives(){FormatWriterSamples.compare("vp3"){Vp3Writer.write(it)}}
    @Test fun originalBytesWithTestOnlyFixedEmptyBlockFiller(){
        for((id,p)in FormatWriterFixtures.patterns)FormatWriterFixtures.compare("$id.vp3",Vp3Writer.writeWithFiller(p){EmbThread(0xff112233.toInt())})
    }
}
