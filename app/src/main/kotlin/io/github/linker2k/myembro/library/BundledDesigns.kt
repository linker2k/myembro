// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import android.content.Context
import java.io.File

/** Read-only bundled gallery, separate from the user's installed/deleted library copies. Call on IO. */
internal object BundledDesigns {
    fun files(context: Context): List<File> {
        val directory = File(context.cacheDir, "bundled_designs_v1")
        SampleInstaller(directory).install(context.assets.list("samples").orEmpty().toList()) { name ->
            context.assets.open("samples/$name")
        }
        return directory.listFiles().orEmpty()
            .filter { it.isFile && DesignLibrary.isSample(it) && it.extension in DesignLibrary.SUPPORTED }
            .sortedBy { it.name }
    }
}
