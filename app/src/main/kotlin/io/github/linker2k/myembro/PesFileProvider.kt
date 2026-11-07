// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro

import androidx.core.content.FileProvider

/** Dedicated provider for temporary embroidery exports; paths are restricted in file_paths.xml. */
class PesFileProvider : FileProvider() {
    override fun getType(uri:android.net.Uri):String="application/octet-stream"
}
