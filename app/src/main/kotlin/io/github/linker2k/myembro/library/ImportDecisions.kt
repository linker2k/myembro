// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

data class DuplicatePrompt(val items: List<DuplicateDesign>, val index: Int = 0, val choices: Map<Int, DuplicateAction> = emptyMap()) {
    val current get() = items[index]
}

/** ViewModel-owned suspension survives rotation; cancelling never publishes staged files. */
class ImportDecisions {
    val prompt = MutableStateFlow<DuplicatePrompt?>(null)
    private var pending: CompletableDeferred<Map<Int, DuplicateAction>?>? = null
    suspend fun choose(items: List<DuplicateDesign>): Map<Int, DuplicateAction>? {
        check(pending == null)
        val result = CompletableDeferred<Map<Int, DuplicateAction>?>()
        pending = result
        prompt.value = DuplicatePrompt(items)
        try { return result.await() }
        finally { prompt.value = null; pending = null }
    }
    fun answer(action: DuplicateAction, applyToRemaining: Boolean) {
        val state = prompt.value ?: return
        val chosen = state.choices.toMutableMap()
        if (applyToRemaining) state.items.drop(state.index).forEach { chosen[it.id] = action }
        else chosen[state.current.id] = action
        if (chosen.size == state.items.size) pending?.complete(chosen.toMap())
        else prompt.value = state.copy(index = state.index + 1, choices = chosen.toMap())
    }
    fun cancel() { pending?.complete(null) }
}
