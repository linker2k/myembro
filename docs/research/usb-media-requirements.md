# USB media requirements: Brother machines, Android, myembro

Research only (2026-11-08). No user docs or code changed. Every claim is tagged:
**[official]** = Brother / AOSP / Samsung page, quoted; **[mirror]** = third-party copy of an official
Brother manual; **[forum/blog]** = not evidence, listed only so it is not mistaken for evidence.
Model-specific facts that could not be found are marked **unverified**.

## 1. Brother embroidery machines

### 1.1 Summary by family

| Family | File system | Capacity | Folders | Files per media | Names | Formats | Source |
|---|---|---|---|---|---|---|---|
| Current htmldoc manuals (two models, doc codes D03J2L-001 / D03J2U-001; the model is not named on the page) | FAT32 | "32 GB or less" recommended | unverified | unverified | A–Z a–z 0–9 `-` `_` recommended | .pes .phc .dst .pen | [B1], [B2] |
| EV1, EV1-LE, Innov-is BX1/BX1e | FAT32 **and NTFS** (Brother Japan FAQ) / FAT32 only (Brother USA) | "8 to 32 GB" recommended | unverified | unverified | — | — | [B3], [B4] |
| Innov-is XP1/XP2/XP3 | "FAT (FAT32)" only; "formatted with other file system cannot be used" | unverified | unverified | unverified | A–Z a–z 0–9 `-` `_` recommended | .pes .pha .phb .phc .dst .phx | [B5] |
| PE-780D, PE-770, SE1800, Innov-is 1250D, DZ820E, SB7900E, SB8000 | not stated | not stated | root **or one folder level** (requires firmware Ver1.10+) | **max 200 files + folders in total** | folder/.dst names: 8 chars shown, longer → 6 chars + `~n` | .pes .dst | [B6] |
| NV1250D (older generation; same manual style as the SE/PE of that era) | not stated | "any size" | **root only** | not stated | ≤8 chars shown in full (.dst only) | .pes .dst only | [M1] |
| 37 models incl. SE1900/SE1950/SE2000, PE800/PE900, NQ1600E/NQ1700E, NS…, PR1050X/1055X/1060W, XE1/XE2 | — | — | — | — | — | .pes .dst .phc .pen | [B7] |
| SE600/SE625/SE630/SE700/SE725/SE750, SE1900/1950/2000, PE800/PE900, NQ1700E … (64 models) | not stated | list contains drives of 1 GB up to 512 GB, some marked ✕ | — | — | — | — | [B8] |
| PR (multi-needle) | not found | not found | not found | not found | not found | (formats in [B7] for PR1050X/1055X/1060W) | PR650 manual page: only "Some USB media may not be usable with this machine" [M2] |
| SDX (ScanNCut) | — | — | — | — | — | .PES/.PHC/.PHX need software 1.86+ | [B9] (not an embroidery machine; listed for the PHX note) |

Not found for any family on an official page: **folder display depth beyond [B6]**, **files per folder**
(only the per-media 200 total in [B6]), **maximum file name length** (only the "8 chars shown" display
rule), **PES version per machine**, **FAT16/exFAT support**. These stay **unverified**.

Not found specifically for SE600/SE700/SE2000 and PE800/PE900 manuals: file system and folder rules.
They are on the compatibility list [B8], and the closest documented siblings ([B6], [M1]) say root
(or one folder) and 200 items. Treat "FAT32, root folder, ≤ 32 GB" as the common denominator of
official statements, not as a per-model guarantee.

### 1.2 PES version

- Brother USA [B10]: designs are backwards compatible but **not forward compatible** for PE-DESIGN
  software; PE-DESIGN offers to "save in an older PES version" for older machines [B11].
- No official per-machine PES version table was found. **Unverified.** Our own evidence: the SE400
  sews PES v1 files written by myembro (real-machine transfers since alpha26).

### 1.3 Quotes and URLs (official)

- [B1] Brother operation manual, Specifications, D03J2L-001 —
  "Supported formats: FAT32", "Recommended size: 32 GB or less", "Some USB media may not be usable
  with this machine.", Data types ".pes .phc .dst .pen".
  https://support.brother.com/g/s/hf/htmldoc/hsm/om/888p5x/en/10_01.html
- [B2] same wording, D03J2U-001. https://support.brother.com/g/s/hf/htmldoc/hsm/om/888p6x/en/10_01.html
- [B3] Brother FAQ "This USB media cannot be used" (EV1, EV1-LE, Innov-is BX1, BX1e) —
  "Supported formats: FAT32 and NTFS", "Recommended size: 8 to 32 GB".
  https://support.brother.com/g/b/faqend.aspx?c=us_ot&lang=en&prod=hf_ev1eus&faqid=faqh00000245_003
- [B4] Brother USA "USB media not working" (EV1, EV1-LE) — "Recommend size is 8 to 32 GB",
  "Supported formats: FAT32"; recommends newly formatted media. Conflicts with [B3] on NTFS.
  https://help.brother-usa.com/app/answers/detail/a_id/187320
- [B5] Brother FAQ (Innov-is XP1/XP2/XP3) — "FAT (FAT32) file system will be supported." A drive
  "formatted with other file system cannot be used"; SD/SDHC yes, "SDXC cards will not be available".
  https://support.brother.com/g/b/faqend.aspx?c=us&lang=en&prod=hf_inovxp1eus&faqid=faqh00101388_000
- [B6] Brother FAQ (PE-780D and related) — "Save embroidery data files in the root directory or
  folders on the root directory of the USB media." "If an embroidery data file is saved in a folder
  on a sub-directory, that file cannot be retrieved by the machine." Folders need "Ver1.10 or later".
  "The maximum total number of embroidery data files and folders you can save in a USB media is 200."
  https://support.brother.com/g/b/faqend.aspx?c=us&lang=en&prod=hf_pe780deus&faqid=faqh00000581_001
- [B7] Brother USA "Design format types that the machine will read" — .pes .dst .phc .pen, 37 models.
  https://help.brother-usa.com/app/answers/detail/a_id/123343/~/design-format-types-that-the-machine-will-read
- [B8] Brother "USB Media Compatibility List", revised April 2023 (64 models incl. SE600–SE750,
  SE1900–SE2000, PE800, PE900, NQ1700E). Per-drive ✓/✕ only; no file-system note in the text.
  https://download.brother.com/welcome/doch100800/usbmedia_888k0x_k1x_k8x_k9x_l0x_l1x_l8x_l9x_j9x_r23-04.pdf
- [B9] Brother USA SDX "embroidery data … not displayed" — software 1.86+.
  https://help.brother-usa.com/app/answers/detail/a_id/161902
- [B10] https://help.brother-usa.com/app/answers/detail/a_id/79899/~/i-cannot-open-my-pes-design-on-my-new-software-version
- [B11] https://help.brother-usa.com/app/answers/detail/a_id/71556/~/how-to-save-the-design-in-a-.pes-format-to-embroider-on-the-machine

### 1.4 Mirrors of official manuals

- [M1] NV1250D operation manual p.180 (manualslib) — "Embroidery data can be recalled from a USB
  flash drive (USB flash memory) of any size." "Only .pes and .dst embroidery data files can be used
  with this machine." "Save the patterns in the root directory of the USB media." "If embroidery data
  is saved in a folder on USB media, that embroidery data cannot be retrieved by the machine."
  https://www.manualslib.com/manual/22122/Brother-Nv1250d.html?page=180
- [M2] PR650 manual p.141 (manualslib) — handling precautions only.
  https://www.manualslib.com/manual/460391/Brother-Pr-650.html?page=141
- SE600 manual on manualowl returned HTTP 403; not read.

### 1.5 Forum/blog (not evidence)

Claims seen: "2 GB or less", "4–16 GB", "8 GB or smaller", "exFAT/NTFS won't work", "avoid USB 3.0".
None is backed by an official page above; do not use. Examples: hooptalent.com, embroideryhoopstore.com,
hoopingstation.com, swingdesign.com.

## 2. Android OTG USB storage

- **AOSP [A1]**: `vold` `PublicVolume::doMount()` handles only `vfat` and `exfat`:
  `if (mFsType == "vfat" && vfat::IsSupported())` … `else if (mFsType == "exfat" && exfat::IsSupported())`,
  otherwise `LOG(ERROR) << getId() << " unsupported filesystem " << mFsType; return -EIO;`.
  No NTFS branch. https://android.googlesource.com/platform/system/vold/+/refs/heads/main/model/PublicVolume.cpp
- **AOSP [A2]**: exFAT is conditional per device: `IsSupported()` needs `/system/bin/mkfs.exfat`,
  `/system/bin/fsck.exfat` and kernel `exfat`. So FAT12/16/32 (`vfat`) is the only file system AOSP
  guarantees; exFAT depends on the vendor build.
  https://android.googlesource.com/platform/system/vold/+/refs/heads/main/fs/Exfat.cpp
- AOSP device configuration page (no file-system list): https://source.android.com/docs/core/storage/config
- **Samsung [S1]** (official, Russian): "На накопителе используется файловая система FAT32 или ExFAT."
  "Объем неограничен." (FAT32 or exFAT; capacity unlimited).
  https://www.samsung.com/ru/support/mobile-devices/what-to-do-if-the-samsung-galaxy-does-not-detect-the-usb-flash-drive-when-connected-via-usb-otg/
- Samsung US (microSD, official): NTFS and APFS not compatible. https://www.samsung.com/us/support/answer/ANS10001928/
- NTFS on Galaxy = third-party app (forum only, not evidence).

**Overlap Brother ∩ Android = FAT32** (FAT32 is the only file system named by every Brother source
and the only one AOSP always mounts). exFAT works on many phones but no Brother embroidery page
names it; NTFS is named only by [B3] and Android does not mount it.

## 3. myembro code (main @ 8870c5dd)

### 3.1 Mounted USB drive (SAF route) — `app/.../usb/UsbExporter.kt`

- Drive list: `StorageManager.storageVolumes` filtered `isRemovable && state == MEDIA_MOUNTED`
  (`UsbExporter.kt:50`). Only volumes Android has already mounted appear, i.e. vfat/exfat per §2.
- Folder: the user picks a folder once via `createOpenDocumentTreeIntent()` (opens at the drive
  root, Android 10+) (`UsbExporter.kt:67-69`). `remember()` rejects a folder not on that drive's UUID and persists
  the grant per volume UUID (`:75-84`). Later exports write straight to that folder
  (`LibraryViewModel.kt:430-438`). **The app does not force the root folder**; if the user picks a
  sub-folder, older machines that read only the root ([M1]) or one level ([B6]) may not see it.
- File name: `<source name>.pes` (`PesExport.prepare`, `DesignFormats.preparePes`); created with MIME
  `application/octet-stream` so the provider keeps the name (`UsbExporter.kt:99-100`).
- Overwrite: **never**. Existing names are listed first; a clash becomes `name (1).pes`, `name (2).pes` …
  (`PesExport.uniqueName`, `engine/.../io/PesExport.kt:49-58`) and a NAME_COLLISION notice is shown.
- File system check: **none**. The app does not read the type (FAT32/exFAT), capacity, the 200-item
  count or non-ASCII names. Free space is reported only if the provider gives `COLUMN_AVAILABLE_BYTES`
  (`:117-130`).
- Every file passes `PesExport.requireSafe` (SewoutCheck P1–P8 + PEC viewport) before writing.
- PES version: setting, default **V1** (`SettingsViewModel.kt:36`), V6 optional.

### 3.2 SE400 direct connection (FAT12) — `usb/DirectUsbAccess.kt`, `usb/DirectUsbExporter.kt`, `engine/.../fat12/`

- Used only when **no** removable volume is mounted (`UsbExporter.kt:49-56`): the SE400 exposes a
  firmware RAM disk Android does not mount. Devices are found by USB class mass storage, subclass 6
  (SCSI), protocol 0x50 (BOT), with bulk IN/OUT endpoints; user USB permission required.
- Block access via libaums BOT/SCSI driver, LUN 0 only; libaums' own partition/FS code is not used
  (`DirectUsbAccess.kt:59-85`).
- `Fat12Volume` is an own implementation of the Microsoft FAT spec: superfloppy or MBR type 0x01,
  BPB validated, clusters must be 1..4084 so a FAT16/32 volume is **refused**, not misread
  (`Fat12Volume.kt:440-452`). Firmware oddities (boot jump, media byte, clamped total) are kept as
  warnings, never repaired.
- Root directory only. Each file gets an 8.3 alias (`UsbDesignNames.unique`, generic words like
  "design"/"embroidery" stripped, `~n` on clash) plus a VFAT long name = original name with only
  forbidden characters replaced (Hangul/spaces kept; confirmed readable on SE400, 2026-11-01).
- Overwrite: never silent. If space or root slots run out, the app shows existing `.PES` files; only
  ones the user selects are deleted, and only if the volume fingerprint is unchanged
  (`DirectUsbExporter.kt:57-75`).
- Writes are verified by read-back; after any I/O error the session is poisoned and remaining files
  are reported as failed, never retried.

## 4. Gaps to settle before writing user docs

1. SE600/SE700/SE2000, PE800/PE900, NQ, PR: file system, folder depth, file count and name length are
   not stated on any official page found. Ask Brother support or read each model's PDF manual
   (support.brother.com → model → Manuals) for the "USB media" section.
2. Whether the app should guide the user to pick the drive root (not a sub-folder) — evidence: [M1], [B6].
3. Whether the app should warn when the folder already holds ≥ 200 items — evidence: [B6] (PE-780D
   family only).
4. exFAT: phone may mount it, but no Brother embroidery source lists it. Safe user wording: "FAT32".
