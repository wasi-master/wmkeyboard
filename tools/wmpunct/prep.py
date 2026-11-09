"""Vocabulary and token arrays from corpus.py's examples.

usage: prep.py <examples.tsv> <out dir> [vocab=24576 pieces=8192 ctx=48 min_count=3 heldout=40000 seed=0 langs=en,de,...]

Writes out/vocab.txt (one token per line, index = id; the four specials
first, then words, then pieces), out/langs.txt (the language ids in slot
order), out/train.npz and out/heldout.npz: `tokens` int32 flat, `starts`
int64 offsets, `labels` uint8, `langs` uint8. Each example is `<s> w...`,
cut to `ctx` tokens from the middle when a sentence spelled in pieces runs
over, so both its first words and its last survive.

The word slots are shared out between the languages by the square root of
their token counts, so English does not crowd a smaller language out of the
vocabulary, and the rest is filled by overall frequency. Words outside the
vocabulary are spelled in pieces (common.Pieces) built over every
language's letters, so any word of any training language can be spelled.

Two passes over the file, so tens of millions of examples fit in a few GB:
the first counts words per language and dedupes on a hash of the lower-cased
text, the second encodes straight into the arrays. Held-out examples are a
random share of the deduped rows, so no held-out sentence is also a
training one.
"""
import collections
import hashlib
import math
import os
import random
import sys

import numpy as np

from punct_common import BOS, LABELS, NUM, SPECIALS, UNK, Pieces, tokens_of


def opts(argv):
    o = dict(vocab=24576, pieces=8192, ctx=48, min_count=3, heldout=40000, seed=0, langs="")
    for a in argv:
        k, v = a.split("=", 1)
        o[k] = type(o[k])(v) if k in o and not isinstance(o[k], str) else v
    return o


def text_hash(text: str) -> int:
    return int.from_bytes(hashlib.blake2b(text.lower().encode("utf-8"), digest_size=8).digest(), "little")


def rows(src, seen=None):
    """`(lang, label, text)` per usable line; with `seen`, the first of each
    text only (the set is filled as it goes)."""
    with open(src, encoding="utf-8") as f:
        for line in f:
            try:
                lang, label, text = line.rstrip("\n").split("\t", 2)
            except ValueError:
                continue
            if seen is not None:
                h = text_hash(text)
                if h in seen:
                    continue
                seen.add(h)
            yield lang, int(label), text


def main():
    src, out = sys.argv[1:3]
    o = opts(sys.argv[3:])
    os.makedirs(out, exist_ok=True)
    lang_ids = {}
    if o["langs"]:
        for l in o["langs"].split(","):
            lang_ids.setdefault(l, len(lang_ids))

    # Pass 1: counts per language, over deduped rows.
    per_lang_counts = collections.defaultdict(collections.Counter)
    seen = set()
    n_rows = 0
    for lang, label, text in rows(src, seen):
        toks = tokens_of(text)
        if not toks:
            continue
        lang_ids.setdefault(lang, len(lang_ids))
        per_lang_counts[lang].update(toks)
        n_rows += 1
    total_tokens = sum(sum(c.values()) for c in per_lang_counts.values())
    print(f"{n_rows} examples, {total_tokens} tokens, {len(lang_ids)} languages", file=sys.stderr)
    if len(lang_ids) > 255:
        raise SystemExit("more than 255 languages")

    word_slots = o["vocab"] - o["pieces"] - len(SPECIALS)
    weights = {l: math.sqrt(sum(c.values())) for l, c in per_lang_counts.items()}
    z = sum(weights.values()) or 1.0
    vocab = list(SPECIALS)
    chosen = set(vocab)
    for l, c in per_lang_counts.items():
        share = int(word_slots * 0.8 * weights[l] / z)
        for w, n in c.most_common():
            if share <= 0 or n < o["min_count"]:
                break
            if w not in chosen:
                chosen.add(w)
                vocab.append(w)
                share -= 1
    overall = collections.Counter()
    for c in per_lang_counts.values():
        overall.update(c)
    del per_lang_counts
    for w, n in overall.most_common():
        if len(vocab) >= word_slots + len(SPECIALS):
            break
        if n < o["min_count"]:
            break
        if w not in chosen:
            chosen.add(w)
            vocab.append(w)
    oov = {w: n for w, n in overall.items() if w not in chosen and w != NUM}
    alphabet = {ch for w in overall if w != NUM for ch in w}
    pieces = Pieces.build(oov, o["pieces"], alphabet)
    del oov
    vocab.extend(pieces.tokens())
    print(f"{len(pieces.starts)} start + {len(pieces.conts)} continuation pieces; vocab {len(vocab)}", file=sys.stderr)
    ids = {w: i for i, w in enumerate(vocab)}
    covered = sum(n for w, n in overall.items() if w in ids) / max(1, sum(overall.values()))
    print(f"word coverage {covered:.4f}", file=sys.stderr)
    del overall
    with open(os.path.join(out, "vocab.txt"), "w", encoding="utf-8") as f:
        for w in vocab:
            f.write(w + "\n")
    inv = {i: l for l, i in lang_ids.items()}
    with open(os.path.join(out, "langs.txt"), "w", encoding="utf-8") as f:
        for i in range(len(inv)):
            f.write(inv[i] + "\n")

    unk = ids[UNK]
    cache = {}

    def encode(w):
        if w in ids:
            return [ids[w]]
        if w not in cache:
            # Bounded: the cache is cleared before the insert, never after.
            if len(cache) > 2_000_000:
                cache.clear()
            enc = pieces.encode(w)
            cache[w] = [ids[p] for p in enc] if enc else [unk]
        return cache[w]

    # Pass 2: every deduped row goes to the held-out set with probability
    # heldout / rows, else to training, encoded straight into the arrays.
    rng = random.Random(o["seed"])
    held_share = min(1.0, o["heldout"] / max(1, n_rows))
    ctx = o["ctx"]

    class Pack:
        def __init__(self, name):
            self.name = name
            self.tokens, self.starts, self.labels, self.langs = [], [0], [], []
            self.over = 0

        def add(self, lang, label, seq):
            if len(seq) > ctx:
                self.over += 1
                head = ctx // 3
                seq = seq[:head] + seq[-(ctx - head):]
            self.tokens.extend(seq)
            self.starts.append(len(self.tokens))
            self.labels.append(label)
            self.langs.append(lang)

        def save(self):
            np.savez(
                os.path.join(out, self.name),
                tokens=np.array(self.tokens, dtype=np.int32),
                starts=np.array(self.starts, dtype=np.int64),
                labels=np.array(self.labels, dtype=np.uint8),
                langs=np.array(self.langs, dtype=np.uint8),
            )
            dist = collections.Counter(self.labels)
            print(f"{self.name}: {len(self.labels)} examples, {len(self.tokens)} tokens, {self.over} cut to ctx; "
                  + " ".join(f"{LABELS[k]} {dist[k]}" for k in range(len(LABELS))), file=sys.stderr)

    train, held = Pack("train.npz"), Pack("heldout.npz")
    held_text = open(os.path.join(out, "heldout.tsv"), "w", encoding="utf-8")
    seen = set()
    for lang, label, text in rows(src, seen):
        toks = tokens_of(text)
        if not toks:
            continue
        seq = [ids[BOS]]
        for w in toks:
            seq += encode(w)
        if rng.random() < held_share and len(held.labels) < o["heldout"]:
            held.add(lang_ids[lang], label, seq)
            held_text.write(f"{lang}\t{label}\t{' '.join(toks)}\n")
        else:
            train.add(lang_ids[lang], label, seq)
    held_text.close()
    train.save()
    held.save()


if __name__ == "__main__":
    main()
