"""Romanised Bengali sentences for the mixed English + Banglish model.

usage: banglish.py <leipzig ben tar.gz> <bn_rom.tsv spelling map> <out.tsv> [limit=0] [seed=0]

The data repo's `romanize.py` (Avro-style: "bhalo", "shobdo") turns each
Bengali word into the spelling a phonetic typist would use, and the
keyboard's own spelling map (`app/src/main/assets/dictionaries/bn_rom.tsv`,
roman → Bengali) is read backwards so a word with listed chat spellings
("tmr", "amk", "valo") is written one of those ways half the time: Banglish
has no standard, and the model should know the common variants. Words
already in Latin letters stay as they are, since English inside a Bengali
sentence is typed as English. Lines come out as `register field lang text`
for prep.py, with the casual chat labels and lang slot 1.
"""
import os
import random
import sys
import tarfile
import unicodedata
from pathlib import Path

# The data repo is normally a sibling checkout; allow an explicit override.
sys.path.insert(
    0,
    os.environ.get(
        "WMKEYBOARD_DATA_SCRIPTS",
        str(Path(__file__).resolve().parents[2] / ".." / "wmkeyboard-data" / "scripts"),
    ),
)
from romanize import romanize_bengali  # noqa: E402

BENGALI = range(0x0980, 0x0A00)


def bengali(word):
    return any(ord(c) in BENGALI for c in word)


def load_map(path):
    variants = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or "\t" not in line:
                continue
            roman, word = line.rstrip("\n").split("\t")[:2]
            word = unicodedata.normalize("NFC", word.strip())
            roman = roman.strip().lower()
            if roman and word:
                variants.setdefault(word, []).append(roman)
    return variants


def main():
    tar_path, map_path, out_path = sys.argv[1:4]
    opts = dict(a.split("=") for a in sys.argv[4:])
    limit = int(opts.get("limit", 0))
    rng = random.Random(int(opts.get("seed", 0)))
    variants = load_map(map_path)
    n = 0
    with tarfile.open(tar_path, "r:gz") as tar, open(out_path, "w", encoding="utf-8") as out:
        member = next(m for m in tar.getmembers() if m.name.endswith("-sentences.txt"))
        for raw in tar.extractfile(member):
            line = raw.decode("utf-8", errors="replace").rstrip("\n")
            text = line.split("\t", 1)[-1]
            words = []
            for token in text.split():
                w = unicodedata.normalize("NFC", token.strip("।,.!?\"'()[]{}:;-—–"))
                if not w:
                    continue
                if bengali(w):
                    listed = variants.get(w)
                    if listed and rng.random() < 0.5:
                        words.append(rng.choice(listed))
                    else:
                        words.append(romanize_bengali(w))
                else:
                    words.append(w)
            if len(words) < 2:
                continue
            # register casual (1), field chat (1), lang slot 1
            out.write(f"1\t1\t1\t{' '.join(words)}\n")
            n += 1
            if limit and n >= limit:
                break
    print(f"{n} sentences", file=sys.stderr)


if __name__ == "__main__":
    main()
