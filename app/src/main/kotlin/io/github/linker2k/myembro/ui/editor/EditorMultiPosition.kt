// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.Command

/** Confirmed machine positions only. Never infer SA434 mounting offsets from coverage dimensions. */
internal fun validEditorPositions(coverage: HoopSpec, positions: List<SewingPosition>): Boolean =
    positions.isNotEmpty() && positions.size <= 16 && positions.map { it.id }.distinct().size == positions.size &&
        positions.all {
            kotlin.math.abs(it.originX.toLong()) * 2 + it.width <= coverage.width &&
                kotlin.math.abs(it.originY.toLong()) * 2 + it.height <= coverage.height
        }

internal data class EditorPositionFit(val outsideIds: Set<String>, val spanningIds: Set<String>)
internal fun Project.positionFit(positions: List<SewingPosition>): EditorPositionFit {
    val outside = linkedSetOf<String>()
    val spanning = linkedSetOf<String>()
    for (obj in objects.filter { it.visible }) {
        val needles = obj.placedPattern().stitches.takeWhile { it.command != Command.END }.filter { it.command == Command.STITCH }
        if (needles.isEmpty()) continue
        if (needles.any { stitch -> positions.none { it.contains(stitch.x, stitch.y) } }) outside += obj.id
        else if (positions.none { position -> needles.all { position.contains(it.x, it.y) } }) spanning += obj.id
    }
    return EditorPositionFit(outside, spanning)
}

internal data class EditorSplitPreview(val project: Project, val positions: List<SewingPosition>, val result: MultiPositionResult)

@Composable
internal fun EditorSplitDialog(preview: EditorSplitPreview, onCancel: () -> Unit, onExport: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.editor_split_export)) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.editor_split_instructions))
            preview.result.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            preview.result.parts.forEach { Text(it.instructions) }
        } },
        confirmButton = { TextButton(onClick = onExport) { Text(stringResource(R.string.editor_split_export)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.editor_cancel)) } })
}
