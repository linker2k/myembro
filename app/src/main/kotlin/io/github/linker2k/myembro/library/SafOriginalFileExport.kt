// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException

/** The provider receives original filenames and bytes, including .myembro and non-PES formats. */
internal fun originalExportFolder(context: Context, tree: Uri): OriginalFileExporter.Folder {
    val folder=DocumentFile.fromTreeUri(context,tree) ?: throw FileNotFoundException(tree.toString())
    if(!folder.isDirectory || !folder.canWrite()) throw SecurityException(tree.toString())
    return object : OriginalFileExporter.Folder {
        override fun names() = folder.listFiles().mapNotNull { it.name }.toSet()
        override fun create(name: String): OriginalFileExporter.Document {
            // Generic MIME avoids a provider appending .pes or converting the stored file format.
            val document=folder.createFile("application/octet-stream",name) ?: throw FileNotFoundException(name)
            return originalExportDocument(context,document.uri,name)
        }
    }
}
internal fun originalExportDocument(context: Context, uri: Uri, fallbackName: String): OriginalFileExporter.Document =
    object : OriginalFileExporter.Document {
        private val document=DocumentFile.fromSingleUri(context,uri)
        override val name get() = document?.name ?: fallbackName
        override fun open() = context.contentResolver.openOutputStream(uri,"wt") ?: throw FileNotFoundException(uri.toString())
        override fun delete() = document?.delete() ?: false
    }
