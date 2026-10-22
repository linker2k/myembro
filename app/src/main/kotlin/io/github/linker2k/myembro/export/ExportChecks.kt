// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.export

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
import io.github.linker2k.myembro.engine.export.Preflight
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import io.github.linker2k.myembro.engine.export.MachineProfile as EngineProfile

/** Pre-export checks against the machine chosen in settings (docs/machines.md). */
object ExportChecks {
    /** SE400 has verified limits; other known machines only their sewing area; custom machines nothing. */
    fun profile(settings: EmbroiderySettings): EngineProfile? {
        val machine = settings.machineProfile ?: return null
        return if (machine.id == "se400") EngineProfile.SE400
        else EngineProfile(machine.displayName, machine.maximumArea.width, machine.maximumArea.height)
    }

    fun check(pattern: EmbPattern, fileName: String, settings: EmbroiderySettings, patternCount: Int = 1): List<String> =
        profile(settings)?.let { Preflight.check(pattern, fileName, it, patternCount = patternCount) }.orEmpty()
}

/** Lists preflight warnings; the user can still export (limits may be unverified for their unit). */
@Composable
fun PreflightDialog(machine: String, warnings: List<String>, onContinue: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.preflight_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.preflight_intro, machine), style = MaterialTheme.typography.bodyMedium)
                warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.preflight_continue)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.preflight_cancel)) } },
    )
}
