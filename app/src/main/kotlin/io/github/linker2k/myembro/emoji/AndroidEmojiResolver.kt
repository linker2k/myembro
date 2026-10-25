// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.emoji

import android.content.Context
import io.github.linker2k.myembro.engine.agent.EmojiResolver

/** Call on the serialized background tool executor. This adapter has no download operation. */
class AndroidEmojiResolver(context: Context) : EmojiResolver {
    private val repository=EmojiRepository(context.applicationContext)
    override val catalog get()=repository.catalog
    override fun isAvailable(emoji: CatalogEmoji)=repository.isAvailable(emoji)
    override fun localSvg(emoji: CatalogEmoji)=repository.localSvg(emoji)
}
