"""Slice a `train.npz` and its sister soft npz into a smaller, hermetic bundle.

usage: slice_data.py <data_dir> <soft.npz> <out_dir> rows=N [seed=0]
                                  |  sentences=N [seed=0]

The new directory is a fully-formed data dir: `train.npz` (just the picked
sentences, token offsets remapped to start at 0), byte copies of `vocab.txt`,
`heldout.npz`, `heldout.tsv`, a `slice.json` (picked source indices, counts,
sha256s), and a `soft.npz` byte-equivalent to the parent on the picked rows
with `sentences` re-indexed into the new file.

Selection: `sentences=N` is a sorted uniform sample of sentence indices;
`rows=N` draws a random permutation and keeps the prefix whose cumulative row
count `sum(starts[i+1] - starts[i])` reaches N, then sorts only for output.
The last sentence may overshoot; the exact total is in `slice.json`.
"""
import argparse
import hashlib
import json
import os
import shutil
import sys

import numpy as np


def _sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _atomic_copy(src, dst):
    tmp = dst + ".tmp"
    shutil.copy2(src, tmp)
    os.replace(tmp, dst)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("data_dir")
    ap.add_argument("soft")
    ap.add_argument("out_dir")
    ap.add_argument("mode")  # the "rows=N" or "sentences=N" arg
    ap.add_argument("seed", nargs="?", default="0")
    ns = ap.parse_args()

    if "=" not in ns.mode:
        ap.error("final mode arg must be of the form rows=N or sentences=N")
    kind, val = ns.mode.split("=", 1)
    if kind not in ("rows", "sentences"):
        ap.error("mode must be rows=N or sentences=N")
    target = int(val)
    seed = int(ns.seed)
    data_dir, soft_path, out_dir = ns.data_dir, ns.soft, ns.out_dir
    os.makedirs(out_dir, exist_ok=True)

    train = np.load(os.path.join(data_dir, "train.npz"))
    tokens_full, starts_full = train["tokens"], train["starts"]
    n_sents = len(starts_full) - 1

    rng = np.random.default_rng(seed)
    if kind == "sentences":
        chosen = np.sort(
            rng.choice(n_sents, size=min(target, n_sents), replace=False)
        ).astype(np.int64)
    else:
        # For ``rows`` mode the sample target is a row count, not a sentence
        # count. Draw a random permutation and take the prefix whose cumulative
        # row count reaches ``target``. Sorting only for output keeps the
        # sample uniform over source indices; the last sentence may overshoot,
        # and the exact total is recorded in ``slice.json``.
        total_rows = int(starts_full[n_sents] - starts_full[0])
        if target > total_rows:
            print(
                f"slice_data: requested rows={target} exceeds total available "
                f"rows={total_rows} (n_sents={n_sents})",
                file=sys.stderr,
            )
            return 3
        if target <= 0:
            chosen = np.empty(0, dtype=np.int64)
        else:
            order = rng.permutation(n_sents)
            lens = (starts_full[order + 1] - starts_full[order]).astype(np.int64)
            take = int(np.searchsorted(np.cumsum(lens), target, side="left")) + 1
            chosen = np.sort(order[:take]).astype(np.int64)

    # Build new train.npz with offsets remapped to start at 0 in original
    # token order (spec: tokens remain in the order the source had them in,
    # no shuffle).
    new_starts = [0]
    for s in chosen:
        new_starts.append(new_starts[-1] + int(starts_full[s + 1] - starts_full[s]))
    new_starts_arr = np.array(new_starts, dtype=np.int64)
    # Concat pieces of tokens for the picked sentences, in `chosen` order.
    pieces = [tokens_full[int(starts_full[s]):int(starts_full[s + 1])] for s in chosen]
    new_tokens = np.concatenate(pieces) if pieces else np.zeros(0, dtype=np.int32)
    assert new_starts_arr[-1] == len(new_tokens), "INVARIANT: starts[-1] != len(tokens)"

    # Remap for the new soft file: sentences -> positions in the new train.
    pos_of = {int(s): i for i, s in enumerate(chosen)}
    train_out = os.path.join(out_dir, "train.npz")
    # Never overwrite an input: the source bundle is the only copy of the
    # corpus prep, and `out_dir == data_dir` would silently truncate it.
    protected = {os.path.realpath(os.path.join(data_dir, n))
                 for n in ("train.npz", "heldout.npz", "vocab.txt", "heldout.tsv")}
    protected.add(os.path.realpath(soft_path))
    for name in ("train.npz", "soft.npz"):
        dst = os.path.realpath(os.path.join(out_dir, name))
        if dst in protected:
            raise SystemExit(f"slice_data: refusing to overwrite input {dst}; choose another out_dir")
    np.savez(train_out + ".tmp.npz", tokens=new_tokens.astype(np.int32), starts=new_starts_arr)
    os.replace(train_out + ".tmp.npz", train_out)

    # Byte-copies of unchanged inputs.
    for name in ("vocab.txt", "heldout.npz", "heldout.tsv"):
        src = os.path.join(data_dir, name)
        if not os.path.exists(src):
            print(f"warning: missing {src}, skipping copy", file=sys.stderr)
            continue
        _atomic_copy(src, os.path.join(out_dir, name))

    # Build the sliced soft npz.
    soft = np.load(soft_path)
    soft_sentences = soft["sentences"]
    soft_starts = soft["starts"]
    soft_ids = soft["ids"]
    soft_probs = soft["probs"]
    # Rows we will keep: those whose sentence index appears in `chosen` AND in
    # the soft file. A picked sentence may have no soft row; we report that as a known miss.
    # Build inverse: which soft sentences correspond to picked train sentences?
    # Fast path: asof join by sorted arrays.
    sent_keep_idx = []
    cs = soft_sentences.astype(np.int64)
    p = 0
    for s in chosen:
        while p < len(cs) and cs[p] < s:
            p += 1
        if p < len(cs) and cs[p] == s:
            sent_keep_idx.append(p)
    out_starts = [0]
    out_ids_parts = []
    out_probs_parts = []
    new_sentences = []
    for j in sent_keep_idx:
        a, b = int(soft_starts[j]), int(soft_starts[j + 1])
        if b <= a:
            continue
        out_ids_parts.append(soft_ids[a:b].copy())  # bit-exact copy of parent's block
        out_probs_parts.append(soft_probs[a:b].copy())
        out_starts.append(out_starts[-1] + (b - a))
        new_sentences.append(int(pos_of[int(cs[j])]))

    # Invariants on the sliced soft.
    assert new_sentences == sorted(new_sentences), "INVARIANT: sliced sentences out of order"
    assert len(new_sentences) == len(set(new_sentences)), "INVARIANT: duplicate sliced sentences"
    if out_ids_parts:
        out_ids = np.concatenate(out_ids_parts)
        out_probs = np.concatenate(out_probs_parts)
    else:
        out_ids = soft_ids[:0].copy()
        out_probs = soft_probs[:0].copy()
    assert int(out_starts[-1]) == len(out_ids), f"INVARIANT: starts[-1]={out_starts[-1]} ids_rows={len(out_ids)}"

    soft_out = os.path.join(out_dir, "soft.npz")
    np.savez(
        soft_out + ".tmp.npz",
        sentences=np.array(new_sentences, dtype=np.int32),
        starts=np.array(out_starts, dtype=np.int64),
        ids=out_ids,
        probs=out_probs,
    )
    os.replace(soft_out + ".tmp.npz", soft_out)

    # Bit-for-bit id/probs equality vs the parent on the picked rows.
    # The build loop above skips sentences with zero soft rows (it does not
    # append to out_starts), so this check must advance its own counter with
    # the appends, not with the position in sent_keep_idx.
    ka = 0
    for sj in sent_keep_idx:
        a, b = int(soft_starts[sj]), int(soft_starts[sj + 1])
        if b <= a:
            continue
        ca = int(out_starts[ka])
        cb = ca + (b - a)
        assert (out_ids[ca:cb] == soft_ids[a:b]).all(), (
            f"INVARIANT: ids of block {ka} not bit-equal to parent rows {a}:{b}"
        )
        assert (out_probs[ca:cb] == soft_probs[a:b]).all(), (
            f"INVARIANT: probs of block {ka} not bit-equal to parent rows {a}:{b}"
        )
        ka += 1

    info = {
        "params": {
            "data_dir": os.path.basename(os.path.normpath(data_dir)),
            "soft": os.path.basename(soft_path),
            "out_dir": os.path.basename(os.path.normpath(out_dir)),
            "mode": ns.mode,
            "seed": seed,
        },
        "picked": [int(s) for s in chosen.tolist()],
        "src_index": {int(s): i for i, s in enumerate(chosen)},
        "train_rows_new": int(len(new_tokens)),
        "soft_sentences_new": int(len(new_sentences)),
        "soft_rows_new": int(len(out_ids)),
        "target": target,
    }
    info.update({
        "vocab_sha256": _sha(os.path.join(out_dir, "vocab.txt")),
        "train_sha256": _sha(train_out),
        "soft_sha256": _sha(soft_out),
    })
    with open(os.path.join(out_dir, "slice.json"), "w", encoding="utf-8") as f:
        json.dump(info, f, indent=2, ensure_ascii=False)
    print(
        f"OK slice {out_dir}: picked {len(chosen)} sentences, train_rows {len(new_tokens)} "
        f"(target {target} mode {kind}), soft_rows {len(out_ids)}",
        file=sys.stderr,
    )


if __name__ == "__main__":
    sys.exit(main())
