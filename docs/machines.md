# Machine profiles

Verified: 2026-10-28. Based on official Brother sources (United States, Korea and Canada); the default model is **Brother SE400**.
Official specification checks and physical Android testing are distinguished. The app exports PES.

| Model | Maximum single embroidery area | Confirmed hoops (width×height) | Transfer |
| --- | --- | --- | --- |
| SE400 | 100×100mm | 100×100, optional 60×20, repositionable 100×170 | USB cable → removable disk on a PC |
| SE600 | 100×100mm | 100×100, optional 60×20, repositionable 100×170 | USB flash drive |
| SE700 | 100×100mm | 100×100, optional SA431 60×20, repositionable SA434 100×170 | USB flash drive / Brother wireless transfer |

A larger repositionable hoop permits embroidery at multiple positions without rehooping the fabric; it does not
increase the single sewing area. The default hoop is 100×100mm (4×4in). All frames can be selected;
frames not confirmed for the selected model display “Not supported by this machine — check before sewing.”

### Confirmed SA431 and SA434 values

The app uses **width×height**. H×W in Brother's accessory tables must not be read directly as width×height.

| Frame | Official H×W | App width×height | Official inches (nominal) | Compatibility confirmed | Position count and offsets |
| --- | --- | --- | --- | --- | --- |
| SA431 / EF61 | 20×60mm | 60×20mm | 2½×1in | SE400·SE600·SE700 | Not applicable |
| SA434 / EF71 | 170×100mm | 100×170mm | 4×6¾in | SE400·SE600·SE700 | **unconfirmed** |

Sources: [Official SA431 product page](https://www.brother-usa.com/p/hoops-stabilizers/SA431),
[Official SA431 2×6cm designation](https://www.brother.com.br/products/sa431),
[Official SA434 product page](https://www.brother-usa.com/p/hoops-stabilizers/SA434),
[Official SE700 accessory list](https://support.brother.com/g/b/sp/colist.aspx?c=us_ot&cao=hfemba&lang=en&prod=hf_se700eus),
[Official guidance: maximum 4×4 at a single position](https://help.brother-usa.com/app/answers/detail/a_id/72260/~/available-hoop-sizes).
SE400 and SE600 were confirmed in the US product-page compatibility lists, and SA431 and SA434 in the SE700 accessory list.
Metric dimensions and nominal inch sizes in the manuals are not exact conversions. For example, 20mm is not converted to an actual 1in.
Official hoop names use nominal sizes; measured design dimensions use the actual conversion of 25.4mm/in.
SA434's “usually 3 positions” or ±35mm has not been confirmed as an official value and is not included in the profile.

## Sources and verification scope

- **SE400**: [Operation Manual](https://download.brother.com/welcome/doch000184/885v31_v32_v33_om04en.pdf)
  printed p.127 (hoops), [official specifications](https://www.brother-usa.com/p/sewing-embroidery/SE400) (maximum 4×4 inches),
  [USB Connections Manual](https://download.brother.com/welcome/doch000186/885v31_v32_usb02_usenes.pdf)
  printed pp.2–4 (PES, Windows removable disk, root directory, temporary disk maximum 12 designs).
  Connecting a USB cable to a PC exposes a removable disk. Android support in official sources is **unconfirmed**. See the additional 2026-10-23 records below for the Fold7 user's measurements and the direct-access path.
  We do not describe this as plugging a USB flash drive directly into the machine.
  The [official Brother Canada FAQ](https://support.brother.ca/app/answers/detail/a_id/133968/~/design-does-not-appear-on-lcd-after-being-transferred)
  explicitly lists SE400 as an applicable model and specifies a maximum of **30,000 stitches** per design, with both the design and the stored hoop limited to 4×4 inches.
  Values in a memory-card FAQ are not applied as general limits on USB PES files.
- **SE600**: [Operation Manual](https://download.brother.com/welcome/doch101011/888m20_om02en.pdf)
  printed pp.8,60 (hoops), pp.74–75 (maximum 10×10cm, PES, USB flash drive).
  Design-count and PES stitch-count limits are **unconfirmed**.
- **SE700**: [Operation Manual](https://download.brother.com/welcome/doch102582/888p10_om01en.pdf)
  printed p.76 (hoops), pp.80–81 (saving/loading), p.96 (specifications).
  **Internal memory** is listed as up to 20 designs / 1024KB, with a maximum of 100,000 stitches per design.
  These values are not extended to USB flash drives. USB design-count and stitch-count limits are **unconfirmed**.
  The [official wireless guidance](https://support.brother.com/g/b/faqend.aspx?c=us&faqid=faqh00101118_008&lang=en&prod=hf_se700eus)
  describes Design Database Transfer (Windows) and PE-DESIGN 11;
  the [official product page](https://www.brother-usa.com/p/sewing-embroidery/SE700) also describes Artspira.
  Direct wireless transfer from myembro has not been implemented. SA434 compatibility was additionally confirmed through the official SE700 accessory list.

**PES filename-length limits are unconfirmed** for all three models. Manuals recommend English letters, digits, hyphens, and underscores.
The displayed length of DST names is not used as a PES filename limit, and filenames are not forcibly converted.

## State and integration API

`ui/machines/MachineProfile.kt` stores only source URLs and confirmed values. Areas use the engine's
`HoopSpec` unchanged, in 0.1mm units. `null` or an empty limit list means unconfirmed, not unlimited.
Other manually entered models have a `null` profile and do not inherit SE400's confirmed capabilities.

```kotlin
val preferences = SettingsPreferences(context)
val settings = preferences.read()
val profile = settings.machineProfile      // MachineProfile?; 미확인 기종은 null
val hoops = preferences.editorHoops()      // List<engine.edit.HoopSpec>; 경고 선택 가능한 전체 프리셋
val initial = preferences.defaultEditorHoop() // HoopSpec; 선택한 프레임의 실제 크기
val dispose = preferences.observe { /* 기종/기본 후프 변경 시 새 목록 조회 */ }
// 화면 또는 ViewModel 폐기 시 dispose()
```

- Changing the model also saves a default hoop of 100×100. Unsupported frames remain selectable and retained. An unset/empty model switches to SE400; existing manually entered model names are preserved.
- Settings, Home, the library, and the viewer observe the same SharedPreferences changes. The viewer also permits unsupported hoops with a warning. Library badges compare the current profile against actual design bounds.
- In the library/viewer, `profile.fits(bounds)` checks **design size**. Objects placed in the editor must also have their position checked using the existing engine's `Bounds.outside(project.hoop)`.
- **Integrator**: change the editor hoop menu to `editorHoops()` and use `defaultEditorHoop()` for new projects.
  If a model change leaves an existing edited design oversized, do not automatically shrink or delete it;
  retain the existing overflow indication. Unconfirmed models need guidance for manual setup.
  Editor and MainActivity sources were not changed in this commit.

## Creation flow

- The Home new-project card and the Create tab open the same `NewProjectSheet`. When a blank hoop is selected
  and only one hoop is available, creation starts with `onBlank(H100)` without a separate hoop question.
- `NewProjectSheet(onBlank, onFromDesign, onFromText, hoops)` provides host callbacks.
  **Integrator**: connect `HomeScreen.onCreateFromDesign` to design selection → editor entry with the selected file.
  Wiring for the new dedicated `DesignPickerScreen(onSelect, onBack)` is in the
  [design-system integration notes](design-system.md#dedicated-design-picker-and-unfolded-layout).
  The default callback uses the existing `onOpenLibrary`, so the host must specify the creation-specific route.
- The lettering entry point is exposed only when connected to an actual screen through
  `HomeScreen(letteringAvailable = true, onLettering = { ... })`. It is hidden by default.
- The Home gallery shows only actual PES samples; empty-canvas templates have been removed.
  Samples open in the viewer through the existing `onOpenRecent(file)`, and the Edit button creates a project.
- The design library is for management only. Its Create button has been removed.


## SE400 file compatibility survey and engine Preflight

Verified: 2026-10-22. SE400's stitch-count limit is not applied unchanged to other 4×4 models such as
SE600 and SE700. **unconfirmed** below does not mean unlimited or supported.

| Item | Confirmed scope for SE400 | Evidence |
| --- | --- | --- |
| Formats | PES, DST | [USB manual](https://download.brother.com/welcome/doch000186/885v31_v32_usb02_usenes.pdf), printed p.2 |
| PES versions | Individual v1/v6 support and the maximum supported version are **unconfirmed** | The official manual specifies only the PES format |
| Design size/stored hoop | Maximum 100×100mm; the stored hoop must also be no larger than 4×4 inches | [Operation manual](https://download.brother.com/welcome/doch000184/885v31_v32_v33_om04en.pdf) p.127, [official FAQ](https://support.brother.ca/app/answers/detail/a_id/133968/~/design-does-not-appear-on-lcd-after-being-transferred) item 8 |
| Maximum stitch count | 30,000 stitches per design | Item 9 of the same official FAQ, explicitly listing SE400 as an applicable model |
| Colors/color changes | Numeric limit **unconfirmed** | The USB manual only states that a limit exists |
| Filenames | English letters, digits, `_`, and `-` recommended; maximum PES length **unconfirmed** | USB manual p.2. Displaying the first 8 characters of a DST name is not a PES length limit |
| Folders/PC transfer batch | Up to 12 designs at the removable disk root | USB manual pp.2,4 |
| Internal storage memory | Up to 12 designs or 512KB total | [Official SE400 memory FAQ](https://support.brother.com/g/b/faqend.aspx?c=us&faqid=faqh00000472_001&lang=en&prod=hf_se400eus) |
| Size per PC-transferred file | **unconfirmed** | The 512KB internal-memory capacity is not extended to per-file/PC-transfer capacity |
| Whether a PEC thumbnail is required | Rejection when omitted is **unconfirmed** | Official sources impose no byte-format requirement. DST can display a name without a thumbnail (USB manual p.2) |

### Format/community evidence and implementation choices

- [pyembroidery 1.5.1 PesWriter.py](https://github.com/EmbroidePy/pyembroidery/blob/1.5.1/pyembroidery/PesWriter.py)
  provides minimal v1 and full v6 paths. v6 writes `CEmbOne`/`CSewSeg`, a custom thread table,
  PEC data, and additional color information. This is implementation evidence, not SE400 certification.
- The community reverse-engineering reference [EduTech Wiki: PES](https://edutechwiki.unige.ch/en/Embroidery_format_PES)
  explains the roles of PES editing information and PEC sewing data, PEC compatibility between versions, and extra information from v4 onward.
  It alone cannot guarantee that a particular SE400 firmware accepts v6.
- myembro v6 ports the full header/sewing-object structure from the above pyembroidery version.
  Both v1/v6 write 48×38 1-bit overall/per-color PEC thumbnails.
  PEC's 64 unique threads and 255 blocks including STOP are **writer-format limits**, not SE400 color limits.
- The default stored hoop for v6 is 100×100mm, matching pyembroidery. A design actually exceeding 100×100
  does not become suitable merely because of this header; Preflight warnings must be checked.

### Integrator: check before export and select the version

The engine's `io.PesWriteSettings` and `export.MachineProfile` are independent of app settings/UI.
The existing app `ui.machines.MachineProfile` has the same name; use import aliases wherever both appear.
The app's default-output-version selection UI, settings persistence, and warning display were not wired in this change.

```kotlin
val settings = PesWriteSettings(PesVersion.V6) // 앱에서 저장한 선택값; 미지정 기본값은 기존 V1
val warnings: List<String> = Preflight.check(pattern, "design.pes", MachineProfile.SE400)
// warnings를 한국어로 표시한 후 내보내기 진행 여부를 사용자가 선택하도록 연결
val bytes = PesWriter.write(pattern, settings) // 또는 PesWriter.writeV6(pattern)
// 실제 저장 바이트의 경계/명령을 확인하려면 PesReader.read(bytes)도 검사 가능
```

`Preflight.check` checks the STITCH count through the first END, bounds including movements, recommended filename
characters, extension, folders, and the number of designs in the transfer batch, returning a list of Korean warnings.
It does not automatically shrink, delete, or convert filenames. `patternCount` is the destination batch count,
including existing designs. Pass total usage as `destination = ExportDestination.INTERNAL_MEMORY, totalBytes = ...`
only when saving to internal memory. The 512KB limit is not applied to PC-transferred file sizes. Unconfirmed
color counts, PES name lengths, and version support are not judged. Project hoop-relative position overflow
must also be checked with the existing `Bounds.outside(project.hoop)`.

`PesExport.prepare(data, fileName, settings)` applies the selected version when converting PEC/DST;
files already in PES are passed through byte-for-byte to preserve editing information. To regenerate PES as v6,
explicitly call `PatternReader.read` followed by `PesWriter.writeV6`. This does not restore high-level vector editing objects.

### Physical SE400 verification required

Engine round trips and pyembroidery cross-checks passed, but **recognition/sewing on a physical SE400 is untested**.
Transfer the same small design within 100×100 as both v1/v6 and check list display, orientation, thread order,
STOP, jumps, and trims. In particular, preserving a file's TRIM command does not imply SE400 automatically cuts thread.
The default output version is not forcibly changed to v6 before physical verification.

## Inch formatter and position-data integration notes

`ui.format.Dimensions.length/size` converts measured mm→in using 25.4. Exact ¼·½·¾ values are shown as fractions;
others use one decimal place with HALF_UP. `hoopSize` uses confirmed manufacturer nominal sizes.
Applied to Home, new projects, Settings, the viewer, and the library; `DimensionsTest` checks conversion/rounding.
**embro-editor**: use `length` for X/Y and `size` for object dimensions.
**embro-letter**: use `length` for character height. Neither owner's screen files were changed.

Only `engine.edit.HoopSpec.positions: List<Bounds>` was added as a data contract. Coordinates are relative to the
project center in 0.1mm units; each Bounds is the sewing rectangle for a position. An empty list means no verified
position layout exists. **embro-model**: convert only confirmed positions to SewingPosition and wire position round trips
in ProjectJson. SA434's position list is currently empty; do not use it for automatic splitting. Existing project APIs
and constructors remain compatible through defaults. Position-splitting logic and editor files were not modified in this commit.

## Custom frames and unrestricted models (addition)

Settings allows frame names, widths, and heights in mm/in to be entered, saved, edited, and deleted.
Inch input is also stored using **25.4mm/in**, with 0.1mm HALF_UP rounding. The UI input range (0.1~10,000mm)
prevents input errors; it is not a machine specification. Settings reference the default frame by ID; deleting it reverts
to 100×100. Dimensions stored in existing `.myembro` projects are not changed.

`MachineProfiles.FREE` is “Other (unrestricted).” It does not claim support for unconfirmed capabilities and does not
enforce machine area, stitch-count, or file-count limits. Actual selected-frame boundaries and unconfirmed-compatibility
warnings remain. Custom frames and unsupported presets are all selectable, without enlarging known machines' sewing areas.

### Integrator: exact-size frame path

- `SettingsPreferences.defaultEditorHoop()` returns a **HoopSpec with the exact selected size**, including custom frames.
- `editorHoops()` includes all selectable frames, including user frames. `machineProfile()?.supports(spec)` determines a warning, not whether selection is allowed.
- Connect `HomeScreen(onNewProjectFrame = { spec -> newProject(spec) })`. NewProjectSheet also adds
  `onBlankFrame: ((HoopSpec) -> Unit)?`, retaining the existing `(Hoop)->Unit` callback.
  **Custom rows in the creation sheet are not shown until the existing host provides the new callback.**
  This compatibility behavior prevents an exact-size frame from being incorrectly replaced with 100×100 or an integer-mm preset at creation.
- The initial lettering hoop in MainActivity must also come from `settings.preferences.defaultEditorHoop()` or
  `settings.settings.defaultFrame.spec`. The existing `defaultHoop.spec()` is for presets only.
- Before export, use `settings.checkFrameExport(pattern, filename, project.hoop)` or
  `Preflight.check(pattern, filename, settings.exportProfile(project.hoop))`.
  Known models are checked against `min(선택 프레임, 기종 최대 단일 영역)`; unrestricted/unconfirmed models
  use the frame size unchanged. Apply SE400's verified limits only to SE400.
- The viewer and library already use custom-frame dimensions directly. Editor, MainActivity, and engine Preflight sources
  were not modified in this change. The three host connections above belong to the integrator.

`CustomFrameTest` checks mm/in input, precision, recovery from corrupted stored values, the selected size after edits/deletion,
and Preflight size contracts for unrestricted and confirmed models. An expanded Brother specification table has not yet been
provided, so no arbitrary models were added. Separate profiles will be added after receiving the official table's confirmed values.

After integrating the latest main, `export/ExportChecks` was also wired to `settings.exportProfile()`.
Selected-frame dimensions now affect the existing library/viewer export paths. Use the integration contract above to pass the editor project's own hoop.

### Post-QA integration status (2026-10-22)

MainActivity's `onNewProjectFrame`, lettering's `defaultFrame.spec`, and ProjectJson position round trips are integrated into the latest main.
**Further embro-editor changes are required**: currently, `EditorViewModel.constrainHoop` replaces requests not in
`editorHoops(profile)` with the first hoop, and `editorExportProblems` blocks unsupported frames. This conflicts with the
policy of permitting unsupported frames with warnings. Menus must use `settings.availableEditorHoops` and preserve the requested
custom frame. Show support only as a warning, separate from Preflight results for actual project boundaries/machine area.
An empty official hoop list for an unrestricted model is not a reason to substitute 100×100. Editor-owned files were not edited directly.


## Direct SE400 USB connection — user measurements (2026-10-23)

The following are **user-provided measurements for SE400 + Samsung Fold7**, not official Brother manual specifications.
They are not generalized to other Android devices or machines.

- SE400's USB-B port acts as USB mass storage, and Windows can copy files normally.
- Fold7 Android does not mount the volume, so it does not appear in the SAF folder picker.
- A separate paid app recognizes volume name **NO NAME**, filesystem **Microsoft FAT**, and displayed capacity **476.0 kB**.
  The user reported it as a FAT12 RAM disk. Rather than infer the format from displayed capacity and write to it,
  myembro checks the actual BPB and data-cluster count to confirm FAT12.
- It is a **volatile RAM disk** whose files disappear when the machine is powered off.
- Actual VID/PID and whether multiple LUNs exist are **unconfirmed**.
  The additional diagnostic records below confirm logical sector size, MBR/BPB, and capacity. **In alpha26, the user confirmed
  direct USB transfer and that the design opens normally on SE400.** Actual sewing remains **unconfirmed**.

The implementation combines the pure Kotlin filesystem in `engine/fat12` with libaums 0.10.0's BOT/SCSI block driver
in `usb/DirectUsbAccess`. When no SAF drive is available, a connected class 8 / subclass 6 / protocol 80 interface is
exposed as a “Direct SE400 connection” drive. Actual writes require USB permission and a volume whose FAT12 BPB was
successfully verified. Filenames use uppercase ASCII 8.3; collisions are avoided with `~1`, `~2`, etc., and no LFN is
created (direct USB export naming policy). There are no formatting, partition-creation, or automatic-repair APIs.
All FAT copies are updated, and each write and the final file are compared through physical reads.

If space or root entries are insufficient, processing stops **before writing** and shows the existing `.PES` list.
Nothing is selected by default; retry requires confirmation to delete files the user checked. Deletion is refused if volume
metadata changed after the confirmation dialog. Files are not automatically deleted, nor are same-name existing files overwritten.

Integration structure, verification commands, and the physical-check sequence are in the [SE400 direct USB implementation notes](se400-direct-usb.md).

### Additional measurements, 2026-10-23: firmware-recreated RAM disk and alpha14 diagnostics

The user reported that powering SE400 off and on empties its files, and formatting or assigning a volume name in Windows
is undone at the next power-on. It is treated as a RAM disk whose boot sector/FAT are synthesized by firmware at power-on.
This behavior is **user-measured**; actual OEM, jump, and media bytes and BPB/READ CAPACITY discrepancies remain **unconfirmed**.
myembro continues to **perform no formatting, volume-name changes, boot-sector modifications, or automatic repair**.

The physical-device error `MBR partition out of device bounds` is handled as follows.

- Both superfloppy and MBR mount-failure reasons are preserved. If a `0xEB`/`0xE9` jump and plausible BPB are present,
  bytes 446–509 are not interpreted as an MBR. The first valid MBR entry must have boot flag `0`/`0x80`,
  type `0x01` (FAT12), a valid start within the device, and positive length. If its length exceeds the remaining actual
  device range, it is clamped to that range with a warning.
- Nonstandard jump/media bytes and BPB/FAT media mismatches are warnings. OEM is not a validation condition.
  If BPB total sectors exceed the actual device/partition, access is limited to the smaller range. The original BPB is
  not written; checks remain for the declared layout's FAT12 cluster count/FAT size, metadata and cluster bounds within
  the actual device, and file chains. FAT-copy differences are warnings under the v3 policy below.
- **Copy/share diagnostics** in the USB failure dialog provides text with INQUIRY vendor/product/revision received during
  existing SCSI initialization; raw READ CAPACITY values, block count, and block size; hex for sector0's first 512 bytes;
  BPB numeric fields; and candidate MBR entries. Unread values are marked unconfirmed. No separate diagnostic write
  commands are sent. Causes of actual nonstandard fields are examined after the user shares the text.

### alpha20 device diagnostics: MBR length mismatch

User diagnostics show sector0 is an MBR without a BPB; its first partition has **boot=0x80, type=0x01,
start=32(0x20), sectors=987(0x3DB)**. READ CAPACITY confirms **lastLBA=987, blockCount=988**.
The declared partition end, 1019, exceeds the actual device end, 988. These are user device diagnostics;
the actual BPB contents at LBA 32 remain **unconfirmed**.

The MBR length is clamped to `988−32=956` sectors with a warning. If BPB total sectors also exceed this range,
access is reduced accordingly; writing is blocked if metadata, complete data clusters, or existing file chains do not fit.
The MBR start must still satisfy `0 < start < blockCount`. MBR, BPB, and volume names are not rewritten or formatted.

The next failure diagnostics show the first 512 bytes in hex and the decoded BPB from the **partition start sector (LBA 32)**
separately from sector0. Bytes already read at mount time are saved before parsing, so BPB parsing failures can be examined
without additional USB read commands. Values whose read failed are marked unconfirmed.

### Device diagnostics v2 → v3: actual BPB and FAT reserved entries

In user diagnostics v2, the BPB at LBA 32 was confirmed as `EB 3C 90`, sector size **512**, **8 sectors** per cluster,
**1** reserved sector, **2** FAT copies, **512** root entries, TotalSectors16 **987**, media **F8**,
SectorsPerFAT **1**, hidden sectors **32**, label **NO NAME**, and FAT12.
Thus FAT1 starts at LBA **33**, FAT2 at **34**, root at **35..66**, and data at **67**.
Actual FAT reserved-entry bytes and differences between the copies are checked in the following v3 report.

There are `(987−35)/8=119` declared data clusters, but only `(988−67)/8=115` fit completely within the device.
The declared `119×4096=476KiB` matches the user's 476.0kB display, but myembro limits allocatable space to **115 clusters**.
FAT standard IDs start at 2, so valid IDs are **2..116**; the four out-of-range slots are **117..120**
(data slots numbered from 1 would be slots 116..119). The last complete cluster is LBA **979..986**;
the remaining single sector at LBA **987** receives no cluster allocation.

- Nonstandard `FAT[0]`/`FAT[1]` reserved entries produce warnings only; FAT1's existing reserved bytes are not normalized.
  Both `00 00 00` and `F8 FF FF` are accepted, and original FAT1 bytes are retained.
- Copy differences generate warnings; reads and allocation use FAT1. Mounting does not modify the disk.
  When the user writes a file, a new FAT1-based table is written identically to both copies.
- If either copy has a used value at ID 117..120, writing is blocked beforehand, recording copy, ID, and value in diagnostics.
  Writing is also blocked if copies change after mounting.
- Diagnostics v3 adds **the first 64 bytes of FAT1/FAT2** and **512 bytes of the first root sector** to the existing
  sector0/LBA32 data. Data read at mount is retained before verification; no extra USB read command is issued.
  The snapshot precedes file writing. No formatting, label changes, or automatic reserved-entry correction is performed.

## Sew-out Check: distinguishing cutting/speed facts from unconfirmed details (2026-10-23)

The blanket statement “SE400/SE600 has no automatic thread cutting” is inaccurate. The following two operations are distinct.

| Operation | SE400 | SE600 | SE700 |
| --- | --- | --- | --- |
| Automatic cutting at the end of a color stage | Works when configured, p.140 | Works when configured, p.66 | Works when configured, p.70 |
| Jump threads within a color | Specific automatic cutting conditions unconfirmed | Manual cleanup guidance p.66 | Manual cleanup guidance p.71 |
| Automatic jump cutting guaranteed by PES TRIM alone | unconfirmed | unconfirmed | unconfirmed |
| Maximum embroidery speed | unconfirmed in this survey | unconfirmed in this survey | 400 SPM, p.96 |

Sources are the printed pages in the [official SE400 manual](https://download.brother.com/welcome/doch000184/885v31_v32_v33_om04en.pdf),
[official SE600 manual](https://download.brother.com/welcome/doch101011/888m20_om02en.pdf), and
[official SE700 manual](https://download.brother.com/welcome/doch102582/888p10_om01en.pdf).
They explicitly state that cutting at the end of a color stage depends on settings. SE600/SE700 separately instruct the user
to cut remaining connecting threads within the same color. These statements are not expanded into “the machine stops at every jump”
or “there is no cutter at all.” The engine currently treats detailed automatic jump-cutting conditions as unconfirmed and plans
manual work where official manual-cleanup guidance exists. For SE400, specific jump conditions are unconfirmed, so a conditional
manual check is shown. Actual machine settings must be entered by the user.

The SE700 p.96 specification table distinguishes sewing speed 70~710 from **maximum embroidery speed 400**.
SE400/SE600 maximum embroidery SPM has not been inferred from this or other models and stored as confirmed.
If an assumed planning speed is used for an unconfirmed model, its evidence is also returned as ASSUMED.
Meters per bobbin remain unconfirmed for all three models because thread weight/wound amount has not been established.
Per-file capacity, supported PES versions, and maximum colors retain their existing unconfirmed status.
Total internal-memory capacity is not applied as a per-file limit.

The related pure JVM APIs, rules, and estimation formulas are in [sewout-check.md](sewout-check.md).
This profile is independent of the app UI profile; UI integration is a separate embro-editor task.

### alpha26 user confirmation and transfer-result records (2026-10-23)

The user confirmed successful direct SE400 USB transfer and that the design opens normally on the machine.
This does not confirm sewing results or support for other machine/phone combinations.

Transfer results remain in a result window until dismissed, summarizing the actual transferred filename (8.3 for direct USB),
PES byte size, and remaining space. Unconfirmed free space (e.g. a session interrupted by failure) is “unknown.”
The measured SE400 batch's FAT reserved entries `00 00 00` and resulting media-byte mismatch, MBR/BPB length adjustment,
and 119→115 cluster restriction are preserved under “Details”/diagnostic copy without user warnings.
Other batches and other nonstandard values are not ignored indiscriminately. A new-name notice is shown for name collisions;
insufficient space and write-verification failures are shown to the user. “Recent USB transfer history” at the top of the
library permits viewing/copying time, actual files, results, and diagnostics for up to the latest 20 transfers, retained in
internal app storage. Formatting, volume-name-change, and automatic-deletion policies are unchanged.

### Actual file size and disk allocation (2026-10-23)

The user reported an actual file size of **10,120 bytes** for `EMBROIDE.PES`, with **12K used** on SE400's screen.
This matches 3 clusters of 4,096 bytes = **12,288 bytes**. The result window and recent-transfer history show
“File 10KB · Disk usage 12KB (4KB units)” together. KB here means 1,024 bytes; short displays are rounded and exact bytes
are retained in diagnostics. Allocation is calculated by rounding each file up to whole clusters and then summing.
Unknown allocation for SAF and older records is not estimated.

Remaining space has always been computed by `Fat12Volume.freeBytes`, counting **free clusters among actual usable FAT IDs 2..116**.
That is, `115 × 4096 − 사용 중인 클러스터 × 4096`. In the reproduction with an otherwise empty disk containing only 1 file,
it is `471040 − 12288 = 458752바이트(448KB)`. FAT/root/boot-sector metadata is already outside the data pool;
other files and allocated clusters also reduce free space. The actual **remaining-capacity value on the machine screen is still unconfirmed**,
so no arbitrary adjustment is made to match that display.

Filename-path investigation: the actual imported filename → `DesignFormats.preparePes` → `ShortNames.unique`, which took
the first 8 characters of the cleaned stem. The app's default filename is `design` or its Korean equivalent; there is no code adding
`EMBROIDERY` for USB export. The PES thread brand `EMBROIDERY` is not used for filenames either.
Thus an input filename starting with that prefix becomes `EMBROIDE.PES`. The file's original full name was not provided,
so its individual upstream origin could not be established.

Direct export now removes common leading labels (embroidery/design/pattern/sample, etc.) from the original design name,
producing `LEMON.PES`, or `LEMON~1.PES` on collision. If a filename consists only of a generic label (including the already
truncated `EMBROIDE`), actual design metadata `name` is used as a fallback; if that is absent too, `DESIGN.PES` is used.
The original name and actual transferred name are both retained in diagnostics; existing files are not overwritten.

## SE400 jump-thread cutting — user sew-out confirmation (2026-10-24)

The user confirmed on a physical SE400 that **jump threads are not cut automatically and must be cut with scissors**.
The source is the user's sew-out report for this task, not an official specification.
The same source is recorded in `SewoutMachines.SE400.automaticJumpTrim=false`, `manualJumpCleanup=true`.
The end-of-color cutter/cutter button and automatic jump-thread cutting are separate capabilities and are distinguished
from existing evidence about the end-of-color cutter.

Display data is `SewoutReport.statistics.manualJumpThreadCount` and the transferred file/history's `manualJumpThreadCount`.
A movement split across multiple JUMPs counts as 1 needle→movement→next-needle connection.
Initial positioning, 0-length movements, final movements without a subsequent needle, and movements between color stages are excluded.
A TRIM command alone is not assumed to mean the thread was physically cut. Unconfirmed capability/older records are null, not disguised as 0.
No actual guidance line or wording was added in this change. PES bytes and stitch generation are unaffected.

Data regression validation: `:engine:test --tests '*JumpThreadCleanupTest' --tests '*SewoutCheckTest'`
17 cases, `:app:testDebugUnitTest --tests '*UsbTransfer*Test'` 12 cases, 0 failures. APK build succeeded.
Checks cover a split JUMP counted 1 time; exclusion of initial/0-length/final movements; STOP/color boundaries; TRIM requests;
unconfirmed values; and compatibility with older records. Local log: `/tmp/myembro-manual-jump-final.log`.

## United States and Korea catalog (verified 2026-10-28)

58 manufacturer entries are selectable: 30 United States and 28 Korea. Regional versions and platform siblings remain distinct IDs. The default and existing stored strings `Brother SE400`, `Brother SE600`, `Brother SE700` are unchanged. All values below use **width×height in mm**, not the frequently used manual **H×W** ordering. The maximum is a **single continuous sewing field**, not repositionable fabric coverage. Nominal inch labels are not multiplied by 25.4 to derive these metric fields.

Only confirmed facts are stored. A blank capability is `null`/unconfirmed, not SE400 inheritance, unlimited capacity, or proof of lack of support. Listed transfer methods are the confirmed subset, not a claim that no other route exists. Automatic **jump** trimming is separate from a color-end/thread-cutter button and from whether trimming is currently enabled.

All >100×100 profiles display **Not verified on a physical machine / 실제 기계 미확인**. Larger HoopSpec values feed the same existing P1–P8 writer/sewout checks; app dimension checks allow at most `widthUnits−1`, `heightUnits−1` (999 for 100mm, 1299/1799 for 130×180mm). These are specification-based profiles, not physical transfer/sewing certifications.

### United States

| Model (stable ID) | Maximum W×H mm | Confirmed continuous hoops W×H mm | Confirmed transfer | Automatic jump trim | Sources |
|---|---|---|---|---|---|
| Brother SE400 (`se400`) | 100×100 | 100×100, 60×20; coverage 100×170 (repositioning) | USB cable → PC | No | [source 1](https://download.brother.com/welcome/doch000184/885v31_v32_v33_om04en.pdf), [source 2](https://download.brother.com/welcome/doch000186/885v31_v32_usb02_usenes.pdf) |
| Brother SE600 (`se600`) | 100×100 | 100×100, 60×20; coverage 100×170 (repositioning) | USB flash drive | No | [source 1](https://download.brother.com/welcome/doch101011/888m20_om02en.pdf) |
| Brother SE700 (`se700`) | 100×100 | 100×100, 60×20; coverage 100×170 (repositioning) | USB flash drive / Brother wireless | No | [source 1](https://download.brother.com/welcome/doch102582/888p10_om01en.pdf) |
| Brother SE425 (`se425`) | 100×100 | 100×100 | USB cable → PC | No | [source 1](https://download.brother.com/welcome/doch000184/885v31_v32_v33_om04en.pdf), [source 2](https://www.brother-usa.com/p/sewing-embroidery/SE425) |
| Brother SE625 (`se625`) | 100×100 | 100×100 | USB flash drive | No | [source 1](https://download.brother.com/welcome/doch101011/888m20_om02en.pdf), [source 2](https://www.brother-usa.com/p/sewing-embroidery/SE625) |
| Brother SE630 (`se630`) | 100×100 | 100×100 | USB flash drive | No | [source 1](https://download.brother.com/welcome/doch101011/888m20_om02en.pdf), [source 2](https://www.brother-usa.com/p/sewing-embroidery-combo/SE630) |
| Brother SE725 (`se725`) | 100×100 | 100×100 | USB flash drive / Brother wireless | No | [source 1](https://download.brother.com/welcome/doch102582/888p10_om01en.pdf), [source 2](https://www.brother-usa.com/p/sewing-embroidery-combo/SE725) |
| Brother PE535 (`pe535`) | 100×100 | 100×100 | USB flash drive | No | [source 1](https://download.brother.com/welcome/doch101288/888m36_m37_om01en.pdf), [source 2](https://www.brother-usa.com/p/sewing-embroidery/PE535) |
| Brother PE550D (`pe550d`) | 100×100 | 100×100 | USB flash drive | No | [source 1](https://download.brother.com/welcome/doch101288/888m36_m37_om01en.pdf), [source 2](https://www.brother-usa.com/p/sewing-embroidery/PE550D) |
| Brother PE545 (`pe545`) | 100×100 | 100×100 | USB flash drive / Brother wireless | No | [source 1](https://download.brother.com/welcome/doch102611/888p30_om01en.pdf), [source 2](https://www.brother-usa.com/p/sewing-embroidery/PE545) |
| Brother NS1750D (`ns1750d`) | 100×100 | 100×100 | USB flash drive | No | [source 1](https://download.brother.com/welcome/doch101020/888_m00_m02_m03_m04_om02en.pdf), [source 2](https://www.brother-usa.com/p/sewing-embroidery-combo/NS1750D) |
| Brother PE770 (`pe770`) | 130×180 | 130×180 | USB flash drive | **unconfirmed** | [source 1](https://www.brother-usa.com/p/sewing-embroidery-combo/PE770) |
| Brother PE800 (`pe800`) | 130×180 | 130×180, 100×100, 60×20 | USB flash drive | **unconfirmed** | [source 1](https://download.brother.com/welcome/doch100741/888l90_l91_l93_k90_k92_om03en.pdf), [source 2](https://www.brother-usa.com/p/embroidery/PE800) |
| Brother SE1900 (`se1900`) | 130×180 | 130×180 | USB flash drive / Brother wireless | **unconfirmed** | [source 1](https://www.brother-usa.com/p/sewing-embroidery/SE1900) |
| Brother SE2000 (`se2000`) | 130×180 | 130×180 | USB flash drive / Brother wireless | Yes (capability) | [source 1](https://www.brother-usa.com/p/sewing-embroidery/SE2000) |
| Brother PE900 (`pe900`) | 130×180 | 130×180 | USB flash drive / Brother wireless | Yes (capability) | [source 1](https://www.brother-usa.com/p/sewing-embroidery/PE900) |
| Brother NS2750D (`ns2750d`) | 130×180 | 130×180, 100×100, 60×20 | **unconfirmed** | **unconfirmed** | [source 1](https://download.brother.com/welcome/doch100730/888l01_l03_k00_02_om03en.pdf), [source 2](https://www.brother-usa.com/p/sewing-embroidery-combo/NS2750D) |
| Brother NQ1400E (`nq1400e`) | 160×260 | 160×260 | USB flash drive | **unconfirmed** | [source 1](https://www.brother-usa.com/p/sewing-embroidery/NQ1400E) |
| Brother NQ1600E (`nq1600e`) | 160×260 | 160×260 | USB flash drive | Yes (capability) | [source 1](https://www.brother-usa.com/p/sewing-embroidery/NQ1600E) |
| Brother NQ1700E (`nq1700e`) | 160×260 | 160×260 | USB flash drive / Brother wireless | Yes (capability) | [source 1](https://www.brother-usa.com/p/sewing-embroidery/NQ1700E) |
| Brother NQ3550W (`nq3550w`) | 160×260 | 160×260 | USB flash drive / Brother wireless | Yes (capability) | [source 1](https://www.brother-usa.com/p/sewing-embroidery-combo/NQ3550W) |
| Brother XE1 (`xe1`) | 240×360 | 240×360 | USB flash drive / Brother wireless | Yes (capability) | [source 1](https://www.brother-usa.com/p/sewing-embroidery-combo/XE1) |
| Brother XJ1 (`xj1`) | 240×360 | 240×360, 130×180, 100×100 | USB flash drive / Brother wireless | **unconfirmed** | [source 1](https://download.brother.com/welcome/doch102121/882w70_om02ko.pdf), [source 2](https://www.brother-usa.com/p/sewing-embroidery-combo/XJ1) |
| Brother XP1 (`xp1`) | 272×408 | 272×408, 240×240, 130×180, 100×100 | USB flash drive | **unconfirmed** | [source 1](https://www.brother-usa.com/p/sewing-embroidery-combo/XP1), [source 2](https://support.brother.com/g/b/faqend.aspx?c=us&lang=en&prod=hf_inovxp3eus&faqid=faqh00101388_000) |
| Brother XP2 (`xp2`) | 272×408 | 272×408, 240×240, 130×180, 100×100 | Brother wireless | **unconfirmed** | [source 1](https://www.brother-usa.com/p/sewing-embroidery-combo/XP2), [source 2](https://support.brother.com/g/b/faqend.aspx?c=us&lang=en&prod=hf_inovxp3eus&faqid=faqh00101388_000) |
| Brother XP3 (`xp3`) | 272×408 | 272×408, 240×240, 130×180, 100×100 | USB flash drive / Brother wireless | **unconfirmed** | [source 1](https://www.brother-usa.com/p/sewing-embroidery-combo/XP3), [source 2](https://support.brother.com/g/b/faqend.aspx?c=us&lang=en&prod=hf_inovxp3eus&faqid=faqh00101388_000) |
| Brother EV1 (`ev1`) | 297×465 | 297×465, 272×272, 130×180, 100×100 | USB flash drive / Brother wireless | Yes (capability) | [source 1](https://www.brother-usa.com/p/sewing-embroidery-combo/EV1) |
| Brother PR680W (`pr680w`) | 300×200 | 300×200, 180×130, 100×100 | USB flash drive / Brother wireless | Yes (capability) | [source 1](https://download.brother.com/welcome/doch102285/884t23_om01en.pdf), [source 2](https://www.brother-usa.com/p/sewing-embroidery/PR680W) |
| Brother PR1050X (`pr1050x`) | 360×200 | 360×200, 180×130, 100×100 | USB flash drive | **unconfirmed** | [source 1](https://download.brother.com/welcome/doch100893/pr1050x_om01ko.pdf), [source 2](https://www.brother-usa.com/p/sewing-embroidery/PR1050X) |
| Brother PRS100 (`prs100`) | 200×200 | 200×200 | USB flash drive / Brother wireless | Yes (capability) | [source 1](https://www.brother-usa.com/p/sewing-embroidery/PRS100) |

### Korea

| Model (stable ID) | Maximum W×H mm | Confirmed continuous hoops W×H mm | Confirmed transfer | Automatic jump trim | Sources |
|---|---|---|---|---|---|
| M370 (`kr-m370`) | 100×100 | 100×100, 60×20 | USB flash drive / Brother wireless | No | [source 1](https://download.brother.com/welcome/doch102712/888_p00_p02_p03_om01ko.pdf) |
| M380D (`kr-m380d`) | 100×100 | 100×100, 60×20 | USB flash drive / Brother wireless | No | [source 1](https://download.brother.com/welcome/doch102712/888_p00_p02_p03_om01ko.pdf) |
| LB7950 (`kr-lb7950`) | 100×100 | 100×100 | USB flash drive / Brother wireless | No | [source 1](https://download.brother.com/welcome/doch102717/888p10_om01ko.pdf) |
| LB8950 (`kr-lb8950`) | 100×100 | 100×100 | USB flash drive / Brother wireless | No | [source 1](https://download.brother.com/welcome/doch102717/888p10_om01ko.pdf) |
| LB6950 (`kr-lb6950`) | 100×100 | 100×100; coverage 100×170 (repositioning) | USB flash drive | No | [source 1](https://download.brother.com/welcome/doch101678/888m20_om01ko.pdf) |
| 이노비스 180 (NV180) (`kr-nv180`) | 100×100 | 100×100; coverage 100×170 (repositioning) | USB flash drive | No | [source 1](https://download.brother.com/welcome/doch101131/888_m00_m02_m03_m04_om02ko.pdf) |
| 이노비스 180D (NV180D) (`kr-nv180d`) | 100×100 | 100×100; coverage 100×170 (repositioning) | USB flash drive | No | [source 1](https://download.brother.com/welcome/doch101131/888_m00_m02_m03_m04_om02ko.pdf) |
| 이노비스 180K (NV180K) (`kr-nv180k`) | 100×100 | 100×100; coverage 100×170 (repositioning) | USB flash drive | No | [source 1](https://download.brother.com/welcome/doch101131/888_m00_m02_m03_m04_om02ko.pdf) |
| 이노비스 900 (NV900) (`kr-nv900`) | 100×100 | 100×100 | USB cable → PC | No | [source 1](https://download.brother.com/welcome/doch000555/inov900dug03en.pdf) |
| 이노비스 900D (NV900D) (`kr-nv900d`) | 100×100 | 100×100 | USB cable → PC | No | [source 1](https://download.brother.com/welcome/doch000555/inov900dug03en.pdf) |
| 이노비스 950 (NV950) (`kr-nv950`) | 100×100 | 100×100 | USB flash drive | No | [source 1](https://download.brother.com/welcome/doch100602/nv950_980om02ko.pdf) |
| 이노비스 980D (NV980D) (`kr-nv980d`) | 100×100 | 100×100 | USB flash drive | No | [source 1](https://download.brother.com/welcome/doch100602/nv950_980om02ko.pdf) |
| 이노비스 980K (NV980K) (`kr-nv980k`) | 100×100 | 100×100 | USB flash drive | No | [source 1](https://download.brother.com/welcome/doch100602/nv950_980om02ko.pdf) |
| F580 (`kr-f580`) | 130×180 | 130×180, 100×100 | USB flash drive / Brother wireless | Yes (capability) | [source 1](https://download.brother.com/welcome/doch102702/888_k10_l11_l12_om01ko.pdf) |
| 이노비스 NS2750D (`kr-ns2750d`) | 130×180 | 130×180, 100×100, 60×20 | USB flash drive | **unconfirmed** | [source 1](https://download.brother.com/welcome/doch100730/888l01_l03_k00_02_om03en.pdf) |
| 이노비스 1250 (NV1250) (`kr-nv1250`) | 130×180 | 130×180, 100×100 | USB flash drive | **unconfirmed** | [source 1](https://download.brother.com/welcome/doch000589/nv1250ug02en.pdf) |
| 이노비스 2600 (NV2600) (`kr-nv2600`) | 160×260 | 160×260, 130×180, 100×100, 60×20 | USB flash drive | **unconfirmed** | [source 1](https://download.brother.com/welcome/doch100373/NV2600_om01ko.pdf) |
| 이노비스 2700 (NV2700) (`kr-nv2700`) | 160×260 | 160×260, 130×180, 100×100 | USB flash drive / Brother wireless | Yes (capability) | [source 1](https://download.brother.com/welcome/doch102458/888g30_g32_g34_om01ko.pdf) |
| 이노비스 4000D (NV4000D) (`kr-nv4000d`) | 180×300 | 180×300, 130×180, 100×100, 60×20 | USB flash drive | Yes (capability) | [source 1](https://download.brother.com/welcome/doch000013/nv4000ug04en.pdf) |
| 이노비스 V3 (`kr-v3`) | 180×300 | 180×300, 130×180, 100×100 | USB flash drive | Yes (capability) | [source 1](https://download.brother.com/welcome/doch100272/v3om01ko.pdf) |
| 이노비스 V3LE (`kr-v3le`) | 200×300 | 200×300, 200×200, 130×180, 100×100 | USB flash drive | Yes (capability) | [source 1](https://download.brother.com/welcome/doch101849/882d82_om01ko.pdf) |
| 이노비스 V5 (`kr-v5`) | 180×300 | 180×300, 130×180, 100×100 | USB flash drive | Yes (capability) | [source 1](https://download.brother.com/welcome/doch100271/v5om01ko.pdf) |
| 이노비스 V5LE (`kr-v5le`) | 200×300 | 200×300, 200×200, 130×180, 100×100 | USB flash drive | Yes (capability) | [source 1](https://download.brother.com/welcome/doch101707/882c53_om01en.pdf) |
| 이노비스 XJ1 (`kr-xj1`) | 240×360 | 240×360, 130×180, 200×300, 240×240, 200×200, 100×100 | USB flash drive / Brother wireless | Yes (capability) | [source 1](https://download.brother.com/welcome/doch102121/882w70_om02ko.pdf) |
| PR680W (한국) (`kr-pr680w`) | 300×200 | 300×200, 180×130, 100×100 | USB flash drive / Brother wireless | Yes (capability) | [source 1](https://download.brother.com/welcome/doch102285/884t23_om01en.pdf) |
| PR1050X (한국) (`kr-pr1050x`) | 360×200 | 360×200, 180×130, 100×100 | USB flash drive | Yes (capability) | [source 1](https://download.brother.com/welcome/doch100893/pr1050x_om01ko.pdf) |
| PR1060W (한국) (`kr-pr1060w`) | 360×200 | 360×200, 180×130, 100×100 | USB flash drive / Brother wireless | Yes (capability) | [source 1](https://download.brother.com/welcome/doch101931/884t15-16_om03_en.pdf) |
| PR1X (한국) (`kr-pr1x`) | 300×200 | 300×200, 180×130, 100×100 | USB flash drive | Yes (capability) | [source 1](https://download.brother.com/welcome/doch103024/882t52_om01ko.pdf) |

### Verification details and excluded models

- Selecting a new machine chooses its first confirmed hoop, including a model-specific frame absent from the legacy enum. SE400/SE600/SE700 continue to choose 100×100. Existing saved preset/custom IDs and machine strings are still read unchanged; no preference migration is required.
- SE600/SE700 automatic jump trimming is now explicitly `false`, based on the manual instruction to cut excess thread jumps within each color (printed pp.66 and 71). Their existing manual-cleanup capability is unchanged. User decision 2026-10-28 removes hand-cut/cleanup advice from app screens; these remain engine facts only. Other SE400/600/700 numerical, storage, transfer, and default values are retained.

- Source bundle: coordinator research dated 2026-10-28 and its official Korean/US manuals. Korean operation-manual accessory tables explicitly label H and W: NV1250 PDF pp.13–14, NV4000D p.16, V3 p.14, V3LE p.14, V5 pp.16–17, V5LE pp.16–18, XJ1 pp.19–20. PR manuals instead explicitly label W×H: PR680W/PR1060W p.15, PR1050X p.23, PR1X pp.54–55. PDF page numbers here count physical PDF pages, not printed page labels.
- F580: PDF pp.87,98,101; NV2700 pp.98,103: field lists are H×W, matching their small-frame H/W labels. NV2600 frame table includes 26×16, 18×13, 10×10, 2×6cm in H×W order. PE800 operation manual product code 888-L90/L91/L93/K90/K92 and its individual product page establish its field. NQ 6×10 models use their individual product pages plus the supplied operation-manual frame tables.
- XP1/XP2/XP3: [official FAQ](https://support.brother.com/g/b/faqend.aspx?c=us&lang=en&prod=hf_inovxp3eus&faqid=faqh00101388_000) explicitly lists all three and specifies H408×W272mm. EV1 individual product page carton contents explicitly lists H465×W297mm. PR long-side orientation is not rotated to match the domestic-machine convention.
- NV1250 optional H300×W130 frame is **not** a 130×300 continuous field: its manual limits one design to H180×W130 (PDF p.180). Position counts/offsets and continuous large-frame behavior are unconfirmed, so that optional frame is not added as a supported continuous hoop.
- NV900/NV900D and NV950/NV980D/NV980K: the manuals limit individual designs to 10×10cm. Optional EF71 H170×W100 coverage is not treated as an increased field; its exact repositioning behavior remains unconfirmed and is not added to these profiles.
- NS1750D wireless claims conflict between product page and manual; wireless remains unconfirmed. SE400 card-slot capabilities are not presented as USB flash-drive support. Generic numeric stitch/file/name limits are not inferred for any new sibling.
- **Not added (large models still lacking sufficiently verified individual maximum-field/orientation evidence in this pass): NV800E, Stellaire XE2, Stellaire XJ2.** NV800E research linked an embroidery design guide rather than its maximum-field operating specification; XE2/XJ2 individual product pages give nominal inches but their exact metric W×H operating-manual evidence was not verified. Generic unnamed Innov-is NS variants are not profiles; specifically confirmed NS2750D is included.
- Other unverified research candidates omitted: 이노비스 170, NQ3500D, NQ575PRW, NS1850L, Quattro 3, unspecified Disney editions and Dream Machine 2. NV1800Q, F410 and F420 are sewing-only and deliberately omitted.

### User-defined machine

Other (free) is an app-defined user option, not a Brother specification. Its source URL points to this policy section; it claims no manufacturer limits or capabilities. A selected physical hoop and the existing P1–P8 file/writer constraints still apply. Unknown custom machine names never resolve to SE400.

### Automated validation

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug
```

`MachineCatalogTest`: unique IDs/nonempty source URLs, app/engine dimensions, stable existing stored model names/default, distinct platform sibling profiles, 998/999/1000 boundaries for every 100×100 model, and PE800 W1298/1299/1300 × H1798/1799/1800. `MachineCatalogBoundaryTest`: all catalog maximum hoops run the same PES writer, viewport and SewoutCheck boundaries. Non-area findings are not suppressed or treated as test failures in the boundary-only assertions.

Latest run (2026-10-28): `BUILD SUCCESSFUL in 2m 39s`; engine 1,003 tests and app 459 tests, 0 failures/errors/skips; `:app:lintDebug` passed. Local full output: `/tmp/myembro-machines/check.log`.
