// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource
import io.github.linker2k.myembro.R

/** Goes straight to Home, clearing the screens above it; null where Home is one back away (or is the screen). */
val LocalGoHome = compositionLocalOf<(() -> Unit)?> { null }

/**
 * Top-bar Home button. [exit] is the screen's own leave path (the same unsaved-change check as its
 * back button), given the Home action to run once leaving is allowed.
 */
@Composable
fun HomeButton(enabled: Boolean = true, home: (() -> Unit)? = LocalGoHome.current, exit: (() -> Unit) -> Unit = { it() }) {
    home ?: return
    IconButton(onClick = { exit(home) }, enabled = enabled) { Icon(Icons.Outlined.Home, stringResource(R.string.home_go)) }
}

/** Pops every screen above Home and returns them, top last; back pops one. */
internal fun <T> MutableList<T>.popToHome(): List<T> = toList().also { clear() }
