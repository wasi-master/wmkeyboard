"""Fit the decoder's verdict gate on the sentence harness's dumps.

usage: fit_gate.py --fit=dump.tsv[:weight][,dump.tsv[:weight]...] --check=dump.tsv[,...]
                   [--hidden=8] [--l2=3e-3] [--steps=4000] [--new-weight=3]
                   [--confidence=4] [--rust=../../native/wm-engine/src/decoder.rs] [--plain]

Dumps come from `sentence_eval ... DUMP=file` (recorded taps with `REPLAY`,
or the simulated hand with `HOLDOUT` for correctly typed words the lists do
not hold). Each buffer the decoder read gives one row: its best reading other
than the letters as typed, against the literal. The label is whether that
reading is the text meant. Rows where neither is right are left out, since
applying or not changes nothing there. Correctly typed held-out words (kind
`new`) are weighted up: rewriting a name is the failure the gate is for.

The features are the ones `decoder::Gate::features` computes from a decode;
the two must agree, which `decoder.rs`'s fixed case checks. With `--rust`,
the fitted constants replace those between `// GATE FIT BEGIN` and
`// GATE FIT END`; with `--plain` too, those between `// GATE PLAIN FIT
BEGIN` and `// GATE PLAIN FIT END`, the set for readings made without the
context model (fit it on dumps from runs with no model).
"""
import math
import re
import sys

import numpy as np

F = 17
GAP_CAP = 8.0
APPLY_MARGIN, KNOWN_MARGIN, ACCENT_MARGIN = 2.0, 6.0, 1.5


def bare(s):
    import unicodedata
    return "".join(c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) != "Mn")


def folds_to(text, typed):
    return text != typed and bare(text) == bare(typed)


def rows_of(path):
    out = []
    for line in open(path, encoding="utf8"):
        f = line.rstrip("\n").split("\t")
        if len(f) < 8 or not f[6] or not f[7]:
            continue
        label, kind, want, buf = f[0], f[1], f[2], f[3]
        if kind == "apostrophe":
            continue
        lit = [float(x) for x in f[6].split(",")[:3]] + [int(f[6].split(",")[3])]
        reads = []
        for r in f[7].split("|"):
            p = r.split(":")
            reads.append(dict(text=p[0], score=float(p[1]), lex=float(p[2]), model=float(p[3]),
                              edits=int(p[4]), slips=int(p[5]), spaces=int(p[6]),
                              edge=int(p[8]) if len(p) > 8 else 0,
                              kinds=[int(x) for x in p[9].split(",")] if len(p) > 9 else [0] * 5))
        out.append(dict(label=label, kind=kind, want=want, buf=buf, out=f[5].lower(), lit=lit, reads=reads,
                        edge=None))
    return out


def features(r):
    """None when the decoder's best reading is the buffer as typed."""
    typed = r["buf"]
    reads = r["reads"]
    if not reads or reads[0]["text"] == typed:
        return None
    c = reads[0]
    ls, llex, lmod, listed = r["lit"]
    others = [x["score"] for x in reads[1:] if x["text"] != typed]
    gap = min(GAP_CAP, c["score"] - others[0]) if others else GAP_CAP
    return [
        c["score"] - ls,
        c["score"] - c["lex"],
        c["lex"] - c["model"],
        c["model"],
        ls - llex,
        llex - lmod,
        lmod,
        1.0 if (c["model"] != 0 or lmod != 0) else 0.0,
        float(listed),
        float(len(c["text"].split(" "))),
        float(c["edits"]),
        float(c["slips"]),
        float(c["spaces"]),
        1.0 if c["edge"] else 0.0,
        float(len(typed)),
        max(0.0, gap),
        1.0 if folds_to(c["text"], typed) else 0.0,
    ]


def labelled(rows):
    X, y, w, keep = [], [], [], []
    for r in rows:
        f = features(r)
        if f is None:
            continue
        cand = r["reads"][0]["text"].lower()
        right_c = cand == r["want"]
        right_l = r["buf"] == r["want"].replace("'", "")
        if not right_c and not right_l:
            continue
        X.append(f)
        y.append(1.0 if right_c else 0.0)
        w.append(1.0)
        keep.append(r)
    return np.array(X), np.array(y), np.array(w), keep


def fit(X, y, wt, hidden, l2, steps, seed=0):
    rng = np.random.default_rng(seed)
    mean = X.mean(axis=0)
    scale = X.std(axis=0)
    scale[scale == 0] = 1.0
    Z = (X - mean) / scale
    n, d = Z.shape
    P = [rng.normal(0, 1 / np.sqrt(d), (d, hidden)), np.zeros(hidden), rng.normal(0, 1 / np.sqrt(hidden), hidden), np.zeros(1)]
    m = [np.zeros_like(p) for p in P]
    v = [np.zeros_like(p) for p in P]
    lr, b1_, b2_, eps = 0.01, 0.9, 0.999, 1e-8
    for t in range(1, steps + 1):
        H = np.tanh(Z @ P[0] + P[1])
        p = 1 / (1 + np.exp(-(H @ P[2] + P[3][0])))
        g = (p - y) * wt / wt.sum()
        gW2 = H.T @ g + l2 * P[2]
        gb2 = np.array([g.sum()])
        gH = np.outer(g, P[2]) * (1 - H * H)
        gW1 = Z.T @ gH + l2 * P[0]
        gb1 = gH.sum(axis=0)
        for i, gr in enumerate([gW1, gb1, gW2, gb2]):
            m[i] = b1_ * m[i] + (1 - b1_) * gr
            v[i] = b2_ * v[i] + (1 - b2_) * gr * gr
            P[i] -= lr * (m[i] / (1 - b1_ ** t)) / (np.sqrt(v[i] / (1 - b2_ ** t)) + eps)
    return mean, scale, P


def prob(model, X):
    mean, scale, P = model
    Z = (X - mean) / scale
    H = np.tanh(Z @ P[0] + P[1])
    return 1 / (1 + np.exp(-(H @ P[2] + P[3][0])))


def rule(f):
    lead, listed, accent = f[0], f[8], f[16]
    bar = APPLY_MARGIN if not listed else (ACCENT_MARGIN if accent else KNOWN_MARGIN)
    return lead >= bar


def report(name, X, y, keep, decide):
    fixed = sum(1 for i in range(len(y)) if decide[i] and y[i] == 1)
    broke = sum(1 for i in range(len(y)) if decide[i] and y[i] == 0)
    broke_new = sum(1 for i in range(len(y)) if decide[i] and y[i] == 0 and keep[i]["kind"] == "new")
    new = sum(1 for r in keep if r["kind"] == "new")
    pos = int(y.sum())
    print(f"  {name:<14} applied right {fixed:5d} of {pos:5d} ({100 * fixed / max(1, pos):5.1f}%)"
          f" | right words changed {broke:4d} of {len(y) - pos:5d} | new words changed {broke_new}/{new}")


def write_rust(path, model, plain=False, pre=None, marker=None, features="GATE_FEATURES"):
    """Writes the weights between the gate's markers: `GATE_*` for readings
    the context model weighed, `GATE_PLAIN_*` (``plain``) for the rest, or
    ``pre`` between ``marker`` BEGIN and END with ``features`` inputs."""
    mean, scale, P = model
    W1, b1, W2, b2 = P
    pre = pre or ("GATE_PLAIN" if plain else "GATE")
    hidden = f"{pre}_HIDDEN"
    marker = marker or ("GATE PLAIN FIT" if plain else "GATE FIT")
    fmt = lambda a: ", ".join(f"{x:.6}" for x in a)
    body = [
        f"// {marker} BEGIN",
        f"const {pre}_MEAN: [f64; {features}] = [{fmt(mean)}];",
        f"const {pre}_SCALE: [f64; {features}] = [{fmt(scale)}];",
        f"const {pre}_W1: [[f64; {features}]; {hidden}] = [",
        *[f"    [{fmt(W1[:, j])}]," for j in range(W1.shape[1])],
        "];",
        f"const {pre}_B1: [f64; {hidden}] = [{fmt(b1)}];",
        f"const {pre}_W2: [f64; {hidden}] = [{fmt(W2)}];",
        f"const {pre}_B2: f64 = {b2[0]:.6};",
        f"// {marker} END",
    ]
    src = open(path).read()
    new = re.sub(rf"// {marker} BEGIN.*?// {marker} END", lambda _: "\n".join(body), src, flags=re.S)
    new = re.sub(rf"const {hidden}: usize = \d+;", f"const {hidden}: usize = {W1.shape[1]};", new)
    open(path, "w").write(new)
    print("wrote", path, pre)


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
    new_w = float(opts.get("--new-weight", 3))
    conf = float(opts.get("--confidence", 4))
    X, y, w, keep = labelled(fit_rows)
    w = np.array([(new_w if r["kind"] == "new" else 1.0) * r.get("weight", 1.0) for r in keep])
    print(f"fit rows {len(y)} (right {int(y.sum())}, new {sum(r['kind'] == 'new' for r in keep)})")
    model = fit(X, y, w, hidden, l2, steps)
    Xc, yc, _, keepc = labelled(check_rows)
    print(f"check rows {len(yc)} (right {int(yc.sum())}, new {sum(r['kind'] == 'new' for r in keepc)})")
    report("fixed bars", Xc, yc, keepc, [rule(f) for f in Xc])
    pc = prob(model, Xc)
    for t in sorted({conf / (conf + 1), 0.6, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95}):
        report(f"gate p>={t:.2f}", Xc, yc, keepc, list(pc >= t))
    if "--rust" in opts:
        write_rust(opts["--rust"], model, plain="--plain" in sys.argv[1:])
        i = 0
        print("fixed case features", ", ".join(f"{v:.3f}" for v in Xc[i]), "p", f"{pc[i]:.6f}")
    else:
        print("first check row", ", ".join(f"{v:.3f}" for v in Xc[0]), "p", f"{pc[0]:.6f}")


if __name__ == "__main__":
    main()
