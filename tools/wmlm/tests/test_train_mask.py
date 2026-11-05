"""Tests for tools/wmlm/train.py mask/weight/alignment semantics.

(a) mask semantics: with a `kd` mask zeroing half the rows, the KD loss equals
    the same computation restricted to those rows.
(b) weight semantics: all-ones weight reproduces unweighted KD exactly; a
    two-level weight equals the hand-computed weighted mean.
(c) alignment: `distill_align=` scatters rows at their word-aware position.

Run: `python -m unittest tests.test_train_mask -v`.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

import numpy as np
import torch

THIS = os.path.dirname(os.path.abspath(__file__))
WMLM = os.path.dirname(THIS)
sys.path.insert(0, WMLM)

from common import BOS, EOS, REGISTERS, FIELDS, LANGS, START, CONT, SPECIALS  # noqa: E402
import train as train_mod  # the patched module


def _vocab_tokens():
    """Tiny 40-token vocab: 16 specials + START('▁a','▁b','▁c') + CONT('##d','##e')
    + 18 ordinary words — chosen so the vocab has pieces for PIECE-bit testing.
    """
    specials = list(SPECIALS)
    pieces = [START + "a", START + "b", START + "c", CONT + "d", CONT + "e", CONT + "f"]
    words = [f"w{i:02d}" for i in range(18)]
    return specials + pieces + words


def _make_hermetic(tmpdir):
    os.makedirs(tmpdir, exist_ok=True)
    vocab = _vocab_tokens()
    with open(os.path.join(tmpdir, "vocab.txt"), "w", encoding="utf-8") as f:
        for w in vocab:
            f.write(w + "\n")
    ids = {w: i for i, w in enumerate(vocab)}
    rng = np.random.default_rng(0)
    # 20 sentences of varying length, packed into a ctx-bounded representation.
    sentences = []
    for s in range(20):
        # sentence length 4..16 tokens (the prefix+EOS adds 5)
        n = 4 + int(rng.integers(0, 12))
        words = [vocab[16 + (s * 7 + i) % 18 + 6] for i in range(n)]  # ordinary words only
        sentences.append((s % 3, s % 4, s % 4, words))
    tokens = []
    starts = [0]
    for reg, field, lang, wlist in sentences:
        tokens.append(ids[REGISTERS[reg]])
        tokens.append(ids[FIELDS[field]])
        tokens.append(ids[LANGS[lang]])
        tokens.append(ids[BOS])
        for w in wlist:
            tokens.append(ids[w])
        tokens.append(ids[EOS])
        starts.append(len(tokens))
    np.savez(os.path.join(tmpdir, "train.npz"),
             tokens=np.array(tokens, dtype=np.int32),
             starts=np.array(starts, dtype=np.int64))
    # heldout.npz/tsv byte stubs
    np.savez(os.path.join(tmpdir, "heldout.npz"),
             tokens=np.array(tokens[:max(EOS_idx(vocab) + 1, 8)], dtype=np.int32),  # tiny
             starts=np.array([0, len(tokens[:max(EOS_idx(vocab) + 1, 8)])], dtype=np.int64))
    with open(os.path.join(tmpdir, "heldout.tsv"), "w", encoding="utf-8") as f:
        f.write("0\t0\t0\thello\n")
    # Build soft covering sentences 0..9 (10 sentences; pick top-k of vocab by
    # row index). Row 0 of each sentence is empty; rows >=1 are non-empty.
    order = list(range(len(sentences)))
    soft_sents = order[:10]
    soft_starts = [0]
    cur_rows = []
    for s in soft_sents:
        n = starts[s + 1] - starts[s] - 5  # words inside
        if n < 1:
            n = 1
        soft_starts.append(soft_starts[-1] + n)
        cur_rows.append(n)
    rows = soft_starts[-1]
    topk = 24
    ids_arr = np.zeros((rows, topk), dtype=np.int16)
    probs_arr = np.zeros((rows, topk), dtype=np.float16)
    pen = rng.random((rows, topk)).astype(np.float32)
    pen[:, 0] = 0
    # Make rows >=1 have full top-k (id=2..25 of vocab, prob descending).
    cur = 0
    for s_idx, n in enumerate(cur_rows):
        for r in range(n):
            if r == 0:
                continue
            order_i = np.argsort(-pen[cur + r])[:topk]
            ids_arr[cur + r] = (order_i + 6).astype(np.int16) % len(vocab)
            p = np.zeros(topk, dtype=np.float32)
            vals = np.linspace(0.5, 0.001, topk)
            p[:topk] = vals
            p /= p.sum()
            probs_arr[cur + r] = p.astype(np.float16)
        cur += n
    np.savez(os.path.join(tmpdir, "soft.npz"),
             sentences=np.array(soft_sents, dtype=np.int32),
             starts=np.array(soft_starts, dtype=np.int64),
             ids=ids_arr, probs=probs_arr)
    return vocab, ids


def EOS_idx(vocab):
    return vocab.index(EOS)


class MaskSemantics(unittest.TestCase):
    """KD loss with a per-row mask must equal the same computation restricted
    to those rows. `ce` (when present) only gates CE positions."""

    def setUp(self):
        torch.manual_seed(0)
        # Build synthetic data: logits[b, t, v] with v=10, batch=2, t=4.
        B, T, V = 2, 5, 10
        self.logits = torch.randn(B, T, V, dtype=torch.float64) * 2
        # ids/probs row-aligned: ids[b, t, k] are integers; probs sum to <= 1 per row.
        K = 4
        rng = np.random.default_rng(0)
        ids = np.zeros((B, T, K), dtype=np.int64)
        probs = np.zeros((B, T, K), dtype=np.float32)
        rows = B * T
        mask = np.zeros(rows, dtype=np.uint8)
        for b in range(B):
            for t in range(T):
                r = b * T + t
                if t < 1:
                    continue  # row 0 empty
                # Pick 1..3 ids
                n = int(rng.integers(1, K + 1))
                pick = np.argsort(rng.random(V))[:n]
                ids[b, t, :n] = pick
                p = rng.random(n).astype(np.float32)
                p *= 1.0 / p.sum()
                probs[b, t, :n] = 0  # clear
                probs[b, t, :n] = p
                mask[r] = 1
        self.ids = torch.from_numpy(ids)
        self.probs = torch.from_numpy(probs)
        self.mask = mask
        # Hand-computed KD loss restricted to mask==1.
        lse = torch.logsumexp(self.logits, dim=-1)
        picked = torch.gather(self.logits, -1, self.ids)
        per_pos = -(self.probs * (picked - lse.unsqueeze(-1))).sum(-1)
        m_t = torch.from_numpy(mask.reshape(B, T))
        filled = (self.probs.sum(-1) > 0) & (m_t > 0)
        self.want = per_pos[filled].mean().item()

    def test_mask_equals_hand(self):
        m_t = torch.from_numpy(self.mask.reshape(self.logits.shape[0], self.logits.shape[1]))
        got = train_mod.distill_loss(self.logits, self.ids, self.probs, mask=m_t)
        self.assertAlmostEqual(float(got.item()), self.want, places=6)

    def test_no_mask_unchanged(self):
        # When mask is None, distill_loss must return today's result.
        lse = torch.logsumexp(self.logits, dim=-1)
        picked = torch.gather(self.logits, -1, self.ids)
        per_pos = -(self.probs * (picked - lse.unsqueeze(-1))).sum(-1)
        filled = self.probs.sum(-1) > 0
        want = per_pos[filled].mean().item()
        got = train_mod.distill_loss(self.logits, self.ids, self.probs)
        self.assertAlmostEqual(float(got.item()), want, places=6)


class WeightSemantics(unittest.TestCase):
    def setUp(self):
        torch.manual_seed(0)
        B, T, V = 2, 5, 10
        self.logits = torch.randn(B, T, V, dtype=torch.float64) * 2
        K = 4
        rng = np.random.default_rng(1)
        ids = np.zeros((B, T, K), dtype=np.int64)
        probs = np.zeros((B, T, K), dtype=np.float32)
        for b in range(B):
            for t in range(T):
                if t < 1:
                    continue
                n = int(rng.integers(1, K + 1))
                pick = np.argsort(rng.random(V))[:n]
                ids[b, t, :n] = pick
                p = rng.random(n).astype(np.float32) + 0.01
                p /= p.sum()
                probs[b, t, :n] = p
        self.ids = torch.from_numpy(ids)
        self.probs = torch.from_numpy(probs)

    def test_ones_weight_equals_unweighted(self):
        ones = np.ones(self.logits.shape[0] * self.logits.shape[1], dtype=np.float32)
        w_t = torch.from_numpy(ones.reshape(self.logits.shape[0], self.logits.shape[1]))
        got = train_mod.distill_loss(self.logits, self.ids, self.probs, weight=w_t).item()
        # Compare to unweighted
        lse = torch.logsumexp(self.logits, dim=-1)
        picked = torch.gather(self.logits, -1, self.ids)
        per_pos = -(self.probs * (picked - lse.unsqueeze(-1))).sum(-1)
        filled = self.probs.sum(-1) > 0
        want = per_pos[filled].mean().item()
        self.assertAlmostEqual(got, want, places=5)

    def test_two_level_weight_hand(self):
        # Two-level weight: 0.5 on the first B*T//2 rows, 2.0 on the rest.
        B, T = self.logits.shape[0], self.logits.shape[1]
        w = np.full(B * T, 2.0, dtype=np.float32)
        w[: B * T // 2] = 0.5
        w_t = torch.from_numpy(w.reshape(B, T))
        got = train_mod.distill_loss(self.logits, self.ids, self.probs, weight=w_t).item()
        lse = torch.logsumexp(self.logits, dim=-1)
        picked = torch.gather(self.logits, -1, self.ids)
        per_pos = -(self.probs * (picked - lse.unsqueeze(-1))).sum(-1)
        filled = self.probs.sum(-1) > 0
        # Hand-compute weighted mean
        wflat = torch.from_numpy(w.reshape(B, T)) * filled.to(torch.float32)
        want = (per_pos * wflat).sum().item() / max(1e-9, wflat.sum().item())
        self.assertAlmostEqual(got, want, places=5)


class SoftAlign(unittest.TestCase):
    """`distill_align=` scatters rows at their word-aware position.

    The fixture deliberately puts a multi-piece word (`▁ab ##cd`) as a row in
    a small soft file so the tell-tale drift from the legacy `3+r` layout
    shows on rows after it. We then drive `Soft.batch` directly and inspect
    the returned tensors."""

    VOCAB = list(SPECIALS) + ["alpha", "bravo", "charlie",
                              START + "ab", CONT + "cd", START + "x"]

    def setUp(self):
        ids = {w: i for i, w in enumerate(self.VOCAB)}
        # Single train sentence: REG FLD LNG BOS alpha bravo ▁ab ##cd alpha
        # bravo EOS. word-groups: alpha/bravo/▁ab+##cd/alpha/bravo = 5 groups
        # at abs positions 4,5,6,8,9 in the token stream.
        tokens = [ids[REGISTERS[0]], ids[FIELDS[0]], ids[LANGS[0]], ids[BOS],
                  ids["alpha"], ids["bravo"], ids[START + "ab"], ids[CONT + "cd"],
                  ids["alpha"], ids["bravo"], ids[EOS]]
        self.train = {"tokens": tokens, "starts": [0, len(tokens)]}
        # Soft: 5 rows for the one train sentence. Row 0 is left empty
        # (matches production; build flags it EMPTY).
        topk = 24
        rows = 5
        ids_arr = np.zeros((rows, topk), dtype=np.int16)
        probs_arr = np.zeros((rows, topk), dtype=np.float16)
        ids_arr[1:, 0] = ids["alpha"]
        probs_arr[1:, 0] = 0.5
        probs_arr[1:, 1:] = 0.5 / (topk - 1)
        ids_arr[1:, 1:] = ids["bravo"]
        self.soft = {
            "sentences": np.array([0], dtype=np.int32),
            "starts": np.array([0, rows], dtype=np.int64),
            "ids": ids_arr, "probs": probs_arr,
        }
        # Align pos (from build output): uint8[rows] = [3, 4, 5, 7, 8].
        self.pos = np.array([3, 4, 5, 7, 8], dtype=np.uint8)

    def _make_align(self, tmpdir):
        np.savez(os.path.join(tmpdir, "align.npz"),
                 pos=self.pos,
                 target=self.soft["ids"].astype(np.int16)[:, 0],
                 sentences=self.soft["sentences"],
                 starts=self.soft["starts"],
                 version=np.array(1, dtype=np.uint8))

    def _make_soft(self, tmpdir):
        np.savez(os.path.join(tmpdir, "soft.npz"),
                 sentences=self.soft["sentences"],
                 starts=self.soft["starts"],
                 ids=self.soft["ids"], probs=self.soft["probs"])

    def test_align_batch_positions(self):
        tmpdir = tempfile.mkdtemp(prefix="align_")
        try:
            self._make_soft(tmpdir)
            self._make_align(tmpdir)
            soft = train_mod.Soft(os.path.join(tmpdir, "soft.npz"), align_path=os.path.join(tmpdir, "align.npz"))
            # T = 32; we just need len(self.pos) <= T-1.
            T = 32
            dev = torch.device("cpu")
            ids_t, _ = soft.batch([0], T, dev)
            ids_np = ids_t.numpy()[0]  # shape (T-1, K)
            # position 3 receives the first row, position 4 the second, ...
            # position 5 (an entire multi-piece word row) gets row 2.
            # position 7 receives row 3, position 8 receives row 4 (rows 3
            # and 4 are after the multi-piece word, where the legacy 3+r
            # layout would have placed them at 6 and 7).
            np.testing.assert_array_equal(ids_np[3, 0], int(self.soft["ids"][0, 0]))
            np.testing.assert_array_equal(ids_np[4, 0], int(self.soft["ids"][1, 0]))
            np.testing.assert_array_equal(ids_np[5, 0], int(self.soft["ids"][2, 0]))
            np.testing.assert_array_equal(ids_np[7, 0], int(self.soft["ids"][3, 0]))
            np.testing.assert_array_equal(ids_np[8, 0], int(self.soft["ids"][4, 0]))
            # Positions 6 is empty (between the multi-piece word's first-token
            # position 5 and the next word's first-token position 7): the
            # second piece ##cd lives at abs index 7, but in word-alignment
            # this is not a teaching row. Legacy layout would have placed
            # row 3 at position 6 instead.
            self.assertEqual(float(ids_np[6].sum()), 0.0)
            # pos value deviation from `3+r` for rows after the multi-piece
            # word (row index 3, 4) — manually verified above.
            for k in (3, 4):
                self.assertNotEqual(int(self.pos[k]), 3 + k,
                                    f"row {k} should drift from 3+r")
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)

    def test_batch_matches_teacher_offsets(self):
        """Soft.batch and the gate builders use one position mapping."""
        tmpdir = tempfile.mkdtemp(prefix="align_offsets_")
        try:
            self._make_soft(tmpdir)
            self._make_align(tmpdir)
            soft = train_mod.Soft(
                os.path.join(tmpdir, "soft.npz"),
                align_path=os.path.join(tmpdir, "align.npz"),
            )
            T = 32
            dev = torch.device("cpu")
            ids_t, probs_t = soft.batch([0], T, dev)
            ids_np, probs_np = ids_t.numpy()[0], probs_t.numpy()[0]
            offsets = train_mod.teacher_row_offsets(soft, [0], T)[0, : T - 1]
            for pos, row in enumerate(offsets):
                if row >= 0:
                    np.testing.assert_array_equal(ids_np[pos], soft.ids[row])
                    np.testing.assert_array_equal(probs_np[pos], soft.probs[row])
                else:
                    self.assertEqual(float(ids_np[pos].sum()), 0.0)
                    self.assertEqual(float(probs_np[pos].sum()), 0.0)
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)

    def test_align_default_bit_identical(self):
        """The default (no distill_align) path MUST stay byte-identical to
        HEAD's train.py: Soft(batch()) uses `3+r`, no offsets tensor is built
        in the per-step loop, and we fall through to `distill_loss(logits,
        ids, probs)`."""
        # We can't run a full training run here (would be slow), but we can
        # verify the contract: passing no align_path puts Soft into the
        # legacy code path.
        tmpdir = tempfile.mkdtemp(prefix="align_legacy_")
        try:
            self._make_soft(tmpdir)
            soft = train_mod.Soft(os.path.join(tmpdir, "soft.npz"))
            self.assertIsNone(soft.align)
            T = 32
            ids_t, _ = soft.batch([0], T, torch.device("cpu"))
            ids_np = ids_t.numpy()[0]
            # Legacy: rows 0..min(5, T-4) = 0..4 are skipped because row 0 is
            # empty (we left it as zeros), so positions 3,4,5,6,7 hold rows
            # 0..4. Position 3 == row 0 (zeros), position 4 == row 1, etc.
            self.assertEqual(float(ids_np[3].sum()), 0.0)  # row 0 was zero
            np.testing.assert_array_equal(ids_np[4, 0], int(self.soft["ids"][1, 0]))
            np.testing.assert_array_equal(ids_np[5, 0], int(self.soft["ids"][2, 0]))
            np.testing.assert_array_equal(ids_np[6, 0], int(self.soft["ids"][3, 0]))
            np.testing.assert_array_equal(ids_np[7, 0], int(self.soft["ids"][4, 0]))
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)

    def test_align_mis_shaped_raises(self):
        """len(pos) != ids.shape[0] must raise on Soft construction."""
        tmpdir = tempfile.mkdtemp(prefix="align_bad_")
        try:
            self._make_soft(tmpdir)
            # Build an align file with the WRONG number of rows.
            np.savez(os.path.join(tmpdir, "align.npz"),
                     pos=np.array([3, 4], dtype=np.uint8),
                     sentences=self.soft["sentences"],
                     starts=self.soft["starts"])
            with self.assertRaises(ValueError):
                train_mod.Soft(os.path.join(tmpdir, "soft.npz"),
                               align_path=os.path.join(tmpdir, "align.npz"))
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)


class AlignEndToEnd(unittest.TestCase):
    """main() must run with `distill_align=` (regression: the first version
    used `np.where(..., out=pos_b)`, which does not exist, and crashed only
    when the option was actually used — the unit tests never entered main())."""

    def test_two_steps_with_align_mask_weight(self):
        tmp = tempfile.mkdtemp(prefix="align_e2e_")
        try:
            data = os.path.join(tmp, "data")
            _make_hermetic(data)
            soft = np.load(os.path.join(data, "soft.npz"))
            starts = soft["starts"]
            rows = int(starts[-1])
            # No piece-spelled words in the fixture, so the word-aware
            # position is 3 + r; a valid, non-trivial alignment file.
            pos = np.empty(rows, dtype=np.uint8)
            for i in range(len(starts) - 1):
                n = int(starts[i + 1] - starts[i])
                pos[starts[i]:starts[i + 1]] = np.arange(3, 3 + n, dtype=np.uint8)
            np.savez(os.path.join(data, "align.npz"), pos=pos,
                     target=np.full(rows, -1, dtype=np.int16),
                     sentences=soft["sentences"], starts=starts)
            np.savez(os.path.join(data, "keep.npz"), kd=np.ones(rows, dtype=np.uint8))
            np.savez(os.path.join(data, "masks.npz"), weight=np.ones(rows, dtype=np.float16))
            out = os.path.join(tmp, "run")
            cmd = [sys.executable, "train.py", data, out,
                   "steps=2", "batch=4", "d=16", "layers=1", "heads=4", "ff=32",
                   "ctx=12", "seed=1", "eval_every=1",
                   "distill=" + os.path.join(data, "soft.npz"),
                   "distill_align=" + os.path.join(data, "align.npz"),
                   "distill_mask=" + os.path.join(data, "keep.npz"),
                   "distill_weight=" + os.path.join(data, "masks.npz")]
            env = os.environ.copy()
            env["PYTHONPATH"] = WMLM + os.pathsep + env.get("PYTHONPATH", "")
            proc = subprocess.run(cmd, cwd=WMLM, env=env, capture_output=True, text=True)
            self.assertEqual(proc.returncode, 0, msg=proc.stderr[-2000:])
            losses = [l for l in proc.stdout.splitlines() if l.startswith("eval step ")]
            self.assertTrue(losses, msg=proc.stdout[-1000:])
            for line in losses:
                self.assertNotIn("nan", line.lower())
                self.assertNotIn("inf", line.lower())
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
