"""Tests for tools/wmlm/slice_data.py.

Builds a tiny synthetic train.npz + soft.npz whose sentences have 2..4 rows
each. Asserts the documented invariants of the slicer:

  - ``rows=N`` yields a final token count ``>= N`` rows.
  - Picked sentence indices are strictly increasing (sorted, no duplicates).
  - The sliced soft file's ``sentences`` block is strictly increasing and
    remapped to the new train ordering (no index from the parent should leak
    through unchanged unless 0).
  - ``soft.npz[ids]`` and ``soft.npz[probs]`` are byte-equal to the parent's
    blocks on the picked (parent-id, parent-start) rows.
  - ``N > total`` exits non-zero with a clear error.

Run: ``PYTHONPATH=. python -m unittest tests.test_slice_data -v``
    (``discover -s tools/wmlm/tests`` also picks it up.)
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest

import numpy as np

THIS = os.path.dirname(os.path.abspath(__file__))
WMLM = os.path.dirname(THIS)
SCRIPT = os.path.join(WMLM, "slice_data.py")


def _make_minimal_data(tmpdir: str, n_sents: int = 20) -> tuple[str, str]:
    """Build a (data_dir, soft_path) pair whose sentences have 2..4 token
    rows. The train.npz has ``n_sents`` sentences each with a deterministic
    length: s_i has 2 + (i % 3) tokens (so lengths cycle 2,3,4). The soft.npz
    covers a subset of those sentences with a known block layout so byte
    equality is testable.
    """
    os.makedirs(os.path.join(tmpdir, "data"), exist_ok=True)
    data_dir = os.path.join(tmpdir, "data")
    # Minimal vocab.txt (kept simple; slice_data copies it through).
    with open(os.path.join(data_dir, "vocab.txt"), "w", encoding="utf-8") as f:
        for i in range(8):
            f.write(f"tok{i}\n")

    starts = [0]
    tokens: list[int] = []
    for s in range(n_sents):
        n_rows = 2 + (s % 3)
        for _ in range(n_rows):
            tokens.append((s * 7 + len(tokens)) % 8)
        starts.append(len(tokens))
    starts_arr = np.array(starts, dtype=np.int64)
    tokens_arr = np.array(tokens, dtype=np.int32)
    np.savez(os.path.join(data_dir, "train.npz"),
             tokens=tokens_arr, starts=starts_arr)

    # soft.npz: cover sentences 0..n_sents-1 but skip every 3rd to simulate
    # the "missing soft row" case.
    soft_sent: list[int] = []
    soft_starts: list[int] = [0]
    soft_ids: list[int] = []
    soft_probs: list[float] = []
    per_sent_rows = 0  # how many soft rows for a given parent sentence
    last_for_sent: list[list[tuple[int, float]]] = []

    def _flush(s: int) -> None:
        nonlocal per_sent_rows
        if per_sent_rows == 0:
            return
        block_ids: list[int] = []
        block_probs: list[float] = []
        for r_idx in range(per_sent_rows):
            cid = 1000 * (s + 1) + r_idx
            block_ids.append(cid)
            block_probs.append(0.1 * (cid % 9))
        soft_ids.extend(block_ids)
        soft_probs.extend(block_probs)
        soft_sent.append(s)
        soft_starts.append(len(soft_ids))
        per_sent_rows = 0

    keep_set = set(range(n_sents)) - set(range(0, n_sents, 3))
    for s in range(n_sents):
        if s in keep_set:
            # Use between 1 and len(tokens_in_s) soft rows for this train
            # sentence, mixing padding rows with real ones.
            train_rows = int(starts_arr[s + 1] - starts_arr[s])
            per_sent_rows = max(1, train_rows - 1)
            _flush(s)
        else:
            _flush(s)  # flush whatever was open for the prior keep sentence

    soft_path = os.path.join(tmpdir, "soft.npz")
    np.savez(
        soft_path,
        sentences=np.array(soft_sent, dtype=np.int32),
        starts=np.array(soft_starts, dtype=np.int64),
        ids=np.array(soft_ids, dtype=np.int64),
        probs=np.array(soft_probs, dtype=np.float32),
    )
    return data_dir, soft_path


class TestSliceRowsMode(unittest.TestCase):
    def test_rows_target_met_with_2to4_row_sentences(self) -> None:
        # n_sents=20, lens cycle 2,3,4 -> total rows = 20 * 3 = 60.
        with tempfile.TemporaryDirectory() as td:
            data_dir, soft_path = _make_minimal_data(td, n_sents=20)
            out_dir = os.path.join(td, "out")
            n_target = 25  # not an exact multiple of any sentence; exercises
                           # the rounding loop.
            res = subprocess.run(
                [sys.executable, SCRIPT, data_dir, soft_path, out_dir,
                 f"rows={n_target}", "7"],
                capture_output=True, text=True,
            )
            self.assertEqual(res.returncode, 0,
                             msg=f"stderr:\n{res.stderr}\nstdout:\n{res.stdout}")
            train_new = np.load(os.path.join(out_dir, "train.npz"))
            self.assertGreaterEqual(int(train_new["tokens"].shape[0]), n_target)

            # Slice JSON accounting.
            info = json.load(open(os.path.join(out_dir, "slice.json")))
            self.assertGreaterEqual(info["train_rows_new"], n_target)
            picked = info["picked"]
            self.assertEqual(picked, sorted(picked))
            self.assertEqual(len(picked), len(set(picked)),
                             msg="picked sentence indices must be unique")

            # Byte equality on ids/probs.
            parent_soft = np.load(soft_path)
            child_soft = np.load(os.path.join(out_dir, "soft.npz"))
            self.assertEqual(child_soft["ids"].dtype, parent_soft["ids"].dtype)
            self.assertEqual(child_soft["probs"].dtype, parent_soft["probs"].dtype)
            # Every block in the child must match the corresponding parent
            # block byte-for-byte in the order captured in slice.json's
            # `picked` (filtered by parent soft membership).
            parent_sent_to_blockstart = {
                int(parent_soft["sentences"][i]):
                    (int(parent_soft["starts"][i]),
                     int(parent_soft["starts"][i + 1]))
                for i in range(len(parent_soft["sentences"]))
            }
            child_starts = child_soft["starts"]
            # Build pairs (parent_sent, child_idx) by intersecting ``picked``
            # with parent-soft membership, in picked order.
            parent_kept = [s for s in picked if s in parent_sent_to_blockstart]
            self.assertTrue(len(parent_kept) > 0)
            for j, ps in enumerate(parent_kept):
                pa, pb = parent_sent_to_blockstart[ps]
                ca = int(child_starts[j])
                cb = int(child_starts[j + 1])
                self.assertTrue(
                    np.array_equal(child_soft["ids"][ca:cb],
                                   parent_soft["ids"][pa:pb]),
                    msg=f"ids mismatch for parent sentence {ps}",
                )
                self.assertTrue(
                    np.array_equal(child_soft["probs"][ca:cb],
                                   parent_soft["probs"][pa:pb]),
                    msg=f"probs mismatch for parent sentence {ps}",
                )

            # sentence remap: child sentences must be a strictly increasing
            # sequence and must refer only to
            # positions in [0, len(picked)). Some picked sentences may have
            # no soft row (we deliberately skip every 3rd parent sentence in
            # the soft); that just means the corresponding child sentence
            # index does not appear in `child_sents`.
            child_sents = [int(x) for x in child_soft["sentences"].tolist()]
            self.assertEqual(child_sents, sorted(child_sents))
            self.assertGreater(len(child_sents), 0)
            self.assertLess(max(child_sents), len(picked))
            # If we covered every picked sentence's soft row, the remap
            # would be exactly [0..k-1]. Verify that for the picked subset
            # that *does* have a parent-soft row, the indices are unique and
            # increasing (i.e. strictly monotonic).
            self.assertEqual(len(child_sents), len(set(child_sents)),
                             msg="child sentence indices must be unique")

    def test_n_gt_total_exits_nonzero(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            data_dir, soft_path = _make_minimal_data(td, n_sents=10)
            out_dir = os.path.join(td, "out")
            res = subprocess.run(
                [sys.executable, SCRIPT, data_dir, soft_path, out_dir,
                 "rows=1000000", "0"],
                capture_output=True, text=True,
            )
            self.assertNotEqual(res.returncode, 0)
            combined = res.stderr + res.stdout
            self.assertTrue(
                ("exceeds total" in combined) or ("too few" in combined)
                or ("rows=1000000" in combined) or ("requested rows" in combined),
                msg=f"no clear diagnostic message. stderr:\n{res.stderr}\nstdout:\n{res.stdout}",
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
