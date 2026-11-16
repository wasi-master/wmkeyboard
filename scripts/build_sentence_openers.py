#!/usr/bin/env python3
"""Build app/src/main/assets/dictionaries/openers.tsv: the words that most
often begin a sentence, per language, from Tatoeba.

The n-gram packs are cut from Leipzig sentences and never recorded which word
opened one, so on an empty field (or after a full stop) only English, with its
hand-written `<s>` rows in en_bigrams.txt, had anything to offer. Tatoeba is
short, everyday sentences, which is the register a keyboard types.

    python3 scripts/build_sentence_openers.py --tatoeba DIR \
        [--languages scripts/tatoeba_languages.tsv] [--out ...]

DIR holds `<iso3>.tsv.bz2` files, Tatoeba's per-language exports
(https://downloads.tatoeba.org/exports/per_language/<iso3>/<iso3>_sentences.tsv.bz2).
The languages file maps an app language id to its Tatoeba code, one
`id<TAB>iso3` per line.

Rules:
  - The newest --hold-out sentences of each language are left out, so the
    typing harness, which types the newest ones, never sees its own answers.
  - A word that is written with a capital inside sentences as well is a name
    (Tatoeba's Tom and Mary open a fifth of some languages' sentences), and is
    left out. A cased word never seen inside a sentence cannot be checked and
    is left out too.
  - Tatoeba's names are just as common in scripts that have no capitals
    (টম, टॉम), so every word must also be among the --common commonest words
    of the language's corpus word pairs (`<id>_bigrams.txt.gz` under
    --ngrams, the wmkeyboard-data repo's data/ directory). A language with no
    word pairs to check against keeps only what the capital test passed, and
    one without capitals is skipped.
  - Languages written without spaces between words are skipped: their first
    "word" is the whole sentence.
  - Each word is kept in the shape it has at the start of a sentence, capital
    included, the way the English rows are written.
"""

import argparse
import bz2
import gzip
import collections
import sys
import unicodedata
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
def tokens(text):
    """Space-separated pieces, punctuation trimmed off both ends; a piece
    that holds a digit, or no letter, is not a word. Done by category rather
    than a regular expression because Python's \\w leaves out the vowel signs
    of every Indic script."""
    out = []
    for piece in text.replace("’", "'").split():
        start, end = 0, len(piece)
        while start < end and unicodedata.category(piece[start])[0] in "PSZ":
            start += 1
        while end > start and unicodedata.category(piece[end - 1])[0] in "PSZ":
            end -= 1
        word = piece[start:end]
        if not word or any(c.isdigit() for c in word) or not any(c.isalpha() for c in word):
            continue
        out.append(word)
    return out


# Tatoeba's stock characters in the scripts that have no capitals to give
# them away, matched as a prefix so the inflected forms (टॉमने, ტომმა, টমে)
# go too. The news corpora behind --common know Tom Cruise and Tom Hanks, so
# that check alone lets টম and टॉम through.
NAMES = (
    "\u091f\u0949\u092e",  # टॉम
    "\u099f\u09ae",  # টম
    "\u062a\u0648\u0645",  # توم
    "\u062a\u06c6\u0645",  # تۆم
    "\u05ea\u05d5\u05dd",  # תום
    "\u10e2\u10dd\u10db",  # ტომ
    "\u1276\u121d",  # ቶም
    "\u05d8\u05d0\u05b8\u05dd",  # טאָם
    "\u2d5f\u2d53\u2d4e",  # ⵟⵓⵎ
    "\u0633\u0627\u0645\u06cc",  # سامی
    "\u0633\u0627\u0645\u064a",  # سامي
    "\u0632\u064a\u0631\u064a",  # زيري
)


def common_words(ngrams, lang, limit):
    """The [limit] commonest words of [lang]'s corpus word pairs, lowercased;
    None when there are none to read."""
    if ngrams is None:
        return None
    path = ngrams / lang / f"{lang}_bigrams.txt.gz"
    if not path.exists():
        return None
    counts = collections.Counter()
    with gzip.open(path, "rt", encoding="utf-8", errors="replace") as f:
        for i, line in enumerate(f):
            if i >= 400_000:
                break
            parts = line.split()
            if len(parts) < 3 or not parts[-1].isdigit():
                continue
            n = int(parts[-1])
            for w in parts[:-1]:
                counts[w.lower()] += n
    return {w for w, _ in counts.most_common(limit)}


def openers(path, hold_out, keep, min_sentences, min_share, common):
    rows = []
    with bz2.open(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3 or not parts[0].isdigit():
                continue
            rows.append((int(parts[0]), parts[2]))
    rows.sort()
    if hold_out:
        rows = rows[:-hold_out] if len(rows) > hold_out else []
    if len(rows) < min_sentences:
        return None
    first = collections.Counter()
    inner_lower = collections.Counter()
    inner_upper = collections.Counter()
    lengths = []
    for _, text in rows:
        words = tokens(text)
        lengths.append(len(words))
        if not words:
            continue
        first[words[0]] += 1
        for w in words[1:]:
            key = w.lower()
            if w == key:
                inner_lower[key] += 1
            else:
                inner_upper[key] += 1
    lengths.sort()
    if lengths[len(lengths) // 2] < 3:
        return None  # no spaces between words
    out = []
    for word, count in first.most_common():
        if len(out) >= keep or count < min_share * len(rows):
            break
        key = word.lower()
        cased = key != word or key.upper() != key
        if key.startswith(NAMES):
            continue
        if common is not None and key not in common:
            continue
        if not cased and common is None:
            return None
        if cased:
            lower, upper = inner_lower[key], inner_upper[key]
            if lower < 3 or lower < upper:
                continue
        out.append((word, count))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tatoeba", required=True, type=Path)
    ap.add_argument("--languages", type=Path, default=REPO / "scripts/tatoeba_languages.tsv")
    ap.add_argument("--out", type=Path, default=REPO / "app/src/main/assets/dictionaries/openers.tsv")
    ap.add_argument("--ngrams", type=Path, default=Path.home() / "Work/wmkeyboard-data/data")
    ap.add_argument("--common", type=int, default=3000)
    ap.add_argument("--hold-out", type=int, default=5000)
    ap.add_argument("--keep", type=int, default=12)
    ap.add_argument("--min-sentences", type=int, default=2000)
    ap.add_argument("--min-share", type=float, default=0.004)
    a = ap.parse_args()

    lines = [
        "# Sentence openers per language: id, word, sentences it began.",
        "# Built by scripts/build_sentence_openers.py from Tatoeba (CC BY 2.0 FR).",
        "# English's live in en_bigrams.txt as <s> rows.",
    ]
    kept = 0
    for row in a.languages.read_text().splitlines():
        if not row.strip() or row.startswith("#"):
            continue
        lang, iso3 = row.split("\t")[:2]
        if lang == "en":
            continue
        path = a.tatoeba / f"{iso3}.tsv.bz2"
        if not path.exists():
            continue
        words = openers(path, a.hold_out, a.keep, a.min_sentences, a.min_share,
                        common_words(a.ngrams, lang, a.common))
        if not words or len(words) < 3:
            continue
        kept += 1
        lines += [f"{lang}\t{w}\t{c}" for w, c in words]
    a.out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{kept} languages -> {a.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
