# myembro User Guide

**English** | [한국어](user-guide.ko.md)

This guide covers myembro v0.2.8.

1. [Getting started](#1-getting-started)
2. [Starting a new project](#2-starting-a-new-project)
3. [Editor](#3-editor)
4. [Lettering](#4-lettering)
5. [Photo to embroidery](#5-photo-to-embroidery)
6. [Emoji](#6-emoji)
7. [Viewer: playback and statistics](#7-viewer-playback-and-statistics)
8. [Sew-out check](#8-sew-out-check)
9. [Sending to the machine](#9-sending-to-the-machine)
10. [Backup and restore](#10-backup-and-restore)
11. [About and known limitations](#11-about-and-known-limitations)

## 1. Getting started

Install the APK from the latest GitHub pre-release. The home screen opens first.

![Home screen](../images/manual/en/01-home.png)

- **New project** card: shows the default hoop (100×100mm, 4×4in). Tap **Start new project**.
- **Recent projects**: saved designs and projects appear here. **See all** opens the full list; **Import design** opens a PES/PEC/DST file from the phone.
- **Sample designs**: built-in designs you can open right away.
- Bottom bar: **Home**, **Designs**, **Create**, **Settings**. The gear at the top right also opens Settings.

## 2. Starting a new project

**Start new project** opens a sheet with five starting points:

![New project sheet](../images/manual/en/02-new-project.png)

| Option | What happens |
|---|---|
| Start from an empty hoop | Choose a hoop, then the editor opens empty |
| Start from my design | Pick a saved design, then the editor opens with it |
| Photo/drawing | Turn a photo or drawing into silhouette, outline or color embroidery (chapter 5) |
| From emoji | Turn an open-source emoji image into embroidery (chapter 6) |
| Start with lettering | Open the lettering screen (chapter 4) |

## 3. Editor

![Editor with the myembro logo](../images/manual/en/03-editor-logo.png)

- Top: back, undo/redo, menu. Below it the hoop chip (for example **Hoop 100×100mm**), the object count and the **Save** button.
- Left tools: select, pan (hand), fit to view, grid.
- **Layers** panel: each design or lettering object is a layer; the eye icon shows or hides it, **+** adds one.
- Tap an object to select it. Drag the corner handles to resize; the size labels show mm and inches.
- Bottom sheet tabs **Transform**, **Stitch**, **Color**. In Transform, type **Width (mm)** / **Height (mm)** (the lock keeps the aspect ratio), set rotation, flip, then tap **Apply**.
- Bottom bar: **Select**, **Design** (add a design), **Transform**, **Lettering**, **Layers**.

The hoop is 100×100mm by default. Designs must stay inside it: coordinates run 0–999 in 0.1mm units, so the maximum design size is 99.9mm. The app enforces this automatically.

## 4. Lettering

![Font library](../images/manual/en/04-font-library.png)

1. Tap **Lettering** in the editor's bottom bar (or **Start with lettering**). The lettering screen shows a preview on the hoop.
2. Type your text in **Text** (the character count is shown).
3. Pick a font under **Embroidery font**, or tap **More fonts** to open the font library. Filters: **All**, **Korean**, **Latin**, **Script**, **Single-line**, **Outline**. **+ Add my font** adds your own TTF font.
4. Fonts are downloaded from their open-source repositories and verified before use. On a font card, tap the download button; **License** shows its license; the heart marks a favorite. If a font cannot write the letters you typed, the card says so (for example, that it does not support the Korean in your text).
5. Set **Letter size**, then tap **+ Add to design**.

![Mixed Korean and Latin text: automatic fill](../images/manual/en/05-lettering-hangul.png)
![Korean only: automatic satin](../images/manual/en/05b-lettering-hangul-satin.png)

The app chooses the stitch type and shows it under the font list, for example **Auto · Fill** or **Auto · Satin**. In the screenshots, mixed text ("안녕 myembro") becomes fill and Korean-only text ("안녕") becomes satin. Only sizes that sew safely are offered. The 72 verified Ink/Stitch fonts are stitched as satin.

![Single-line font, Normal](../images/manual/en/06-single-line-normal.png)
![Single-line font, Bold](../images/manual/en/06b-single-line-bold.png)

Single-line fonts (for example Hershey Script 1-stroke) offer **Normal** (running stitch, one pass) and **Bold** (triple bean stitch from Ink/Stitch). Where the original font draws the same segment twice, Bold can pass there 6 times.

## 5. Photo to embroidery

Choose **Photo/drawing** on the new project sheet. The screen has three steps: crop, choose a mode, then the result.

**Step 2 — mode.** Choose **Silhouette**, **Outline** or **Color drawing**.

![Silhouette mode](../images/manual/en/07-photo-silhouette.png)

- **Silhouette**: one color, filled. The **Detail** slider keeps smaller contours the higher it is.
- **Embroidery size · width** slider: the aspect ratio is kept. The range is computed for the picture (20mm–77.1mm in this example), and the design is recalculated when you release the slider.

![Outline mode](../images/manual/en/08-photo-outline.png)

- **Outline**: one color, traced outline of the same picture.

![Color drawing mode](../images/manual/en/09-photo-color.png)

- **Color drawing** (2–6 colors): set **Number of colors** with the slider, or turn on **Grayscale**. Transparent background is not stitched; the most common color at the edge is removed as background; colors are matched to the Brother 64-color threads automatically.

**Step 3 — result.**

![Result with sew-out check passed](../images/manual/en/09b-photo-result.png)

The result shows **Sew-out check · Passed**, the stitch count and estimated time, the actual size and the **Brother threads needed** (number and name). The time is an estimate. Tap **Editor** to keep editing or **Send** to send it to the machine.

## 6. Emoji

![Korean emoji search](../images/manual/en/10-emoji-korean-search.png)

Choose **From emoji** and search. Korean search terms work (based on Unicode CLDR); the screenshot shows results for "하트" (heart). Some emoji are marked as needing a download first. Set the size with the same embroidery size slider as the photo screen.

## 7. Viewer: playback and statistics

![Viewer during playback](../images/manual/en/11-viewer-playback.png)

Open any design to view it with thread colors, zoom and pan and the hoop outline. Stitch playback shows progress (for example 414/2,808 stitches), and statistics show stitch count, colors, estimated time and size. PES (v1–v10), PEC and DST can be opened.

## 8. Sew-out check

![Pre-sew check for SE400](../images/manual/en/12-sewout-check.png)

The **Pre-sew check** sheet shows the selected machine (here Brother SE400), the number of blocks, warnings and thread trims, and the estimated time. Tabs: **Problems**, **Sewing order**, **Per-color estimate**, **Machine spec**. Info items explain what is unverified (for example the maximum sewing speed used for time estimates); **Show location** jumps to the stitch.

Every design the app generates is checked before saving or sending for the conditions that cause real sew-out failures:

- too many needle penetrations in one 0.5mm cell
- repeated penetrations at the same point
- missing start/end lock stitches
- stitches longer than 12.0mm
- file errors, or stitches outside the hoop (0–999 in 0.1mm units)

If any check fails, saving and sending are blocked. Imported PES files are kept byte-for-byte.

## 9. Sending to the machine

![Selecting a design for USB export](../images/manual/en/13-transfer-usb.png)

1. Connect the phone to the Brother SE400 with a USB cable (USB-B on the machine side).
2. In **Designs**, enter selection mode and check the designs to send. Filters: **All**, **Fits 100×100**, **PES**, **Recent**, favorites.
3. Tap **Export via USB**. If no USB device is connected, a banner says **No USB connected**; tap it to see how to export via USB.

![Transfer failed without USB](../images/manual/en/13b-transfer-usb-fail.png)

If the transfer fails, the result screen explains how to connect (OTG) and offers **Copy diagnostic info**.

![Transfer history](../images/manual/en/14-transfer-history.png)

The clock icon in selection mode opens the recent USB transfer history (the screenshot was taken on an emulator, so only failed attempts are listed).

**Important for the SE400:** it has no automatic thread cutter. Travel threads between colors and positions are not cut, so trim them by hand after sewing. The app shows guidance for this. Files written to the SE400 over USB are erased when the machine is turned off.

**SE600/SE700:** machine profiles (100×100mm, USB memory) based on the manuals; transfer has not been tested on real machines yet.

## 10. Backup and restore

![Settings: backup](../images/manual/en/15-settings-backup.png)

At the top of **Settings**, **Backup** stores designs, projects, downloaded fonts and settings in one ZIP. Tap **Save backup** and keep the ZIP outside the app; **Restore ZIP** reads it back. Below it, **Machine** shows the selected model (Brother SE400) with its maximum embroidery area (100×100mm) and notes.

**Take a backup now.** The current APK is signed with a development key. When releases switch to the official signing key, the new version cannot be installed over the old one: you must uninstall once and reinstall, which deletes app data. Restore your ZIP afterwards.

## 11. About and known limitations

![Settings: theme and about](../images/manual/en/16-about.png)

At the bottom of **Settings**: **Display theme**, **App info and licenses**, and the version (0.2.8 with the build commit). myembro is GPL-3.0-or-later.

![Home in dark theme](../images/manual/en/17-home-dark.png)

Known limitations:
- SE400: no automatic thread cutting; trim travel threads by hand.
- Signing key change: uninstall/reinstall will be needed once; back up first.
- SE600/SE700: transfer not yet tested on real machines.
