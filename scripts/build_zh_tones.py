#!/usr/bin/env python3
"""
Builds app/src/main/assets/dictionaries/zh_tones.txt, the tones each Chinese
character is read with, for the Zhuyin composer (ZhuyinTones.kt).

The conversion pack the Zhuyin layout reads (pinyin_v2.tsv) has toneless
readings, so a typed tone mark had nothing to choose by: ㄒㄧ with the first
tone and ㄒㄧˋ listed the same characters, 系 ahead of 吸. This table says which
tones a character takes for each of its syllables, so the composer can put the
characters that fit the typed tone first.

Source: Rime's terra_pinyin.dict.yaml (地球拼音, rime-terra-pinyin; data from
CC-CEDICT, Chewing, OpenCC, Android Pinyin IME and moedict; CC BY-SA 3.0 /
LGPL-3.0), which ships in the data repo's cjk/rime/rime_bopomofo.zip. Only
single-character rows are read. Characters are kept when the pinyin pack
writes them, or a Traditional form of one (OpenCC's s2t and TW variants), and
each character takes the tones of its whole Simplified/Traditional family, so
质 has the zhi2 that only 質 lists.

Output, one line per toned syllable: `syllable+tone<TAB>characters`, e.g.
`xi1<TAB>西希息吸…`. Tones 1-4 and 5 (neutral).

Usage:
  python3 scripts/build_zh_tones.py \
      --terra <dir>/terra_pinyin.dict.yaml \
      --pack ../wmkeyboard-data/cjk/pinyin_v2.tsv
"""

import argparse
import re
import zipfile
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ASSETS = REPO / "app" / "src" / "main" / "assets" / "dictionaries"
DATA_REPO = REPO.parent / "wmkeyboard-data" / "cjk"

TONED = re.compile(r"([a-z]+)([1-5])")


def read_terra(path: Path) -> str:
    if path.suffix == ".zip":
        with zipfile.ZipFile(path) as z:
            return z.read("terra_pinyin.dict.yaml").decode("utf-8")
    return path.read_text(encoding="utf-8")


def opencc(path: Path) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        key, _, values = line.partition("\t")
        if len(key) == 1:
            out[key] = [v for v in values.split() if len(v) == 1]
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--terra", type=Path, default=DATA_REPO / "rime" / "rime_bopomofo.zip")
    ap.add_argument("--pack", type=Path, default=DATA_REPO / "pinyin_v2.tsv")
    ap.add_argument("--out", type=Path, default=ASSETS / "zh_tones.txt")
    args = ap.parse_args()

    inventory = {
        s.strip() for s in (ASSETS / "pinyin_syllables.txt").read_text(encoding="utf-8").splitlines()
        if s.strip() and not s.startswith("#")
    }

    body = read_terra(args.terra).split("\n...\n", 1)[1]
    readings: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
    for line in body.splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2 or len(parts[0]) != 1:
            continue
        m = TONED.fullmatch(parts[1])
        if m and m.group(1) in inventory:
            readings[parts[0]][m.group(1)].add(m.group(2))

    # Simplified ↔ Traditional families, both ways.
    family: dict[str, set[str]] = defaultdict(set)
    for table in ("s2t.txt", "tw_variants.txt"):
        for key, values in opencc(ASSETS / table).items():
            for v in values:
                family[key].add(v)
                family[v].add(key)

    wanted: set[str] = set()
    for line in args.pack.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 2:
            wanted.update(parts[1])
    for c in list(wanted):
        wanted.update(family.get(c, ()))

    by_tone: dict[str, list[str]] = defaultdict(list)
    for c in sorted(wanted):
        merged: dict[str, set[str]] = defaultdict(set)
        for member in {c} | family.get(c, set()):
            for syllable, tones in readings.get(member, {}).items():
                merged[syllable] |= tones
        for syllable, tones in merged.items():
            for tone in tones:
                by_tone[syllable + tone].append(c)

    with args.out.open("w", encoding="utf-8") as f:
        f.write("# Tones of each character by syllable, for Zhuyin: syllable+tone<TAB>characters.\n")
        f.write("# From Rime terra_pinyin.dict.yaml (rime-terra-pinyin; CC BY-SA 3.0 / LGPL-3.0).\n")
        f.write("# Regenerate with scripts/build_zh_tones.py; do not edit by hand.\n")
        for key in sorted(by_tone):
            f.write(f"{key}\t{''.join(by_tone[key])}\n")


if __name__ == "__main__":
    main()
