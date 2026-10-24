// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.emoji

import android.content.Context
import io.github.linker2k.myembro.fonts.ChecksumMismatchException
import io.github.linker2k.myembro.fonts.FontRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Emoji pictures like Ink/Stitch fonts: the bundled ones are read from assets (checked against
 * the catalog sha256), the rest are downloaded once from the pinned commit, verified and kept in
 * app storage. Only verified bytes are ever returned.
 */
class EmojiRepository(context: Context) {
    private val assets = context.applicationContext.assets
    private val dir = File(context.filesDir, "emoji")
    val catalog: EmojiCatalog by lazy { EmojiCatalog.parse(assets.open(EmojiCatalog.ASSET).use { it.readBytes().decodeToString() }) }

    fun isAvailable(e: CatalogEmoji): Boolean = e.bundled || file(e).isFile

    /** SVG text of [e], downloading it first when it is neither bundled nor downloaded. Call off the main thread. */
    suspend fun svg(e: CatalogEmoji): String = withContext(Dispatchers.IO) {
        val bytes = if (e.bundled) assets.open("${EmojiCatalog.BUNDLED}/${e.id}.svg").use { it.readBytes() }
            else file(e).takeIf { it.isFile }?.readBytes() ?: download(e)
        if (sha256(bytes) != e.sha256) throw ChecksumMismatchException(e.id)
        bytes.decodeToString()
    }

    private fun download(e: CatalogEmoji): ByteArray {
        dir.mkdirs()
        val tmp = File(dir, "${e.id}.part")
        try {
            FontRepository.fetchPinned(catalog.url(e), tmp, e.sha256, e.id, e.size) {}
            check(tmp.renameTo(file(e))) { "Cannot store ${e.id}" }
        } finally { tmp.delete() }
        return file(e).readBytes()
    }

    private fun file(e: CatalogEmoji) = File(dir, "${e.id}.svg")
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
