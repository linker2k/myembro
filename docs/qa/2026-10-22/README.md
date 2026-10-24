# 2026-10-22 UI QA

Device: emulator-5554, default 1080×2400 / 420dpi. App language Korean, default light (option A).
Integrated latest main changes into codex/worker during validation. Initial baseline main `19ebb35` + user frames `b55d246`; final baseline main `b9d2571` integration (`320bee8`) + UI fixes in this QA commit.
`assembleDebug` and app unit tests passed. All engine tests also passed in SA431/SA434 commits.

Observed APK installation and editorpreview app launches from other sessions on the shared emulator. Immediately after installation,
verified matching build/device APK SHA-256, but the device hash later changed. Deleted interference captures;
distinguish below what actual touch interaction verified from items still awaiting integration. Screen PNGs are original captures.

## Flows actually verified

| Flow | Results and evidence |
| --- | --- |
| Splash | Confirmed final integrated APK's light brand background/hoop icon. [Splash](01-splash.png) |
| Home/create sheet | Home cards and shared sheet from the Create tab, with 3 paths: empty hoop/my designs/text. [Sheet](12-new-project-choices.png) |
| Hoop selection | Selected default 100×100, SA431, SA434, and unsupported-frame warning. SA434 positions shown as unconfirmed. [Selection](13-frame-choices.png) |
| Editor add/move/rotate | Empty hoop→add sample→drag X9.15/Y11.18mm→90° rotation. [Move/rotate](16-editor-moved-rotated.png) |
| Editor save | Confirmed `.myembro` app-internal save success message. [Save](17-editor-saved.png). Test files organized under separate names |
| Korean/English lettering | Default Korean sample Bomnal (Spring Day), English MYEMBRO input. [Korean](18-lettering-korean.png), [English](19-lettering-english.png) |
| 3-pass bean stitch | Selected installed Hershey Sans→3-pass bean stitch→add design. [Selection](20-lettering-bean.png), [Editor](21-editor-lettering.png) |
| Dedicated picker | My designs/samples tabs, star search 3 results, selected star triple→editor. [Search](23-picker-search.png), [Result](24-picker-to-editor.png) |
| Design library | Search, 100×100/PES/recent filters, name sorting, favorite/delete QA copy. [Search](27-library-search.png), [Favorite](28-library-favorite.png), [After deletion](29-library-deleted-before-fix.png) |
| USB banner | SE400 USB cable/PC removable-disk guidance and OTG unconfirmed status. [Guidance](26-library-usb-help.png) |
| Viewer | Confirmed canvas/color order/mm/in statistics, playback progressing to 45%. [Viewer](30-viewer.png), [Playback](31-viewer-playing.png) |
| Export | PES save/share/USB sheet, SAF default flower.pes filename, ACTION_SEND share sheet. Did not send to a target app. [Sheet](32-export-sheet.png), [SAF](33-viewer-save-picker.png), [Share](34-viewer-share.png) |

## Findings and actions

| Status | Owner | Item |
| --- | --- | --- |
| Fixed | embro-worker | Design-library delete icon immediately deleted files without confirmation. Added a target-count confirmation dialog; delete only if selection still matches at confirmation. [Actually verified after fix](48-library-delete-fixed.png). Deleted a QA copy and confirmed it disappeared from the list. |
| Fixed | embro-worker | Only the final polite sentence-ending syllable wrapped onto the next line in foldable home guidance. Moved guidance below the image across full width. Also sort the default custom frame first in creation choices. [Foldable home](46-home-fold.png) |
| Fixed | embro-worker | Dual inch display could truncate viewer size statistics in a narrow column. Portrait size is now a separate full-width row. Applied mm/in formatting to library/settings/hoop selection too. |
| Fixed | embro-worker | Latest main ExportChecks checked only machine maximum size. Connected actual 0.1mm dimensions of the selected custom frame. |
| Fixed | embro-worker | SE400 settings showed stitch-count limit as 'unconfirmed', while the official FAQ in docs confirmed 30,000 stitches. Aligned settings description with the officially confirmed value. |
| Forwarded | embro-editor | Hoop/X/Y/width/height properties display only mm. Need shared Dimensions.length/size. [Editor](16-editor-moved-rotated.png) |
| Forwarded | embro-letter / embro-fonts | MYEMBRO shows recommended minimum heights of 992.7mm for Rounded Gothic and 255.8mm for Plain Gothic. Quality metrics/user guidance need review. [English](19-lettering-english.png) |
| Forwarded | embro-letter | No inch display for lettering hoop/letter height/letter spacing/line spacing. Need Dimensions formatter. |
| Integration needed | embro-editor | New MainActivity callbacks are in main. However, EditorViewModel.constrainHoop/editorMachine lists still adjust custom/unsupported frames to the first supported hoop and block export. Need to retain requested HoopSpec and implement warning-selection policy. See post-QA integration notes in docs/machines.md. |
| unconfirmed | embro-codex / embro-model | Extended-machine official specification table not received. SA434 position count/offsets remain an empty list until officially confirmed. Do not insert arbitrary 3 positions/±35mm as product specifications. |

Searched owned home/library/viewer/settings/picker for hardcoded UI colors; none found.
Embroidery thread colors/design thumbnails are actual design data, distinct from brand UI tokens.

## Additional settings/expanded-layout validation

- Confirmed SE400→SE600→SE700 switching and each USB/wireless/internal-memory guidance.
  [SE600](37-settings-se600.png), [SE700](38-settings-se700.png), [Unrestricted machine](45-settings-free.png).
  Final commit clarified unrestricted-machine guidance as 'free configuration without machine restrictions'.
- Custom frame: enter 1×2.5in→save 25.4×63.5mm→edit width to 50.8mm→select default→delete.
  [Inch input](40-custom-frame-inch.png), [Edit](41-custom-frame-edited.png),
  [Home default](42-home-custom-default.png), [Delete confirmation](44-custom-delete-confirm.png).
- Selected custom frame in creation sheet, but editor header was 100×100mm.
  [Actual reproduction](43-editor-custom-clamped.png). Editor adjusts after MainActivity passes the frame;
  `embro-editor` still needs a fix retaining requested size. Precise-size saving/Preflight adapter unit tests passed.
- `adb shell wm size 1968x2184`: [Home](46-home-fold.png), [Library](47-library-fold.png),
  [Viewer](49-viewer-fold.png), [Picker](50-picker-fold.png). Confirmed 2 columns and parallel canvas/information layout at 600dp or larger.
  Viewer mm/in size is readable across two lines in the information panel; playback/hoop/speed controls remain accessible.
- Final `assembleDebug`, full `:app:testDebugUnitTest`, `git diff --check` passed.

## Restoration and scope

After `wm size reset`, restored **1080×2400 / 420dpi**, app language **[] (system default)**, system night **no**,
app **light**, machine **SE400**, default hoop **100×100**. Removed QA custom frames/QA design
copies/QA project files; retained original designs/installed fonts. Actual USB hardware transfer and
SE400/600/700 machine sewout are outside this emulator QA scope and were not verified.

Extended-machine table not yet received. Do not arbitrarily add large-frame machine/PR limits/compatible hoops/transfers
before official values for the 'confirmed' table arrive. SA434 position counts/offsets also remain unconfirmed.
