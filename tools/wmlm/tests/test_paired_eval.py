"""Unit tests for tools.wmlm.paired_eval.

Checks hand-derivable cases:
  - b=10, c=2: exact McNemar p computed against math.comb.
  - Perfect agreement (b=c=0): p=1.0, delta 0, CI contains 0.
  - Bootstrap CI brackets the point estimate, deterministic for a fixed seed.
  - --mde 2588 yields ~1.74 pp at p_disc 0.10 and ~2.46 pp at 0.20.
  - --power round-trips: n(pd=0.1, d=0.02) ~ 1960.
  - Id mismatch and non-0/1 columns exit 2.

Run with:
    PYTHONPATH=. python -m unittest tests.test_paired_eval -v
"""

from __future__ import annotations

import io
import math
import os
import random
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr
from typing import List, Tuple

# Allow importing paired_eval.py when PYTHONPATH=tools/wmlm is set.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import paired_eval  # noqa: E402


def _write_dump(rows: List[Tuple[int, int, int]], path: str, columns: List[str]) -> None:
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\t".join(columns) + "\n")
        for r in rows:
            fh.write("\t".join(str(x) for x in r) + "\n")


class TestStats(unittest.TestCase):
    def test_mcnemar_b10_c2(self) -> None:
        # P(X <= 2) where X ~ Bin(12, 0.5); two-sided = 2 * that.
        n_disc = 12
        k = 2
        cdf_2 = sum(math.comb(n_disc, j) for j in range(k + 1)) / (2 ** n_disc)
        expected = min(1.0, 2.0 * min(cdf_2, 0.5))
        self.assertAlmostEqual(paired_eval.mcnemar_exact(10, 2), expected, places=12)
        # Symmetric, order swap doesn't matter.
        self.assertAlmostEqual(paired_eval.mcnemar_exact(2, 10), expected, places=12)

    def test_perfect_agreement(self) -> None:
        # Hand-rolled: 30 items, A and B identical (all 1s).
        rows = [(i, 1, 1) for i in range(30)]
        with tempfile.TemporaryDirectory() as td:
            p = os.path.join(td, "dump.tsv")
            _write_dump(rows, p, ["item_id", "metric_a", "metric_b"])
            argv = [p, p, "--metric", "metric_a", "--bootstrap", "1000", "--seed", "0"]  
            buf_out, buf_err = io.StringIO(), io.StringIO()
            with redirect_stdout(buf_out), redirect_stderr(buf_err):
                rc = paired_eval.run(argv)
            self.assertEqual(rc, 0, buf_err.getvalue())
            self.assertIn("delta    +0.0000", buf_out.getvalue())
            self.assertIn("p_exact  1.0", buf_out.getvalue())
            # CI must contain 0 (delta == 0) and bracket the point estimate (== 0).
            ci_lo = paired_eval.bootstrap_ci([1] * 30, [1] * 30, 1000, 0)[0]
            ci_hi = paired_eval.bootstrap_ci([1] * 30, [1] * 30, 1000, 0)[1]
            self.assertLessEqual(ci_lo, 0.0 + 1e-12)
            self.assertGreaterEqual(ci_hi, 0.0 - 1e-12)

    def test_bootstrap_deterministic_and_brackets(self) -> None:
        # Construct a fixed paired series.
        random.seed(123)
        n = 200
        a = [random.choice([0, 1]) for _ in range(n)]
        b = [1 - av if random.random() < 0.3 else av for av in a]
        delta = sum(av - bv for av, bv in zip(a, b)) / n
        # Determinism: same seed -> same CI.
        ci1 = paired_eval.bootstrap_ci(a, b, 5000, 7)
        ci2 = paired_eval.bootstrap_ci(a, b, 5000, 7)
        self.assertEqual(ci1, ci2)
        # Brackets the point estimate.
        self.assertLessEqual(ci1[0], delta + 1e-12)
        self.assertGreaterEqual(ci1[1], delta - 1e-12)

    def test_mde_at_2588(self) -> None:
        # For reference: at n=2588 p_disc=0.10 -> 1.74 pp; at 0.20 -> 2.46 pp.
        d_10 = paired_eval.mde_for_n(2588, 0.10)
        d_20 = paired_eval.mde_for_n(2588, 0.20)
        self.assertAlmostEqual(d_10, 0.0174, delta=5e-4,
                               msg=f"expected ~0.0174, got {d_10:.4f}")
        self.assertAlmostEqual(d_20, 0.0246, delta=5e-4,
                               msg=f"expected ~0.0246, got {d_20:.4f}")

    def test_power_round_trip(self) -> None:
        # For pd=0.1, d=0.02 -> n ~ 1960.
        n_req = paired_eval.n_for_power(0.10, 0.02)
        self.assertTrue(1960 - 20 <= n_req <= 1960 + 20, f"n_for_power(0.1, 0.02) = {n_req}")

    def test_id_mismatch_exits_2(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            a = os.path.join(td, "a.tsv")
            b = os.path.join(td, "b.tsv")
            _write_dump([(i, 1, 1) for i in range(20)], a, ["item_id", "m"])
            _write_dump([(i + 1, 1, 1) for i in range(20)], b, ["item_id", "m"])
            buf_err = io.StringIO()
            with redirect_stdout(io.StringIO()), redirect_stderr(buf_err):
                rc = paired_eval.run([a, b, "--metric", "m"])
            self.assertEqual(rc, 2)
            self.assertIn("item_id sets differ", buf_err.getvalue())

    def test_non_01_value_exits_2(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            p = os.path.join(td, "d.tsv")
            with open(p, "w", encoding="utf-8") as fh:
                fh.write("item_id\tm\n")
                fh.write("0\t1\n")
                fh.write("1\t2\n")
            buf_err = io.StringIO()
            with redirect_stdout(io.StringIO()), redirect_stderr(buf_err):
                rc = paired_eval.run([p, p, "--metric", "m"])
            self.assertEqual(rc, 2)
            self.assertIn("metric value must be one of 0, 1, -1", buf_err.getvalue())

    def test_duplicate_id_exits_2(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            p = os.path.join(td, "d.tsv")
            with open(p, "w", encoding="utf-8") as fh:
                fh.write("item_id\tm\n")
                fh.write("0\t1\n")
                fh.write("0\t0\n")
            buf_err = io.StringIO()
            with redirect_stdout(io.StringIO()), redirect_stderr(buf_err):
                rc = paired_eval.run([p, p, "--metric", "m"])
            self.assertEqual(rc, 2)
            self.assertIn("duplicate item_id 0", buf_err.getvalue())

    def test_self_check_zero_delta(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            p = os.path.join(td, "d.tsv")
            _write_dump([(i, 1, 1) for i in range(100)], p, ["item_id", "m"])
            argv = [p, p, "--metric", "m", "--bootstrap", "2000", "--seed", "0"]  
            buf_out, buf_err = io.StringIO(), io.StringIO()
            with redirect_stdout(buf_out), redirect_stderr(buf_err):
                rc = paired_eval.run(argv)
            self.assertEqual(rc, 0)
            self.assertIn("p_exact  1.0", buf_out.getvalue())
            self.assertIn("delta    +0.0000", buf_out.getvalue())

    def test_holm_rejects_one(self) -> None:
        # 4 p-values, only the smallest is significant at 5%; the chain breaks.
        result = paired_eval.holm([0.001, 0.012, 0.06, 0.40])
        # First in order: 0.001, thr=0.0125 -> reject; 0.012 thr=0.0167 -> reject?
        # 0.012 <= 0.0167 yes; 0.06 <= 0.025? no -> stop.
        self.assertEqual(len(result), 4)
        self.assertTrue(result[0]["reject"])         # 0.001 rank1 thr0.0125
        self.assertTrue(result[1]["reject"])         # 0.012 rank2 thr0.0167 (0.012 <= 0.0167)
        self.assertFalse(result[2]["reject"])        # 0.06 rank3 thr0.025 -> stop chain
        self.assertFalse(result[3]["reject"])

    def test_chi2_with_cc_is_a_probability(self) -> None:
        # b=30, c=10 -> (|30-10|-1)^2 / (30+10) = 19^2 / 40 = 9.025, n_disc >= 25.
        chi2 = paired_eval.chi2_with_cc(30, 10)
        self.assertIsNotNone(chi2)
        stat, p = chi2
        self.assertAlmostEqual(stat, (abs(30 - 10) - 1) ** 2 / (30 + 10),
                               places=12)
        self.assertAlmostEqual(stat, 9.025, places=6)
        # df=1 p via erfc(sqrt(stat/2)).
        expected_p = math.erfc(math.sqrt(stat / 2.0))
        self.assertAlmostEqual(p, expected_p, places=12)
        self.assertGreater(p, 0.0)
        self.assertLess(p, 1.0)
        # Handed numeric: for stat=9.025, p ~ 0.00267.
        self.assertAlmostEqual(p, 0.00267, places=3)

        # Stable for swapped order (stat only depends on |b-c| and b+c).
        chi2_swap = paired_eval.chi2_with_cc(10, 30)
        self.assertIsNotNone(chi2_swap)
        self.assertAlmostEqual(chi2_swap[0], stat, places=12)
        self.assertAlmostEqual(chi2_swap[1], p, places=12)

        # Below threshold -> None.
        self.assertIsNone(paired_eval.chi2_with_cc(12, 12))

        # For large discordant counts the continuity-corrected chi2 p should
        # agree closely with the exact McNemar p (they are both valid for the
        # same hypothesis; chi2 is a continuity-corrected approximation).
        b, c = 80, 20
        exact = paired_eval.mcnemar_exact(b, c)
        chi2_large = paired_eval.chi2_with_cc(b, c)
        self.assertIsNotNone(chi2_large)
        # Both should reject at 1e-3 and be within an order of magnitude.
        self.assertLess(exact, 1e-3)
        self.assertLess(chi2_large[1], 1e-3)
        self.assertLess(abs(exact - chi2_large[1]), 5e-3)

    def test_minus_one_metric_is_dropped(self) -> None:
        # Dump with -1 entries in the selected metric must be accepted;
        # rows == -1 must be filtered and counted; the remaining n must
        # equal (total rows - dropped rows).
        with tempfile.TemporaryDirectory() as td:
            pa = os.path.join(td, "a.tsv")
            pb = os.path.join(td, "b.tsv")
            # 30 rows total; first 5 in each file have -1 for that file's
            # metric value (so A also drops 5, B also drops 5 -> the inner
            # join on item_id keeps 25 rows for both, with dropped=5).
            # For the 25 kept rows: A has 15 ones / 10 zeros; B has 13 ones /
            # 12 zeros => b = 12 (A=1,B=0), c = 10 (A=0,B=1).
            rows_a, rows_b = [], []
            for i in range(30):
                if i < 5:
                    rows_a.append((i, -1))
                    rows_b.append((i, -1))
                else:
                    j = i - 5
                    rows_a.append((i, 1 if j < 15 else 0))
                    rows_b.append((i, 1 if j >= 12 else 0))
            with open(pa, "w", encoding="utf-8") as fh:
                fh.write("item_id\tm\n")
                for r in rows_a:
                    fh.write("\t".join(str(x) for x in r) + "\n")
            with open(pb, "w", encoding="utf-8") as fh:
                fh.write("item_id\tm\n")
                for r in rows_b:
                    fh.write("\t".join(str(x) for x in r) + "\n")
            argv = [pa, pb, "--metric", "m", "--bootstrap", "2000", "--seed", "0"]
            buf_out, buf_err = io.StringIO(), io.StringIO()
            with redirect_stdout(buf_out), redirect_stderr(buf_err):
                rc = paired_eval.run(argv)
            self.assertEqual(rc, 0, buf_err.getvalue())
            text = buf_out.getvalue()
            # Exactly 5 dropped rows.
            self.assertIn("dropped  5", text)
            # Reported n = 25.
            self.assertIn("n        25", text)
            # Sanity: b = 12 (A right / B wrong), c = 10 (A wrong / B right).
            self.assertIn("b          12", text)
            self.assertIn("c          10", text)

    def test_minus_one_too_few_rows_exits_2(self) -> None:
        # After dropping -1 entries fewer than 2 rows remain -> exit 2.
        with tempfile.TemporaryDirectory() as td:
            p = os.path.join(td, "d.tsv")
            with open(p, "w", encoding="utf-8") as fh:
                fh.write("item_id\tm\n")
                fh.write("0\t1\n")
                fh.write("1\t-1\n")
                fh.write("2\t-1\n")
            buf_err = io.StringIO()
            with redirect_stdout(io.StringIO()), redirect_stderr(buf_err):
                rc = paired_eval.run([p, p, "--metric", "m"])
            self.assertEqual(rc, 2)
            err = buf_err.getvalue()
            self.assertTrue(("too few" in err) or ("< 2" in err) or ("n=1" in err),
                            msg=f"stderr did not mention too-few rows:\n{err}")

    def test_minus_one_asymmetric_drops_pair(self) -> None:
        # A writes -1 where B has a real value (and vice versa): the id sets
        # still match, and the pair is dropped symmetrically instead of
        # aborting with "item_id sets differ".
        with tempfile.TemporaryDirectory() as td:
            pa = os.path.join(td, "a.tsv")
            pb = os.path.join(td, "b.tsv")
            rows_a = [(0, 1), (1, 0), (2, -1), (3, -1), (4, 1)]
            rows_b = [(0, 1), (1, 1), (2, 0), (3, 1), (4, 0)]
            for path, rows in ((pa, rows_a), (pb, rows_b)):
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write("item_id\tm\n")
                    for r in rows:
                        fh.write("\t".join(str(x) for x in r) + "\n")
            buf_out, buf_err = io.StringIO(), io.StringIO()
            with redirect_stdout(buf_out), redirect_stderr(buf_err):
                rc = paired_eval.run([pa, pb, "--metric", "m", "--bootstrap", "2000", "--seed", "0"])
            self.assertEqual(rc, 0, msg=buf_err.getvalue())
            text = buf_out.getvalue()
            self.assertIn("dropped  2", text)
            self.assertIn("n        3", text)
            # Kept: id0 (1,1), id1 (0,1)=c, id4 (1,0)=b.
            self.assertIn("b           1", text)
            self.assertIn("c           1", text)

    def test_minus_one_partial_alignment_drops_correctly(self) -> None:
        # Symmetric -1 rows (same id in both dumps) are dropped as pairs; this
        # is the typing_eval.rs case where both modes skip together.
        with tempfile.TemporaryDirectory() as td:
            pa = os.path.join(td, "a.tsv")
            pb = os.path.join(td, "b.tsv")
            # Same id set in A and B; -1 appears identically in both.
            with open(pa, "w", encoding="utf-8") as fh:
                fh.write("item_id\tm\n")
                fh.write("0\t1\n")
                fh.write("1\t1\n")
                fh.write("2\t0\n")
                fh.write("3\t-1\n")
                fh.write("4\t-1\n")
            with open(pb, "w", encoding="utf-8") as fh:
                fh.write("item_id\tm\n")
                fh.write("0\t1\n")
                fh.write("1\t1\n")
                fh.write("2\t1\n")
                fh.write("3\t-1\n")
                fh.write("4\t-1\n")
            buf_err = io.StringIO()
            buf_out = io.StringIO()
            with redirect_stdout(buf_out), redirect_stderr(buf_err):
                rc = paired_eval.run([pa, pb, "--metric", "m"])
            self.assertEqual(rc, 0, msg=buf_err.getvalue())
            text = buf_out.getvalue()
            self.assertIn("dropped  2", text)
            # Kept ids: 0,1,2 in both; n=3.
            self.assertIn("n        3", text)
            # A=(0,1)(1,1)(2,0); B=(0,1)(1,1)(2,1). So A and B both right at
            # ids 0,1; A wrong / B right at id 2 (c=1); no A right / B wrong
            # since B is always 1 when A is 1 -> b=0.
            self.assertIn("b           0", text)
            self.assertIn("c           1", text)


class TestEndToEndCLI(unittest.TestCase):
    def test_cli_exit_2_on_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            a = os.path.join(td, "a.tsv")
            b = os.path.join(td, "b.tsv")
            _write_dump([(i, 1) for i in range(10)], a, ["item_id", "m"])
            _write_dump([(i + 1, 1) for i in range(10)], b, ["item_id", "m"])
            script = os.path.join(os.path.dirname(os.path.abspath(paired_eval.__file__)), "paired_eval.py")
            cp = subprocess.run(
                [sys.executable, script, a, b, "--metric", "m"],
                capture_output=True, text=True,
            )
            self.assertEqual(cp.returncode, 2)
            self.assertIn("item_id sets differ", cp.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
