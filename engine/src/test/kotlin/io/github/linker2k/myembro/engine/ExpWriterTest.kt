// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine

import io.github.linker2k.myembro.engine.io.ExpWriter
import kotlin.test.*

class ExpWriterTest {
    @Test fun threeBundledCc0Representatives(){FormatWriterSamples.compare("exp"){ExpWriter.write(it)}}
    @Test fun defaultsMatchIsolatedPyembroidery151Oracle() {
        for((id,p)in FormatWriterFixtures.patterns)FormatWriterFixtures.compare("$id.exp",ExpWriter.write(p))
    }
    @Test fun writerDoesNotMutateSource() {
        for((_,p)in FormatWriterFixtures.patterns){
            val stitches=p.stitches.toList();val threads=p.threads.toList();val metadata=p.metadata.toMap()
            ExpWriter.write(p);assertEquals(stitches,p.stitches);assertEquals(threads,p.threads);assertEquals(metadata,p.metadata)
        }
    }
}
