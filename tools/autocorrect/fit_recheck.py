"""Fit the re-check's verdict on the sentence harness's re-check dumps.

usage: fit_recheck.py --fit=recheck.tsv[:weight][,...] --check=recheck.tsv[,...]
                      [--hidden=8] [--l2=3e-3] [--steps=4000] [--confidence=4]
                      [--rust=../../native/wm-engine/src/decoder.rs]

Dumps come from `sentence_eval ... RECHECK=1 RECHECK_DUMP=file`: one row per
committed buffer read again once the words after it landed. Each row gives
the best reading against what was committed, and the label is whether that
reading is the text meant. Rows where neither is right are kept with label
0 and weight ``--wrong-weight`` (default 0.5): putting one wrong word in
place of another changes nothing in the count but is a rewrite the user
sees.

The features are the ones `decoder::RecheckGate::features` computes; the
two must agree, which `decoder.rs`'s fixed case checks. With `--rust`, the
fitted constants replace those between `// RECHECK FIT BEGIN` and
`// RECHECK FIT END`.
"""
import sys

import numpy as np

from fit_gate import fit, prob, write_rust

F = 22
GAP_CAP = 8.0
MARGIN, KNOWN_MARGIN = 3.0, 5.0


def rows_of(path):
    out = []
    for line in open(path, encoding="utf8"):
        f = line.rstrip("\n").split("\t")
        if len(f) < 8 or not f[7]:
            continue
        own = f[5].split(",")
        reads = []
        for r in f[7].split("|"):
            p = r.split(":")
            reads.append(dict(text=p[0], score=float(p[1]), lex=float(p[2]), model=float(p[3]),
                              edits=int(p[4]), slips=int(p[5]), spaces=int(p[6]), edge=int(p[8]),
                              kinds=[int(x) for x in p[9].split(",")] if len(p) > 9 else [0] * 5))
        out.append(dict(label=f[0], depth=int(f[1]), want=f[2], committed=f[3], typed=f[4],
                        own=float(own[0]), own_lex=float(own[1]), own_model=float(own[2]),
                        own_literal=int(own[3]), listed=int(own[4]), best_right=float(own[5]),
                        own_right=float(own[6]), reads=reads))
    return out


def features(r):
    """None when the best reading is what was committed."""
    reads = r["reads"]
    if not reads or reads[0]["text"] == r["committed"]:
        return None
    c = reads[0]
    others = [x["score"] for x in reads[1:] if x["text"] != r["committed"]]
    gap = min(GAP_CAP, c["score"] - others[0]) if others else GAP_CAP
    return [
        c["score"] - r["own"],
        c["score"] - c["lex"],
        c["lex"] - c["model"],
        c["model"],
        r["own"] - r["own_lex"],
        r["own_lex"] - r["own_model"],
        r["own_model"],
        1.0 if (c["model"] != 0 or r["own_model"] != 0) else 0.0,
        float(r["own_literal"]),
        float(r["listed"]),
        float(len(c["text"].split(" "))),
        float(c["edits"]),
        float(c["slips"]),
        float(c["spaces"]),
        float(c["kinds"][0]),
        float(c["kinds"][1] + c["kinds"][2]),
        float(c["kinds"][3]),
        float(c["kinds"][4]),
        float(len(r["typed"])),
        max(0.0, gap),
        r["best_right"] - r["own_right"],
        float(r["depth"]),
    ]


def labelled(rows, wrong_weight):
    X, y, w, keep = [], [], [], []
    for r in rows:
        f = features(r)
        if f is None:
            continue
        cand = r["reads"][0]["text"].lower()
        right_c = cand == r["want"]
        right_o = r["committed"] == r["want"]
        X.append(f)
        y.append(1.0 if right_c else 0.0)
        w.append((1.0 if (right_c or right_o) else wrong_weight) * r.get("weight", 1.0))
        keep.append((r, right_c, right_o))
    return np.array(X), np.array(y), np.array(w), keep


def rule(f):
    lead, literal, listed = f[0], f[8], f[9]
    return lead >= (KNOWN_MARGIN if literal and listed else MARGIN)


def report(name, keep, decide):
    fixed = sum(1 for (r, c, o), d in zip(keep, decide) if d and c)
    broke = sum(1 for (r, c, o), d in zip(keep, decide) if d and o)
    wrong = sum(1 for (r, c, o), d in zip(keep, decide) if d and not c and not o)
    print(f"  {name:<14} fixed {fixed:5d} | broke {broke:4d} | wrong to wrong {wrong:4d} | net {fixed - broke:5d}")


def main():
    opts = dict(a.split("=", 1) for a in sys.argv[1:] if a.startswith("--") and "=" in a)
    # `file:weight` weighs that file's rows (recorded taps above simulated).
    fit_rows = []
    for spec in opts["--fit"].split(","):
        path, _, weight = spec.partition(":")
        for r in rows_of(path):
            r["weight"] = float(weight or 1)
            fit_rows.append(r)
    check_rows = [r for p in opts["--check"].split(",") for r in rows_of(p)]
    hidden = int(opts.get("--hidden", 8))
    l2 = float(opts.get("--l2", 3e-3))
    steps = int(opts.get("--steps", 4000))
    wrong_weight = float(opts.get("--wrong-weight", 0.5))
    X, y, w, keep = labelled(fit_rows, wrong_weight)
    print(f"fit rows {len(y)} (right {int(y.sum())})")
    model = fit(X, y, w, hidden, l2, steps)
    Xc, yc, _, keepc = labelled(check_rows, wrong_weight)
    print(f"check rows {len(yc)} (right {int(yc.sum())})")
    for depth in (1, 2):
        idx = [i for i, (r, _, _) in enumerate(keepc) if r["depth"] == depth]
        if not idx:
            continue
        print(f" depth {depth}:")
        k = [keepc[i] for i in idx]
        report("fixed bars", k, [rule(Xc[i]) for i in idx])
        pc = prob(model, Xc[idx])
        for t in (0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.95):
            report(f"gate p>={t:.2f}", k, list(pc >= t))
    if "--rust" in opts:
        write_rust(opts["--rust"], model, pre="RECHECK_GATE", marker="RECHECK FIT", features="RECHECK_FEATURES")
        print("fixed case features", ", ".join(f"{v:.3f}" for v in Xc[0]), "p", f"{prob(model, Xc[:1])[0]:.6f}")


if __name__ == "__main__":
    main()
