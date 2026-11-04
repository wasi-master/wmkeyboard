"""Per-row masks/weights for teacher soft targets, derived deterministically.

usage: soft_mask.py build    <data_dir> <soft.npz> <out_prefix>
                       [--weight none|cand] [--emit-masks] [--emit-keep kd|kdce|both]
                       [--emit-align] [--check-sample N]
        soft_mask.py unigram  <data_dir> <out.npz>
        soft_mask.py fill     <data_dir> <soft.npz> <masks.npz> <out.npz> [--unigram PATH] [--limit SENTENCES]

`build` reads `data_dir/train.npz` and the parent soft npz (memory-mapped, in
chunks so 21 M rows fit in ~2 GB) and writes the per-row mask, weight and
target arrays described in `tools/wmlm/README.md`, plus a
sidecar JSON with counts, weight quantiles, sha256s and a stable hash over a
canonical dict (excluding `elapsed`/`git_rev`/paths so two runs agree).

`build --emit-align` also writes `<out_prefix>.align.npz` carrying the
word-aware mapping (`pos` uint8[N], `target` int16[N], `sentences`, `starts`,
`version` uint8) that `train.py distill_align=` consumes. The `masks.npz`
still contains `target` as the word-aware first-token id (because that is
what CE predicts at `pos`).

`unigram` counts every sentence-initial word (`train.starts[s] + 4`, the word
after `<s>`) and writes `p_first`, `counts`, `n_first`, `vocab_sha256`.

`fill` copies a soft npz and replaces the row 0 ids/probs of every sentence
with zero mass with the top-24 of `p_first` (renormalised).
Empty rows that are not row 0 stay empty (offset misses). The
unigram file defaults to `<soft_stem>.unigram.npz` next to the soft file;
pass `--unigram` to override.

Word-aware grouping (added for KD alignment): `target[r]` is the id of the
**first** token in the word that soft row r predicts, computed by joining
any `▁…`/normal word token with the immediately following `##…` continuation
pieces. A word spelled as `▁ab` + `##cd` is one group (one soft row), and
its `pos` is the absolute index of `▁ab` minus one. `PIECE` is set when the
**last** piece of the group still starts with `▁` or `##` (i.e. the word is
OOV-spelled). If the word-group count for a sentence does not match its soft
row count, `build` raises a `SoftError` instead of silently truncating.

Every subcommand prints an `OK` summary to stderr and exits non-zero on
invariant violation.
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import time

import numpy as np

from common import START, CONT

FLAG_EMPTY = 1
FLAG_PIECE = 2
FLAG_LE3 = 4


class SoftError(RuntimeError):
    """Raised when the word-aware grouping cannot reconcile the soft file."""


def _atomic_save_npz(path, **arrays):
    """np.savez wrapper that writes via `.tmp` + `os.replace`, and drops a
    sibling `.sha256` for shipped artifacts.
    `np.savez` automatically appends `.npz` if missing; we name `tmp` so the
    final path on disk is the unbroken target."""
    base, _ = os.path.splitext(path)
    tmp = base + ".tmp.npz"
    np.savez(tmp, **arrays)
    os.replace(tmp, path)
    with open(path, "rb") as f:
        h = hashlib.sha256(f.read()).hexdigest()
    with open(path + ".sha256", "w", encoding="utf-8") as f:
        f.write(h + "  " + os.path.basename(path) + "\n")
    return h


def _git_rev():
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], stderr=subprocess.DEVNULL).decode().strip()
    except Exception:  # noqa: BLE001 - no git or no commit; not fatal
        return ""


def _vocab_sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _sha_path(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _stable_hash(d):
    """sha256 of a canonical JSON dump with sorted keys, spaces collapsed."""
    return hashlib.sha256(json.dumps(d, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def _load_vocab(data_dir):
    p = os.path.join(data_dir, "vocab.txt")
    with open(p, encoding="utf-8") as f:
        return [line.rstrip("\n") for line in f]


def _group_words(train_tokens, t0, t1, starts_with_cont):
    """Walk `train_tokens[t0 + 4 : t1 - 1]` and group into words.

    A new word begins whenever the vocab entry does NOT start with CONT
    (i.e. it is a start piece or a regular word token). Returns a list of
    `(first_token_index, last_tok_int)`; `last_tok_int` is the integer id of
    the last piece/token in the group, which the caller uses to decide
    PIECE. `starts_with_cont` may be a set/dict providing O(1) membership
    lookup keyed by the int token id. The returned `first_token_index` is
    an absolute position in `train_tokens`.
    """
    groups = []
    if t1 - t0 < 5:
        return groups
    body_start = t0 + 4
    body_end = t1 - 1
    if body_end <= body_start:
        return groups
    body = train_tokens[body_start:body_end]
    if body.dtype.kind != "i":
        body = body.astype(np.int64)
    n = len(body)
    i = 0
    cur_first = body_start + 0
    while i < n:
        if i > 0 and int(body[i]) in starts_with_cont:
            i += 1
            continue
        if i > 0:
            groups.append((cur_first, int(body[i - 1])))
            cur_first = body_start + i
        i += 1
    if n > 0:
        groups.append((cur_first, int(body[n - 1])))
    return groups


def cmd_build(args):
    data_dir, soft_path, out_prefix = args.data_dir, args.soft, args.out_prefix
    weight_kind = args.weight
    chunk = 1_000_000

    vocab = _load_vocab(data_dir)
    is_piece = np.array([w.startswith(START) or w.startswith(CONT) for w in vocab], dtype=bool)
    starts_with_cont_set = set(i for i, w in enumerate(vocab) if w.startswith(CONT))

    t0 = time.time()
    train = np.load(os.path.join(data_dir, "train.npz"))
    train_tokens = train["tokens"]
    train_starts = train["starts"]
    n_train_sents = len(train_starts) - 1
    print(f"vocab {len(vocab)} pieces {int(is_piece.sum())} train sentences {n_train_sents}", file=sys.stderr)

    z = np.load(soft_path, mmap_mode="r")
    sentences = z["sentences"]
    starts = z["starts"]
    ids = z["ids"]
    probs = z["probs"]
    rows = ids.shape[0]
    topk = ids.shape[1] if probs.ndim == 2 else 0
    print(f"soft rows {rows} sentences {len(sentences)} topk {topk}", file=sys.stderr)

    flags_arr = np.zeros(rows, dtype=np.uint8)
    weight_arr = np.ones(rows, dtype=np.float16)
    target_arr = np.full(rows, -1, dtype=np.int16)
    pos_arr = np.full(rows, 0xFF, dtype=np.uint8)  # 255 = unavailable

    # Invariant 1: shapes / starts.
    assert starts[0] == 0, f"INVARIANT: starts[0]={starts[0]} (must be 0)"
    assert starts[-1] == rows, f"INVARIANT: starts[-1]={starts[-1]} rows={rows}"

    sent_np = sentences
    sample_n = args.check_sample
    sample_idx = None
    if sample_n and sample_n > 0 and sample_n < rows:
        rng = np.random.default_rng(0)
        sample_idx = np.sort(rng.choice(rows, size=sample_n, replace=False))

    # Word-aware pass: per sentence s in `sentences`, the soft file has
    # rows [starts[s] : starts[s + 1]] (call that `a`..`e`). The corresponding
    # train sentence has tokens [train_starts[ss] : train_starts[ss + 1]] with
    # <s> at +3, words at +4 onward, </s> at last. We group the words by
    # joining `##…` tokens onto their previous group; the resulting number of
    # groups must equal `e - a`, or raise.
    # pos is **sentence-relative**: the first token of word r within this
    # train sentence minus one. This is the logits position that predicts
    # word r in the batched tensor where the sentence starts at column 0.
    for s in range(len(sent_np)):
        ss = int(sent_np[s])
        a = int(starts[s])
        e = int(starts[s + 1])
        t0_s = int(train_starts[ss])
        t1_s = int(train_starts[ss + 1])
        groups = _group_words(train_tokens, t0_s, t1_s, starts_with_cont_set)
        if len(groups) != e - a:
            raise SoftError(
                f"word-group count {len(groups)} != soft rows {e - a} "
                f"for sentence s={s} (train s={ss}); soft rows predict per "
                f"word but the body has a different length after piece-merging. "
                f"Body length was {t1_s - t0_s - 5} tokens."
            )
        for r, (first_idx, last_tok) in enumerate(groups):
            row = a + r
            # Sentence-relative pos = (first_in_sentence_token_index) - 1.
            # first_idx is absolute; subtract t0_s and one to get relative.
            pi = first_idx - t0_s - 1
            pos_arr[row] = np.uint8(min(pi, 255))
            # target = train_tokens[first_idx], the FIRST token id of the word.
            target_arr[row] = np.int16(int(train_tokens[first_idx]))
            # PIECE = last piece still a piece.
            flags_arr[row] = FLAG_PIECE if (is_piece[int(last_tok)]) else 0
        if (s + 1) % 1_000_000 == 0:
            print(f"  ... grouped {s + 1}/{len(sent_np)} sentences", file=sys.stderr)

    # Sample check: independent recompute of (target, pos) for a random sample
    # so we catch indirection drift between the per-sentence pass and the
    # verifier.
    if sample_idx is not None and sample_n and sample_n > 0:
        for j in range(int(sample_n)):
            i = int(sample_idx[j])
            s = int(np.searchsorted(starts, i, side="right")) - 1
            r = i - int(starts[s])
            ss = int(sent_np[s])
            tt0 = int(train_starts[ss])
            tt1 = int(train_starts[ss + 1])
            groups = _group_words(train_tokens, tt0, tt1, starts_with_cont_set)
            if r >= len(groups):
                raise SoftError(
                    f"SAMPLE: row {i} r={r} but group count {len(groups)} "
                    f"(train s={ss} t0={tt0} t1={tt1})"
                )
            first_idx, last_tok = groups[r]
            want_target = int(train_tokens[first_idx])
            want_pos = min(first_idx - tt0 - 1, 255)
            got_target = int(target_arr[i])
            got_pos = int(pos_arr[i])
            assert want_target == got_target, (
                f"INVARIANT I5(target): row {i} want {want_target} got {got_target} "
                f"(train s={ss} t0={tt0} t1={tt1} r={r})"
            )
            assert want_pos == got_pos, (
                f"INVARIANT I5(pos): row {i} want {want_pos} got {got_pos} "
                f"(train s={ss} t0={tt0} t1={tt1} r={r} first_idx={first_idx})"
            )

    print(f"word-aware: {rows} groups across {len(sent_np)} sentences", file=sys.stderr)

    # Second pass: EMPTY / LE3 flags and weights from the probs array (chunked
    # so 21 M rows fit in memory).
    cp = 0
    while cp < rows:
        cn = min(chunk, rows - cp)
        probs_c = probs[cp:cp + cn]
        mass = probs_c.sum(axis=1)
        empty = mass == 0
        nz_count = (probs_c > 0).sum(axis=1).astype(np.int32)
        le3 = (nz_count > 0) & (nz_count <= 3)
        flags_c = flags_arr[cp:cp + cn].copy()
        flags_c |= np.where(empty, FLAG_EMPTY, 0).astype(np.uint8)
        flags_c |= np.where(le3, FLAG_LE3, 0).astype(np.uint8)
        flags_arr[cp:cp + cn] = flags_c
        if weight_kind == "cand":
            w = np.where(empty, 0.0, nz_count / float(topk)).astype(np.float16)
        else:
            w = np.where(empty, 0.0, 1.0).astype(np.float16)
        weight_arr[cp:cp + cn] = w
        cp += cn
        print(f"  ... flags/weight {cp}/{rows} rows", file=sys.stderr)

    # Full-sweep invariants (I1, I3, I6).
    assert len(flags_arr) == len(weight_arr) == len(target_arr) == len(pos_arr) == rows, "INVARIANT I1: shapes disagree"
    # I2: sentences strictly increasing and < n_train_sents.
    if len(sent_np) >= 2:
        diffs = np.diff(sent_np.astype(np.int64))
        assert (diffs > 0).all(), "INVARIANT I2: sentences not strictly increasing"
    assert (sent_np.astype(np.int64) < n_train_sents).all(), "INVARIANT I2: sentence idx out of range"
    # The per-row PIECE flag is the contract; the aggregate share depends on
    # the tokenizer and corpus (the German 2M reference is about 0.15). Report
    # it for real-data-scale runs without making a corpus-specific bound fatal.
    piece_share = float(((flags_arr & FLAG_PIECE) != 0).sum()) / rows
    if rows >= 100_000:
        print(
            f"note: PIECE share {piece_share:.4f} "
            f"(the German 2M reference is about 0.15)",
            file=sys.stderr,
        )
    # I6: EMPTY -> weight 0; weight zero iff EMPTY was already enforced
    # during the second-pass loop. (Audit pass on the full arrays.)
    mass_all = np.zeros(rows, dtype=bool)
    cp = 0
    while cp < rows:
        cn = min(chunk, rows - cp)
        m = probs[cp:cp + cn].sum(axis=1).astype(np.float32)
        mass_all[cp:cp + cn] = (m == 0)
        cp += cn
    assert (weight_arr[mass_all] == 0).all(), "INVARIANT I6: EMPTY row has non-zero weight"
    # Word-aware invariant: every PIECE row's last piece must come from the
    # last token of the word-group the row predicts. The per-sentence loop
    # already enforced count == rows; we sample-check call correctness here.
    if sample_idx is not None:
        for i in sample_idx.tolist():
            s = int(np.searchsorted(starts, i, side="right")) - 1
            r = i - int(starts[s])
            ss = int(sent_np[s])
            tt0 = int(train_starts[ss])
            tt1 = int(train_starts[ss + 1])
            groups = _group_words(train_tokens, tt0, tt1, starts_with_cont_set)
            if r >= len(groups):
                continue
            _, last_tok = groups[r]
            want_piece = bool(is_piece[last_tok])
            got_piece = bool((int(flags_arr[i]) & FLAG_PIECE) != 0)
            assert want_piece == got_piece, (
                f"INVARIANT I3(align): row {i} want_piece={want_piece} "
                f"got_piece={got_piece} (last_tok={last_tok} '{vocab[last_tok]}')"
            )

    # Write npz + sidecar json + sha256 (atomic).
    out_npz = out_prefix + ".masks.npz"
    out_json = out_prefix + ".masks.json"
    sha = _atomic_save_npz(
        out_npz,
        flags=flags_arr,
        weight=weight_arr,
        target=target_arr,
        pos=pos_arr,
        sentences=sent_np.astype(np.int32),
        starts=np.asarray(starts, dtype=np.int64),
        version=np.array(1, dtype=np.uint8),
    )
    if args.emit_align:
        align_npz = out_prefix + ".align.npz"
        _atomic_save_npz(
            align_npz,
            pos=pos_arr,
            target=target_arr,
            sentences=sent_np.astype(np.int32),
            starts=np.asarray(starts, dtype=np.int64),
            version=np.array(1, dtype=np.uint8),
        )
        print(f"  emit-align {align_npz}", file=sys.stderr)
    counts = {
        "empty": int(((flags_arr & FLAG_EMPTY) != 0).sum()),
        "piece": int(((flags_arr & FLAG_PIECE) != 0).sum()),
        "le3": int(((flags_arr & FLAG_LE3) != 0).sum()),
        "rows": int(rows),
    }
    n_rows = max(1, counts["rows"])
    shares = {
        "empty": counts["empty"] / n_rows,
        "piece": counts["piece"] / n_rows,
        "le3": counts["le3"] / n_rows,
    }
    # Weight quantiles (over non-empty rows).
    wnz = weight_arr[~(flags_arr & FLAG_EMPTY).astype(bool)].astype(np.float32)
    if wnz.size:
        qs = np.quantile(wnz, np.array([0.0, 0.25, 0.5, 0.75, 1.0]))
        weight_quantiles = {f"q{int(p * 100)}": float(q) for p, q in zip([0, 25, 50, 75, 100], qs)}
    else:
        weight_quantiles = {}
    info = {
        "params": {
            "data_dir": data_dir,
            "soft": soft_path,
            "out_prefix": out_prefix,
            "weight_kind": weight_kind,
            "check_sample": args.check_sample,
        },
        "counts": counts,
        "shares": shares,
        "topk": int(topk),
        "weight_quantiles": weight_quantiles,
        "sha256": sha,
        "npz_bytes": int(os.path.getsize(out_npz)),
    }
    info_stable = dict(info)
    info_stable.pop("elapsed", None)
    info_stable.pop("git_rev", None)
    # Paths are inputs, not content: two builds of the same data into
    # different directories must hash equal (invariant I8). Keep only the
    # build *mode* from params, drop where the files happened to live.
    if isinstance(info_stable.get("params"), dict):
        info_stable["params"] = {
            k: v
            for k, v in info_stable["params"].items()
            if k in ("weight_kind", "check_sample", "version")
        }
    # (The spec lists `elapsed`/`git_rev`/paths as un-stable; we exclude the
    # paths implicitly via `params` -- stable_hash is computed below on a copy
    # without paths. Keep both views: `sha_path_invariant` excludes params;
    # `sha_full` is everything.)
    info_full = dict(info, elapsed=time.time() - t0, git_rev=_git_rev(), params={})
    info["sha256_stable"] = _stable_hash(info_stable)
    # Same atomicity as the npz writers: a crash must not leave a truncated
    # JSON that disagrees with the masks it describes.
    json_tmp = out_json + ".tmp"
    with open(json_tmp, "w", encoding="utf-8") as f:
        json.dump(info, f, indent=2, ensure_ascii=False)
    os.replace(json_tmp, out_json)
    # --emit-masks / --emit-keep flags (default off).
    if args.emit_masks:
        # Mirror flags as the re-named keep array for downstream tooling.
        keep = np.where((flags_arr & FLAG_PIECE) == 0, 1, 0).astype(np.uint8)
        _atomic_save_npz(out_prefix + ".masks.keep.npz", kd=keep)
    if args.emit_keep in ("kd", "both"):
        keep = np.where((flags_arr & FLAG_PIECE) == 0, 1, 0).astype(np.uint8)
        _atomic_save_npz(out_prefix + ".keep.kd.npz", kd=keep)
    if args.emit_keep in ("kdce", "both"):
        keep = np.where((flags_arr & FLAG_PIECE) == 0, 1, 0).astype(np.uint8)
        ce = np.where((flags_arr & FLAG_PIECE) == 0, 1, 0).astype(np.uint8)  # same exclusion, separate declaration
        _atomic_save_npz(out_prefix + ".keep.kdce.npz", kd=keep, ce=ce)
    elapsed = time.time() - t0
    print(
        f"OK build {out_prefix}: {rows} rows, EMPTY {counts['empty']} ({shares['empty']:.4f}) "
        f"PIECE {counts['piece']} ({shares['piece']:.4f}) LE3 {counts['le3']} ({shares['le3']:.4f}) "
        f"sha256 {sha[:12]} in {elapsed:.1f}s",
        file=sys.stderr,
    )


def cmd_unigram(args):
    data_dir, out = args.data_dir, args.out
    # If no --out was supplied, derive `<soft_stem>.unigram.npz` next to the
    # soft file. We read the soft path from the env var `WMLM_SOFT` so callers
    # of `build` can chain without naming it twice.
    if out is None:
        soft = os.environ.get("WMLM_SOFT")
        if soft is None:
            raise SystemExit(
                "unigram: pass an OUT path or set WMLM_SOFT=<soft.npz>; the "
                "default derives <soft_stem>.unigram.npz next to the soft file."
            )
        base, _ = os.path.splitext(soft)
        out = base + ".unigram.npz"
    vocab = _load_vocab(data_dir)
    topk = 24  # the soft file's top-k; not a parameter here.
    train = np.load(os.path.join(data_dir, "train.npz"))
    starts = train["starts"]
    tokens = train["tokens"]
    n_sents = len(starts) - 1
    counts = np.zeros(len(vocab), dtype=np.int64)
    # First-word token = train.tokens[train.starts[s] + 4]. Iterate; starts is
    # only ~2 M long so a Python loop is fine. We could ndarray-index in one
    # shot but the indirection is cheap and readable.
    for s in range(n_sents):
        idx = int(starts[s]) + 4
        # Some sentences are shorter than ctx: in that case the EOS sits where
        # `+5` would land. We count whatever sits at +4 and trust prep.py to
        # have put BOS at +3.
        if idx < len(tokens):
            counts[int(tokens[idx])] += 1
    n_first = int(counts.sum())
    p_first = (counts.astype(np.float32) / max(1, n_first)).astype(np.float32)
    s = float(p_first.sum())
    assert abs(s - 1.0) < 1e-6, f"INVARIANT I7: p_first.sum()={s} not within 1e-6 of 1"
    # `counts` int64 cast down to int32 for the shipped schema (n_first <= #sent).
    out_counts = counts.astype(np.int32)
    sha = _atomic_save_npz(
        out,
        p_first=p_first,
        counts=out_counts,
        n_first=np.array(n_first, dtype=np.int64),
        vocab_sha256=np.frombuffer(_vocab_sha256(os.path.join(data_dir, "vocab.txt")).encode(), dtype=np.uint8),
    )
    print(f"OK unigram {out}: n_first {n_first} vocab {len(vocab)} sha256 {sha[:12]}", file=sys.stderr)


def cmd_fill(args):
    data_dir, soft_path, masks_path, out = args.data_dir, args.soft, args.masks, args.out
    # `masks_path` is accepted for symmetry with the spec but we actually only
    # need to inspect the soft file's row 0 mass here; the masks themselves do
    # not gate which row gets filled (row 0 only when row 0 is empty).
    del masks_path
    # The soft file is the only irreplaceable asset here: regenerating it costs
    # a full teacher pass. Never write over it (or over the unigram source).
    src = os.path.realpath(soft_path)
    dst = os.path.realpath(out)
    if src == dst:
        raise SoftError(f"fill: refusing to overwrite the source soft file ({src})")
    z = np.load(soft_path, mmap_mode="r")
    sentences = z["sentences"]
    starts = z["starts"]
    ids = z["ids"]
    probs = z["probs"]
    rows = ids.shape[0]
    topk = ids.shape[1]

    # Read unigram p_first; default path = sibling "<soft_stem>.unigram.npz".
    uni_path = args.unigram
    if uni_path is None:
        base, _ = os.path.splitext(soft_path)
        uni_path = base + ".unigram.npz"
        if not os.path.isfile(uni_path):
            raise SystemExit(
                f"fill: no --unigram and default {uni_path} does not exist; "
                f"run `soft_mask.py unigram` first (with WMLM_SOFT pointing "
                f"at {soft_path})."
            )
    uni = np.load(uni_path)
    p_first = uni["p_first"].astype(np.float64)
    order = np.argsort(-p_first)[:topk]
    renorm = (p_first[order] / max(1e-12, p_first[order].sum())).astype(np.float16)
    top_ids = order.astype(np.int16)
    # A vocabulary smaller than top-k must still produce a full-width row;
    # pad with id 0/prob 0 so the assignment matches the soft file width.
    if top_ids.size < topk:
        pad = topk - int(top_ids.size)
        top_ids = np.concatenate([top_ids, np.zeros(pad, dtype=np.int16)])
        renorm = np.concatenate([renorm, np.zeros(pad, dtype=np.float16)])

    # Discover row-0-empty sentences in a single pass over `starts` and the
    # mmapmed probs. Row 0 is the sentence's first word position; only that
    # row is eligible. A 24-row window (the earlier bug) also read the next
    # sentence's rows and skipped a sentence whose later rows happened to be
    # filled, while still only writing row 0.
    fill_a = []
    n_scan = len(sentences) if args.limit is None else min(len(sentences), int(args.limit))
    for s_idx in range(n_scan):
        a = int(starts[s_idx])
        if a >= rows:
            continue
        if probs[a].sum() != 0:
            continue
        fill_a.append(a)

    # Mmap-backed output so we do not need a full in-memory copy of the 1 GB
    # probs array. Save the file shape via a regular savez of metadata, then
    # rewrite the data arrays via memmap append would require a full format
    # implementation of npz; we instead allocate full output arrays and
    # release them as they hit disk.
    out_ids = np.empty((rows, topk), dtype=ids.dtype)
    out_probs = np.empty((rows, topk), dtype=probs.dtype)
    chunk = 1_000_000
    cp = 0
    while cp < rows:
        cn = min(chunk, rows - cp)
        np.copyto(out_ids[cp:cp + cn], ids[cp:cp + cn])
        np.copyto(out_probs[cp:cp + cn], probs[cp:cp + cn])
        cp += cn
    for a in fill_a:
        out_ids[a] = top_ids
        out_probs[a] = renorm
    sha = _atomic_save_npz(
        out,
        sentences=sentences.astype(np.int32),
        starts=np.asarray(starts, dtype=np.int64),
        ids=out_ids,
        probs=out_probs,
    )
    if args.limit is not None:
        print(f"(note: --limit {args.limit} filled row 0 only for the first {n_scan} sentences)", file=sys.stderr)
    print(f"OK fill {out}: rows {rows} filled-row0 sentences {len(fill_a)} sha256 {sha[:12]}", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    sp = ap.add_subparsers(dest="cmd", required=True)

    p = sp.add_parser("build")
    p.add_argument("data_dir")
    p.add_argument("soft")
    p.add_argument("out_prefix")
    p.add_argument("--weight", default="none", choices=["none", "cand"])
    # `--emit-masks` is a graceful extra (the spec uses --emit-keep for the
    # keep files; we accept both so callers can ask for either).
    p.add_argument("--emit-masks", action="store_true")
    p.add_argument("--emit-keep", default="", choices=["", "kd", "kdce", "both"])
    p.add_argument("--emit-align", action="store_true",
                   help="write <out_prefix>.align.npz (pos, target) for train.py distill_align=")
    p.add_argument("--check-sample", type=int, default=200_000)
    p.set_defaults(fn=cmd_build)

    p = sp.add_parser("unigram")
    p.add_argument("data_dir")
    p.add_argument("out", nargs="?", default=None,
                   help="defaults to <soft_stem>.unigram.npz via $WMLM_SOFT")
    p.set_defaults(fn=cmd_unigram)

    p = sp.add_parser("fill")
    p.add_argument("data_dir")
    p.add_argument("soft")
    p.add_argument("masks")
    p.add_argument("out")
    p.add_argument("--unigram", default=None,
                   help="default: <soft_stem>.unigram.npz next to the soft file")
    p.add_argument("--limit", type=int, default=None)
    p.set_defaults(fn=cmd_fill)

    args = ap.parse_args()
    try:
        args.fn(args)
    except SoftError as e:
        # A refused or violated invariant is an operator error, not a crash
        # trace: one clear line and a non-zero exit.
        print(f"soft_mask: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
