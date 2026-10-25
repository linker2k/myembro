// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import android.app.Instrumentation
import android.os.Bundle
import io.github.linker2k.myembro.engine.diagnostics.FontFillProbe
import java.io.File
import java.util.zip.GZIPOutputStream

class FontFillProbeInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        try {
            // AAPT expands .gz test assets and strips the suffix.
            val input=context.assets.open("font-snapshots/noto-sans-kr-latin.myembro").bufferedReader().use { it.readText() }
            val out=File(targetContext.filesDir,"font-fill-probe.json.gz")
            GZIPOutputStream(out.outputStream()).use { it.write(FontFillProbe.run(input).toByteArray(Charsets.UTF_8)) }
            finish(-1,Bundle().apply { putString("result","PASS files/font-fill-probe.json.gz") })
        } catch(t:Throwable) { finish(0,Bundle().apply { putString("error",t.stackTraceToString()) }) }
    }
}
