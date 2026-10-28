// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.machines

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.linker2k.myembro.R

/** Localized labels are presentation only; stored model names and IDs stay compatible. */
@StringRes
internal fun machineNameResource(id: String?): Int? = when (id) {
    "kr-m370" -> R.string.machine_name_kr_m370
    "kr-m380d" -> R.string.machine_name_kr_m380d
    "kr-lb7950" -> R.string.machine_name_kr_lb7950
    "kr-lb8950" -> R.string.machine_name_kr_lb8950
    "kr-lb6950" -> R.string.machine_name_kr_lb6950
    "kr-nv180" -> R.string.machine_name_kr_nv180
    "kr-nv180d" -> R.string.machine_name_kr_nv180d
    "kr-nv180k" -> R.string.machine_name_kr_nv180k
    "kr-nv900" -> R.string.machine_name_kr_nv900
    "kr-nv900d" -> R.string.machine_name_kr_nv900d
    "kr-nv950" -> R.string.machine_name_kr_nv950
    "kr-nv980d" -> R.string.machine_name_kr_nv980d
    "kr-nv980k" -> R.string.machine_name_kr_nv980k
    "kr-f580" -> R.string.machine_name_kr_f580
    "kr-ns2750d" -> R.string.machine_name_kr_ns2750d
    "kr-nv1250" -> R.string.machine_name_kr_nv1250
    "kr-nv2600" -> R.string.machine_name_kr_nv2600
    "kr-nv2700" -> R.string.machine_name_kr_nv2700
    "kr-nv4000d" -> R.string.machine_name_kr_nv4000d
    "kr-v3" -> R.string.machine_name_kr_v3
    "kr-v3le" -> R.string.machine_name_kr_v3le
    "kr-v5" -> R.string.machine_name_kr_v5
    "kr-v5le" -> R.string.machine_name_kr_v5le
    "kr-xj1" -> R.string.machine_name_kr_xj1
    "kr-pr680w" -> R.string.machine_name_kr_pr680w
    "kr-pr1050x" -> R.string.machine_name_kr_pr1050x
    "kr-pr1060w" -> R.string.machine_name_kr_pr1060w
    "kr-pr1x" -> R.string.machine_name_kr_pr1x
    "free" -> R.string.settings_machine_free
    else -> null
}

@Composable
internal fun machineDisplayName(id: String?, fallback: String): String =
    machineNameResource(id)?.let { stringResource(it) } ?: fallback
