"""Vocabulary and token arrays from corpus.py's examples.

usage: prep.py <examples.tsv> <out dir> [vocab=24576 pieces=8192 ctx=48 min_count=3 heldout=40000 seed=0 langs=en,de,...]

Writes out/vocab.txt (one token per line, index = id; the four specials
first, then words, then pieces), out/langs.txt (the language ids in slot
order), out/train.npz and out/heldout.npz: `tokens` int32 flat, `starts`
int64 offsets, `labels` uint8, `langs` uint8. Each example is `<s> w...`,
cut to `ctx` tokens from the middle when a sentence spelled in pieces runs
over, so both its first words and its last survive.

The word slots are shared out between the languages by the square root of
their example counts, so English does not crowd a smaller language out of
the vocabulary, and the rest is filled by overall frequency. Words outside
the vocabulary are spelled in pieces (common.Pieces) built over every
language's letters, so any word of any training language can be spelled.

Held-out examples are drawn after a global dedupe on the lower-cased text, so
no held-out sentence is also a training one.
"""
import collections
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


def main():
    src, out = sys.argv[1:3]
    o = opts(sys.argv[3:])
    os.makedirs(out, exist_ok=True)
    per_lang_counts = collections.defaultdict(collections.Counter)
    examples = []
    seen = set()
    lang_ids = {}
    if o["langs"]:
        for l in o["langs"].split(","):
            lang_ids.setdefault(l, len(lang_ids))
    with open(src, encoding="utf-8") as f:
        for line in f:
            try:
                lang, label, text = line.rstrip("\n").split("\t", 2)
            except ValueError:
                continue
            key = text.lower()
            if key in seen:
                continue
            seen.add(key)
            toks = tokens_of(text)
            if not toks:
                continue
            lang_ids.setdefault(lang, len(lang_ids))
            per_lang_counts[lang].update(toks)
            examples.append((lang_ids[lang], int(label), toks))
    del seen
    total_tokens = sum(sum(c.values()) for c in per_lang_counts.values())
    print(f"{len(examples)} examples, {total_tokens} tokens, {len(lang_ids)} languages", file=sys.stderr)
    if len(lang_ids) > 255:
        raise SystemExit("more than 255 languages")

    word_slots = o["vocab"] - o["pieces"] - len(SPECIALS)
    # Fair shares by the square root of each language's token count.
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
    vocab.extend(pieces.tokens())
    print(f"{len(pieces.starts)} start + {len(pieces.conts)} continuation pieces; vocab {len(vocab)}", file=sys.stderr)
    ids = {w: i for i, w in enumerate(vocab)}
    covered = sum(n for w, n in overall.items() if w in ids) / max(1, sum(overall.values()))
    print(f"word coverage {covered:.4f}", file=sys.stderr)
    with open(os.path.join(out, "vocab.txt"), "w", encoding="utf-8") as f:
        for w in vocab:
            f.write(w + "\n")
    with open(os.path.join(out, "langs.txt"), "w", encoding="utf-8") as f:
        for l, i in sorted(lang_ids.items(), key=lambda kv: kv[1]):
            f.write(l + "\n")

    unk = ids[UNK]
    cache = {}

    def encode(w):
        if w in ids:
            return [ids[w]]
        if w not in cache:
            enc = pieces.encode(w)
            cache[w] = [ids[p] for p in enc] if enc else [unk]
        return cache[w]

    rng = random.Random(o["seed"])
    rng.shuffle(examples)
    held = examples[: o["heldout"]]
    train = examples[o["heldout"]:]
    ctx = o["ctx"]

    def pack(items, name):
        tokens, starts, labels, langs = [], [], [], []
        over = 0
        for lang, label, toks in items:
            seq = [ids[BOS]]
            for w in toks:
                seq += encode(w)
            if len(seq) > ctx:
                over += 1
                keep_head = ctx // 3
                keep_tail = ctx - keep_head
                seq = seq[:keep_head] + seq[-keep_tail:]
            starts.append(len(tokens))
            tokens.extend(seq)
            labels.append(label)
            langs.append(lang)
        starts.append(len(tokens))
        np.savez(
            os.path.join(out, name),
            tokens=np.array(tokens, dtype=np.int32),
            starts=np.array(starts, dtype=np.int64),
            labels=np.array(labels, dtype=np.uint8),
            langs=np.array(langs, dtype=np.uint8),
        )
        dist = collections.Counter(labels)
        print(f"{name}: {len(items)} examples, {len(tokens)} tokens, {over} cut to ctx; "
              + " ".join(f"{LABELS[k]} {dist[k]}" for k in range(len(LABELS))), file=sys.stderr)

    pack(train, "train.npz")
    pack(held, "heldout.npz")
    with open(os.path.join(out, "heldout.tsv"), "w", encoding="utf-8") as f:
        inv = {i: l for l, i in lang_ids.items()}
        for lang, label, toks in held:
            f.write(f"{inv[lang]}\t{label}\t{' '.join(toks)}\n")


if __name__ == "__main__":
    main()
