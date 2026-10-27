# myembro

**English** | [한국어](README.ko.md)

![myembro — open-source embroidery app for Android (view, edit, letter and digitize Brother PES designs)](docs/images/readme-banner.png)

**Make embroidery designs on your Android phone and send them to your Brother machine.**
Free and open source.

Current version: **v0.4.0-beta.1 (beta)**

![myembro in one minute](docs/images/intro-en.gif)

## What you can do

- 🖼️ **Photo or drawing → embroidery.** Pick a picture and choose silhouette, outline or color drawing (2–6 colors). The preview shows the real thread colors.
- 🔤 **Lettering.** Type text in Korean or English. 72 embroidery fonts, plus your own TTF fonts. The app picks a stitch type that sews safely.
- 😀 **Emoji → embroidery.** Search emoji (Korean words work too) and turn one into a design.
- ✂️ **Edit.** Place, resize and rotate designs and text on the hoop.
- 👀 **View.** Open PES, PEC and DST files. See thread colors, play the stitches and check stitch count, size and time.
- 🔌 **Send to the machine.** Send designs to a Brother SE400 over a USB cable.
- 💾 **Back up.** Save designs, projects, fonts and settings in one ZIP and restore them later.

| Color drawing | Lettering | Sew-out check |
|---|---|---|
| ![Color drawing mode](docs/images/manual/en/09-photo-color.png) | ![Korean lettering](docs/images/manual/en/05b-lettering-hangul-satin.png) | ![Sew-out check](docs/images/manual/en/12-sewout-check.png) |

## Install

1. Open the [latest release](https://github.com/linker2k/myembro/releases/latest).
2. Download the `.apk` file to your Android phone.
3. Open it and allow installing from this source when the phone asks.

### Moving from 0.3.x to the beta

The beta is signed with a new key, so it cannot install over 0.3.x. Do this once:

1. In the old app, go to **Settings → Backup → Save backup**. Keep the ZIP outside the app.
2. Uninstall the old myembro.
3. Install the beta.
4. Go to **Settings → Backup → Restore ZIP** and pick your backup.

After reinstalling, allow USB folder access again.

## Where to start

Open the app and tap **Start a new project**. You can start from a blank hoop, your own design, a photo or drawing, an emoji, or lettering.

The [user guide](docs/manual/user-guide.md) walks through every screen with pictures.

## Machines

| Machine | Hoop | File | Status |
|---|---|---|---|
| Brother SE400 | 100×100mm | PES | Direct USB transfer, tested on a real machine |
| Brother SE600 / SE700 | 100×100mm | PES | Profiles from the manuals; transfer not tested on real machines yet |

## Safety checks

Before you save or send, the app checks every design it makes for five problems that ruin real sew-outs:

1. Too many stitches packed into one small spot
2. The needle hitting the same point again and again
3. Missing lock stitches at the start or end
4. Stitches longer than 12mm
5. A broken file, or stitches outside the hoop

If any one is found, saving and sending are blocked. PES files you import are kept exactly as they are.

## Known limitations

- The SE400 has no automatic thread cutter. Trim the threads between colors by hand after sewing; the app shows guidance.
- SE600/SE700 transfer has not been tested on real machines yet.
- In color drawing, very small pieces (under 3mm²) are removed, and gaps narrower than 0.4mm between colors are filled.
- Photo stitch generation stops after 1 minute. Pictures with about 100 color areas can hit this; use lower detail or a smaller crop.

## For developers

### Build

Requirements: JDK 17, Android SDK (platform 35).

```bash
./gradlew :engine:test          # engine tests
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

```
engine/   Pure Kotlin (JVM): model, file readers and writers, stitch generation
app/      Android app (Jetpack Compose)
relay/    Optional Cloudflare Worker for AI connection
tools/    Test fixture and check scripts
```

Stitch generation ports verified originals (Ink/Stitch, Potrace, AutoTrace, Wu quantization) with their default settings, and automated tests compare the results with the originals.

### AI and PC control

- PC control from Claude Code, Codex CLI and other MCP clients: [docs/agent/mcp.md](docs/agent/mcp.md)
- AI connection relay (experimental): [relay/README.md](relay/README.md)

### Credits and license

- Third-party notices and license texts: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- Main sources: [Ink/Stitch](https://github.com/inkstitch/inkstitch), [Potrace](https://potrace.sourceforge.net/), [AutoTrace](https://github.com/autotrace/autotrace), [pyembroidery](https://github.com/EmbroidePy/pyembroidery), [DrawingBotV3](https://github.com/SonarSonic/DrawingBotV3)
- License: [GPL-3.0-or-later](LICENSE). Issues and pull requests are welcome.
