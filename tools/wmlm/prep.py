"""Vocabulary and token arrays from a sentences.tsv (see corpus.py).

usage: prep.py <sentences.tsv> <out dir> [vocab=16384] [ctx=32] [seed_words=en.txt]
                [min_count=2] [heldout=20000] [pieces=0]

`pieces=4096` reserves that many of the vocabulary for subword pieces (see
common.Pieces): a word outside the vocabulary is then spelled in pieces
rather than `<unk>`, in training and in the reader.

Writes out/vocab.txt (one token per line, index = id; the 16 specials first),
out/train.npz and out/heldout.npz: `tokens` int32 flat, `starts` int64
sentence offsets (each sentence = reg field lang <s> w... </s>, truncated to
ctx tokens including the prefix and the end marker).

`seed_words` is the keyboard's own frequency list: every word in it that the
corpus has seen at least once goes into the vocabulary first, so the model
has an opinion about the words the strip can actually offer; the rest of
the vocabulary is the corpus's commonest words.
"""
import collections
import os
import random
import sys

import numpy as np

from common import BOS, EOS, FIELDS, LANGS, NUM, REGISTERS, SPECIALS, UNK, Pieces, tokenize


def opts(argv):
    o = dict(vocab=16384, ctx=32, seed_words="", min_count=2, heldout=20000, seed=0, pieces=0)
    for a in argv:
        k, v = a.split("=", 1)
        o[k] = type(o[k])(v) if k in o and not isinstance(o[k], str) else v
    return o


def main():
    src, out = sys.argv[1:3]
    o = opts(sys.argv[3:])
    os.makedirs(out, exist_ok=True)
    counts = collections.Counter()
    sentences = []
    with open(src, encoding="utf-8") as f:
        for line in f:
            reg, field, lang, text = line.rstrip("\n").split("\t", 3)
            toks = tokenize(text)
            if not toks:
                continue
            counts.update(toks)
            sentences.append((int(reg), int(field), int(lang), toks))
    print(f"{len(sentences)} sentences, {sum(counts.values())} tokens, {len(counts)} types", file=sys.stderr)

    vocab = list(SPECIALS)
    seen = set(vocab)
    word_slots = o["vocab"] - o["pieces"]
    if o["seed_words"]:
        with open(o["seed_words"], encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                w = line.split()[0].lower()
                if w in counts and w not in seen and counts[w] >= o["min_count"]:
                    vocab.append(w)
                    seen.add(w)
                if len(vocab) >= word_slots:
                    break
    for w, c in counts.most_common():
        if len(vocab) >= word_slots:
            break
        if c < o["min_count"]:
            break
        if w not in seen:
            vocab.append(w)
            seen.add(w)
    pieces = None
    if o["pieces"] > 0:
        oov = {w: c for w, c in counts.items() if w not in seen and w != NUM}
        alphabet = {ch for w in counts if w != NUM for ch in w}
        pieces = Pieces.build(oov, o["pieces"], alphabet)
        vocab.extend(pieces.tokens())
        print(f"{len(pieces.starts)} start + {len(pieces.conts)} continuation pieces", file=sys.stderr)
    ids = {w: i for i, w in enumerate(vocab)}
    with open(os.path.join(out, "vocab.txt"), "w", encoding="utf-8") as f:
        for w in vocab:
            f.write(w + "\n")
    covered = sum(c for w, c in counts.items() if w in ids) / max(1, sum(counts.values()))
    print(f"vocab {len(vocab)}, token coverage {covered:.4f}", file=sys.stderr)

    unk = ids[UNK]
    piece_cache = {}

    def encode(w):
        if w in ids:
            return [ids[w]]
        if pieces is None:
            return [unk]
        if w not in piece_cache:
            enc = pieces.encode(w)
            piece_cache[w] = [ids[p] for p in enc] if enc else [unk]
        return piece_cache[w]

    rng = random.Random(o["seed"])
    rng.shuffle(sentences)
    held = sentences[: o["heldout"]]
    train = sentences[o["heldout"]:]

    def pack(items, name):
        tokens, starts = [], []
        for reg, field, lang, toks in items:
            starts.append(len(tokens))
            seq = [ids[REGISTERS[reg]], ids[FIELDS[field]], ids[LANGS[lang]], ids[BOS]]
            for w in toks:
                seq += encode(w)
            seq = seq[: o["ctx"] - 1] + [ids[EOS]]
            tokens.extend(seq)
        starts.append(len(tokens))
        np.savez(os.path.join(out, name), tokens=np.array(tokens, dtype=np.int32), starts=np.array(starts, dtype=np.int64))
        print(f"{name}: {len(items)} sentences, {len(tokens)} tokens", file=sys.stderr)

    pack(train, "train.npz")
    pack(held, "heldout.npz")
    # The held-out sentences as text too, for the typing harness
    # (wm-engine's typing_eval), which must never read a training sentence.
    with open(os.path.join(out, "heldout.tsv"), "w", encoding="utf-8") as f:
        for reg, field, lang, toks in held:
            f.write(f"{reg}\t{field}\t{lang}\t{' '.join(toks)}\n")


if __name__ == "__main__":
    main()
