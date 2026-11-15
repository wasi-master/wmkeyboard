"""Hinglish (romanised Hindi) sentences for the mixed English + Hinglish model.

usage: hinglish.py <MADLAD hi_Latn jsonl.gz>... <out.tsv> [limit=0]

The text is MADLAD-400's `hi_Latn` clean split, the same crawl the Hinglish
word pairs are counted from (the data repo's `scripts/build_hi_rom_ngrams.py`),
and it goes through the same filters, imported from that script so the two
never drift apart: a document needs a tenth of its words to be common Hindi
function words, a keyword-stuffed document is dropped whole, so is one with
three or more explicit words, and a line repeated word for word is read once.
On top of that a sentence naming an explicit word is left out wherever it
came from. English inside a Hinglish sentence stays, since that is how people
type it ("office ja raha hun"). Lines come out as `register field lang text`
for prep.py, with the casual chat labels and lang slot 1.
"""
import os
import sys
from collections import Counter
from pathlib import Path

# The data repo is normally a sibling checkout; allow an explicit override.
sys.path.insert(
    0,
    os.environ.get(
        "WMKEYBOARD_DATA_SCRIPTS",
        str(Path(__file__).resolve().parents[2] / ".." / "wmkeyboard-data" / "scripts"),
    ),
)
from build_hi_rom_ngrams import (  # noqa: E402
    EXPLICIT,
    HINDI,
    MIN_HINDI,
    SENTENCE,
    WORD,
    count_run,
    documents,
    stuffed,
)


def sentences(doc, seen_lines):
    """The document's sentences as word lists, or None when the document is dropped."""
    out = []
    for line in doc.lower().split("\n"):
        line = line.strip()
        if not line or hash(line) in seen_lines:
            continue
        seen_lines.add(hash(line))
        for sentence in SENTENCE.split(line):
            words = WORD.findall(sentence)
            if words:
                out.append(words)
    words = sum(len(s) for s in out)
    explicit = sum(w in EXPLICIT for s in out for w in s)
    hindi = sum(w in HINDI for s in out for w in s)
    pairs = Counter()
    for s in out:
        count_run(s, pairs, Counter())
    if not words or hindi < MIN_HINDI * words or explicit >= 3 or stuffed(pairs):
        return None
    return out


def main():
    args = [a for a in sys.argv[1:] if "=" not in a]
    opts = dict(a.split("=", 1) for a in sys.argv[1:] if "=" in a)
    limit = int(opts.get("limit", 0))
    *inputs, out_path = args
    seen_lines = set()
    n = docs = dropped = 0
    with open(out_path, "w", encoding="utf-8") as out:
        for path in inputs:
            for doc in documents(path):
                docs += 1
                kept = sentences(doc, seen_lines)
                if kept is None:
                    dropped += 1
                    continue
                for words in kept:
                    if len(words) < 2 or any(w in EXPLICIT for w in words):
                        continue
                    # register casual (1), field chat (1), lang slot 1
                    out.write(f"1\t1\t1\t{' '.join(words)}\n")
                    n += 1
                    if limit and n >= limit:
                        break
                if limit and n >= limit:
                    break
    print(f"{docs} documents, {dropped} dropped, {n} sentences", file=sys.stderr)


if __name__ == "__main__":
    main()
