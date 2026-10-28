# Fabric and thread colors in the editor

Based on main `d8d025f`. Open the Colors sheet from the project menu or the
existing Color inspector tab. The sheet has a fixed, live hoop preview at the top;
Undo/Redo below it; a scrollable fabric and thread section; and Apply/Cancel at the
bottom. Fabric choices are white, ivory, black, gray, navy, red and denim, a custom
`#RRGGBB` input, and Theme default. These are user-choice swatches, not claims about
materials or sewing properties. All application chrome uses the existing theme.

Threads are grouped by their existing thread identity in sewing order. Choosing a
Brother thread changes every matching block/object together. When My threads is
active its owned colors appear first, without hiding the rest of the Brother chart.
The picker stays inline under the preview. Both English and Korean labels are
provided. No background/thread similarity warning or unverified threshold is added.

## State and compatibility

`Project` adds optional `fabricColorArgb` (null by default) and `threadColors` (empty
by default). Project JSON v1 stores only nondefault appearance fields; legacy files
therefore keep their previous theme surface/transparent-thumbnail behavior.
Appearance is included in ordinary project equality, dirty checking, drafts and
History snapshots. Apply commits the final appearance as one editor undo step;
Cancel/back/outside dismissal discards the sheet-local history entirely. An
optimistic comparison refuses Apply if another editor/AI operation changed the
project while the sheet was open.

Thread substitution applies to the final thread table after the original flatten
operation. Original object geometry, text/shape caches, imported bytes, positions,
color-change commands and sewing order remain intact. Fabric is display-only
metadata (`myembro.fabric_argb`) on the flattened pattern. The editor canvas,
StitchCanvas (including the project viewer), and Thumbnails consume the appearance.
The new sheet handles unavailable device-side generation as a preview error rather
than crashing or creating export bytes.

## Automated validation

Command (JDK 17, no emulator):

```sh
./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

Result: `BUILD SUCCESSFUL in 4m 53s`. Engine: 1,005 tests; app: 458 tests; both have
0 failures/errors/skips. Lint: 0 errors, 174 warnings. Debug APK:
`app/build/outputs/apk/debug/app-debug.apk`.

`ProjectColorsTest` checks legacy/default and nondefault JSON round trips, repeated
thread blocks across objects, text-cache identity, undo, unchanged fabric-only PES
bytes, and exact equality of all decoded PES coordinates AND commands after a
thread change (23 commands in the synthetic example). `EditorColorsDraftTest`
checks local cancellation/undo/redo, a single global Apply undo step, and owned-first
Brother ordering. `EditorColorsPanelUiTest` runs real Compose actions under
Robolectric 4.14.1 with native graphics: custom fabric, preview state, undo/redo,
Apply/Cancel, Brother selection and actual thumbnail background pixels. Window
screenshots are not claimed; no emulator/device was used.

Robolectric and Compose UI test dependencies are test-only, except the standard
Compose debug test-activity manifest, which is excluded from release builds.

## Device-review rendering fixes (2026-10-28)

Based on main `e1cb4fb`, branch `agent/colors-fixes`:

- StitchCanvas clips native-canvas drawing to its own bounds. Its background
  `drawColor()` can no longer overwrite the sheet title or neighboring controls,
  including in dark mode.
- BlockEditState resolves region threads through Project.threadColor; its cache
  also keys on threadColors. RegionCanvas paints the project's fabric, while
  default projects keep their previous theme background. Region geometry, hit
  testing, selection and stitch settings are unchanged.
- Layer thumbnails resolve the project palette and key their render cache on it;
  their backdrop uses the project fabric. Undo and redo redraw the same object
  instance correctly.
- Colors is in the project overflow menu and remains reachable from the Color
  inspector tab. The extra top-bar palette icon is removed. Compact windows use
  the short localized Editor title, while expanded windows keep the full title.
- Fabric/thread swatches use the theme outline so black remains distinguishable
  on a similarly dark surface.

Validation command:
`./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain`.
Final output: `BUILD SUCCESSFUL in 1m 34s`. Engine: 1,021 tests; app: 470 tests;
0 failures/errors/skips. Lint: 0 errors, 174 warnings. APK:
`app/build/outputs/apk/debug/app-debug.apk`. No emulator/device was used.

New tests: ColorAppearanceRenderTest (2) checks real project-to-region color
mapping with unchanged paths/settings, and layer palette application with unchanged
needle commands. ColorRenderingUiTest (5) uses Robolectric native graphics to
verify light/dark title-area clipping, layer thread/fabric pixels and restoration,
block fabric/thread pixels, and a black swatch's outline on a matching background.
The existing 2 Colors-sheet interaction tests also pass. Pixel checks draw the
activity's content view into an in-memory bitmap, avoiding unsupported Window
PixelCopy capture in this JVM environment. No personal images are test fixtures.
