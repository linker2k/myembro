# myembro

![myembro — open-source embroidery app for Android (view, edit, letter and digitize Brother PES designs)](docs/images/readme-banner.png)

**Open-source embroidery app for Android** — view, edit, letter and digitize Brother **PES**
designs on your phone and send them to the machine.

**Open-source embroidery app for Android** — view and edit Brother **PES** designs on your phone,
create new designs from text and photos, and send them to your embroidery machine.

## Failure-free designs

Before saving or transferring any app-generated PES, code checks for conditions that cause actual sewout failures
(needle crowding within a 0.5mm cell, consecutive needle entries at the same point, missing start/end locks,
excessive stitch length, file errors, and hoop overflow). Any violation blocks saving and transfer.
Stitch generation ports verified originals(Ink/Stitch, Potrace, AutoTrace, and Wu quantization) with their default parameters
rather than inventing new methods, and automated comparisons verify agreement with the originals.
The app automatically decides potentially unsafe choices, such as stitch types and size ranges, without exposing detailed settings.
Every generated PES is checked in code for the conditions that cause real sew-out failures and is
blocked from saving or sending if any is found. Stitch generation ports verified originals with
their default parameters instead of inventing new algorithms.

## Features (v0.2.x)

- **Viewer and stitch playback:** open PES (v1–v10), PEC, and DST; render thread colors; zoom/pan; simulate sewing; show statistics(stitch count, size, colors, trims, estimated time) and hoops
- **Editor:** arrange designs and text, resize, rotate, and save projects
- **Lettering:**
  - Satin with 72 Ink/Stitch embroidery fonts(verified font catalog) — 72 Ink/Stitch embroidery fonts as satin
  - TTF fonts(including Hangul): the app automatically determines stitch type(satin, fill, running) and allowed size ranges based on text size
- **Fill:** Ink/Stitch default tatami and underlay
- **Photo to embroidery:** monochrome silhouette(fill)/outline modes and resizing
- **Sew-out check:** automatically blocks the failure conditions above. Imported PES bytes remain unchanged
- **Machines:** Brother SE400 — direct transfer from the phone over a USB cable(verified on hardware), transfer history, and travel-thread cleanup guidance for machines without automatic cutting. SE600/SE700 — machine profiles(100×100mm, USB flash drive)
- **Full backup/restore:** save and restore designs, projects, downloaded fonts, and settings in one ZIP
- **Versions:** from v0.2.0 onward, each release is v0.2.N. Distributed as GitHub pre-releases

## Known limitations

- SE400 has no automatic thread cutting; color/position travel threads must be trimmed by hand after sewing(the app provides guidance).
- The current APK is signed with a development key. Switching to an official signing key will prevent updating over the existing app, requiring a one-time uninstall and reinstall. Back up everything through Settings beforehand.
- SE600/SE700 profiles are based on manuals; hardware transfer is still unconfirmed.

## Project layout

```
engine/   Pure Kotlin (JVM) — model and file readers, no Android dependency
app/      Android app (Jetpack Compose)
tools/    make_fixtures.py — builds test fixtures with pyembroidery
```

## Build

Requirements: JDK 17, Android SDK (platform 35).

```bash
./gradlew :engine:test          # reader parity tests
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

### Regenerating test fixtures

The engine tests compare our readers stitch-by-stitch with
[pyembroidery](https://github.com/EmbroidePy/pyembroidery):

```bash
pip install pyembroidery==1.5.1
python3 tools/make_fixtures.py engine/src/test/resources/fixtures
```

## Credits

All third-party notices and full license texts: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

- [Ink/Stitch](https://github.com/inkstitch/inkstitch) v3.3.0 — stitch algorithms (satin, tatami fill, locks) ported, and embroidery fonts (GPL-3.0-or-later; fonts under their own licenses)
- [Potrace](https://potrace.sourceforge.net/) 1.16 — bitmap contour tracing (GPL-2.0-or-later)
- [AutoTrace](https://github.com/autotrace/autotrace) — thinning and centerline tracing (LGPL-2.1-or-later / GPL-2.0-or-later parts)
- Xiaolin Wu color quantizer, Graphics Gems II (Graphics Gems permission)
- [pyembroidery](https://github.com/EmbroidePy/pyembroidery) — file format readers ported and used as a test reference (MIT)
- JTS Topology Suite, JGraphT, libaums, AndroidX and bundled fonts (Noto Sans KR, Gowun Batang, Jua, …) — see the notices

## License

[GPL-3.0-or-later](LICENSE). Contributions welcome — issues and pull requests are open.
