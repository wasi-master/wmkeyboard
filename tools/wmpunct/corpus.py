"""Training examples out of text corpora: `lang<TAB>label<TAB>sentence`.

usage: corpus.py <lang> <spec> [<spec> ...] >> examples.tsv
  spec = tatoeba:<path>            Tatoeba's <iso3>_sentences.tsv.bz2
         opus:<path>[:<max lines>] an OPUS mono `<lang>.txt.gz` (OpenSubtitles), one line per subtitle
         leipzig:<path>            a Leipzig <name>.tar.gz (news, web, wikipedia)
         lines:<path>              plain text, one sentence per line (gz read if the name ends .gz)
  options: --comma=<share>  share of comma prefixes kept per sentence (default 0.35)
           --max=<n>        at most n examples per spec (after filtering)
           --seed=<n>

Each sentence that ends in one mark this model learns becomes one example
with that mark's label and the mark taken off; a sentence with commas in it
also yields, with probability `--comma`, one of its comma prefixes as a
clause-break example, so the model learns what an unfinished clause looks
like. Lines that hold two sentences, an address, or a dotted abbreviation are
dropped (common.end_label).

Subtitle lines come through a filter of their own: speaker dashes, bracketed
sound cues, song lyrics with ♪ and lines in capitals are skipped.
"""
import bz2
import gzip
import random
import re
import sys
import tarfile

from punct_common import END_MARKS, comma_prefixes, end_label

_CUE = re.compile(r"^\s*[\[(\{].*[\])\}]\s*$")
_NOTE = re.compile(r"[♪♫#]")
_DASHES = re.compile(r"^\s*-+\s*")
_HTML = re.compile(r"<[^>]+>")


def opus_lines(path, limit):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8", errors="replace") as f:
        n = 0
        for line in f:
            if limit and n >= limit:
                break
            n += 1
            s = _HTML.sub("", line).strip()
            if not s or _NOTE.search(s) or _CUE.match(s):
                continue
            s = _DASHES.sub("", s)
            letters = [c for c in s if c.isalpha()]
            if len(letters) >= 6 and sum(c.isupper() for c in letters) > 0.7 * len(letters):
                continue
            yield s


def tatoeba_lines(path):
    with bz2.open(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3 and parts[2].strip():
                yield parts[2].strip()


def leipzig_lines(path):
    with tarfile.open(path, "r:gz") as tar:
        for member in tar.getmembers():
            if member.name.endswith("-sentences.txt"):
                f = tar.extractfile(member)
                for raw in f:
                    line = raw.decode("utf-8", errors="replace").rstrip("\n")
                    parts = line.split("\t", 1)
                    text = parts[1] if len(parts) == 2 else parts[0]
                    if text.strip():
                        yield text.strip()


def plain_lines(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.strip():
                yield line.strip()


def main():
    lang = sys.argv[1]
    comma_share, cap, seed = 0.35, 0, 0
    specs = []
    for a in sys.argv[2:]:
        if a.startswith("--comma="):
            comma_share = float(a.split("=", 1)[1])
        elif a.startswith("--max="):
            cap = int(a.split("=", 1)[1])
        elif a.startswith("--seed="):
            seed = int(a.split("=", 1)[1])
        else:
            specs.append(a)
    rng = random.Random(seed)
    out = sys.stdout
    for spec in specs:
        parts = spec.split(":")
        kind, path = parts[0], parts[1]
        if kind == "tatoeba":
            lines = tatoeba_lines(path)
        elif kind == "opus":
            lines = opus_lines(path, int(parts[2]) if len(parts) > 2 else 0)
        elif kind == "leipzig":
            lines = leipzig_lines(path)
        elif kind == "lines":
            lines = plain_lines(path)
        else:
            raise SystemExit(f"unknown spec {spec}")
        counts = [0, 0, 0, 0]
        seen = set()
        total = 0
        for line in lines:
            if cap and total >= cap:
                break
            # Greek asks with ";" and separates with "·"; the model learns the
            # class, so the mark is read as the question it is.
            if lang == "el":
                line = line.replace(";", "?")
            got = end_label(line)
            if got is None:
                continue
            body, label = got
            key = body.lower()
            if key in seen:
                continue
            seen.add(key)
            if "\t" in body:
                continue
            out.write(f"{lang}\t{label}\t{body}\n")
            counts[label] += 1
            total += 1
            if comma_share > 0 and rng.random() < comma_share:
                prefixes = comma_prefixes(line)
                if prefixes:
                    p = rng.choice(prefixes)
                    if "\t" not in p and p.lower() not in seen:
                        seen.add(p.lower())
                        out.write(f"{lang}\t3\t{p}\n")
                        counts[3] += 1
                        total += 1
        print(f"{lang} {spec}: {total} examples . {counts[0]} ? {counts[1]} ! {counts[2]} , {counts[3]}", file=sys.stderr)


if __name__ == "__main__":
    main()
