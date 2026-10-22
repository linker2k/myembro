# Third-party notices

myembro is licensed under GPL-3.0-or-later (see [LICENSE](LICENSE)). It contains code
derived from the following projects, whose notices are reproduced as their licenses require.

## pyembroidery

- Source: https://github.com/EmbroidePy/pyembroidery (version 1.5.1)
- Ported files: `engine/.../io/PesReader.kt` (PesReader.py, PecReader.py),
  `engine/.../io/DstReader.kt` (DstReader.py), `engine/.../io/PecThreads.kt` (EmbThreadPec.py),
  `engine/.../io/PecWriter.kt` (PecWriter.py, PecGraphics.py, EmbThread.py),
  `engine/.../io/PesWriter.kt` (PesWriter.py, minimal/truncated v1 path),
  `PatternBuilder.interpolateTrims` / `interpolateDuplicateColorAsStop` (EmbPattern.py).
- Changes: translated to Kotlin; bounded PEC block parsing, truncation warnings, streaming
  trim interpolation; deterministic exact-first unique PEC palette allocation, explicit trim
  preservation, integer move splitting, fixed-width labels and a two-byte PEC END;
  minimal PES v1 output (see docs/writers.md and git history).

```
MIT License

Copyright (c) 2018 

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Downloadable fonts

No font files are bundled in the app or this repository. `app/src/main/assets/fonts/catalog.json`
lists curated open-source fonts that the user may download on demand. Policy:

- Only fonts whose license allows redistribution and embedding in derived output (stitch files)
  are listed: SIL Open Font License 1.1, or the Hershey font license.
- Every download URL names a fixed commit, and the file is rejected unless its SHA-256 matches
  the catalog. The license text is downloaded (also checksum-verified) or, for Hershey fonts,
  taken from the font's own `<metadata>`, and stored next to the font as `<id>.LICENSE.txt`;
  the app shows it from the font library.
- Adding a font: pin the commit, compute `sha256` locally, record license and designer here.

| Fonts | Kind | License | Source (pinned) |
|---|---|---|---|
| Hershey Sans 1-stroke, Script 1-stroke, Serif Medium, Gothic English | single-line SVG | Hershey font license (acknowledgement of Dr. A. V. Hershey, U.S. NBS, and James Hurt, Cognition, Inc. must accompany the data; embedded in each file) | https://gitlab.com/oskay/svg-fonts @ `8c71f2d9e1a5292047bb88e5595a766241b82cc6` |
| EMS Readability, Allure, Tech, Casual Hand, Delight | single-line SVG | OFL-1.1 (derivatives of OFL Google Fonts, by Windell H. Oskay / Evil Mad Scientist) | https://gitlab.com/oskay/svg-fonts @ `8c71f2d9e1a5292047bb88e5595a766241b82cc6` |
| Relief SingleLine | single-line SVG | OFL-1.1, © 2022 The Relief SingleLine Project Authors | https://github.com/isdat-type/Relief-SingleLine @ `01dfc5779ec1e9e4b288d96c6c96c23bfccbaf9d` |
| Jua, Do Hyeon (Woowahan Brothers), Gowun Dodum (Yanghee Ryu), Nanum Gothic, Nanum Pen Script (NHN Corporation), Gaegu (JIKJI SOFT) | outline TTF (Korean + Latin) | OFL-1.1 | https://github.com/google/fonts @ `45e3d5269e4a17d823f6d84470bc084dc5ae46f6` |

## Ink/Stitch (planned)

Digitizing algorithms will be ported from https://github.com/inkstitch/inkstitch
(GPL-3.0-or-later). Each ported file will record its upstream path and commit.

## Hershey Futural single-stroke font data

- Asset: `engine/src/main/resources/lettering/futural.jhf` (unmodified, 96 records).
- Source: https://github.com/kamalmostafa/hershey-fonts/blob/1356bf2f83d380fcef68c887e88675eb9d445d86/hershey-fonts/futural.jhf
- Source notice: https://github.com/kamalmostafa/hershey-fonts/blob/1356bf2f83d380fcef68c887e88675eb9d445d86/COPYING
- SHA-256: `4958a1d06ea04709feb530deaab53d1efa1285d0d3e92a4a3edd74d279871ced`.
- Public-domain-origin Hershey glyph data, distributed with the acknowledgement and
  format restrictions reproduced below. This is the data-specific permission, not
  the upstream C library's GPL-2.0 license; no upstream code is included.
- The notice is also packaged as `lettering/HERSHEY-LICENSE.txt` in the engine JAR/APK.

```text
Hershey fonts (hershey-fonts/*.jhf) license

 Copyright: 1967 Dr. A. V. Hershey, James Hurt

 This distribution of the Hershey Fonts may be used by anyone for
 any purpose, commercial or otherwise, providing that:
    1. The following acknowledgements must be distributed with
        the font data:
        - The Hershey Fonts were originally created by Dr.
            A. V. Hershey while working at the U. S.
            National Bureau of Standards.
        - The format of the Font data in this distribution
            was originally created by
                James Hurt
                Cognition, Inc.
                900 Technology Park Drive
                Billerica, MA 01821
                (mit-eddie!ci-dandelion!hurt)
    2. The font data in this distribution may be converted into
        any other format *EXCEPT* the format distributed by
        the U.S. NTIS (which organization holds the rights
        to the distribution and use of the font data in that
        particular format). Not that anybody would really
        *want* to use their format... each point is described
        in eight bytes as "xxx yyy:", where xxx and yyy are
        the coordinate values as ASCII numbers.

----------------------------------------------------------------------
```

## Jua Korean outline font

- Asset: `app/src/main/res/font/jua_regular.ttf` (unmodified `Jua-Regular.ttf`).
- Source: https://github.com/google/fonts/blob/69d2493f8b919292a9c4b530081934877093c038/ofl/jua/Jua-Regular.ttf
- License: SIL Open Font License 1.1; Copyright 2018 The Jua Project Authors.
- Source license: https://github.com/google/fonts/blob/69d2493f8b919292a9c4b530081934877093c038/ofl/jua/OFL.txt
- SHA-256 (TTF): `769677aef240bfc3b9965f2b50748075bff885e6c6992fc591a3fb268279f898`.
- Full copyright and OFL text are included in
  [`app/src/main/assets/lettering/jua/OFL.txt`](app/src/main/assets/lettering/jua/OFL.txt)
  and packaged in the APK. Android font resources contain the TTF; the text notice
  is an asset so it is not compiled as a font resource.
- Bundling/embedding/redistribution is permitted with the copyright and license;
  the font itself cannot be sold alone. Font software remains under OFL; generated
  lettering does not inherit OFL. No remote font code or build scripts were run.
