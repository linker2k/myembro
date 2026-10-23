#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Builds app/src/main/assets/fonts/inkstitch-catalog.json from a local, read-only checkout of
https://github.com/inkstitch/embroidery-fonts (data only: nothing from the checkout is executed).

usage: python3 -I tools/inkstitch_catalog.py <checkout> <survey.tsv> <out.json> [licenses.md]

<survey.tsv> comes from the engine test InkStitchSurveyTool (INKSTITCH_FONTS=<checkout>/src); it
says which fonts this engine sews completely and gives the reference ('H') height at scale 1.
The license class of every folder is decided here by hand from its LICENSE file and font.json;
the quoted evidence is written to [licenses.md] so the table can be re-checked.
"""
import csv, hashlib, html, json, math, pathlib, re, subprocess, sys, unicodedata, urllib.parse

REPO = "https://raw.githubusercontent.com/inkstitch/embroidery-fonts"

# Redistributable and usable in a GPL app: OFL, GPL, CC0/public domain, CC BY, CC BY-SA whose
# underlying font is the author's own, public domain or under a free license.
CC_BY_SA_OK = {
    "abecedaire", "abecedaire_small", "braille", "circular_3letters_monogram", "gingo200", "invercelia",  # made for Ink/Stitch
    "apex_lake", "chopin",                      # underlying font public domain
    "geneva_rounded", "geneva_simple",          # Hershey (CC BY-SA 2.5; Hershey terms allow any use)
    "dejavufont",                               # Bitstream Vera / DejaVu license
    "emilio_20", "emilio_20_applique", "emilio_20_bold", "emilio_20_simple", "emilio_20_simple_small",
    "emilio_20_tartan", "emilio_20_tricolore",  # original Emilio 20 is CC BY-SA
}
PUBLIC = {"excalibur_KOR", "excalibur_small", "paquerette"}
CC_BY = {"cogs_KOR"}  # LICENSE says CC BY 4.0 although font.json says CC-BY-SA 4.0
GPL = {"precious"}    # LICENSE is GPL-3.0 although font.json says OFL
# CC BY-SA adaptation of a freeware font whose terms rely on a private permission e-mail or EULA.
CONDITIONAL = {"aventurina", "bluenesia_satin", "cherryforinkstitch", "cherryforkaalleen", "dinomouse72",
               "learning_curve", "monicha", "nick_ainley"}
NONCOMMERCIAL = {"flowery_crosses", "flowery_multicolor", "handkerchief", "ladies_present", "magic_crosses",
                 "nautical", "priscilla", "very_crossy"}
NO_DERIVATIVES = {"infinipicto"}


def classify(name, lic_text, json_license):
    if name in NONCOMMERCIAL: return "불가", "CC BY-NC-SA 4.0", "비상업 조건: GPL 앱(상업 재배포 허용)과 양립 불가"
    if name in NO_DERIVATIVES: return "불가", "CC BY-ND 4.0", "변경 금지: 크기 조정·땀 생성이 변형에 해당할 수 있음"
    if name in CONDITIONAL: return "조건부", "CC BY-SA 4.0 + 원본 프리웨어 조건", "원본 폰트 권리자의 개별 허락(메일)·EULA에 의존"
    if name in PUBLIC: return "가능", "Public domain / CC0", ""
    if name in CC_BY: return "가능", "CC BY 4.0", "font.json은 CC-BY-SA 4.0이라 적었으나 LICENSE는 CC BY 4.0"
    if name in GPL: return "가능", "GPL-3.0", "font.json은 OFL이라 적었으나 LICENSE는 GPL-3.0(원작자 허락 인용)"
    if name in CC_BY_SA_OK:
        return "가능", ("CC BY-SA 2.5" if "2.5" in json_license else "CC BY-SA 4.0"), ""
    if "open font license" in lic_text.lower():
        return "가능", "OFL-1.1", ""
    raise SystemExit(f"unclassified font folder: {name} ({json_license})")


def evidence(lic_text):
    lines = [l.strip() for l in lic_text.splitlines() if l.strip()]
    keys = re.compile(r"licen[cs]|public domain|cc0|creative commons|commercial|permission|accepted|allowed|authori[sz]e", re.I)
    picked = [l for l in lines[:40] if keys.search(l)][:2] or lines[:1]
    return " / ".join(p[:160] for p in picked).replace("|", "\\|")


def glyph_names(svg):
    text = svg.read_text(errors="replace")
    names = {unicodedata.normalize("NFC", html.unescape(m)) for m in re.findall(r'inkscape:label="GlyphLayer-([^"]*)"', text)}
    return sorted(n for n in names if n)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    checkout, survey, out = map(pathlib.Path, sys.argv[1:4])
    md = pathlib.Path(sys.argv[4]) if len(sys.argv) > 4 else None
    commit = subprocess.run(["git", "-C", str(checkout), "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    dirty = subprocess.run(["git", "-C", str(checkout), "status", "--porcelain"], capture_output=True, text=True, check=True).stdout.strip()
    if dirty: raise SystemExit("checkout has local changes; hashes would not match the pinned commit")
    rows = {r["font"]: r for r in csv.DictReader(open(survey), delimiter="\t")}
    fonts, table = [], []
    for d in sorted((checkout / "src").iterdir(), key=lambda p: p.name.lower()):
        meta = json.loads((d / "font.json").read_text(encoding="utf-8-sig"))
        lic = next(p for p in d.iterdir() if p.name.lower() == "license")
        text = lic.read_text(errors="replace")
        cls, lic_name, note = classify(d.name, text, meta.get("font_license", ""))
        r = rows.get(d.name)
        svg = d / "ltr.svg"
        has_h = svg.is_file() and 'inkscape:label="GlyphLayer-H"' in svg.read_text(errors="replace")
        # Commands (start/end positions), empty paths and text objects change no stitches that exist;
        # every other note is an element this engine cannot sew yet.
        missing = [u for u in (r["unsupported"].split(",") if r and r["unsupported"] else [])
                   if not u.startswith(("command:", "empty:", "text="))]
        sewable = r is not None and not missing and not r["error"] and not r.get("rangeError") and has_h
        reason = "" if sewable else ("ltr.svg 없음(여러 파일/RTL)" if not svg.is_file() else
                                     "'H' 글리프 없음" if not has_h else
                                     f"미지원 요소: {','.join(missing)}" if r and missing else
                                     f"봉제 실패: {r['error'] or r.get('rangeError')}" if r else "조사 누락")
        table.append((d.name, meta.get("font_license", ""), lic_name, cls, note, evidence(text), "예" if sewable else f"아니오 ({reason})"))
        if cls != "가능" or not sewable: continue
        q = lambda f: urllib.parse.quote(f"src/{d.name}/{f}")
        ref = float(r["refHeightMm"])
        fonts.append({
            "id": "inkstitch-" + re.sub(r"[^a-z0-9]+", "-", d.name.lower()).strip("-"),
            "name": meta.get("name", d.name),
            "kind": "inkstitch-svg",
            "scripts": ["latin"],
            "license": lic_name,
            "designer": "Ink/Stitch embroidery-fonts contributors" + (f"; original font: {meta['original_font'].strip()}" if (meta.get("original_font") or "").strip() else ""),
            "url": f"{REPO}/{commit}/{q('ltr.svg')}", "sha256": sha(svg), "size": svg.stat().st_size,
            "metaUrl": f"{REPO}/{commit}/{q('font.json')}", "metaSha256": sha(d / "font.json"),
            "licenseUrl": f"{REPO}/{commit}/{q(lic.name)}", "licenseSha256": sha(lic),
            # The digitizer's tested range, on the app's 'H' ink-height basis (half-mm, rounded inward).
            "recommendedMinHeightMm": math.ceil(round(ref * float(r["minScale"]) * 2, 6)) / 2,
            "recommendedMaxHeightMm": math.floor(round(ref * float(r["maxScale"]) * 2, 6)) / 2,
            # Below the app's 6 mm Latin floor only the digitizer's own testing vouches for the font.
            "qualityGrade": "GOOD" if ref * float(r["minScale"]) >= 6.0 else "CAUTION",
            "thinThreadRequired": bool(re.search(r"thin thread|\(60\)|60 ?wt", meta.get("description", ""), re.I)),
            # Glyph names (NFC) and forced case, so the app can tell before download whether a text fits.
            "letterCase": meta.get("letter_case") or "",
            "glyphs": glyph_names(svg),
        })
    out.write_text(json.dumps({
        "version": 1,
        "source": f"https://github.com/inkstitch/embroidery-fonts/tree/{commit}",
        "note": "Pre-digitized Ink/Stitch fonts. Pinned commit; every file is checked against sha256. Only folders "
                "classified 가능 in docs/inkstitch-fonts.md and sewn completely by this engine are listed. Heights are "
                "the reference glyph 'H' ink height at the font's min_scale/max_scale. Generated by tools/inkstitch_catalog.py.",
        "fonts": fonts,
    }, ensure_ascii=False, indent=1) + "\n")
    if md:
        lines = ["| 폴더 | font.json | LICENSE 판정 | 재배포 | 비고 | LICENSE 인용 | 우리 엔진으로 봉제 |", "|---|---|---|---|---|---|---|"]
        lines += ["| " + " | ".join(str(c).replace("\n", " ") for c in t) + " |" for t in table]
        md.write_text(f"<!-- generated by tools/inkstitch_catalog.py from embroidery-fonts {commit} -->\n" + "\n".join(lines) + "\n")
    print(len(fonts), "fonts listed;", sum(1 for t in table if t[3] == "가능"), "가능,",
          sum(1 for t in table if t[3] == "조건부"), "조건부,", sum(1 for t in table if t[3] == "불가"), "불가")


if __name__ == "__main__":
    main()
