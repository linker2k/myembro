<!-- SPDX-License-Identifier: GPL-3.0-or-later OR MIT -->

# Realistic preview improvements: GPL-3.0-or-later OR MIT

The repository stays under [GPL-3.0-or-later](../../LICENSE). The project-written realistic preview improvement work
listed here is additionally offered under the [MIT license](../../LICENSES/MIT.txt): you may use it under
GPL-3.0-or-later **or** MIT. Third-party code is not covered. The realistic renderer that this work calls
(`engine/.../render/realistic/` except `FixedRealisticPreview.kt`, adapted from Ink/Stitch, Inkscape and resvg) keeps
its own licenses and notices in [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).

Basis: user decision 2026-11-13. Line numbers refer to commit `988bfad2`.

## Whole files

Every line of these files comes from the improvement commits `06fe5aae`, `01b6b1cd` and `ecb3bae7` (2026-11-06),
plus a one-line note in `25df999e` (`docs/realistic-speed-experiment.md`). No third-party text is copied into them.
Each file carries `SPDX-License-Identifier: GPL-3.0-or-later OR MIT`; the CSV carries it in a `.license` sidecar file.

| file | commits |
|---|---|
| `engine/src/main/kotlin/io/github/linker2k/myembro/engine/render/realistic/FixedRealisticPreview.kt` | 06fe5aae, ecb3bae7 |
| `engine/src/test/kotlin/io/github/linker2k/myembro/engine/render/realistic/FixedRealisticPreviewTest.kt` | 06fe5aae, ecb3bae7 |
| `docs/realistic-renderer.md` | 06fe5aae, 01b6b1cd, ecb3bae7 |
| `docs/realistic-speed-experiment.md` | ecb3bae7, 25df999e |
| `docs/realistic-fixed-preview-measurements.csv` (+ `.csv.license`) | 06fe5aae |
| this document | – |

## Parts of mixed files

These files stay `GPL-3.0-or-later` as a whole. Only the lines listed below are also offered under MIT.

| file | commit | lines at 988bfad2 |
|---|---|---|
| `app/src/main/kotlin/io/github/linker2k/myembro/ui/StitchCanvas.kt` | 06fe5aae | 6–7, 131, 133, 192–196, 207–213, 216–217 |
| same | 01b6b1cd | 197–206, 214–215 |
| `engine/build.gradle.kts` (`measureFixedRealisticPreview` task) | 06fe5aae | 312–324 |
| `app/src/main/kotlin/io/github/linker2k/myembro/ui/ViewerScreen.kt` | d413877d | 254 |
| `app/src/androidTest/kotlin/io/github/linker2k/myembro/ui/RealisticViewerInstrumentation.kt` | d413877d | 56, 86, 90, 143, 150–151, 157, 162, 172 |
| `app/src/main/kotlin/io/github/linker2k/myembro/ui/ViewerRealisticPreview.kt` | 06fe5aae | 10–11, 42–45, 89, 92, 100, 123–140, 157–158, 166–174, 176–184, 186–188, 190–203, 208–209, 214–219, 229, 245, 253 |
| same | 01b6b1cd | 19, 30–33, 90, 99, 141–156, 159–165, 175, 204, 212–213, 233–237, 239, 248, 258 |
| same | ecb3bae7 | 77–87, 95, 185, 189 |
| `app/src/test/kotlin/io/github/linker2k/myembro/ui/ViewerRealisticPreviewTest.kt` | 06fe5aae | 60–67, 69–72 |
| same | 01b6b1cd | 68, 73–77, 79, 81–82, 84–97 |
| same | ecb3bae7 | 22–37, 78, 80, 83 |

Not covered: the other lines of `ViewerRealisticPreview.kt` (128 lines) and `ViewerRealisticPreviewTest.kt` (44 lines)
come from the first viewer integration `80568745` (2026-10-31) and `a7f344fb`, and stay GPL-3.0-or-later only until
their scope is confirmed.

## Copyright

The copyright holder line for the MIT option is not confirmed yet; it is not filled in here and no per-file
`SPDX-FileCopyrightText` is added until it is.
