# Free embroidery design sources and redistribution review

Reviewed 2026-10-22. A zero price/download button is not permission to redistribute
files in an OSS app. Only the explicitly licensed source below is bundled; other
sites are links for users to inspect and download under their own applicable terms.

| Source | Evidence / conditions | Decision |
|---|---|---|
| [EmbroidePy/samples](https://github.com/EmbroidePy/samples/tree/6e65f1ae8599b1e04d3e082aae1b86abed52ec9e) | [MIT notice](https://github.com/EmbroidePy/samples/blob/6e65f1ae8599b1e04d3e082aae1b86abed52ec9e/LICENSE), Copyright (c) 2019 EmbroidePy. Repository holds PES, PEC and DST test files. | Bundle a resampled derivative of `spiralbox.pes`; retain complete MIT notice, source commit, original hash and output hash. Other variants are the same artwork in other encodings, not additional designs. `scene.pes` was inspected but excluded as a sparse parser fixture, not a useful starter design. |
| [pyembroidery](https://github.com/EmbroidePy/pyembroidery) | MIT code library; its code license is not automatically a license for every external design mentioned in tests/issues. | Use the already installed local 1.5.1 encoder; do not run downloaded scripts or bundle unattributed test artwork. |
| [Brother projects and free patterns](https://sewingcraft.brother.eu/en/inspiration-and-events/projects-and-free-patterns) | [Terms of use, section 5](https://sewingcraft.brother.eu/en/terms-of-use): designs are copyrighted; reproduction/distribution needs written permission and the listed patterns are for private purposes. | Links only. No Brother design data is bundled. |
| [Brother Artspira](https://sewingcraft.brother.eu/en/artspira) | Vendor/account/machine-specific permissions vary by design; licensed artist/Disney content is personal-use only. Permission to sew or sell a physical item does not grant redistribution of its design file. | Links only. No Artspira or Disney design data is bundled. |
| [Brother 50 free patterns promotion](https://sewingcraft.brother.eu/en/artspira/50-free-patterns) | Page explicitly states personal, non-commercial use and an expired promotion. | Historical reference only; not a current offer and not a redistributable source. |

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
