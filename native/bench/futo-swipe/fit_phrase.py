"""Fits the phrase lattice's weights (GlidePhraseLattice) on real swipes.

Works from `glide_beam_eval`'s `PHRASES=1 DUMP=<path>`: each phrase's segments
and their candidates, split by session into a fit half and a held-out half. Finds
the n-gram weights on the fit half by coordinate descent, then the context model's
weight and cap the same way (`phrase_lm_eval` scores the phrases), and prints each
step's numbers on the held-out half, with forward-only reading for comparison.

    python -I fit_phrase.py <dump.tsv> <bigrams.txt> <trigrams.txt> <phrase_lm_eval binary> <model.wmlm>
"""

import collections
import heapq
import math
import subprocess
import sys


def load(path, parts):
    out = {}
    for line in open(path):
        p = line.split()
        if len(p) == parts + 1:
            out[tuple(p[:parts])] = int(p[parts])
    return out


def read_dump(path):
    phrases = collections.OrderedDict()
    for line in open(path):
        f = line.rstrip("\n").split("\t")
        p = phrases.setdefault(f[0], {"held": int(f[4]), "words": [], "pools": [], "ctx": f[5].split()})
        p["words"].append(f[3])
        pool = []
        if len(f) > 6 and f[6]:
            for c in f[6].split("|"):
                w, s = c.rsplit(":", 1)
                pool.append((w, float(s)))
        p["pools"].append(pool)
    return list(phrases.values())


def main():
    dump, bi_path, tri_path, lm_eval, model = sys.argv[1:6]
    bi, tri = load(bi_path, 2), load(tri_path, 3)
    firsts = {k[0] for k in bi}
    phrases = read_dump(dump)
    print(len(phrases), "phrases", flush=True)

    def lift(x):
        def of(w2, w1, w):
            if w1 is None:
                return 0.0
            t = tri.get((w2, w1, w), 0) if w2 is not None else 0
            if t:
                return x["tri"] * math.log1p(t)
            b = bi.get((w1, w), 0)
            if b:
                return x["bi"] * math.log1p(b)
            return -x["unseen"] if w1 in firsts else 0.0
        return of

    def start(p):
        c = p["ctx"]
        return (c[-2] if len(c) > 1 else None), (c[-1] if c else None)

    def beam(p, s, width=64):
        h2, h1 = start(p)
        paths = [(0.0, (), h2, h1)]
        for pool in p["pools"]:
            if not pool:
                continue
            next_paths = [(sc + ns + s(w2, w1, w), path + (w,), w1, w) for sc, path, w2, w1 in paths for w, ns in pool]
            paths = heapq.nlargest(width, next_paths, key=lambda v: v[0])
        return paths

    def forward(p, s):
        h2, h1 = start(p)
        out = []
        for pool in p["pools"]:
            if pool:
                w = max(pool, key=lambda c: c[1] + s(h2, h1, c[0]))[0]
                out.append(w)
                h2, h1 = h1, w
        return tuple(out)

    def tally(held, pick):
        hit = n = 0
        by = collections.defaultdict(lambda: [0, 0])
        for p in phrases:
            if p["held"] != held:
                continue
            o, t = pick(p), tuple(p["words"])
            hit += sum(a == b for a, b in zip(o, t))
            n += len(t)
            by[len(t)][0] += 1
            by[len(t)][1] += o == t
        return round(hit / n, 4), {k: round(v[1] / v[0], 4) for k, v in sorted(by.items())}

    silent = lambda *_: 0.0
    print("leader, held out", tally(1, lambda p: forward(p, silent)), flush=True)

    fit = [p for p in phrases if p["held"] == 0][:2000]

    def fit_score(x):
        s = lift(x)
        hit = n = 0
        for p in fit:
            o = beam(p, s)[0][1]
            hit += sum(a == b for a, b in zip(o, p["words"]))
            n += len(p["words"])
        return hit / n

    grid = {"tri": [0.1, 0.2, 0.3, 0.4, 0.6], "bi": [0.06, 0.12, 0.18, 0.24, 0.36], "unseen": [0, 0.25, 0.5, 1, 2]}
    x = {"tri": 0.3, "bi": 0.18, "unseen": 0.5}
    top = fit_score(x)
    for _ in range(2):
        for k, values in grid.items():
            for v in values:
                y = dict(x, **{k: v})
                t = fit_score(y)
                if t > top + 1e-6:
                    top, x = t, y
    print("n-gram weights", x, flush=True)
    s = lift(x)
    print("lattice, held out", tally(1, lambda p: beam(p, s)[0][1]), flush=True)
    print("forward only, held out", tally(1, lambda p: forward(p, s)), flush=True)

    rows = []
    for p in phrases:
        p["beam"] = beam(p, s)[:8]
        rows += [" ".join(p["ctx"][-16:]) + "\t" + " ".join(b[1]) for b in p["beam"]]
    out = subprocess.run([lm_eval, model], input="\n".join(rows) + "\n", capture_output=True, text=True).stdout.split()
    i = 0
    for p in phrases:
        p["lp"] = [float(v) for v in out[i:i + len(p["beam"])]]
        i += len(p["beam"])

    def blended(weight, cap):
        def pick(p):
            if not p["beam"]:
                return ()
            ref = p["lp"][0]
            best, best_score = None, -math.inf
            for b, lp in zip(p["beam"], p["lp"]):
                limit = cap * len(b[1])
                shift = 0.0 if math.isnan(lp) or math.isnan(ref) else max(-limit, min(limit, weight * (lp - ref)))
                if b[0] + shift > best_score:
                    best, best_score = b[1], b[0] + shift
            return best
        return pick

    grid_lm = [(w, c) for w in (0.1, 0.2, 0.3, 0.5, 0.7, 1.0) for c in (0.5, 1, 2, 3)]
    w, c = max(grid_lm, key=lambda g: tally(0, blended(*g))[0])
    print("context model weight", w, "cap per word", c)
    print("with the model, held out", tally(1, blended(w, c)), flush=True)


if __name__ == "__main__":
    main()
