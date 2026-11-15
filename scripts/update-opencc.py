#!/usr/bin/env python3
"""Refresh the bundled Simplified -> Traditional tables from a pinned OpenCC release (#609).

    scripts/update-opencc.py              # the pinned tag below
    scripts/update-opencc.py ver.1.4.3    # move the pin

The small character and regional tables are bundled in the APK. The phrase
table is a megabyte, so it is a downloadable CJK pack instead: it is written
to the sibling wmkeyboard-data checkout (cjk/s2t_phrases.txt, or $WMKB_DATA/cjk),
and the size and SHA-256 printed for it go into CjkDictCatalog.kt.

HanVariant.kt runs OpenCC's own s2t / s2tw / s2twp / s2hk conversion chains over these
files, so they are copied verbatim rather than flattened: every row keeps all of
its values (the converter takes the first, as OpenCC does), and identity rows
like 皇后 -> 皇后 stay, because they are what stops the character table turning
the 后 into 後.

The one file OpenCC does not ship as text is STPhrases_GeneratedFromRegionalPhrases,
which its Bazel build derives from the Taiwan and Hong Kong phrase keys via t2s.
It is regenerated here the same way (data/scripts/generate_st_phrases_from_
regional_phrases.py) and folded into s2t_phrases.txt, the union s2t.json loads.
OpenCC's t2s also consults TSCharactersExt, itself a build product; it only
covers rare characters, so a regional key it would have reached just never
segments as a unit.

The files are fetched here and committed, not at build time: F-Droid builds
offline from a fixed source tree.
"""

import hashlib
import os
import sys
import urllib.request
from pathlib import Path

TAG = sys.argv[1] if len(sys.argv) > 1 else "ver.1.4.2"
RAW = f"https://raw.githubusercontent.com/BYVoid/OpenCC/{TAG}/data/dictionary"
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/dictionaries"
DATA = Path(os.environ.get("WMKB_DATA", ROOT.parent / "wmkeyboard-data")) / "cjk"

# Bundled asset name -> OpenCC dictionary it is a copy of.
COPIES = {
    "s2t.txt": "STCharacters",
    "tw_phrases.txt": "TWPhrases",
    "tw_variants_phrases.txt": "TWVariantsPhrases",
    "tw_variants.txt": "TWVariants",
    "hk_variants_phrases.txt": "HKVariantsPhrases",
    "hk_variants.txt": "HKVariants",
    # jp2t.json: Japanese new forms to old (kyujitai, #962).
    "jp_shinjitai_phrases.txt": "JPShinjitaiPhrases",
    "jp_shinjitai_characters.txt": "JPShinjitaiCharacters",
}


def fetch(name: str) -> list[tuple[str, str]]:
    """OpenCC text dictionary as (key, values) rows, in file order."""
    with urllib.request.urlopen(f"{RAW}/{name}.txt") as response:
        text = response.read().decode("utf-8")
    rows = []
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        key, _, values = line.partition("\t")
        if key and values:
            rows.append((key, values.strip()))
    return rows


def first(rows: list[tuple[str, str]]) -> dict[str, str]:
    table = {}
    for key, values in rows:
        table.setdefault(key, values.split(" ")[0])
    return table


def max_match(text: str, group: list[dict[str, str]], longest: int) -> str:
    """OpenCC's Conversion::Convert with a short-circuit dictionary group."""
    out, i = [], 0
    while i < len(text):
        for table in group:
            hit = next(
                (n for n in range(min(longest, len(text) - i), 0, -1) if text[i:i + n] in table),
                0,
            )
            if hit:
                out.append(table[text[i:i + hit]])
                i += hit
                break
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def write(name: str, source: str, rows: list[tuple[str, str]], out: Path = OUT) -> Path:
    header = (
        f"# OpenCC {source}, {TAG} (https://github.com/BYVoid/OpenCC), Apache-2.0\n"
        "# Format: key<TAB>value(s), values separated by spaces; the first is used\n"
        "# Regenerate with scripts/update-opencc.py; do not edit by hand.\n"
    )
    body = "".join(f"{key}\t{values}\n" for key, values in rows)
    path = out / name
    path.write_text(header + body, encoding="utf-8")
    print(f"{len(rows):>6}  {path}")
    return path


def main() -> int:
    for asset, source in COPIES.items():
        write(asset, source, fetch(source))

    # t2s.json: TSPhrases, then TSCharacters, short-circuit.
    ts_phrases, ts_chars = first(fetch("TSPhrases")), first(fetch("TSCharacters"))
    longest = max(map(len, ts_phrases))
    projections: dict[str, list[str]] = {}
    for source in ("HKPhrases", "TWPhrases"):
        for key, _ in fetch(source):
            simplified = max_match(key, [ts_phrases, ts_chars], longest)
            # Upstream's rule: a short regional key would split longer
            # Simplified words before STPhrases got the chance to match them.
            if len(simplified) >= 3:
                projections.setdefault(simplified, []).append(key)
    conflicts = {k: v for k, v in projections.items() if len(set(v)) > 1}
    if conflicts:
        for key in sorted(conflicts):
            print(f"conflicting projection {key}: {' '.join(conflicts[key])}", file=sys.stderr)
        return 1

    # s2t.json loads STPhrases and the generated table as one union group.
    st_phrases = fetch("STPhrases")
    seen = {key for key, _ in st_phrases}
    generated = [(key, keys[0]) for key, keys in projections.items() if key not in seen]
    pack = write(
        "s2t_phrases.txt",
        "STPhrases + STPhrases_GeneratedFromRegionalPhrases",
        sorted(st_phrases + generated),
        DATA,
    )
    data = pack.read_bytes()
    print(f"        sizeBytes = {len(data)}, sha256 = {hashlib.sha256(data).hexdigest()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
