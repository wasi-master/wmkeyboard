#!/usr/bin/env python3
"""Paired statistical evaluation of two binary TSV item dumps.

Input format (both files): a TSV with a header that includes the column
``item_id`` and one metric column (``--metric``). For the selected metric,
the legal values are ``0`` or ``1`` *or* the sentinel ``-1``: ``-1`` means
"this mode did not compute this metric" (e.g. ``typing_eval.rs`` writes it
for metrics a mode does not produce). The two dumps are first joined on
``item_id``; a pair is **dropped** when *either* side is ``-1``. This is
symmetric, so one mode writing the sentinel and the other writing 0/1 no
longer aborts as an id-set mismatch. The result table prints the ``dropped``
count and the remaining ``n``. If fewer than 2 pairs remain after dropping,
the tool exits 2 with a clear message. Any other non-integer or real value not
in {0, 1, -1}, a missing metric column, duplicate ``item_id`` rows, or an
id-set mismatch between A and B cause exit code 2 with a clear message.

Output: a JSON file with the full result set and a human-readable table on
stdout.

Pure stdlib. No numpy/scipy.

Power/MDE derivation
--------------------
For paired proportions with discordant rate ``p_disc = (b+c)/n`` (b = A right /
B wrong, c = A wrong / B right) the standard two-sided 80%-power / alpha 0.05
sample-size formula is

    N = (1.96 * sqrt(p_disc) + 0.8416 * sqrt(max(p_disc - d^2, 0)))^2 / d^2

(For the standard McNemar power calculation.) We solve this for ``d``
given ``N`` and ``p_disc`` by bisection on ``d in (0, sqrt(p_disc)]`` and for
``N`` given ``p_disc`` and ``d`` by closed form (with ``max(p_disc - d^2, 0)``
clamped to zero).

Exact McNemar and power formulas
---------------------------------
* Two-sided McNemar: ``P = 2 * sum_{k=min(b,c)}^{b+c} C(b+c, k) * 0.5^{b+c}``
  capped at 1.0 -- identical to ``2 * min(P(X<=min(b,c)), 0.5)`` for X ~
  Bin(b+c, 0.5). Equivalently ``P = 2 * CDF(min(b,c))`` where
  ``P(X<=k) = 0.5^{n_disc} * sum_{j=0}^{k} C(n_disc, j)``.
* McNemar's chi-square statistic (1 df, continuity-corrected):

    X^2 = (|b - c| - 1)^2 / (b + c)          when b + c >= 25, else omitted.

  We report this as ``chi2_stat`` and convert it to a two-sided p-value via
  the df=1 survival function ``erfc(sqrt(X^2/2))`` -- stdlib ``math.erfc``.
  The exact McNemar p remains the primary p; ``p_chi2_cc`` is reported as a
  compatibility figure.
* Holm over a family of p-values given by ``--holm p1,p2,...``: order p-values
  ascending; for the k-th, reject H_k if p_k <= alpha/(m - k + 1). The
  smallest index that fails to reject terminates the chain.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import sys
from typing import Dict, List, Optional, Sequence, Tuple


DISCORDANT_PDS = (0.05, 0.10, 0.20, 0.30)


class FatalUsage(Exception):
    """Exit code 2: malformed input, mismatched ids, values outside {0, 1, -1}."""


def read_tsv(path: str, metric: str) -> Tuple[List[int], List[int], int]:
    """Return (ids, values, sentinel) in file order; ``sentinel`` counts rows
    whose metric value was ``-1`` (not computed by this mode). Those rows stay
    in ``ids``/``values`` as placeholders so the caller can join on the full
    ``item_id`` set and then drop every pair where either side is ``-1``.

    Legal metric values are ``0``, ``1``, or ``-1``. Any other non-integer or
    real value not in {0, 1, -1} raises FatalUsage. A missing metric column,
    duplicate ``item_id`` rows, or an unreadable file all raise FatalUsage.
    """
    if not os.path.isfile(path):
        raise FatalUsage(f"input file not found: {path}")
    with open(path, "r", encoding="utf-8", newline="") as fh:
        header = fh.readline().rstrip("\n").rstrip("\r").split("\t")
    if not header:
        raise FatalUsage(f"{path}: empty file / no header")
    if "item_id" not in header:
        raise FatalUsage(f"{path}: header lacks required column 'item_id' (got {header})")
    if metric not in header:
        raise FatalUsage(f"{path}: header lacks column '{metric}' (got {header})")
    id_col = header.index("item_id")
    metric_col = header.index(metric)
    ids: List[int] = []
    values: List[int] = []
    sentinel = 0
    seen_ids = set()
    with open(path, "r", encoding="utf-8", newline="") as fh:
        fh.readline()  # skip header
        for lineno, raw in enumerate(fh, start=2):
            line = raw.rstrip("\n").rstrip("\r")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) <= max(id_col, metric_col):
                raise FatalUsage(f"{path}:{lineno}: not enough columns (got {len(parts)}, need >= {max(id_col, metric_col) + 1})")
            try:
                iid = int(parts[id_col])
            except ValueError:
                raise FatalUsage(f"{path}:{lineno}: item_id is not an integer ({parts[id_col]!r})")
            if iid in seen_ids:
                raise FatalUsage(f"{path}:{lineno}: duplicate item_id {iid}")
            seen_ids.add(iid)
            try:
                val = int(parts[metric_col])
            except ValueError:
                raise FatalUsage(f"{path}:{lineno}: metric value is not an integer ({parts[metric_col]!r})")
            if val not in (0, 1, -1):
                raise FatalUsage(f"{path}:{lineno}: metric value must be one of 0, 1, -1 (got {val})")
            if val == -1:
                # Sentinel: keep the id so the join is symmetric; the pair is
                # dropped after alignment when either side is -1.
                sentinel += 1
            ids.append(iid)
            values.append(val)
    return ids, values, sentinel


def align(a_ids: Sequence[int], a_vals: Sequence[int],
          b_ids: Sequence[int], b_vals: Sequence[int]) -> Tuple[List[int], List[int], List[int]]:
    """Inner-join on item_id, preserving A's order. Raises on mismatch/dup.

    Values are returned unchanged (including ``-1``); the caller drops pairs
    where either side is the sentinel after the join.
    """
    if len(a_ids) != len(set(a_ids)):
        # caught earlier per-file, but defensive
        raise FatalUsage("duplicate item_ids in A (caught at load time)")
    if len(b_ids) != len(set(b_ids)):
        raise FatalUsage("duplicate item_ids in B (caught at load time)")
    if set(a_ids) != set(b_ids):
        only_a = sorted(set(a_ids) - set(b_ids))[:10]
        only_b = sorted(set(b_ids) - set(a_ids))[:10]
        more = "..." if len(only_a) == 10 else ""
        raise FatalUsage(
            f"item_id sets differ (n_A={len(set(a_ids))}, n_B={len(set(b_ids))}); "
            f"first only-in-A: {only_a}{more}, first only-in-B: {only_b}{more}"
        )
    pos = {iid: i for i, iid in enumerate(b_ids)}
    iids = []
    a_out = []
    b_out = []
    for iid, av in zip(a_ids, a_vals):
        iids.append(iid)
        a_out.append(av)
        b_out.append(b_vals[pos[iid]])
    return iids, a_out, b_out


def mcnemar_exact(b: int, c: int) -> float:
    """Two-sided exact McNemar p (mid-p-style is NOT applied; this uses the
    symmetric binomial form). For ``b + c == 0`` returns 1.0."""
    n = b + c
    if n == 0:
        return 1.0
    # P(X <= min(b,c)) for X ~ Bin(n, 0.5); integer arithmetic only.
    k = min(b, c)
    # accumulate 0.5^n * sum_{j=0..k} C(n, j)
    # We work in rationals to avoid cancellation, then double the half.
    # Use Python's arbitrary precision ints.
    half_n = 1 << n  # 2 ** n
    cdf_times_pow2 = 0
    for j in range(0, k + 1):
        cdf_times_pow2 += math.comb(n, j)
    p_one_sided = cdf_times_pow2 / half_n
    return min(1.0, 2.0 * min(p_one_sided, 0.5))


def chi2_with_cc(b: int, c: int) -> Optional[Tuple[float, float]]:
    """Return (chi2_stat, p_chi2_cc) for b + c >= 25, else None.

    ``chi2_stat`` is McNemar's continuity-corrected chi-square statistic with
    1 degree of freedom::

        X^2 = (|b - c| - 1)^2 / (b + c)

    ``p_chi2_cc`` is the two-sided p-value for df=1, computed exactly via the
    stdlib as ``erfc(sqrt(X^2 / 2))``. This is the survival function of a
    chi-square(1) and is equivalent to scipy's ``1 - chi2.cdf(X^2, 1)``.
    """
    n_disc = b + c
    if n_disc < 25:
        return None
    stat = (abs(b - c) - 1) ** 2 / n_disc
    p = math.erfc(math.sqrt(stat / 2.0))
    return (stat, p)


def bootstrap_ci(a: Sequence[int], b: Sequence[int], n_boot: int, seed: int,
                 alpha: float = 0.025) -> Tuple[float, float]:
    """Paired percentile bootstrap on the per-item delta (A - B).

    Per-item deltas are in {-1, 0, 1}, so a replicate's delta is determined by
    the counts of +1 and -1 draws, which are multinomial. Drawing those counts
    directly is O(1) per replicate; resampling the item list is O(n_boot x n)
    Python work and stalls on large dumps."""
    deltas = [av - bv for av, bv in zip(a, b)]
    n = len(deltas)
    if n == 0 or n_boot <= 0:
        return (float("nan"), float("nan"))
    up = sum(1 for d in deltas if d > 0)
    down = sum(1 for d in deltas if d < 0)
    p_up = up / n
    # P(down | not up) keeps the two draws independent of each other while
    # reproducing the multinomial exactly.
    p_down_given_not_up = down / (n - up) if n > up else 0.0
    rng = random.Random(seed)

    def binom(trials: int, p: float) -> int:
        draw = getattr(rng, "binomialvariate", None)
        if draw is not None:
            return int(draw(trials, p))
        # Python < 3.12: sum of Bernoulli draws on the same seeded RNG.
        return sum(1 for _ in range(trials) if rng.random() < p)

    stats = []
    for _ in range(n_boot):
        pos = binom(n, p_up)
        neg = binom(n - pos, p_down_given_not_up)
        stats.append((pos - neg) / n)
    stats.sort()
    lo = stats[min(int(alpha * n_boot), len(stats) - 1)]
    hi = stats[min(int((1.0 - alpha) * n_boot), len(stats) - 1)]
    return (lo, hi)


def holm(pvals: Sequence[float]) -> List[Dict[str, float]]:
    """Holm step-down: order p ascending; for k-th (1-indexed), reject if
    p_k <= alpha / (m - k + 1). alpha=0.05."""
    m = len(pvals)
    if m == 0:
        return []
    alpha = 0.05
    order = sorted(range(m), key=lambda i: pvals[i])
    out = []
    rejected_chain = True
    for rank, idx in enumerate(order):
        k = rank + 1
        thr = alpha / (m - k + 1)
        reject = rejected_chain and pvals[idx] <= thr
        out.append({
            "index": idx,
            "p": pvals[idx],
            "rank": k,
            "threshold": thr,
            "reject": bool(reject),
        })
        if not reject:
            rejected_chain = False
    return out


def mde_for_n(n: int, pd: float) -> float:
    """Bisection on d for ``N = (1.96*sqrt(pd) + 0.8416*sqrt(max(pd-d^2,0)))^2 / d^2``.

    Returns d in [0, sqrt(pd)] (NaN if N <= 0 or pd not in (0,1])."""
    if n <= 0 or not (0.0 < pd < 1.0):
        return float("nan")
    # We want N(target) = n. The function is monotonic decreasing in d over
    # (0, sqrt(pd)] so we bisect on the right tail.
    target = float(n)
    lo, hi = 1e-6, math.sqrt(pd) - 1e-12
    if hi <= 0:
        return float("nan")
    # Check: at d=eps, N is enormous (>= pd^2 / target small needs huge n),
    # decreasing toward hi. Always target must lie above the minimum.
    def n_at(d: float) -> float:
        under = max(pd - d * d, 0.0)
        return (1.96 * math.sqrt(pd) + 0.8416 * math.sqrt(under)) ** 2 / (d * d)
    # Check the boundary -- at d = sqrt(pd), N_at is finite.
    if n_at(hi) > target:
        # n is above even the largest achievable N at this pd; return hi.
        return hi
    for _ in range(200):
        mid = 0.5 * (lo + hi)
        if n_at(mid) > target:
            lo = mid
        else:
            hi = mid
    return 0.5 * (lo + hi)


def n_for_power(pd: float, d: float) -> int:
    if not (0.0 < pd < 1.0) or d <= 0:
        return 0
    if d * d > pd:
        # then sqrt(max(pd - d^2, 0)) = 0; formula collapses to (1.96^2 * pd) / d^2.
        return int(math.ceil((1.96 * math.sqrt(pd)) ** 2 / (d * d)))
    under = max(pd - d * d, 0.0)
    n = (1.96 * math.sqrt(pd) + 0.8416 * math.sqrt(under)) ** 2 / (d * d)
    return int(math.ceil(n))


def render_table(result: Dict, mde_block: Optional[Dict], power_block: Optional[Dict],
                 holm_block: Optional[List[Dict]]) -> str:
    out = []
    out.append(f"n        {result['n']}")
    out.append(f"dropped  {result['dropped']}    (metric == -1, mode did not compute)")
    out.append(f"acc_a    {result['acc_a']:.4f}")
    out.append(f"acc_b    {result['acc_b']:.4f}")
    out.append(f"delta    {result['delta']:+.4f}   (A - B; positive = A better)")
    out.append(f"b        {result['b']:>4}    (A right / B wrong)")
    out.append(f"c        {result['c']:>4}    (A wrong / B right)")
    out.append(f"p_disc   {result['p_disc']:.4f}   ((b+c)/n)")
    out.append(f"p_exact  {result['p_mcnemar_exact']:.6f}   (McNemar, exact, primary)")
    if result["chi2_stat"] is None:
        out.append("chi2_stat n/a (b+c < 25)")
        out.append("p_chi2_cc n/a (b+c < 25)")
    else:
        out.append(f"chi2_stat {result['chi2_stat']:.4f}   (|b-c|-1)^2/(b+c), 1 df)")
        out.append(f"p_chi2_cc {result['p_chi2_cc']:.6f}   (chi^2 with continuity correction, df=1, erfc(sqrt(stat/2)); {result['b']+result['c']} discordant)")
    out.append(f"ci_lo    {result['ci_lo']:+.4f}")
    out.append(f"ci_hi    {result['ci_hi']:+.4f}    (95% paired bootstrap, percentile, n={result.get('_boot_n', '?')}, seed={result.get('_boot_seed', '?')})")
    if holm_block:
        out.append("holm     (alpha=0.05)")
        for h in holm_block:
            out.append(f"   rank {h['rank']:>2}  p={h['p']:.4f}  thr={h['threshold']:.4f}  reject={h['reject']}")
    if mde_block:
        out.append("mde      (minimum detectable difference at given n, 80% power, alpha=0.05)")
        for pd in DISCORDANT_PDS:
            d = mde_block.get(str(pd))
            if d is None or math.isnan(d):
                out.append(f"   p_disc={pd:.2f} : n/a")
            else:
                out.append(f"   p_disc={pd:.2f} : d={d:.4f}  ({d * 100:.2f} pp)")
    if power_block:
        out.append(f"power    (sample size for p_disc={power_block['pd']:.2f}, delta={power_block['d']:.4f} -> {power_block['d']*100:.2f} pp)")
        out.append(f"   n_required = {power_block['n_required']}")
    return "\n".join(out)


def run(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="paired_eval.py",
        description="Paired McNemar + bootstrap CI for two binary TSV item dumps.",
    )
    parser.add_argument("dump_a", nargs="?", help="TSV dump A, columns include item_id and --metric")
    parser.add_argument("dump_b", nargs="?", help="TSV dump B, columns include item_id and --metric")
    parser.add_argument("--metric", help="column name (values 0, 1, or -1)")
    parser.add_argument("--json", help="write machine-readable JSON to this path")
    parser.add_argument("--bootstrap", type=int, default=10000, help="bootstrap resamples (default 10000)")
    parser.add_argument("--seed", type=int, default=0, help="bootstrap seed (default 0)")
    parser.add_argument("--holm", help="comma-separated p-values for Holm correction, e.g. '0.001,0.012,0.06'")
    parser.add_argument("--mde", type=int, help="print MDE at this n for p_disc in {5,10,20,30} percent")
    parser.add_argument("--power", dest="power_pd", type=float, help="with --delta, print required n for p_disc=p_power")
    parser.add_argument("--delta", type=float, help="delta to use with --power")
    args = parser.parse_args(argv)

    # Planning modes need no dumps: `--mde N` prints the minimum detectable
    # difference at that n, `--power PD --delta D` prints the required n.
    if args.dump_a is None or args.dump_b is None or args.metric is None:
        if args.power_pd is not None:
            if args.delta is None:
                parser.error("--power needs --delta")
            print(f"power    p_disc={args.power_pd:.2f} delta={args.delta:.4f} -> n_required = {n_for_power(args.power_pd, args.delta)}")
            return 0
        if args.mde is not None:
            print("mde      (minimum detectable difference at given n, 80% power, alpha=0.05)")
            for pd in DISCORDANT_PDS:
                d = mde_for_n(args.mde, pd)
                print(f"   p_disc={pd:.2f} : d={d:.4f}  ({d * 100:.2f} pp)")
            return 0
        parser.error("dump_a, dump_b and --metric are required unless --mde/--power is used")

    try:
        a_ids, a_vals, a_sentinel = read_tsv(args.dump_a, args.metric)
        b_ids, b_vals, b_sentinel = read_tsv(args.dump_b, args.metric)
        iids, a_out, b_out = align(a_ids, a_vals, b_ids, b_vals)
    except FatalUsage as e:
        print(f"paired_eval: {e}", file=sys.stderr)
        return 2

    # Drop a pair when either side is the sentinel. Doing this after the join
    # keeps the contract symmetric: one dump may write -1 where the other
    # computed a value without turning the id sets into a fatal mismatch.
    keep = [(i, av, bv) for i, av, bv in zip(iids, a_out, b_out)
            if av != -1 and bv != -1]
    dropped = len(iids) - len(keep)
    iids = [k[0] for k in keep]
    a_out = [k[1] for k in keep]
    b_out = [k[2] for k in keep]

    n = len(iids)
    if n < 2:
        # Fewer than 2 paired rows after dropping sentinel pairs; statistics
        # are undefined.
        print(
            f"paired_eval: too few rows after dropping -1 pairs "
            f"(n={n}, paired={len(iids)}, dropped={dropped}, "
            f"sentinel_a={a_sentinel}, sentinel_b={b_sentinel}; need >= 2)",
            file=sys.stderr,
        )
        return 2
    correct_a = sum(a_out)
    correct_b = sum(b_out)
    b = sum(1 for av, bv in zip(a_out, b_out) if av == 1 and bv == 0)
    c = sum(1 for av, bv in zip(a_out, b_out) if av == 0 and bv == 1)
    p_disc = (b + c) / n
    p_exact = mcnemar_exact(b, c)
    chi2_ret = chi2_with_cc(b, c)
    if chi2_ret is None:
        chi2_stat: Optional[float] = None
        p_chi2_cc: Optional[float] = None
    else:
        chi2_stat, p_chi2_cc = chi2_ret

    # Bootstrap on delta = (A - B)
    ci_lo, ci_hi = bootstrap_ci(a_out, b_out, args.bootstrap, args.seed)

    result = {
        "n": n,
        "dropped": dropped,
        "acc_a": correct_a / n,
        "acc_b": correct_b / n,
        "delta": (correct_a - correct_b) / n,
        "b": b,
        "c": c,
        "p_disc": p_disc,
        "p_mcnemar_exact": p_exact,
        "chi2_stat": chi2_stat,
        "p_chi2_cc": p_chi2_cc,
        "ci_lo": ci_lo,
        "ci_hi": ci_hi,
        "_boot_n": args.bootstrap,
        "_boot_seed": args.seed,
        "metric": args.metric,
        "dump_a": os.path.basename(args.dump_a),
        "dump_b": os.path.basename(args.dump_b),
    }

    mde_block: Optional[Dict[str, float]] = None
    if args.mde is not None:
        mde_block = {str(pd): mde_for_n(args.mde, pd) for pd in DISCORDANT_PDS}
        result["mde_at_n"] = mde_block

    power_block: Optional[Dict] = None
    if args.power_pd is not None and args.delta is not None:
        d = args.delta
        if not (0.0 < d < 1.0):
            print(f"paired_eval: --delta must be in (0, 1) (got {d})", file=sys.stderr)
            return 2
        n_required = n_for_power(args.power_pd, d)
        power_block = {"pd": args.power_pd, "d": d, "n_required": n_required}
        result["power"] = power_block
    elif (args.power_pd is None) != (args.delta is None):
        print("paired_eval: --power and --delta must be given together", file=sys.stderr)
        return 2

    holm_block: Optional[List[Dict]] = None
    if args.holm:
        try:
            pvals = [float(x) for x in args.holm.split(",")]
        except ValueError as e:
            print(f"paired_eval: --holm: not a comma list of floats ({e})", file=sys.stderr)
            return 2
        holm_block = holm(pvals)
        result["holm"] = holm_block

    print(render_table(result, mde_block, power_block, holm_block))
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(result, fh, indent=2, sort_keys=False)
            fh.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(run())
