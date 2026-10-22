// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import android.view.HapticFeedbackConstants
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.linker2k.myembro.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorHistoryButton(vm: EditorViewModel, redo: Boolean) {
    val view = LocalView.current
    val enabled = if (redo) vm.redoEnabled else vm.undoEnabled
    val steps = if (redo) vm.redoSteps else vm.undoSteps
    val label = stringResource(if (redo) R.string.editor_redo else R.string.editor_undo)
    val state = when {
        vm.gestureInProgress -> stringResource(R.string.editor_history_dragging)
        vm.busy -> stringResource(R.string.editor_history_busy)
        vm.letteringOpen -> stringResource(R.string.editor_edit_text)
        steps == 0 -> stringResource(if (redo) R.string.editor_redo_empty else R.string.editor_undo_empty)
        else -> stringResource(R.string.editor_history_steps, steps)
    }
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("$label · $state") } }, state = rememberTooltipState()) {
        IconButton(enabled = enabled, modifier = Modifier.semantics { stateDescription = state }, onClick = {
            val changed = if (redo) vm.redo() else vm.undo()
            // CLOCK_TICK is a short discrete step and respects the system haptic setting.
            if (changed) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }) {
            Icon(if (redo) Icons.AutoMirrored.Filled.Redo else Icons.AutoMirrored.Filled.Undo, label)
        }
    }
}
