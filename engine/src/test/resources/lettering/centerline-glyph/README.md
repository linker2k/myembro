# Fixed public Hangul raster inputs

These are Noto Sans KR 400 glyphs 정, 민, 안, 녕 from the unchanged bundled OFL-1.1 font.
See manifest.json for the font/input hashes and raster settings; the existing license is
`../../autotrace/thinning/noto_sans_kr_OFL.txt` in these test resources.
Generated once with Pillow binary rendering at 128px, 8px padding, baseline anchor `ls`;
this generator does not run AutoTrace or produce expected centerlines. P5 PGM rows are
0=ink, 255=white. No personal designs or downloaded assets are included.

Both the frozen pre-move chain and the extracted engine chain consume these exact bytes.
This proves chain relocation equivalence, not Pillow/Android rasterizer equivalence.
Normal tests do not run Pillow, decode a TTF, or require a font installed on the host.
