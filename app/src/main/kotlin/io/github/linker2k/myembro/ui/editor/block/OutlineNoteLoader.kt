// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reading the note can initialize ShapeObject's lazy stitch renderer. Never run it on the UI thread. */
internal suspend fun loadOutlineNote(read: () -> Pair<Int, Boolean>): Pair<Int, Boolean> =
    withContext(Dispatchers.Default) { read() }
