# myembro

![myembro — Open-source embroidery viewer for Android](docs/images/readme-banner.png)

**Open-source embroidery app for Android** — view, simulate and (soon) digitize machine
embroidery designs on your phone. Brother **PES** first.

**안드로이드용 오픈소스 자수 앱** — 휴대폰에서 자수 도안을 보고, 바느질 과정을 시뮬레이션하고,
(곧) 직접 디지타이징까지. 브라더 **PES** 우선 지원.

## Features / 기능 (v0.1 — viewer)

- Open **PES** (v1–v10), **PEC**, **DST** files — from the file picker or "Open with" from a file manager
- Thread-colored stitch rendering with pinch-zoom / pan (double-tap to fit)
- Sewing simulation: play/pause, scrub slider, adjustable speed, needle position
- Stats: stitch count, size (mm), colors, trims, estimated sewing time
- Color block list — tap to jump to the end of a block
- Hoop overlay (default 100×100 mm; 130×180, 160×260, 200×200, 200×300) with oversize warning
- Optional jump (travel) display

## Roadmap

See [PLAN.md](PLAN.md) (Korean). In short:

1. ✅ Engine core: pattern model, PES/PEC/DST readers verified against pyembroidery
2. 🚧 Viewer / simulator
3. 🚧 Output: PEC / PES v1 writer, checked on a real machine before any digitizing
4. Geometry foundation, minimal project model, small SVG import
5. Digitizing engine — running, satin, fill, tatami (ported from Ink/Stitch), sewn out at every step
6. Editor with project save / reopen, lettering
7. Photo → embroidery wizard

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

- File format readers are ported from [pyembroidery](https://github.com/EmbroidePy/pyembroidery) (MIT) — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- Digitizing algorithms will be ported from [Ink/Stitch](https://github.com/inkstitch/inkstitch) (GPL-3.0).

## License

[GPL-3.0-or-later](LICENSE). Contributions welcome — issues and pull requests are open.
