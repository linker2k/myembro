// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** All provider queries, recursive enumeration, hashing, parsing and copying run on IO. */
suspend fun Context.importDesigns(
    uris: List<Uri> = emptyList(), tree: Uri? = null,
    chooseDuplicates: suspend (List<DuplicateDesign>) -> Map<Int, DuplicateAction>?,
    progress: (DesignImportProgress) -> Unit = {},
): DesignImportReport = withContext(Dispatchers.IO) {
    val coroutine = currentCoroutineContext()
    val roots = if (tree == null) uris.map { uri ->
        object : DesignImportEntry {
            override val id = uri.toString()
            // Resolve lazily, so a broken provider cannot abort the remaining selected URIs.
            override val name: String by lazy { runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "unknown" }
            override val directory = false
            override fun open(): InputStream = contentResolver.openInputStream(uri) ?: error("Cannot read $name")
        }
    } else listOf(TreeEntry(this@importDesigns, tree, DocumentsContract.getTreeDocumentId(tree), "", true))
    BatchDesignImporter(File(filesDir, "designs")).prepare(roots, progress) { coroutine.ensureActive() }.use { plan ->
        val actions = if (plan.duplicates.isEmpty()) emptyMap() else withContext(Dispatchers.Main) {
            chooseDuplicates(plan.duplicates)
        }
        coroutine.ensureActive()
        if (actions == null) DesignImportReport(emptyList(), emptyList(), cancelled = true)
        else plan.commit(actions, progress) { coroutine.ensureActive() }
    }
}

private class TreeEntry(
    private val context: Context, private val tree: Uri, private val documentId: String,
    override val name: String, override val directory: Boolean,
) : DesignImportEntry {
    override val id = DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString()
    override fun open(): InputStream = context.contentResolver.openInputStream(Uri.parse(id)) ?: error("Cannot read $name")
    override fun children(): List<DesignImportEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val cursor = context.contentResolver.query(uri, columns, null, null, null) ?: error("Cannot list $name")
        return cursor.use {
            buildList {
                while (it.moveToNext()) add(TreeEntry(context, tree, it.getString(0), it.getString(1) ?: "unknown",
                    it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
            }
        }
    }
}
