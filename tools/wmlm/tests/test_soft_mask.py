"""Tests for tools/wmlm/soft_mask.py — the per-row mask/align invariants.

Two test classes:

- SyntheticGenerator: builds a tiny vocab with `▁ab`/`##cd` pieces, a tiny
  train.npz, and a tiny soft.npz whose sentences deliberately include a
  missing-from-soft train sentence, an empty row r=0, an empty row r=2, and
  an <=3-candidate row. Asserts the per-row flags/weights/targets follow
  the documented mask contract.

Run: `python -m unittest discover -s tools/wmlm/tests`.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

import numpy as np

THIS = os.path.dirname(os.path.abspath(__file__))
WMLM = os.path.dirname(THIS)
sys.path.insert(0, WMLM)

from common import BOS, EOS, PAD, REGISTERS, FIELDS, LANGS, START, CONT  # noqa: E402


def _make_data_with_prefix(tmpdir, vocab, sentences):
    """Build (vocab.txt, train.npz) where each sentence has the prep.py
    layout: [REG, FLD, LANG, BOS, words..., EOS]."""
    ids = {w: i for i, w in enumerate(vocab)}
    tokens = []
    starts = [0]
    for wlist in sentences:
        tokens.extend([ids[REGISTERS[0]], ids[FIELDS[0]], ids[LANGS[0]], ids[BOS]]
                       + [ids[w] for w in wlist] + [ids[EOS]])
        starts.append(len(tokens))
    with open(os.path.join(tmpdir, "vocab.txt"), "w", encoding="utf-8") as f:
        for w in vocab:
            f.write(w + "\n")
    np.savez(os.path.join(tmpdir, "train.npz"),
             tokens=np.array(tokens, dtype=np.int32),
             starts=np.array(starts, dtype=np.int64))
    return ids


def _make_data(tmpdir, vocab, sentences):
    """Build a (vocab.txt, train.npz) pair where each sentence has the
    5-token prefix [reg, field, lang, BOS, w0..wL, EOS] layout from prep.py."""
    with open(os.path.join(tmpdir, "vocab.txt"), "w", encoding="utf-8") as f:
        for w in vocab:
            f.write(w + "\n")
    ids = {w: i for i, w in enumerate(vocab)}
    tokens = []
    starts = [0]
    for i, sent in enumerate(sentences):
        for w in sent:
            tokens.append(ids[w])
        tokens.append(ids[EOS])
        starts.append(len(tokens))
    np.savez(os.path.join(tmpdir, "train.npz"), tokens=np.array(tokens, dtype=np.int32), starts=np.array(starts, dtype=np.int64))
    return ids


def _make_soft(tmpdir, sentences_in_soft, word_to_ids_per_pos, starts_in_soft):
    """Build a soft npz with `sentences_in_soft` (list of train-sentence
    indices), `ids`/`probs` zero-initialised except where
    `word_to_ids_per_pos[s_idx]` provides (row_idx, ids_top24, probs_top24)."""
    topk = 24
    rows = sum(starts_in_soft[i + 1] - starts_in_soft[i] for i in range(len(sentences_in_soft)))
    ids = np.zeros((rows, topk), dtype=np.int16)
    probs = np.zeros((rows, topk), dtype=np.float16)
    cur = 0
    for s_idx, s in enumerate(sentences_in_soft):
        nrows = starts_in_soft[s_idx + 1] - starts_in_soft[s_idx]
        for r in range(nrows):
            if (s_idx, r) in word_to_ids_per_pos:
                ti, pp = word_to_ids_per_pos[(s_idx, r)]
                ids[cur + r] = ti
                probs[cur + r] = pp
        cur += nrows
    np.savez(
        os.path.join(tmpdir, "soft.npz"),
        sentences=np.array(sentences_in_soft, dtype=np.int32),
        starts=np.array(starts_in_soft, dtype=np.int64),
        ids=ids,
        probs=probs,
    )


def _run(cmd, cwd):
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)


class SyntheticGenerator(unittest.TestCase):
    """Synthetic word-aware fixtures: 5 train sentences (one with a multi-piece
    word) and a soft file whose row count matches the per-word count. Empty
    rows leave the weight/EMPTY/LE3 invariants intact, and the multi-piece
    word forces a PIECE bit and a `pos` that drifts from the legacy 3+r layout
    on the rows that follow it."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="soft_mask_test_")
        # 16 specials + 10 words + 4 pieces.
        specials = [PAD, BOS, EOS, "<unk>", "<num>"] + REGISTERS + FIELDS + LANGS
        assert len(specials) == 16
        self.vocab = specials + [
            "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india", "juliet",
            START + "pi", CONT + "ce", CONT + "st", START + "ab",
        ]
        self.ids = {w: i for i, w in enumerate(self.vocab)}
        # Word lists — train sentence uses [REG,FLD,LANG,BOS] + words + EOS.
        # S1 carries a multi-piece word so word-aware grouping matters.
        self.word_lists = [
            ["alpha", "bravo", "charlie", "delta", "echo"],     # S0 = 5 words, all single-piece
            ["foxtrot", "golf", "hotel", "india", "juliet"],    # S1 = 5 words, all single-piece
            ["alpha", "alpha", "alpha", "alpha", "alpha"],      # S2 = 5 words
            ["alpha", "alpha", "alpha", "alpha", "alpha"],      # S3 = 5 words
            ["alpha", "bravo", "alpha", "bravo", "alpha"],      # S4 = 5 words
        ]
        # Train sentence 2 (index 1) is skipped in the soft file (mimics
        # the 18 skipped train sentences the production soft has).
        _make_data_with_prefix(self.tmp, self.vocab, self.word_lists)
        # Soft: sentences [0, 2, 3, 4] -> indices to skip = [1].
        per_pos = {}
        ce_id = self.ids[CONT + "ce"]
        ab_id = self.ids[START + "ab"]
        e_id = self.ids["echo"]
        j_id = self.ids["juliet"]
        a_id = self.ids["alpha"]
        # Sentence 0 (rows 5): r=0 empty, r=1 full, r=2 empty, r=3 empty, r=4 full.
        per_pos[(0, 1)] = (np.array([j_id, a_id] + [a_id] * 22, dtype=np.int16), np.array([0.4, 0.3] + [0.3 / 22] * 22, dtype=np.float16))
        per_pos[(0, 4)] = (np.array([a_id, e_id] + [a_id] * 22, dtype=np.int16), np.array([0.5, 0.3] + [0.2 / 22] * 22, dtype=np.float16))
        # Sentence 2 (rows 5): r=2 LE3 (2 cands), r=3 full.
        per_pos[(2, 2)] = (np.array([ab_id, ce_id] + [0] * 22, dtype=np.int16), np.array([0.6, 0.4] + [0] * 22, dtype=np.float16))
        per_pos[(2, 3)] = (np.array([j_id, a_id] + [a_id] * 22, dtype=np.int16), np.array([0.4, 0.3] + [0.3 / 22] * 22, dtype=np.float16))
        # Sentence 4 (rows 5): r=4 full.
        per_pos[(4, 4)] = (np.array([e_id, a_id] + [a_id] * 22, dtype=np.int16), np.array([0.5, 0.2] + [0.3 / 22] * 22, dtype=np.float16))
        soft_sents = [0, 2, 3, 4]
        soft_starts = [0, 5, 10, 15, 20]
        _make_soft(self.tmp, soft_sents, per_pos, soft_starts)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_invariants(self):
        out_prefix = os.path.join(self.tmp, "out")
        proc = _run([
            sys.executable, "soft_mask.py", "build",
            self.tmp, os.path.join(self.tmp, "soft.npz"), out_prefix,
            "--weight", "cand",
            "--check-sample", "1000000",  # all rows
        ], cwd=WMLM)
        self.assertEqual(proc.returncode, 0, msg=f"stderr:\n{proc.stderr}\nstdout:\n{proc.stdout}")
        z = np.load(out_prefix + ".masks.npz")
        flags, weight, target = z["flags"], z["weight"], z["target"]
        pos = z["pos"]
        rows = flags.shape[0]
        starts = z["starts"]
        # I1: shapes agree.
        self.assertEqual(len(flags), rows)
        self.assertEqual(len(weight), rows)
        self.assertEqual(len(target), rows)
        self.assertEqual(len(pos), rows)
        self.assertEqual(starts[0], 0)
        self.assertEqual(starts[-1], rows)
        # I2: sentences strictly increasing.
        sentences = z["sentences"]
        self.assertTrue((np.diff(sentences.astype(np.int64)) > 0).all(), "sentences not strictly increasing")
        # PIECE bit ↔ last token of word-group is a piece.
        target_ids = target.astype(np.int64)
        target_in = target_ids >= 0
        is_piece_token = np.array([w.startswith(START) or w.startswith(CONT) for w in self.vocab])
        piece_bit = (flags & 2) > 0
        no_piece_bit = (flags & 2) == 0
        # Train token stream for sample I5 checks.
        train = np.load(os.path.join(self.tmp, "train.npz"))
        t_tokens = train["tokens"]
        t_starts = train["starts"]
        # Word-aware recompute: build groups per sentence and compare.
        starts_with_cont = set(i for i, w in enumerate(self.vocab) if w.startswith(CONT))
        for i in range(rows):
            s = int(np.searchsorted(starts, i, side="right")) - 1
            r = i - int(starts[s])
            ss = int(sentences[s])
            tt0 = int(t_starts[ss])
            tt1 = int(t_starts[ss + 1])
            # body start = t0 + 4, body end = t1 - 1 (exclude EOS).
            body_start, body_end = tt0 + 4, tt1 - 1
            groups = []
            cur_first = body_start
            j = body_start
            while j < body_end:
                tok = int(t_tokens[j])
                if j > body_start and (tok in starts_with_cont):
                    j += 1
                    continue
                if j > body_start:
                    groups.append((cur_first, int(t_tokens[j - 1])))
                    cur_first = j
                j += 1
            if body_end > body_start:
                groups.append((cur_first, int(t_tokens[body_end - 1])))
            self.assertLess(r, len(groups), f"row {i}: r={r} out of groups {len(groups)}")
            first_idx, last_tok = groups[r]
            want_target = int(t_tokens[first_idx])
            want_pos = min(first_idx - tt0 - 1, 255)
            want_piece = bool(is_piece_token[last_tok])
            self.assertEqual(int(target[i]), want_target, f"row {i} target mismatch")
            self.assertEqual(int(pos[i]), want_pos, f"row {i} pos mismatch")
            # PIECE bit ↔ target is a piece-token.
            self.assertEqual(bool(piece_bit[i]), want_piece, f"row {i} PIECE bit")
            # No PIECE bit on a non-piece target where target valid.
            if target_in[i] and 0 <= int(target_ids[i]) < len(self.vocab):
                self.assertEqual(bool(no_piece_bit[i]),
                                 not is_piece_token[int(target_ids[i])],
                                 f"row {i} no-piece-target PIECE mismatch")
        # I6: EMPTY -> weight 0; LE3 -> weight <= 3/24.
        empty_bit = (flags & 1) > 0
        le3_bit = (flags & 4) > 0
        self.assertTrue((weight[empty_bit] == 0).all(), "EMPTY row has non-zero weight")
        self.assertTrue((weight[le3_bit] <= 3.0 / 24.0 + 1e-3).all(), "LE3 row weight > 3/24")


class UnigramAndSlice(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="unigram_test_")
        ids = {w: i for i, w in enumerate(_vocab_tokens_small())}
        self.vocab_ids = ids
        sents = [
            ["alpha", "bravo", "charlie"],  # first-word = alpha
            ["alpha", "alpha", "alpha"],   # first-word = alpha
            ["bravo", "alpha", "bravo"],   # first-word = bravo
            ["charlie", "alpha", "echo"],  # first-word = charlie
            ["alpha", "alpha"],            # first-word = alpha
        ]
        # Build a train.npz with the prep.py layout: 5-token prefix + words + EOS.
        _, BOS, EOS, _, _ = [ids[w] for w in ("<pad>", "<s>", "</s>", "<unk>", "<num>")]
        REG0, FLD0, LNG0 = ids[REGISTERS[0]], ids[FIELDS[0]], ids[LANGS[0]]
        tokens = []
        starts = [0]
        for wlist in sents:
            tokens.extend([REG0, FLD0, LNG0, BOS] + [ids[w] for w in wlist] + [EOS])
            starts.append(len(tokens))
        with open(os.path.join(self.tmp, "vocab.txt"), "w", encoding="utf-8") as f:
            for w in _vocab_tokens_small():
                f.write(w + "\n")
        np.savez(os.path.join(self.tmp, "train.npz"),
                 tokens=np.array(tokens, dtype=np.int32),
                 starts=np.array(starts, dtype=np.int64))
        # First-word counts: alpha 3, bravo 1, charlie 1, delta 0, echo 0, foxtrot 0.
        # Bottom 17 (specials + delta/echo/foxtrot) all share count=0; argsort
        # on probabilities breaks ties by ascending token index.
        # Top-5 hand-computed: alpha, bravo, charlie, then <pad> (idx 0) before
        # <num> (idx 4) since both have count=0.
        self.expected_top5 = [self.vocab_ids["alpha"], self.vocab_ids["bravo"], self.vocab_ids["charlie"], 0, 4]  # PAD, NUM

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_unigram_sum_one(self):
        out = os.path.join(self.tmp, "uni.npz")
        proc = _run([sys.executable, "soft_mask.py", "unigram", self.tmp, out], cwd=WMLM)
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        z = np.load(out)
        p_first = z["p_first"]
        s = float(p_first.sum())
        self.assertAlmostEqual(s, 1.0, places=6)
        n_first = int(z["n_first"])
        self.assertEqual(n_first, 5)  # 5 sentences
        # I7: top-5 by probability
        order = np.argsort(-p_first)[:5]
        self.assertEqual(order.tolist(), self.expected_top5)
        # vocab_sha256 bytes round-trip
        v_sha = bytes(z["vocab_sha256"].tolist()).decode()
        self.assertEqual(len(v_sha), 64)


def _vocab_tokens_small():
    """Tiny vocab for the unigram/slice tests: 16 specials + 6 small words."""
    specials = [PAD, BOS, EOS, "<unk>", "<num>"] + REGISTERS + FIELDS + LANGS
    return specials + ["alpha", "bravo", "charlie", "delta", "echo", "foxtrot"]


class FillCommand(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="fill_test_")
        specials = [PAD, BOS, EOS, "<unk>", "<num>"] + REGISTERS + FIELDS + LANGS
        self.vocab = specials + ["alpha", "bravo", "charlie", "delta", "echo"]
        sents = [["alpha"], ["bravo"], ["charlie"]]
        _make_data_with_prefix(self.tmp, self.vocab, sents)
        # Soft over sentences 0 and 2 (skip 1); row 0 of each empty.
        topk = 24
        ids = np.zeros((2, topk), dtype=np.int16)
        probs = np.zeros((2, topk), dtype=np.float16)
        ids[0, 0] = 1
        ids[0, 1] = 2
        probs[0, 0] = 0.8
        probs[0, 1] = 0.2
        ids[1, 0] = 1
        ids[1, 1] = 2
        probs[1, 0] = 0.7
        probs[1, 1] = 0.3
        np.savez(
            os.path.join(self.tmp, "soft.npz"),
            sentences=np.array([0, 2], dtype=np.int32),
            starts=np.array([0, 1, 2], dtype=np.int64),
            ids=ids,
            probs=probs,
        )
        # Build unigram with each as first word: alpha, bravo, charlie.
        out_uni = os.path.join(self.tmp, "uni.npz")
        proc = _run([sys.executable, "soft_mask.py", "unigram", self.tmp, out_uni], cwd=WMLM)
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_fill_replaces_row0(self):
        out = os.path.join(self.tmp, "out.npz")
        proc = _run([sys.executable, "soft_mask.py", "fill",
                     os.path.join(self.tmp, "soft.npz"),
                     out, "--unigram", os.path.join(self.tmp, "uni.npz")], cwd=WMLM)
        self.assertEqual(proc.returncode, 0, msg=f"stderr:\n{proc.stderr}\nstdout:\n{proc.stdout}")
        z = np.load(out)
        # row 0 of each sentence was 0-mass; should be replaced with top-24
        # of unigram. Top-24 over a 21-token vocab = all 21 tokens (everything
        # after specials plus the 5 words). Check probs sum to 1 within fp16.
        self.assertGreater(float(z["probs"][0].sum()), 0.0)
        self.assertGreater(float(z["probs"][1].sum()), 0.0)
        # row 1 of each sentence kept original (1.0 sum, ids[0,0]=1, [0,1]=2)
        self.assertAlmostEqual(float(z["probs"][0, 0:2].sum()), 1.0, places=3)
        self.assertAlmostEqual(float(z["probs"][1, 0:2].sum()), 1.0, places=3)

    def test_fill_eligibility_is_row0_only(self):
        # Two two-row sentences. Sentence 0's row 0 is empty but row 1 is
        # filled: the old 24-row window read row 1, skipped the sentence and
        # left row 0 empty. Sentence 1's row 0 is filled, row 1 empty: it must
        # not be touched, and the empty row 1 must stay empty.
        ids = np.zeros((4, 24), dtype=np.int16)
        probs = np.zeros((4, 24), dtype=np.float16)
        ids[1, 0], probs[1, 0] = 1, 1.0      # sentence 0, row 1
        ids[2, 0], probs[2, 0] = 1, 1.0      # sentence 1, row 0
        soft = os.path.join(self.tmp, "soft_rows.npz")
        np.savez(soft, sentences=np.array([0, 2], dtype=np.int32),
                 starts=np.array([0, 2, 4], dtype=np.int64), ids=ids, probs=probs)
        out = os.path.join(self.tmp, "out_rows.npz")
        proc = _run([sys.executable, "soft_mask.py", "fill", soft,
                     out, "--unigram", os.path.join(self.tmp, "uni.npz")], cwd=WMLM)
        self.assertEqual(proc.returncode, 0, msg=f"stderr:\n{proc.stderr}\nstdout:\n{proc.stdout}")
        z = np.load(out)
        # Sentence 0 row 0 filled from the unigram despite row 1 being filled.
        self.assertGreater(float(z["probs"][0].sum()), 0.0)
        # Its row 1 is unchanged.
        self.assertEqual(float(z["probs"][1, 0]), 1.0)
        # Sentence 1 row 0 is unchanged; its empty row 1 stays empty.
        self.assertEqual(float(z["probs"][2, 0]), 1.0)
        self.assertEqual(float(z["probs"][3].sum()), 0.0)


class SliceCommand(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="slice_test_")
        specials = [PAD, BOS, EOS, "<unk>", "<num>"] + REGISTERS + FIELDS + LANGS
        self.vocab = specials + [f"w{i}" for i in range(20)]  # 36 tokens
        sents = [[f"w{i % 20}"] * (3 + (i % 4)) for i in range(40)]
        _make_data(self.tmp, self.vocab, sents)
        # Build a tiny soft covering all 40 sentences, one row each.
        ids = np.tile(np.arange(5, dtype=np.int16)[None, :], (40, 1))
        probs = np.zeros((40, 24), dtype=np.float16)
        probs[:, :3] = np.array([0.5, 0.3, 0.2], dtype=np.float16)
        np.savez(os.path.join(self.tmp, "soft.npz"),
                 sentences=np.arange(40, dtype=np.int32),
                 starts=np.arange(41, dtype=np.int64),
                 ids=ids, probs=probs)
        # Make a fake heldout.npz + heldout.tsv so the copy step succeeds.
        np.savez(self.tmp + "/heldout.npz", tokens=np.zeros(4, dtype=np.int32), starts=np.array([0, 4], dtype=np.int64))
        with open(self.tmp + "/heldout.tsv", "w") as f:
            f.write("0\t0\t0\thello world\n")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_sentences_mode(self):
        out_dir = os.path.join(self.tmp, "out")
        proc = _run([sys.executable, "slice_data.py", self.tmp,
                     os.path.join(self.tmp, "soft.npz"), out_dir, "sentences=10", "0"], cwd=WMLM)
        self.assertEqual(proc.returncode, 0, msg=f"stderr:\n{proc.stderr}\nstdout:\n{proc.stdout}")
        z = np.load(os.path.join(out_dir, "train.npz"))
        # The number of sentences in the new train.npz should be 10.
        self.assertEqual(len(z["starts"]) - 1, 10)
        self.assertEqual(z["starts"][0], 0)
        self.assertEqual(z["starts"][-1], len(z["tokens"]))
        # heldout/vocab byte copies
        for f in ("vocab.txt", "heldout.npz", "heldout.tsv", "soft.npz", "slice.json"):
            self.assertTrue(os.path.exists(os.path.join(out_dir, f)), f"missing {f}")
        # Soft file id/probs blocks byte-equal to parent
        ps = np.load(os.path.join(self.tmp, "soft.npz"))
        cs = np.load(os.path.join(out_dir, "soft.npz"))
        ssent = cs["sentences"]
        starts = cs["starts"]
        for j, s in enumerate(ssent):
            a, b = int(starts[j]), int(starts[j + 1])
            pa = int(s)
            self.assertTrue((cs["ids"][a:b] == ps["ids"][pa:pa + (b - a)]).all())
            self.assertTrue((cs["probs"][a:b] == ps["probs"][pa:pa + (b - a)]).all())

    def test_rows_mode_overshoot(self):
        out_dir = os.path.join(self.tmp, "rows_out")
        proc = _run([sys.executable, "slice_data.py", self.tmp,
                     os.path.join(self.tmp, "soft.npz"), out_dir, "rows=15", "0"], cwd=WMLM)
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        z = np.load(os.path.join(out_dir, "train.npz"))
        # Sendences chosen until cumulative rows >= 15.
        self.assertGreaterEqual(len(z["tokens"]), 15)


class WordAwareAlign(unittest.TestCase):
    """Group-contiguous `##…` tokens onto their previous `▁…` start, expose
    `pos = first_token_index - 1`, set PIECE on the last-piece group, and
    raise SoftError when the row count disagrees with the word-group count."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="soft_align_")
        specials = [PAD, BOS, EOS, "<unk>", "<num>"] + REGISTERS + FIELDS + LANGS
        assert len(specials) == 16
        # 16 specials + 5 ordinary words + 4 pieces = 25 tokens.
        self.vocab = specials + [
            "alpha", "bravo", "charlie", "delta", "echo",
            START + "ab", CONT + "cd",  # the multi-piece word `▁ab ##cd`
            START + "x",                  # an OOV single start piece
        ]
        self.ids = {w: i for i, w in enumerate(self.vocab)}
        # Two sentences: S0 = "alpha bravo ▁ab ##cd charlie" (5 words, 4 tokens
        # for the last word), S1 = "alpha ▁x bravo" (3 words, the middle one
        # is a single-piece OOV).
        _make_data_with_prefix(self.tmp, self.vocab, [
            ["alpha", "bravo", START + "ab", CONT + "cd", "charlie"],
            ["alpha", START + "x", "bravo"],
        ])
        # Soft: one row per WORD (=4 for S0 = alpha/bravo/▁ab+##cd/charlie,
        # 3 for S1 = alpha/▁x/bravo) — the piece-merge happens in build.
        topk = 24
        s0_words = 4
        s1_words = 3
        rows = s0_words + s1_words
        ids = np.zeros((rows, topk), dtype=np.int16)
        probs = np.zeros((rows, topk), dtype=np.float16)
        for r in range(rows):
            ids[r, 0] = r + 1
            ids[r, 1:] = 1
            probs[r, 0] = 0.5
            probs[r, 1:] = 0.5 / (topk - 1)
        np.savez(
            os.path.join(self.tmp, "soft.npz"),
            sentences=np.array([0, 1], dtype=np.int32),
            starts=np.array([0, s0_words, rows], dtype=np.int64),
            ids=ids, probs=probs,
        )

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_pos_and_target(self):
        out_prefix = os.path.join(self.tmp, "out")
        proc = _run([
            sys.executable, "soft_mask.py", "build", self.tmp,
            os.path.join(self.tmp, "soft.npz"), out_prefix,
            "--weight", "none", "--emit-align", "--check-sample", "0",
        ], cwd=WMLM)
        self.assertEqual(proc.returncode, 0, msg=f"stderr:\n{proc.stderr}\nstdout:\n{proc.stdout}")
        z = np.load(out_prefix + ".masks.npz")
        # First sentence: alpha(0), bravo(1), ▁ab+##cd(2), charlie(3).
        # token positions (relative to t0): prefix=4 (reg,field,lang,bos at
        # 0..3, words at 4..).
        # S0 word r=0 -> alpha at train_tokens[t0+4]; pos = t0+4-1 = t0+3.
        # r=1 -> bravo at t0+5; pos = t0+4.
        # r=2 -> ▁ab at t0+6 (##cd merges -> 1 row); pos = t0+5.
        # r=3 -> charlie at t0+7; pos = t0+6.
        # S1 word r=0 -> alpha at s1_t0+4; pos = s1_t0+3.
        # r=1 -> ▁x at s1_t0+5; pos = s1_t0+4.
        # r=2 -> bravo at s1_t0+6; pos = s1_t0+5.
        # S0 train tokens: [R,F,L,B, alpha, bravo, ▁ab, ##cd, charlie, EOS].
        # starts = [0, 10, 18]. word-groups at first_idx = 4,5,6,8 -> S0-R
        # (sentence-relative) pos = first_idx - t0 - 1 = 3, 4, 5, 7.
        # S1 starts at 10, word-groups at first_idx 14,15,16 -> relative pos
        # = 3, 4, 5 (RESTART because pos is per-sentence).
        s0_pos = [3, 4, 5, 7]
        s1_pos = [3, 4, 5]
        want_pos = s0_pos + s1_pos
        pos = z["pos"]
        self.assertEqual(pos.tolist(), want_pos)
        # PIECE bits: rows whose last-token is a piece.
        # S0 row 2 -> last is ##cd (CONT) -> PIECE. All others -> not PIECE.
        # S1 row 1 -> last is ▁x (START) -> PIECE. Others not PIECE.
        flags = z["flags"]
        piece_bits = (flags & 2) > 0
        want_piece = [False, False, True, False, False, True, False]
        self.assertEqual(piece_bits.tolist(), want_piece)
        # Target column: the FIRST token of each word.
        targets = z["target"]
        ab_first = self.ids[START + "ab"]
        x_first = self.ids[START + "x"]
        want_tgts = [
            self.ids["alpha"], self.ids["bravo"], ab_first, self.ids["charlie"],
            self.ids["alpha"], x_first, self.ids["bravo"],
        ]
        self.assertEqual(targets.tolist(), want_tgts)
        # align.npz exists and matches.
        a = np.load(out_prefix + ".align.npz")
        self.assertEqual(a["pos"].tolist(), want_pos)
        self.assertEqual(a["target"].tolist(), want_tgts)
        self.assertEqual(a["sentences"].tolist(), [0, 1])
        # Pos drift: rows whose train-side first token is not the r-th body token.
        # S0 row 2 = the multi-piece word -> still equals 3+r by coincidence.
        # S0 row 3 is the first row to drift (pos 7, not 6).
        drifted_rows = [3, 4, 5, 6]
        for k in drifted_rows:
            self.assertNotEqual(int(pos[k]), 3 + k,
                                f"row {k} should differ from old 3+r layout")
        # Sanity: rows 0..2 still match 3+r.
        for k in [0, 1, 2]:
            self.assertEqual(int(pos[k]), 3 + k,
                             f"row {k} should still equal 3+r")

    def test_mismatch_raises(self):
        # Train has a 2-word body (alpha, bravo) -> groups=2. We declare the
        # soft file with 3 rows so the word-group count disagrees with the
        # soft row count and SoftError must fire.
        specials = [PAD, BOS, EOS, "<unk>", "<num>"] + REGISTERS + FIELDS + LANGS
        tmp = tempfile.mkdtemp(prefix="mis_")
        try:
            vocab = specials + ["alpha", "bravo"]
            with open(os.path.join(tmp, "vocab.txt"), "w", encoding="utf-8") as f:
                for w in vocab:
                    f.write(w + "\n")
            ids = {w: i for i, w in enumerate(vocab)}
            tokens = [ids[REGISTERS[0]], ids[FIELDS[0]], ids[LANGS[0]], ids[BOS],
                      ids["alpha"], ids["bravo"], ids[EOS]]
            np.savez(os.path.join(tmp, "train.npz"),
                     tokens=np.array(tokens, dtype=np.int32),
                     starts=np.array([0, 7], dtype=np.int64))
            ids_arr = np.zeros((3, 24), dtype=np.int16)
            probs_arr = np.zeros((3, 24), dtype=np.float16)
            np.savez(os.path.join(tmp, "soft.npz"),
                     sentences=np.array([0], dtype=np.int32),
                     starts=np.array([0, 3], dtype=np.int64),
                     ids=ids_arr, probs=probs_arr)
            out_prefix = os.path.join(tmp, "out")
            proc = _run([
                sys.executable, "soft_mask.py", "build", tmp,
                os.path.join(tmp, "soft.npz"), out_prefix,
                "--weight", "none", "--check-sample", "0",
            ], cwd=WMLM)
            self.assertNotEqual(proc.returncode, 0, msg=proc.stderr)
            # main() catches SoftError and prints one clean line (no traceback).
            self.assertIn("word-group count", proc.stderr, msg=proc.stderr)
            self.assertNotIn("Traceback", proc.stderr, msg=proc.stderr)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


class InvariantsBitwise(unittest.TestCase):
    """The non-alignment invariants (I1-I2) still hold, and the new mask.npz
    preserves its schema (flags, weight, target, pos, sentences, starts, version)."""

    def test_npz_schema(self):
        tmp = tempfile.mkdtemp(prefix="schema_")
        try:
            specials = [PAD, BOS, EOS, "<unk>", "<num>"] + REGISTERS + FIELDS + LANGS
            vocab = specials + ["alpha"]
            with open(os.path.join(tmp, "vocab.txt"), "w", encoding="utf-8") as f:
                for w in vocab:
                    f.write(w + "\n")
            ids = {w: i for i, w in enumerate(vocab)}
            tokens = [ids[REGISTERS[0]], ids[FIELDS[0]], ids[LANGS[0]], ids[BOS],
                      ids["alpha"], ids[EOS]]
            np.savez(os.path.join(tmp, "train.npz"),
                     tokens=np.array(tokens, dtype=np.int32),
                     starts=np.array([0, 6], dtype=np.int64))
            ids_arr = np.zeros((1, 24), dtype=np.int16)
            probs_arr = np.zeros((1, 24), dtype=np.float16)
            np.savez(os.path.join(tmp, "soft.npz"),
                     sentences=np.array([0], dtype=np.int32),
                     starts=np.array([0, 1], dtype=np.int64),
                     ids=ids_arr, probs=probs_arr)
            out_prefix = os.path.join(tmp, "out")
            proc = _run([sys.executable, "soft_mask.py", "build", tmp,
                         os.path.join(tmp, "soft.npz"), out_prefix,
                         "--weight", "none", "--check-sample", "0"], cwd=WMLM)
            self.assertEqual(proc.returncode, 0, msg=proc.stderr)
            z = np.load(out_prefix + ".masks.npz")
            for name in ("flags", "weight", "target", "pos", "sentences", "starts", "version"):
                self.assertIn(name, z.files, f"missing {name}")
            self.assertEqual(z["pos"].dtype, np.uint8)
            self.assertEqual(z["target"].dtype, np.int16)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_counts_match_flags(self):
        """masks.json counts must count ROWS, not the bit values.

        Regression for the bug where `(flags & FLAG_PIECE).sum()` doubled the
        PIECE count (bit 2) and quadrupled LE3 (bit 4), so every real build
        reported 2x/4x the true share.
        """
        tmp = tempfile.mkdtemp(prefix="counts_")
        try:
            specials = [PAD, BOS, EOS, "<unk>", "<num>"] + REGISTERS + FIELDS + LANGS
            vocab = specials + ["alpha", "\u2581ab", "##cd"]
            with open(os.path.join(tmp, "vocab.txt"), "w", encoding="utf-8") as f:
                for w in vocab:
                    f.write(w + "\n")
            ids = {w: i for i, w in enumerate(vocab)}
            # One word spelled as two pieces: `▁ab` + `##cd` -> one soft row.
            tokens = [ids[REGISTERS[0]], ids[FIELDS[0]], ids[LANGS[0]], ids[BOS],
                      ids["\u2581ab"], ids["##cd"], ids[EOS]]
            np.savez(os.path.join(tmp, "train.npz"),
                     tokens=np.array(tokens, dtype=np.int32),
                     starts=np.array([0, len(tokens)], dtype=np.int64))
            ids_arr = np.zeros((1, 24), dtype=np.int16)
            probs_arr = np.zeros((1, 24), dtype=np.float16)
            ids_arr[0, :4] = [ids["alpha"], ids["\u2581ab"], ids["##cd"], ids[PAD]]
            probs_arr[0, :2] = np.float16(0.5)  # 2 candidates -> LE3
            np.savez(os.path.join(tmp, "soft.npz"),
                     sentences=np.array([0], dtype=np.int32),
                     starts=np.array([0, 1], dtype=np.int64),
                     ids=ids_arr, probs=probs_arr)
            out_prefix = os.path.join(tmp, "out")
            proc = _run([sys.executable, "soft_mask.py", "build", tmp,
                         os.path.join(tmp, "soft.npz"), out_prefix,
                         "--weight", "cand", "--check-sample", "0"], cwd=WMLM)
            self.assertEqual(proc.returncode, 0, msg=proc.stderr)
            z = np.load(out_prefix + ".masks.npz")
            flags = z["flags"]
            with open(out_prefix + ".masks.json", encoding="utf-8") as f:
                counts = json.load(f)["counts"]
            self.assertEqual(counts["piece"], int(((flags & 2) != 0).sum()))
            self.assertEqual(counts["le3"], int(((flags & 4) != 0).sum()))
            self.assertEqual(counts["empty"], int(((flags & 1) != 0).sum()))
            self.assertEqual(counts["piece"], 1)
            self.assertEqual(counts["le3"], 1)
            self.assertEqual(counts["empty"], 0)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
