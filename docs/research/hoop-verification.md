# Hoop verification: 58 machine profiles vs official Brother sources

Checked 2026-11-08 against `main` `8870c5dd`. App values come from
`app/.../ui/machines/MachineProfile.kt` (SE400/SE600/SE700) and
`engine/.../sewout/BrotherMachineCatalog.kt` (55 entries); `docs/machines.md` and the README
table repeat the same values. No app code was changed.

## Method and rules

- Only official Brother sources: download.brother.com manuals and accessory sheets,
  support.brother.com manual pages and accessory lists (`colist.aspx`), brother-usa.com product
  pages, the official accessory catalog (`doch101817/acc_catalog_as_of_august_2025.pdf`).
- Brother home-machine tables print **H × W** (e.g. "18 cm (H) × 13 cm (W)"); they are transposed to
  the app's **W × H** (130×180). PR manuals mix orders ("300 mm (W) × 200 mm (H)" in PR680W/PR1060W/PR1X,
  "200 mm(H) × 360 mm(W)" in PR1050X KR); both give a wide PR field, so the app's 300×200/360×200 are correct.
- Inch values ("5" x 7"") are nominal; mm values from manuals win.
- **Max** = official maximum single embroidery area vs `maximumArea`.
- **Hoops** = official regular rectangular frames (included *or* sold separately: small/medium/large/
  extra-large/square/quilt) vs the app's hoop list. Special frames are listed in notes but not used for the
  verdict: border, magnetic, cap, cylinder, compact, round, clamp, sleeve, jumbo, and multi-position/
  repositioning frames (SA434 100×170, SA445 130×300). SA434 is kept where the app already models it.
- A row is **match** only when both Max and Hoops match. "Unverified" = no official document links the values
  to that model name.
- Page numbers are printed pages unless marked "pdf".

## Summary

| Check | Match | Mismatch | Unverified |
| --- | --- | --- | --- |
| Max area | 57 | **0** | 1 (V3LE) |
| Max + hoop list | 11 | 46 | 1 (V3LE) |

- **No app value exceeds an official value.** Every app hoop is an official frame for its model; every mismatch
  is an official hoop the app does not list (it then shows "Not supported by this machine — check before sewing").
- PR small frame is **60×40** (W×H), not 60×20.
- SA431 small frame has three official layouts on newer 4×4 manuals: 60×20, 50×30, 30×40 (W×H)
  (888-M20 p.64, M00 p.66, M36 p.25, P10 p.68, P30 p.31). The app models only 60×20.
- brother-usa.com SA431/SA434 "For Use With" lists omit SE630/SE700/SE725/PE535/PE545/PE550D/NS1750D,
  contradicting the manuals and support.brother.com accessory lists; the latter were used.

## Results (app W×H, mm)

Official hoop lists: **bold** = included in the box; others sold separately (or "some regions").

### US 4×4

| Model | Official max | Official hoops (W×H) | Source | App max / hoops | Result |
| --- | --- | --- | --- | --- | --- |
| SE400 | 100×100 ("height up to 10 cm … width up to 10 cm") | **100×100 SA432**, 60×20 SA431, SA434 100×170 coverage | [M-885V31] p.8, p.127; [CO] se400eus | 100×100 / 100×100, 60×20, 100×170 cov. | match |
| SE425 | 100×100 | same as SE400 | [M-885V31] p.127; [CO] se425eus | 100×100 / 100×100 | **mismatch**: no 60×20, no 100×170 |
| SE600 | 100×100 ("up to a maximum of 10 cm × 10 cm") | **100×100**, 60×20 SA431, SA434 100×170 (10×10 per position, footnote *2) | [M-888M20] p.8, p.74; [CO] se600eus | 100×100 / 100×100, 60×20, 100×170 cov. | match |
| SE625 | 100×100 | same as SE600 | [M-888M20] p.8, p.74; [CO] se625eus | 100×100 / 100×100 | **mismatch**: no 60×20, no 100×170 |
| SE630 | 100×100 | same as SE600 | [M-888M20] p.8, p.74; [CO] se630eus | 100×100 / 100×100 | **mismatch**: no 60×20, no 100×170 |
| SE700 | 100×100 ("Maximum of 10 cm × 10 cm") | **100×100**, 60×20 SA431, SA434 100×170; magnetic 100×100 | [M-888P10] p.68, p.96; [CO] se700eus | 100×100 / 100×100, 60×20, 100×170 cov. | match |
| SE725 | 100×100 | same as SE700 | [M-888P10] p.96; [CO] se725eus | 100×100 / 100×100 | **mismatch**: no 60×20, no 100×170 |
| PE535 | 100×100 | **100×100**, 60×20 SA431, SA434 100×170 | [M-888M36] p.8, p.35; [CO] pe535eus | 100×100 / 100×100 | **mismatch**: no 60×20, no 100×170 |
| PE550D | 100×100 | same as PE535 | [M-888M36]; [CO] pe550deus | 100×100 / 100×100 | **mismatch**: no 60×20, no 100×170 |
| PE545 | 100×100 ("Maximum of 10 cm × 10 cm") | **100×100**, 60×20 SA431, SA434 100×170; magnetic 100×100 | [M-888P30] p.31, p.55; [CO] pe545eus | 100×100 / 100×100 | **mismatch**: no 60×20, no 100×170 |
| NS1750D | 100×100 | **100×100**, 60×20 SA431, SA434 100×170 | [M-888M00] p.8, p.77; [CO] inovns1750deus | 100×100 / 100×100 | **mismatch**: no 60×20, no 100×170 |

### Korea 4×4

| Model | Official max | Official hoops (W×H) | Source | App max / hoops | Result |
| --- | --- | --- | --- | --- | --- |
| M370 | 100×100 ("최대 10cm × 10cm") | **100×100**, 60×20 (no 17×10 frame in manual) | [M-888P00ko] p.70, p.78, p.98 | 100×100 / 100×100, 60×20 | match |
| M380D | 100×100 | same as M370 | [M-888P00ko] | 100×100 / 100×100, 60×20 | match |
| LB7950 | 100×100 | **100×100**, 60×20 (no 17×10 frame) | [M-888P10ko] p.68, p.76, p.96 | 100×100 / 100×100 | **mismatch**: no 60×20 |
| LB8950 | 100×100 | same as LB7950 | [M-888P10ko] | 100×100 / 100×100 | **mismatch**: no 60×20 |
| LB6950 | 100×100 | **100×100**, 60×20 SA431, SA434 100×170 (10×10 per position) | [M-888M20ko] p.8, p.74 | 100×100 / 100×100, 100×170 cov. | **mismatch**: no 60×20 |
| 이노비스 180 (NV180) | 100×100 | **100×100**, 60×20 SA431, SA434 100×170 | [M-888M00ko] p.8, p.77 | 100×100 / 100×100, 100×170 cov. | **mismatch**: no 60×20 |
| 이노비스 180D | 100×100 | same as NV180 | [M-888M00ko] | same as NV180 | **mismatch**: no 60×20 |
| 이노비스 180K | 100×100 | same as NV180 | [M-888M00ko] | same as NV180 | **mismatch**: no 60×20 |
| 이노비스 900 (NV900) | 100×100 (medium-frame text; no spec line) | **100×100 SA432**, 60×20 SA431, SA434 "H 17 cm × W 10 cm" | [M-NV900ko] p.8, p.143; [M-NV900en] p.9–10, p.145 | 100×100 / 100×100 | **mismatch**: no 60×20, no 100×170 |
| 이노비스 900D | 100×100 | same as NV900 (Korea support lists one "Innov-is 900" entry, `hf_inov900deas`) | same | 100×100 / 100×100 | **mismatch**: no 60×20, no 100×170 |
| 이노비스 950 (NV950) | 100×100 (medium-frame text) | **100×100 EF62**, 60×20 EF61, 100×170 EF71 | [M-NV950ko] p.9–10, p.145 | 100×100 / 100×100 | **mismatch**: no 60×20, no 100×170 |
| 이노비스 980D | 100×100 | same as NV950 | [M-NV950ko] | 100×100 / 100×100 | **mismatch**: no 60×20, no 100×170 |
| 이노비스 980K | 100×100 | same as NV950 | [M-NV950ko] | 100×100 / 100×100 | **mismatch**: no 60×20, no 100×170 |

NV900/NV950/980 manuals describe the 17×10 frame as "used to embroider multiple patterns without changing the
fabric position", with no explicit 10×10-per-position footnote (same wording as SE400 p.127).

### 5×7 and 6×10

| Model | Official max | Official hoops (W×H) | Source | App max / hoops | Result |
| --- | --- | --- | --- | --- | --- |
| PE770 | 130×180 ("height up to 18 cm … width up to 13 cm") | **130×180 SA444**, 100×100 SA443, 60×20 SA442; SA445 130×300 multi-position | [M-PE770] p.5–6, p.42–43 | 130×180 / 130×180 | **mismatch**: no 100×100, 60×20 |
| PE800 | 130×180 ("Embroidery field 18 cm × 13 cm") | **130×180 SA444**, 100×100 SA443, 60×20 SA442; SA445; magnetic 100×180 | [M-PE800] p.B-5, B-6, E-31; [CA] emb_f | 130×180 / 130×180, 100×100, 60×20 | match |
| SE1900 | 130×180 | **130×180 SA444**, 100×100 SA443, 60×20 SA442; SA445 | [M-NS2750D] p.B-6, E-31; [Q-SE1900] | 130×180 / 130×180 | **mismatch**: no 100×100, 60×20 |
| SE2000 | 130×180 ("Maximum of 180 mm × 130 mm") | **130×180**, 100×100, 60×20; magnetic 100×180; SA445 | [M-888K10] p.85, p.99, p.115; [A-SE2000] | 130×180 / 130×180 | **mismatch**: no 100×100, 60×20 |
| PE900 | 130×180 | **130×180**, 100×100, 60×20; magnetic 100×180 | [M-888K80] p.32, p.37, p.58; [A-PE900] | 130×180 / 130×180 | **mismatch**: no 100×100, 60×20 |
| NS2750D | 130×180 | **130×180 SA444**, **SA445 130×300 multi-position**, 100×100 SA443, 60×20 SA442 | [M-NS2750D] p.B-6, B-7; [Q-NS2750D] | 130×180 / 130×180, 100×100, 60×20 | match |
| NQ1400E | 160×260 ("Embroidery field 26 cm × 16 cm") | **160×260 SA441**, 130×180 SA439, 100×100 SA438, 60×20 SA437, 150×150 SA448; border 100×180 | [M-888G80] p.B-5, B-6, B-29 | 160×260 / 160×260 | **mismatch**: no 130×180, 100×100, 60×20, 150×150 |
| NQ1600E | 160×260 | same as NQ1400E | [M-888G90] p.B-5, B-6; [CA] emb_e | 160×260 / 160×260 | **mismatch**: no 130×180, 100×100, 60×20, 150×150 |
| NQ1700E | 160×260 ("maximum of 26 cm × 16 cm") | **160×260**, **130×180**, 100×100, 60×20, 150×150 SA448 (catalog); border | [M-888G60] p.42, p.46; [A-NQ1700E]; [CA] emb_e | 160×260 / 160×260 | **mismatch**: no 130×180, 100×100, 60×20, 150×150 |
| NQ3550W | 160×260 | **160×260**, **130×180**, 100×100, 60×20 (border/square unverified) | [M-888G26] p.96, p.101; [A-NQ3700D] | 160×260 / 160×260 | **mismatch**: no 130×180, 100×100, 60×20 |
| F580 | 130×180 ("최대 180mm × 130mm") | **130×180**, **130×300 multi-position**, 100×100, 60×20; magnetic 100×180 | [M-888K10ko] p.96, p.99, p.115; [A-F580] | 130×180 / 130×180, 100×100 | **mismatch**: no 60×20 |
| 이노비스 NS2750D | 130×180 | same as US NS2750D (Korea page links the same manual) | [M-NS2750D] | 130×180 / 130×180, 100×100, 60×20 | match |
| 이노비스 1250 (NV1250) | 130×180 ("18 cm (H) × 13 cm (W)") | **130×180 SA444**, **SA445 130×300**, 100×100 SA443, 60×20 SA442 | [M-NV1250] p.10–11, p.178 | 130×180 / 130×180, 100×100 | **mismatch**: no 60×20 (no Korea support page found; values from the English NV1250 manual) |
| 이노비스 2600 (NV2600) | 160×260 ("26 cm × 16 cm") | **160×260**, **130×180**, 100×100, 60×20, 150×150; border 100×180 | [M-NV2600ko] p.B-5, B-6 | 160×260 / 160×260, 130×180, 100×100, 60×20 | **mismatch**: no 150×150 |
| 이노비스 2700 (NV2700) | 160×260 | **160×260**, **130×180**, 100×100, 60×20; 150×150 SA448 (catalog e-1) | [M-888G30ko] p.96, p.101; [A-NV2700] | 160×260 / 160×260, 130×180, 100×100 | **mismatch**: no 60×20, 150×150 |

### Large field and PR

| Model | Official max | Official hoops (W×H) | Source | App max / hoops | Result |
| --- | --- | --- | --- | --- | --- |
| XE1 | 240×360 (largest frame "H 36 cm × W 24 cm"; no max line) | **240×360 SA449S**, **130×180 SA439S**, 100×100 (some regions), 200×300 SA447, 240×240 SA450S, 200×200 SA446, 150×150 SA448, 60×20 SA437; border 100×300 | [M-882W80] p.14–15 (pdf) | 240×360 / 240×360 | **mismatch**: no 130×180, 100×100, 200×300, 240×240, 200×200, 150×150, 60×20 |
| XJ1 | 240×360 | **240×360**, **130×180**, **240×240**, **100×100** (US box), 200×300, 200×200, 150×150; border | [M-882W70en] p.19–20 (pdf); [P-XJ1] | 240×360 / 240×360, 130×180, 100×100 | **mismatch**: no 240×240, 200×300, 200×200, 150×150 |
| 이노비스 XJ1 | 240×360 | same as XJ1 (same 882-W70 table) | [M-882W70ko] p.19–20 (pdf) | 240×360 / 240×360, 130×180, 200×300, 240×240, 200×200, 100×100 | **mismatch**: no 150×150 |
| XP1 | 272×408 ("408 mm (H) × 272 mm (W)") | **272×408**, **240×240**, **130×180**, **100×100** | [M-XP1] p.31, p.115 (pdf); [A-XP1]; [FAQ-XP] | 272×408 / 272×408, 240×240, 130×180, 100×100 | match |
| XP2 | 272×408 | 272×408, **272×272**, 240×240 (sold separately), 130×180, 100×100 | [M-XP2] p.31 (pdf); [FAQ-XP] | 272×408 / 272×408, 240×240, 130×180, 100×100 | **mismatch**: no 272×272 |
| XP3 | 272×408 | same as XP2 | [M-XP3] p.29 (pdf); [FAQ-XP] | same as XP2 | **mismatch**: no 272×272 |
| EV1 | 297×465 ("465 mm (H) × 297 mm (W)") | 297×465, 272×272, 130×180, 100×100 | [M-EV1] p.130, p.273 (pdf); [P-EV1] | 297×465 / 297×465, 272×272, 130×180, 100×100 | match |
| PR680W | 300×200 ("300 mm (W) × 200 mm (H)") | **300×200 PRH300**, **180×130 PRH180**, **100×100 PRH100**, **60×40 PRH60**, 200×200 quilt; cap/cylinder/border/round/compact/clamp/magnetic | [M-884T23] p.15–17 (pdf) | 300×200 / 300×200, 180×130, 100×100 | **mismatch**: no 60×40, 200×200 |
| PR680W (한국) | 300×200 | same as PR680W (same 884-T23 manual; Korea part D01H2M-001 for small frame) | [M-884T23] | same as PR680W | **mismatch**: no 60×40, 200×200 |
| PR1050X | 360×200 ("200 mm(H) × 360 mm(W)") | **360×200 PRPH360**, **180×130**, **100×100**, **60×40**, 300×200 PRH300; jumbo 360×360, cap, border, … | [M-PR1050Xko] p.14, p.23–24 (pdf) | 360×200 / 360×200, 180×130, 100×100 | **mismatch**: no 60×40, 300×200 (no separate US manual checked; same model) |
| PR1050X (한국) | 360×200 | same | [M-PR1050Xko] | same | **mismatch**: no 60×40, 300×200 |
| PR1060W (한국) | 360×200 ("360 mm (W) × 200 mm (H)") | **360×200**, **180×130**, **100×100**, **60×40**, 200×200 quilt; jumbo 360×360, cap, border, round | [M-884T15] p.15–16 (pdf) | 360×200 / 360×200, 180×130, 100×100 | **mismatch**: no 60×40, 200×200 |
| PR1X (한국) | 300×200 ("최대 300mm(W) × 200mm(H)") | **300×200**, **100×100**, 180×130 (옵션), 60×40 (옵션), 200×200 quilt; border, sleeve, clamp, compact, cap, magnetic | [M-882T52ko] p.55, p.109 (pdf) | 300×200 / 300×200, 180×130, 100×100 | **mismatch**: no 60×40, 200×200 |
| PRS100 | 200×200 ("200 mm (H) × 200 mm (W)") | **200×200 PRSTH200**, **100×100 PRH100**, compact 70×41/50×50/44×38/75×33 (included), 180×130 PRH180, 60×40 PRH60; flat 200×200, border 180×100, round, cap, cylinder, clamp | [M-882T51] p.10, p.18–19 (pdf); [P-PRS100] | 200×200 / 200×200 | **mismatch**: no 100×100, 180×130, 60×40 |
| 이노비스 4000D (NV4000D) | 180×300 ("300 mm (H) × 180 mm (W)") | **180×300 SA440**, **130×180 SA439**, **100×100 SA438**, **60×20 SA437** | [M-NV4000] p.16, p.176 (pdf) | 180×300 / 180×300, 130×180, 100×100, 60×20 | match (English manual; no Korean manual found) |
| 이노비스 V3 | 180×300 (frame "H 30 cm × W 18 cm") | **180×300 SA440**, 130×180, 100×100 (by region), 150×150 SA448; border 100×300 | [M-V3ko] p.14 (pdf) | 180×300 / 180×300, 130×180, 100×100 | **mismatch**: no 150×150 |
| 이노비스 V3LE | 200×300 in [M-882D82ko] | 200×300 SA447, 200×200 SA446, 130×180, 100×100, 150×150 | [M-882D82ko] p.14 (pdf) | 200×300 / 200×300, 200×200, 130×180, 100×100 | **unverified**: that PDF names only product code 882-D82, never "V3LE"; the V3 manual cover also lists 882-D80/D82 with a 180×300 frame |
| 이노비스 V5 | 180×300 ("최대 30 cm × 18 cm") | **180×300 SA440**, 130×180 and 100×100 (some regions), 60×20 SA437, 150×150 SA448; border | [M-V5ko] p.16 (pdf) | 180×300 / 180×300, 130×180, 100×100 | **mismatch**: no 60×20, 150×150 |
| 이노비스 V5LE | 200×300 ("Maximum 30 cm × 20 cm") | **200×300 SA447**, 130×180 and 100×100 (some regions), 200×200 SA446, 60×20 SA437, 150×150 SA448; border | [M-882C53] p.16, p.18 (pdf) | 200×300 / 200×300, 200×200, 130×180, 100×100 | **mismatch**: no 60×20, 150×150 |

## Mismatch list (46)

All are missing official hoops; no maximum area differs.

- Missing 60×20 and SA434 100×170: SE425, SE625, SE630, SE725, PE535, PE550D, PE545, NS1750D,
  이노비스 900, 900D, 950, 980D, 980K (13)
- Missing 60×20 only: LB7950, LB8950, LB6950, 이노비스 180, 180D, 180K, F580, 이노비스 1250 (8)
- Missing 100×100 and 60×20: PE770, SE1900, SE2000, PE900 (4)
- Missing 130×180, 100×100, 60×20 (+150×150): NQ1400E, NQ1600E, NQ1700E, NQ3550W (4)
- Missing 150×150 (and 60×20): 이노비스 2600, 2700, V3, V5, V5LE (5)
- XE1 (7 frames), XJ1 US (240×240, 200×300, 200×200, 150×150), 이노비스 XJ1 (150×150) (3)
- Missing 272×272: XP2, XP3 (2)
- PR: PR680W, PR680W (한국), PR1060W, PR1X (60×40, 200×200); PR1050X, PR1050X (한국) (60×40, 300×200);
  PRS100 (100×100, 180×130, 60×40) (7)

Also not modeled anywhere: multi-position SA445 130×300 (PE770/PE800/SE1900/SE2000/NS2750D/F580/NV1250)
and the SA431 50×30 / 30×40 small-frame layouts.

## Sources

Manuals ([M-…]) are under `https://download.brother.com/welcome/`:

| Key | Path |
| --- | --- |
| M-885V31 | doch000184/885v31_v32_v33_om04en.pdf |
| M-888M20 | doch101011/888m20_om02en.pdf |
| M-888P10 | doch102582/888p10_om01en.pdf |
| M-888M36 | doch101288/888m36_m37_om01en.pdf |
| M-888P30 | doch102611/888p30_om01en.pdf |
| M-888M00 | doch101020/888_m00_m02_m03_m04_om02en.pdf |
| M-888P00ko | doch102712/888_p00_p02_p03_om01ko.pdf |
| M-888P10ko | doch102717/888p10_om01ko.pdf |
| M-888M20ko | doch101678/888m20_om01ko.pdf |
| M-888M00ko | doch101131/888_m00_m02_m03_m04_om02ko.pdf |
| M-NV900ko / M-NV900en | doch000716/nv900ug01kr.pdf / doch000555/inov900dug03en.pdf |
| M-NV950ko | doch100602/nv950_980om02ko.pdf |
| M-PE770 | doch000034/nv7075pe757778ug03en.pdf |
| M-PE800 | doch100741/888l90_l91_l93_k90_k92_om03en.pdf |
| M-NS2750D | doch100730/888l01_l03_k00_02_om03en.pdf |
| Q-SE1900 / Q-NS2750D | doch101313/888k00_qg01_us-endefrnlitesptbrru.pdf / doch100736/888l01_k02_qg02_us-endefrnlitesptbrru.pdf |
| M-888K10 / M-888K10ko | doch102507/888_k10_l11_l12_om01en.pdf / doch102702/888_k10_l11_l12_om01ko.pdf |
| M-888K80 | doch102511/888_k80_l80_l82_om01en.pdf |
| M-888G80 / M-888G90 / M-888G60 | doch100205/888g80_g82_om02en.pdf / doch100996/888g90_om01en.pdf / doch102340/888g60_g70_g72_om01en.pdf |
| M-888G26 / M-888G30ko | doch102337/888g26_g30_g32_g34_om02en.pdf / doch102458/888g30_g32_g34_om01ko.pdf |
| M-NV1250 / M-NV2600ko | doch000589/nv1250ug02en.pdf / doch100373/NV2600_om01ko.pdf |
| A-SE2000 / A-PE900 / A-NQ1700E | doch102542/f7wcbus02acc01enesfrpt.pdf / doch102518/f7wemus01acc01enesfrpt.pdf / doch102348/nq1700e_acc01enesfrpt.pdf |
| A-NQ3700D / A-F580 / A-NV2700 | doch102347/nq3700d_acc01enesfrpt.pdf / doch102549/f7wcbas01acc01entrarzhszhtko.pdf / doch102411/nq3700d_nv2700_acc01entrarsctcko.pdf |
| M-882W80 / M-882W70en / M-882W70ko | doch101722/882w80_w82_om02en.pdf / doch101717/882w70_om02en.pdf / doch102121/882w70_om02ko.pdf |
| M-XP1 / A-XP1 / M-XP2 / M-XP3 | doch101447/xp1_omemb_04_en.pdf / doch101427/xp1_acc01usca_en.pdf / doch102013/xp2_omemb02usca_en.pdf / doch102491/xp3_omemb01usca_en.pdf |
| M-EV1 | doch103008/ev1om05usas_en.pdf |
| M-884T23 / M-PR1050Xko / M-884T15 / M-882T52ko / M-882T51 | doch102285/884t23_om01en.pdf / doch100893/pr1050x_om01ko.pdf / doch101931/884t15-16_om03_en.pdf / doch103024/882t52_om01ko.pdf / doch100481/882t51om02en.pdf |
| M-NV4000 / M-V3ko / M-882D82ko / M-V5ko / M-882C53 | doch000013/nv4000ug04en.pdf / doch100272/v3om01ko.pdf / doch101849/882d82_om01ko.pdf / doch100271/v5om01ko.pdf / doch101707/882c53_om01en.pdf |
| CA | doch101817/acc_catalog_as_of_august_2025.pdf |

Other official pages:

- [CO] accessory list: `https://support.brother.com/g/b/sp/colist.aspx?c=us_ot&cao=hfemba&lang=en&prod=hf_<code>`
  (e.g. `hf_se425eus`, `hf_inovns1750deus`)
- [FAQ-XP] https://support.brother.com/g/b/faqend.aspx?c=us&lang=en&prod=hf_inovxp3eus&faqid=faqh00101388_000
- [P-XJ1] https://www.brother-usa.com/p/sewing-embroidery-combo/XJ1 ,
  [P-EV1] https://www.brother-usa.com/p/sewing-embroidery-combo/EV1 ,
  [P-PRS100] https://www.brother-usa.com/p/sewing-embroidery/PRS100
- Korea model listing: `https://support.brother.com/g/b/productsearchajax.aspx?series=…&c=kr&lang=ko&pcatid=36`
