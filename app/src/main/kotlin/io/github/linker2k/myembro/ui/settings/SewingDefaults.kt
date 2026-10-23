// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.settings

import androidx.compose.runtime.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions
import io.github.linker2k.myembro.engine.model.EmbPattern

/** Automatic source defaults, with no persisted overrides or advanced settings UI. */
@Composable
internal fun rememberSewingDefaults(): SewingOptions = remember { SewingOptions() }

/** Provenance is required: opening a PES never opts it into generated-geometry processing. */
internal fun EmbPattern.withSewingDefaults(options: SewingOptions): EmbPattern =
    if (metadata[GeneratedPatternProcessor.FINISHED_KEY] == "v1") GeneratedPatternProcessor.finish(this, options = options)
    else this
