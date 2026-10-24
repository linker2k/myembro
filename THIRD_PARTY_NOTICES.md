# Third-party notices

myembro is licensed under GPL-3.0-or-later (see [LICENSE](LICENSE)). It contains code
derived from the following projects, whose notices are reproduced as their licenses require.

## Ink/Stitch algorithm ports (GPL-3.0-or-later)

- Source: https://github.com/inkstitch/inkstitch, stable tag v3.3.0, commit `b0edd96311ece82ee48816dc93466274b12c2a9c`.
- Copyright (c) 2010 Authors; additional authors are recorded in upstream git history.
- Kotlin adaptations in `engine/.../inkstitch/` port running/bean, explicit satin
  rails/rungs/short-stitch/underlay, deterministic graph auto-fill, and lock/plan processing.
  Each file names its original functions. See [docs/stitch-algorithms.md](docs/stitch-algorithms.md)
  for exact scope, app safety differences and verification limits.
- Upstream GPL v3 LICENSE copied verbatim to `engine/src/main/resources/licenses/inkstitch/LICENSE`;
  source files explicitly permit version 3 or later. No Python runtime or upstream dependencies bundled.

## Dancing Script Bold — regression tests only (OFL-1.1)

- Copyright 2016 The Dancing Script Project Authors, Reserved Font Name “Dancing Script”.
- Unmodified TTF from Google Fonts/Impallari Type, pinned to
  https://github.com/googlefonts/DancingScript/tree/291f87c05206179b6bfd53735a5638b5cc84e3b3.
- SHA-256 `ea80fedd89e84c4b572f0d780416956e84b0354d0df2d7985fe1883956677dc4`.
- Font and complete OFL text: `engine/src/test/resources/fonts/dancing-script-bold/`.
  This is a public-font-generated regression input, not the user's private embroidery file.

## Outline thinning algorithm reference

- Zicheng Guo and Richard W. Hall, *Parallel thinning with two-subiteration algorithms*,
  Communications of the ACM 32(3), 1989, pp. 359–373:
  https://doi.org/10.1145/62065.62074.
- `engine/.../lettering/OutlineColumns.kt` independently implements the mathematical
  connectivity tests, bounded rasterization, skeleton tracing and vector rail pairing.
  Its small lookup masks are generated from those equations, not copied tables.
  No paper text/images, OpenCV binaries/source tables, or commercial font/design data
  are included in the repository or distributed application.

## Ink/Stitch satin-column conceptual reference

- Reference: [Ink/Stitch Satin Column manual](https://inkstitch.org/docs/stitches/satin-column/),
  consulted 2026-10-22: paired rails, same-rail zigzag spacing, per-side pull compensation,
  maximum-length split stitches, center-walk and zigzag underlay.
- Project/source: https://github.com/inkstitch/inkstitch;
  [upstream GPL v3 license](https://github.com/inkstitch/inkstitch/blob/main/LICENSE).
- `engine/.../lettering/SatinColumn.kt` is an independent Kotlin implementation under
  this repository's GPL-3.0-or-later license. No Ink/Stitch source code, images, font
  data, or other assets were copied into that implementation; no remote code was executed.
  The newer source-derived ports under `engine/.../inkstitch/` are separately attributed below.

## Ink/Stitch embroidery fonts (downloaded data; five fonts and stitch previews bundled)

- Source: https://github.com/inkstitch/embroidery-fonts at commit
  `c7e3a05c3d6f5c77b881c50f257851af9a8e9cb8`; per-font licenses (OFL-1.1, CC BY-SA, CC BY,
  CC0/public domain, GPL-3.0) are listed in `docs/inkstitch-fonts.md`.
- `app/src/main/assets/fonts/inkstitch-catalog.json` holds only pinned URLs and sha256 values.
  The app downloads a chosen font's `ltr.svg`, `font.json` and `LICENSE` to the device and keeps
  the license beside it. No font file is in this repository or the APK; `assets/fonts/previews/` holds one
  small picture per font of its stitches (the sample "Abc"), rendered from that font under its own
  license (OFL-1.1, CC BY / BY-SA, GPL-3.0 or public domain, as listed in the catalog with its authors).
- Five fonts are bundled in the APK for offline use, unmodified and verified against the same
  pinned sha256: `assets/fonts/bundled/<id>/` holds each font's `ltr.svg`, `font.json` and `LICENSE`
  (all SIL Open Font License 1.1): sacramarif, chicken_scratch, chicken_little, medium_font,
  bathaus_FI. Selection basis: `docs/inkstitch-fonts.md` (번들 글꼴).

## pyembroidery

- Source: https://github.com/EmbroidePy/pyembroidery (version 1.5.1)
- Ported files: `engine/.../io/PesReader.kt` (PesReader.py, PecReader.py),
  `engine/.../io/DstReader.kt` (DstReader.py), `engine/.../io/PecThreads.kt` (EmbThreadPec.py),
  `engine/.../io/PecWriter.kt` (PecWriter.py, PecGraphics.py, EmbThread.py),
  `engine/.../io/PesWriter.kt`, `engine/.../io/PesV6Writer.kt` (PesWriter.py,
  minimal/truncated v1 and full v6 header/CEmbOne/CSewSeg/addendum paths),
  `PatternBuilder.interpolateTrims` / `interpolateDuplicateColorAsStop` (EmbPattern.py).
- Changes: translated to Kotlin; bounded PEC block parsing, truncation warnings, streaming
  trim interpolation; deterministic exact-first unique PEC palette allocation, explicit trim
  preservation, integer move splitting, fixed-width labels and a two-byte PEC END;
  minimal PES v1 and full PES v6 output, UTF-8 byte-length strings, checked/split v6
  sewing sections (see docs/writers.md and git history).

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

In addition to the bundled Hershey and Jua assets documented below,
`app/src/main/assets/fonts/catalog.json` lists curated open-source fonts that the user
may download on demand. Policy:

- Only fonts whose license allows redistribution and embedding in derived output (stitch files)
  are listed: SIL Open Font License 1.1, Apache License 2.0, or the Hershey font license.
- Every download URL names a fixed commit, and the file is rejected unless its SHA-256 matches
  the catalog. The license text is downloaded (also checksum-verified) or, for Hershey fonts,
  taken from the font's own `<metadata>`, and stored next to the font as `<id>.LICENSE.txt`;
  the app shows it from the font library.
- Adding a font: pin the commit, compute `sha256` locally, record license and designer here.
- Static instances are preferred over variable fonts; where Google Fonts ships only a variable
  file, the static Regular/Bold comes from the upstream repository named in its `METADATA.pb`.

| Fonts | Kind | License | Source (pinned) |
|---|---|---|---|
| Hershey Sans 1-stroke, Sans Medium, Script 1-stroke, Script Medium, Serif Medium, Serif Medium Italic, Serif Bold, Serif Bold Italic, Gothic English, Gothic German, Gothic Italian | single-line SVG | Hershey font license (acknowledgement of Dr. A. V. Hershey, U.S. NBS, and James Hurt, Cognition, Inc. must accompany the data; embedded in each file) | https://gitlab.com/oskay/svg-fonts @ `8c71f2d9e1a5292047bb88e5595a766241b82cc6` |
| EMS Readability, Readability Italic, Allure, Tech, Casual Hand, Delight, Delight Swash Caps, Bird, Bird Swash Caps, Brush, Capitol, Decorous Script, Elfin, Felix, Herculean, Invite, League, Little Princess, Misty Night, Neato, Nixish, Nixish Italic, Osmotron, Pancakes, Pepita, Qwandry, Society, Swiss | single-line SVG | OFL-1.1 (derivatives of OFL fonts, by Sheldon B. Michaels and Windell H. Oskay / Evil Mad Scientist; each file's `<metadata>` names the source font and its designer) | https://gitlab.com/oskay/svg-fonts @ `8c71f2d9e1a5292047bb88e5595a766241b82cc6` |
| Relief SingleLine | single-line SVG | OFL-1.1, © 2022 The Relief SingleLine Project Authors | https://github.com/isdat-type/Relief-SingleLine @ `01dfc5779ec1e9e4b288d96c6c96c23bfccbaf9d` |
| Jua, Do Hyeon (Woowahan Brothers), Gowun Dodum (Yanghee Ryu), Nanum Gothic, Nanum Pen Script (NHN Corporation), Gaegu (JIKJI SOFT) | outline TTF (Korean + Latin) | OFL-1.1 | https://github.com/google/fonts @ `45e3d5269e4a17d823f6d84470bc084dc5ae46f6` |
| Black Han Sans (Zess Type), Sunflower Medium (JIKJISOFT), Gamja Flower (YoonDesign Inc), Hi Melody (YoonDesign Inc), Poor Story (Yoon Design), Single Day (DXKorea Inc), Yeon Sung (Woowahan brothers), Stylish (AsiaSoft Inc), Song Myung (JIKJI), Gugi (TAE System & Typefaces Co.), Cute Font (TypoDesign Lab. Inc), East Sea Dokdo (YoonDesign Inc), Dokdo (FONTRIX), Kirang Haerang (Woowahan Brothers), Nanum Myeongjo (Sandoll Communication), Nanum Myeongjo Bold (Sandoll Communication), Nanum Brush Script (Sandoll Communication), Gothic A1 (HanYang I&C Co), Gothic A1 Bold (HanYang I&C Co), Dongle (Yanghee Ryu), Orbit (Sooun Cho, JAMO), IBM Plex Sans KR (Mike Abbink, Bold Monday), IBM Plex Sans KR Bold (Mike Abbink, Bold Monday) | outline TTF (Korean + Latin) | OFL-1.1 | https://github.com/google/fonts @ `45e3d5269e4a17d823f6d84470bc084dc5ae46f6` |
| Hahmlet (Hypertype) | outline TTF (Korean + Latin), static Regular | OFL-1.1 | https://github.com/hyper-type/hahmlet @ `106541144954a9b7037b8d3837097ba660312655` |
| Noto Serif KR (Google) | outline OTF (Korean + Latin), static Regular subset | OFL-1.1 | https://github.com/notofonts/noto-cjk @ `985fa52c81c1d6692ccdd82bc3656e8fb932fd89` |
| Bebas Neue, Anton, Alfa Slab One, Righteous, Abril Fatface, Pacifico, Lobster, Lobster Two Bold, Kaushan Script, Cookie, Damion, Courgette, Leckerli One, Grand Hotel, Norican, Great Vibes, Alex Brush, Allura, Parisienne, Pinyon Script, Sacramento (designers listed in `catalog.json`) | outline TTF (Latin) | OFL-1.1 | https://github.com/google/fonts @ `45e3d5269e4a17d823f6d84470bc084dc5ae46f6` |
| Permanent Marker (Font Diner), Satisfy (Sideshow), Yellowtail (Astigmatic) | outline TTF (Latin) | Apache-2.0 | https://github.com/google/fonts @ `45e3d5269e4a17d823f6d84470bc084dc5ae46f6` (`apache/`) |
| Playfair Display v1.200 (Claus Eggers Sørensen) | outline TTF (Latin), static Regular | OFL-1.1 | https://github.com/google/fonts @ `d08233f9c581786e8c834a1f0695a4f27869c119` |
| Oswald, Dancing Script Bold, Fredoka, Baloo 2, Comfortaa Bold, Cinzel, Caveat Bold | outline TTF (Latin), static instances | OFL-1.1 | googlefonts/OswaldFont @ `89795261`, googlefonts/DancingScript @ `291f87c0`, hafontia-zz/Fredoka-One @ `35c584ff`, yanone/Baloo2-Variable @ `da523dfa`, alexeiva/comfortaa @ `4aa21cfb`, NDISCOVER/Cinzel @ `8271e162`, googlefonts/caveat @ `59745e81` (full hashes in `catalog.json`) |

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

## Bundled sample design pack

- Assets: 26 `app/src/main/assets/samples/sample_*.pes` files; the installed pack is
  inventoried, checksummed and attributed in `app/src/main/assets/samples/manifest.json`
  and [samples/LICENSES.md](app/src/main/assets/samples/LICENSES.md), both packaged in the APK.
- 25 original geometry/layout designs: CC0-1.0, Copyright 2026 myembro contributors;
  express dedication and full CC0 text are packaged in the samples directory. Generator
  `tools/make_samples.py` remains GPL-3.0-or-later.
- The three converted Hershey monograms retain the Hershey acknowledgement/format
  restriction above and in `samples/LICENSES.md`; their original arrangement is CC0.
- The two Korean outline designs use the bundled OFL-1.1 Jua font described above.
  Generated artwork is CC0; the font remains OFL and its full notice remains packaged.
- `sample_community_spiralbox.pes`: derivative of EmbroidePy/samples `spiralbox.pes`,
  commit `6e65f1ae8599b1e04d3e082aae1b86abed52ec9e`, MIT, Copyright (c) 2019 EmbroidePy.
  Source: https://github.com/EmbroidePy/samples/blob/6e65f1ae8599b1e04d3e082aae1b86abed52ec9e/spiralbox.pes .
  License: https://github.com/EmbroidePy/samples/blob/6e65f1ae8599b1e04d3e082aae1b86abed52ec9e/LICENSE .
  Original data SHA-256: `4e6ca07e33132787c2211eb676443e8c9fc6629993e37be7b886bde21df3066c`.
  Changes: recentering, running-stitch resampling, explicit trims and PES v6 conversion.
- Test fixtures (source repository only, not in the APK): `engine/src/test/resources/fixtures/embroidepy/`
  `random1-brother-v5.pes`, `random1-brother-v6.pes`, `random1-premier-v9.pes` and
  `random1-wilcom-v6.PES`, unmodified copies from the same EmbroidePy/samples commit, MIT,
  Copyright (c) 2019 EmbroidePy; the LICENSE file is copied beside them.
  Full original MIT copyright/permission/warranty notice follows and is also packaged:

```text
MIT License

Copyright (c) 2019 EmbroidePy

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

Brother and other personal-use-only or unclear-license designs are not bundled;
see [free design sources](docs/free-design-sources.md). Only source data/licenses
were downloaded; no upstream code was executed.

## Noto Sans KR

- Source: https://github.com/google/fonts/tree/main/ofl/notosanskr
- Bundled unmodified in `app/src/main/res/font/noto_sans_kr.ttf`.
- License: SIL Open Font License 1.1; also bundled in `assets/licenses/noto_sans_kr_OFL.txt`.

```text
Copyright 2014-2021 Adobe (http://www.adobe.com/), with Reserved Font Name 'Source'

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
https://scripts.sil.org/OFL


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

## Gowun Batang

- Source: https://github.com/google/fonts/tree/main/ofl/gowunbatang
- Bundled unmodified in `app/src/main/res/font/gowun_batang.ttf`.
- License: SIL Open Font License 1.1; also bundled in `assets/licenses/gowun_batang_OFL.txt`.

```text
Copyright 2021 The Gowun Batang Project Authors (https://github.com/yangheeryu/Gowun-Batang)


This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
http://scripts.sil.org/OFL


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

## AndroidX Browser

- Dependency: `androidx.browser:browser:1.8.0`, Android Open Source Project contributors.
- Used for opening external design-source/terms URLs in Custom Tabs; no remote website
  code or design assets are bundled by this directory.
- License: Apache License 2.0, full text packaged in
  `app/src/main/assets/licenses/androidx_browser_APACHE-2.0.txt`.
- Source/release: https://developer.android.com/jetpack/androidx/releases/browser#1.8.0

## JTS Topology Suite (core)

- Dependency: `org.locationtech.jts:jts-core:1.20.0`, unmodified JVM library.
- Source: https://github.com/locationtech/jts/tree/1.20.0 (tag commit `6e95fe8`).
- Upstream offers EPL-2.0 OR EDL-1.0. myembro selects **EDL-1.0**, the revised
  BSD-style alternative, for this dependency; see the upstream licensing FAQ:
  https://github.com/locationtech/jts/blob/1.20.0/FAQ-LICENSING.md .
- Copyright (c) 2007, Eclipse Foundation, Inc. and its licensors. All rights reserved.
  Source notices include Vivid Solutions, Martin Davis, other JTS contributors and
  INRIA Prisme Project (RobustDeterminant, relicensed with Olivier Devillers' permission).
- Full EDL text, upstream licensing overview and source copyright inventory are packaged
  in the engine JAR/APK at `licenses/jts/LICENSE_EDLv1.txt`, `licenses/jts/LICENSES.md`,
  and `licenses/jts/COPYRIGHTS.txt`. Source data/licenses were read; no downloaded
  source code or scripts were executed. Gradle uses the published JTS core binary.
- Used by `engine/geometry/Geometry.kt` for noding, polygonization, overlays, buffer
  and intersections. SVG parsing and running/tatami generation are original myembro
  GPL-3.0-or-later code implementing the referenced W3C syntax and basic geometry;
  see docs/geometry.md for algorithm sources and limitations.

## libaums core

- Dependency: `me.jahnen.libaums:core:0.10.0`, unmodified published AAR (including its
  native USB/errno helpers). Copyright (C) 2014–2019 Magnus Jahnen and contributors;
  project contributors include Davide Depau.
- Project/source: https://github.com/magnusja/libaums . Pinned source archive:
  https://repo.maven.apache.org/maven2/me/jahnen/libaums/core/0.10.0/core-0.10.0-sources.jar .
- License: Apache License, Version 2.0. Full upstream license is bundled at
  `app/src/main/assets/licenses/libaums_APACHE-2.0.txt`.
- Used for USB BOT / SCSI block access (LUN 0) with Android USB host permission.
  The optional `libusbcommunication` module is not included. No libaums filesystem
  factory is invoked. `engine/fat12` is an independent GPL-3.0-or-later Kotlin
  implementation; the app adapts sector I/O and handles explicit deletion consent.

## Ink/Stitch ports

- Source: Ink/Stitch **v3.3.0**, commit `b0edd96311ece82ee48816dc93466274b12c2a9c`:
  https://github.com/inkstitch/inkstitch/tree/b0edd96311ece82ee48816dc93466274b12c2a9c .
- Ported source files are identified in each Kotlin header and `docs/inkstitch-port.md`.
  Copyright (c) Ink/Stitch contributors; original files also credit Authors (git history).
- License: **GPL-3.0-or-later**. Full GPL text is packaged at
  `engine/src/main/resources/licenses/inkstitch/LICENSE` in the engine JAR/APK.
- Original sources are read only: no Ink/Stitch/Inkscape execution or dependency installation.
  No Python runtime, NumPy, Shapely or NetworkX dependency is bundled by these ports.
  Existing JTS core (EDL-1.0; see above) supplies the geometry replacement.
- Fill ports cover `lib/stitches/{fill,tatami_fill,contour_fill,guided_fill,circular_fill,
  linear_gradient_fill,meander_fill,tartan_fill}.py`, fill-travel running stitch helpers,
  `lib/elements/fill_stitch.py`, `lib/utils/{geometry,smoothing,clamp_path,prng}.py`,
  `lib/stitches/utils/connect_geometries.py`, `lib/tiles.py` and `lib/tartan/utils.py`.
  The JTS-backed multigraph, shortest paths and BLAKE2s subset are translated
  implementations. Weighted perfect matching for Euler augmentation uses JGraphT
  Blossom V (license selection and transitive dependencies below).
- Data: 75 original `tiles/*/tile.svg` files are redistributed under GPL-3.0-or-later
  in `engine/src/main/resources/inkstitch/tiles/`. Source-path notices were added as
  XML comments; an index and `ORIGIN.txt` record the pinned source. No preview images,
  fonts, private embroidery files or upstream executable code are bundled with them.
- Changes and explicit unsupported settings are listed in `docs/inkstitch-port.md`.
  The previous app fill implementations remain in use until independent quality approval.

## JGraphT core and transitive libraries

- `org.jgrapht:jgrapht-core:1.5.2`, unmodified published JVM library.
  Source: https://github.com/jgrapht/jgrapht/tree/jgrapht-1.5.2 .
  Upstream offers LGPL-2.1-or-later OR EPL-2.0; myembro selects
  **LGPL-2.1-or-later**, compatible with the application's GPL-3.0-or-later license.
  Copyright (C) 2003–2023 Barak Naveh and contributors. The Java Blossom V
  implementation credits Copyright (C) 2018–2023 Timofey Chudakov and contributors.
  This is the JGraphT Java implementation, not the separate noncommercial Blossom V binary.
- Also used by photo-outline sequencing through the unmodified `NearestNeighborHeuristicTSP` implementation; no new route solver is bundled.
- Used by fill routing for minimum-weight perfect matching of odd-degree vertices,
  replacing NetworkX's Euler augmentation matching. Graph storage, BFS, Dijkstra and
  traversal are Kotlin ports. Public dependency binaries are used; upstream source
  archives were only read to verify notices.
- LGPL text and source offer/link are packaged in `licenses/jgrapht/LGPL-2.1.txt`
  and `licenses/jgrapht/NOTICE.txt` in the engine JAR/APK. No library code is modified;
  source is available from the pinned repository and the published Maven sources JAR.
- Transitive `org.jheaps:jheaps:0.14`: **Apache-2.0**. Copyright (C) 2014–2016
  Dimitrios Michail and contributors. Full license and source notice are packaged at
  `licenses/jheaps/APACHE-2.0.txt` and `licenses/jheaps/SOURCE-NOTICE.txt`.
  Source: https://repo.maven.apache.org/maven2/org/jheaps/jheaps/0.14/jheaps-0.14-sources.jar .
- Transitive `org.apfloat:apfloat:1.10.1`: **MIT**. Copyright (C) 2002–2021
  Mikko Tommila. Full license and source link are packaged at `licenses/apfloat/MIT.txt`.
  Source: https://repo.maven.apache.org/maven2/org/apfloat/apfloat/1.10.1/apfloat-1.10.1-sources.jar .

## inkex path-number compatibility

Ink/Stitch v3.3.0 pins Inkscape extensions/inkex at `EXTENSIONS_AT_INKSCAPE_1.4.1`.
Its `inkex/paths/interfaces.py:PathCommand.number_template` uses six significant digits;
our font path canonicalization follows that serialization rule. Source:
https://gitlab.com/inkscape/extensions/-/blob/EXTENSIONS_AT_INKSCAPE_1.4.1/inkex/paths/interfaces.py .
The referenced source is GPL-2.0-or-later, Copyright (C) 2018 Martin Owens and
Copyright (C) 2023 Jonathan Neuhauser. No inkex package is bundled, installed or executed.
The surrounding Ink/Stitch glyph port remains GPL-3.0-or-later as identified in its header.

## Ink/Stitch fixed font-oracle test fixtures

`engine/src/test/resources/inkstitch/font-equivalence/` contains the public upstream generic
A/B test-font input and a subset of independently decoded official v3.3.0 output coordinates.
Source: https://github.com/inkstitch/inkstitch/tree/b0edd96311ece82ee48816dc93466274b12c2a9c/tests/lettering/fonts/testfont .
Copyright Ink/Stitch contributors. Upstream repository license GPL-3.0-or-later and the input's
declared SIL OFL-1.1 text are included beside the data. Source metadata is retained; the one
reversible=false newline correction and all provenance/hashes are recorded in PROVENANCE.md.
Test resources only: no catalog font, personal design or runtime dependency is bundled by this addition.

## Inkex manual cubic-superpath boundary (oracle runner only)

- Project: Inkscape extensions / Inkex, `EXTENSIONS_AT_INKSCAPE_1.4.1`.
- Fixed source: `2044255c7a79a0d0b5fb20cb8b0f573ed1ae7892`, the Inkex tag pinned by Ink/Stitch v3.3.0's `requirements.txt`.
- Sources: [inkex/paths/arc.py](https://gitlab.com/inkscape/extensions/-/blob/2044255c7a79a0d0b5fb20cb8b0f573ed1ae7892/inkex/paths/arc.py), [inkex/paths/path.py](https://gitlab.com/inkscape/extensions/-/blob/2044255c7a79a0d0b5fb20cb8b0f573ed1ae7892/inkex/paths/path.py).
- Copyright (C) 2018 Martin Owens; Copyright (C) 2023 Jonathan Neuhauser.
- Original license: **GPL-2.0-or-later**. This compatible later-version option is exercised for distribution of the Kotlin derivative under **GPL-3.0-or-later**; see the repository [LICENSE](LICENSE) for the GPL v3 text.
- Ported use: `engine/src/oracle/.../ManualCspPath.kt` reproduces the arc-to-CSP sector/node rules and manual subpath closure. It is separate from custom lock paths, which use SVG `Path.end_points` semantics.
- Only source text was inspected. Inkex/Ink/Stitch modules were not imported or executed and no dependencies were installed.

The same runner boundary also ports Inkex `inkex/bezier.py` (`cspsubdiv`, `beziersplitatt`, `maxdist`) and `inkex/transforms.py` (`DirectedLineSegment.distance_to_point`) at commit `2044255c7a79a0d0b5fb20cb8b0f573ed1ae7892`, under GPL-2.0-or-later with the GPL v3 later-version option exercised here. Additional original notices: Copyright (C) 2005 Aaron Spike; Copyright (C) 2010 Nick Drobchenko; Copyright (C) 2006 Jean-Francois Barraud; Copyright (C) 2010 Alvin Penner. Sources: [bezier.py](https://gitlab.com/inkscape/extensions/-/blob/2044255c7a79a0d0b5fb20cb8b0f573ed1ae7892/inkex/bezier.py), [transforms.py](https://gitlab.com/inkscape/extensions/-/blob/2044255c7a79a0d0b5fb20cb8b0f573ed1ae7892/inkex/transforms.py). These controls are transformed before fixed 0.1 SVG-px source subdivision; manual node and custom-lock endpoint semantics remain separate.

## Pystitch output boundary and opt-in writer profile

- Source: [inkstitch/pystitch v1.0.1](https://github.com/inkstitch/pystitch/tree/8fc0b113e02ea81d9a507bfad3f7fd624a8895bd), commit `8fc0b113e02ea81d9a507bfad3f7fd624a8895bd`.
- `SourceOutputBoundary.kt` (oracle runner) follows `EmbMatrix.py` translate/scale evaluation and `EmbEncoder.py` nearest-even native rounding, in addition to Ink/Stitch `lib/output.py`'s origin policy. The optional `inkstitchCommands` writer profile follows `PecWriter.py` viewport-prefix, jump, trim and duplicate-color STOP serialization. The public adapter coordinate contract remains unchanged. Default app PES output additionally applies the SE400 zero-command cleanup and top-left viewport policy documented in docs/sewout-zero-start.md and docs/pes-viewport.md; the explicit source-oracle profile retains its upstream serialization.
- License: **MIT**, Copyright (c) 2018 (the original notice supplies no name). The full MIT grant and disclaimer are the same text reproduced in the pyembroidery section above. No pystitch runtime dependency is added and no original code was executed.
- Version note: pyembroidery 1.5.1 has the same native round rule but different matrix multiplication order; the two implementations are not claimed identical.

## Hangul strict-writing stroke tables — local sweep tooling only (MIT)

- Source: [juunini/count-korean-stroke-js](https://github.com/juunini/count-korean-stroke-js/blob/5f595640db4a059caf0d535fc72a4751a706e077/index.ts), commit `5f595640db4a059caf0d535fc72a4751a706e077`.
- Copyright (c) 2025 Juunini; MIT license reproduced in
  [`tools/licenses/count-korean-stroke-MIT.txt`](tools/licenses/count-korean-stroke-MIT.txt).
- `tools/sweep_korean_ttf.py` translates the `strict=true` initial/vowel/final
  stroke tables to rank the common supported Hangul syllables. This is a
  reproducible writing-stroke convention, not a claim that handwriting has one
  universal stroke count. Upstream JavaScript was read, never executed.
- Tooling only; no new font binaries, private fonts or embroidery designs bundled.

## Potrace-derived binary contour and polygon core (engine-only partial port)

- Source: Potrace 1.16, Copyright (C) 2001–2019 Peter Selinger.
- Official release: https://potrace.sourceforge.net/download/1.16/potrace-1.16.tar.gz
- Source mirror commit: https://github.com/skyrpex/potrace/commit/886392d351417d6753aff61de08e294d1506e502
  (`Merge with v1.16`); `src/decompose.c` and `src/trace.c` are byte-identical to the release archive.
- Kotlin translations: `engine/.../inkstitch/svg/image/BinaryContourTracer.kt` and
  `OptimalPolygon.kt`, `CurveFitter.kt` and `CurveOptimizer.kt`, derived from `src/decompose.c`, `src/bitmap.h`, `src/trace.c`,
  and `src/auxiliary.h`. Modified 2026 by myembro contributors.
- License: **GPL-2.0-or-later**, selecting GPL v3 terms for combination with this
  GPL-3.0-or-later application. Upstream permission is stated in its README;
  the complete upstream GPL v2 text is packaged as `licenses/potrace/COPYING` in the engine JAR/APK.
- This is an unfinished, internal algorithm port, not an endorsed or finished Potrace product.
  No original executable, native library or new dependency is bundled. No application entry point.
  See `docs/image-pipeline.md` for the exact implemented stages and differences.

## Wu color quantizer / Graphics Gems II

- Author: Xiaolin Wu; thanks to Tom Lane for documentation and bug correction.
- Source: `gemsii/quantizer.c` (original C v2 of the 1991 Graphics Gems II algorithm),
  https://github.com/erich666/GraphicsGems/tree/ca4898517a93b6b45444a9cda5fd50132694b864 .
- Kotlin translation: `engine/.../inkstitch/svg/image/ImageQuantizer.kt`.
- Upstream is the custom Graphics Gems permission below, **not MIT or public domain**.
  Original file also states “Free to distribute, comments and suggestions are appreciated.”
  Copyright remains with the original authors; the Kotlin translation is GPL-3.0-or-later
  with upstream notices preserved. No native library or app integration is added.
- Original permission and author notice are packaged under `licenses/graphics-gems/`.

```text
LICENSE

This code repository predates the concept of Open Source, and predates most licenses along such lines. As such, the official license truly is:

EULA: The Graphics Gems code is copyright-protected. In other words, you cannot claim the text of the code as your own and resell it. Using the code is permitted in any program, product, or library, non-commercial or commercial. Giving credit is not required, though is a nice gesture. The code comes as-is, and if there are any flaws or problems with any Gems code, nobody involved with Gems - authors, editors, publishers, or webmasters - are to be held responsible. Basically, don't be a jerk, and remember that anything free comes with no guarantee.
```
