# Display theme

The default is theme A light regardless of system settings. Selecting Light, Dark, or Follow system in Settings' display theme saves to `myembro_settings.theme_mode`. Missing or unknown values fall back to light. `ThemeModeTest` verifies this rule.

The `MyembroTheme` root observes settings, and nested screens inherit the same theme. Editor/lettering/font screens also reflect it through existing `MyembroTheme` and MaterialTheme tokens. Dynamic colors are not used. Window backgrounds and system-bar icons update with the same tokens.

The OS draws the splash window before app code runs. MainActivity is the only launch activity; its theme is `Theme.Myembro.Starting`(values = light, values-night = dark). On Android 12 and later, the saved app-specific mode via `UiModeManager.setApplicationNightMode` determines this day/night choice, so a light setting shows a light splash even when the system is dark. On Android 11 and earlier, only the splash follows system settings; app screens follow the saved setting.

**Do not enable/disable launcher components.** alpha9 switched between theme-specific launcher activities 3 using `setComponentEnabledSetting`; launchers such as Samsung One UI treated this as removing/adding an app entry point, making Home screen icons disappear or move(removed in alpha10).

On Android 12 and later, also saves UiModeManager's app-specific mode. `MODE_NIGHT_AUTO` clears the app-specific qualifier to follow system settings([AOSP implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/UiModeManagerService.java), [Android dark theme guide](https://developer.android.com/develop/ui/views/theming/darktheme)). Light/dark splash XML colors are generated from Color.kt, applying A/B/C token selection identically.

Validation: `:app:assembleDebug`, `:app:testDebugUnitTest --tests '*ThemeModeTest'`. On emulator-5554, verified setting selection/persistence, dark screens, and a light starting window with the system in dark mode.
