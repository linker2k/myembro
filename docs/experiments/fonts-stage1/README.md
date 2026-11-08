# Ink/Stitch fonts stage 1 + cross stitch merge (2026-11-08)

Inputs: embroidery-fonts `c7e3a05c3d6f5c77b881c50f257851af9a8e9cb8` and Ink/Stitch `v3.3.0`, both read-only clones in
`~/myembro-private/refs/` (public data, kept outside the repo only for size).

## Kept results
- `survey.tsv`: `InkStitchSurveyTool.survey` over all 132 `ltr.svg` folders on the merged engine (171acebd); the
  catalog input.
- Sweep results: merged into `docs/inkstitch-font-sweep.json` (101 entries).
- Catalog and license table: `app/src/main/assets/fonts/inkstitch-catalog.json` (88 fonts), table in `docs/inkstitch-fonts.md`.
- Previews: `app/src/main/assets/fonts/previews/inkstitch-{braille,dejavufont,ellenika,paquerette}.png`.

| Font | Sweep (heights passed) | Texts | Catalog range |
|---|---|---|---|
| braille | 9/9 | Hamburg, Jung Min, 2024 | 7.5–21.0 mm |
| dejavufont | 7/7 | Hamburg, Jung Min, 2024 | 13.5–24.5 mm |
| ellenika | 8/8 | 2024, Αθήνα | 15.5–28.0 mm |
| paquerette | 4/4 | Hamburg, Jung Min, 2024 | 23.5–28.0 mm |
| hebrew_simple_rounded(_small), circular_3letters_monogram, invercelia | no sewable sample text | — | excluded (RTL / positional forms) |

Manual stitch oracle (upstream inkex 1.4.1 `strip_control_points` vs our parser):
`MANUAL_ORACLE PASS dejavufont paths=7 nodes=22 worstPx=5.11e-05`,
`MANUAL_ORACLE PASS paquerette paths=1568 nodes=18218 worstPx=5.00e-04`.

## Reproduce
```sh
F=~/myembro-private/refs/embroidery-fonts; O=/tmp/myembro-fonts   # O: throwaway output
git clone https://github.com/inkstitch/embroidery-fonts $F && git -C $F checkout c7e3a05c3d6f5c77b881c50f257851af9a8e9cb8
# oracle (throwaway venv; inkex 1.4.1 without its GUI deps)
python3 -m venv $O/venv && $O/venv/bin/pip install --no-deps inkex==1.4.1 && $O/venv/bin/pip install cssselect "lxml<6" numpy tinycss2 pyparsing
for f in dejavufont paquerette; do $O/venv/bin/python -I tools/inkstitch_manual_oracle.py $F/src/$f $O/oracle/$f.json; done
INKSTITCH_FONTS=$F/src INKSTITCH_MANUAL_ORACLE=$O/oracle ./gradlew :engine:test --tests '*InkStitchManualStitchTest' -i
# survey, sweep (new fonts only), previews
INKSTITCH_FONTS=$F/src INKSTITCH_SURVEY=$O/survey ./gradlew :engine:test --tests '*InkStitchSurveyTool.survey'
INKSTITCH_FONTS=$F/src INKSTITCH_SWEEP=$O/sweep INKSTITCH_SWEEP_FONTS=braille,dejavufont,ellenika,paquerette ./gradlew :engine:test --tests '*InkStitchFontSweep.sweep'
INKSTITCH_FONTS=$F/src INKSTITCH_PREVIEWS=$O/previews INKSTITCH_PREVIEW_IDS=braille,dejavufont,ellenika,paquerette ./gradlew :engine:test --tests '*InkStitchSurveyTool.previews'
# catalog + license table (table = last argument's markdown, pasted from the generated comment line to the end of docs/inkstitch-fonts.md)
python3 -I tools/inkstitch_catalog.py $F $O/survey/survey.tsv app/src/main/assets/fonts/inkstitch-catalog.json $O/licenses.md docs/inkstitch-font-sweep.json
```
