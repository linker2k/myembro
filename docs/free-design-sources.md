# Free embroidery design directory and redistribution review

Checked on: **2026-10-22**. A free price does not grant permission to redistribute files. The sites below are
provided only as links for users to download directly in their browsers; no additional designs are bundled in the app.
Korean/English summaries, formats, account requirements, check dates, and source URLs written after reading the original sites are in
[`free_design_sources.json`](../app/src/main/assets/sources/free_design_sources.json).
Site descriptions/terms are summaries only; also check each design's current terms and hoop size.

## Links included in the app (5 sites)

| Site / free listing | Directly checked terms, formats, and download evidence | Confirmed conditions |
|---|---|---|
| [Brother Sewingcraft](https://sewingcraft.brother.eu/en/inspiration-and-events/projects-and-free-patterns) | [Official terms §2·§5](https://sewingcraft.brother.eu/en/terms-of-use), PES download links on the free page | Personal use; no file redistribution or commercial use. Available to visitors without an account. English page. |
| [Embroidery Library](https://emblibrary.com/pages/free-embroidery-designs) | [Licensing](https://emblibrary.com/pages/licensing), [supported formats](https://emblibrary.com/blogs/help/find-embroidery-file-formats), [account/ZIP downloads](https://emblibrary.com/blogs/help/download-embroidery-design-files) | PES·DST and other formats; ZIP downloads from account order history. Limited sales of finished items you embroider yourself are allowed; mass production and digital file sharing prohibited. |
| [Urban Threads](https://urbanthreads.com/pages/free-embroidery-designs) | [Licensing](https://urbanthreads.com/pages/licensing), [formats including PES for an actual $0 free product](https://urbanthreads.com/products/i-heart-ut), [account download procedure](https://urbanthreads.com/blogs/help/downloading-instructions) | PES·DST and other formats; account/free-order process. Limited sales of finished items you embroider yourself are allowed; mass production and file sharing prohibited. Distinguishes purchase-dependent gifts from standalone free listings. |
| [Designs by JuJu](https://www.designsbyjuju.com/free-designs) | [2026 terms of use](https://www.designsbyjuju.com/terms-of-use), [account/format/download FAQ](https://www.designsbyjuju.com/knowledge-base) | PES·DST and other formats; account/order process. **Free designs may not be used on finished items for sale**; purchase the design for commercial use. File sharing prohibited for both free and paid designs. |
| [Kreative Kiwi](https://www.kreativekiwiembroidery.co.nz/Download-Free-In-the-hoop-Embroidery-Designs) | [Copyright/finished-item sales](https://www.kreativekiwiembroidery.co.nz/copy-right), [formats including PES for a free product](https://www.kreativekiwiembroidery.co.nz/product/free-embroidery-addict-), [free-order/account procedure](https://www.kreativekiwiembroidery.co.nz/blog/How-to-Download-our-Free-Embroidery-Designs.html) | PES·DST and other formats; account/free-order process. Sales of finished items you make yourself are allowed; inquire about a separate commercial license for volume sales. File redistribution prohibited. |

Actual HTTP requests to Embroidery Library·Urban Threads·JuJu·Kiwi listing/terms/format/download pages
confirmed 200 responses and body content(final URLs used after redirects).
Brother returned 403 to ordinary Python HTTP requests, but both the free listing and
terms body were confirmed using the web browsing tool. No access restrictions were bypassed and no accounts, orders, or payments were created.
No design files/site images/logos/remote code were copied into the directory.

## Candidates researched but excluded from the app

| Candidate | Findings / reason for exclusion |
|---|---|
| [Janome official free listing](https://www.janome.com/free-embroidery-designs/) | Free listing confirmed in the browsing tool. The page identifies both files as JEF, providing no evidence of PES availability; the linked `/terms-conditions/` body could not be retrieved. Excluded rather than guessing conditions. Ordinary HTTP returns 403. |
| Husqvarna Viking / Pfaff / mySewnet | Official mySewnet root/library return HTTP 200, but currently show CREATIVATE branding and a login-focused dynamic screen. The terms URL also returns HTTP 200, but readable terms, current free downloads, and PES availability could not all be confirmed. No guessed brand-specific free links added. |
| [Ink/Stitch](https://inkstitch.org/) gallery | Official home returns HTTP 200. Independently of the home page's OSS software license, a current free-design download gallery and individual design terms could not both be confirmed, so excluded. |

The JSON records check dates to account for site changes. Rechecking covers free listings, terms, formats,
and download conditions; if any remain unconfirmed, remove the site from the app list.

## Import through app sharing

The public screen is `FreeDesignSourcesScreen(onBack)`, using theme A tokens and Custom Tabs.
Sharing a downloaded PES/PEC/DST or ZIP can add it to the library through `DesignLibrary.importShared(uri)`.
**MainActivity routing/intent-receiving wiring is the integrator's task**;
[integration notes](free-design-sources-integration.md) identify the specific change points.
Manifest SEND/SEND_MULTIPLE filters, safe ZIP extraction functions, and unit tests are ready.

## Bundled public design sources(existing sample pack)

This link directory does not change the existing sample pack. Existing public data includes only one
`spiralbox.pes` derivative under the
[MIT license](https://github.com/EmbroidePy/samples/blob/6e65f1ae8599b1e04d3e082aae1b86abed52ec9e/LICENSE)
of the [pinned EmbroidePy/samples commit](https://github.com/EmbroidePy/samples/tree/6e65f1ae8599b1e04d3e082aae1b86abed52ec9e).
The same design in different formats was not counted as additional designs.
Brother Artspira account/device-restricted designs, personal-use-only designs, and files from expired free promotions are not bundled.

## Our starter pack

26 PES designs (25 original arrangements + one MIT community design), all centered
within an 86×86 mm area in a 100×100 mm hoop. Original sample artwork is expressly
CC0; converted Hershey glyphs retain their acknowledgement and Jua font attribution
is recorded. See [pack licenses](../app/src/main/assets/samples/LICENSES.md),
[manifest](../app/src/main/assets/samples/manifest.json) and [thumbnail sheet](samples.png).

`tools/make_samples.py` is offline and reads only checked-in font/PES data. With the
existing pyembroidery 1.5.1 environment plus local Pillow/fontTools:

```sh
PYTHONPATH=/usr/lib/python3/dist-packages /path/to/venv/bin/python tools/make_samples.py
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug
```

Running/triple-running paths target 2.5 mm stitches; scanline fills use 0.4 mm row
spacing, 3 mm along-row stitches and a 1.5 mm alternating phase. Normal running
segments target the requested 1.5–4 mm range. Shorter contour-end/corner stitches,
zero-distance needle entry after a jump, and short 0.4 mm fill turnarounds are
intentional; no needle-down move exceeds 4 mm. Disconnected travel is trimmed
before jumping. These are geometric starter designs; fabric/stabilizer and machine
settings still need an ordinary test sew. The verifier uses our own PesReader.

First launch/first launch after upgrading from the single-flower demo installs the
whole pack once. Existing user files are preserved. Deleting a sample does not
make it reappear at next launch. Sample file names start with `sample_` and the
library shows a localized sample badge. A failed copy leaves no partial PES and is
retried on the next launch; completed copies are not overwritten.
