// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.export

import android.content.res.Resources

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
import io.github.linker2k.myembro.ui.machines.exportProfile
import io.github.linker2k.myembro.engine.export.MachineProfile as EngineProfile

/** Pre-export checks against the machine chosen in settings (docs/machines.md). */
object ExportChecks {
    /** Actual selected frame, capped by verified continuous machine area; free models keep the frame. */
    fun profile(settings: EmbroiderySettings): EngineProfile = settings.exportProfile()

    fun check(pattern: EmbPattern, fileName: String, settings: EmbroiderySettings, patternCount: Int = 1,
        resources: Resources? = null): List<String> =
        Preflight.check(pattern, fileName, profile(settings), patternCount = patternCount,
            fileNameMessages = resources?.let { r -> fileNameMessages { id, argument -> r.getString(id, argument) } }
                ?: Preflight.FileNameMessages())

    internal fun fileNameMessages(string: (Int, Any) -> String) = Preflight.FileNameMessages(
        invalidCharacters = { machine -> string(R.string.preflight_filename_characters, machine) },
        tooLong = { limit -> string(R.string.preflight_filename_too_long, limit) },
    )
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
