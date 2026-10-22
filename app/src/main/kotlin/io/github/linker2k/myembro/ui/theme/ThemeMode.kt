// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.theme

/** Stored values are stable names; missing/unknown preferences always select the craft light theme. */
enum class ThemeMode {
    LIGHT, DARK, SYSTEM;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }

    companion object {
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: LIGHT
    }
}
