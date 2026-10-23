// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import android.content.Context
import java.security.MessageDigest

/** Exact exported content identity, never a filename/extension heuristic. No imported file is tagged. */
internal class GeneratedPesOrigins(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("generated_pes_origins", Context.MODE_PRIVATE)
    fun contains(bytes: ByteArray): Boolean = preferences.getBoolean(digest(bytes), false)
    fun record(bytes: ByteArray) { preferences.edit().putBoolean(digest(bytes), true).apply() }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
