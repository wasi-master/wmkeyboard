"""The letters a stroke marks, and when to offer them (#1291, `GlideLiteral`).

A mirror of `GlideLiteral.read` (the keys where a stroke starts, turns by
35 degrees or more over 0.6 key widths either side, pauses for 8 times its
median pace, and ends, read 0.2 key widths back from the lift), run over the
real-swipe fixture, and the measurement behind `GlideLiteral.FIT_SHARE`. The
Kotlin port reads the same letters as this on 1,994 of the 2,000 strokes; the
other six are points equidistant from two keys, rounded apart by Float.

    python -I literal_gate.py prepare <futo-swipes.txt.gz> <word list> <dir>

writes into <dir>: the fixture as `glide_beam_eval` reads it (`strokes.jsonl`,
`qwerty.json`), the word list less every word the fixture holds
(`missing.txt`, standing in for words no list has), and each stroke's
letters as a word list of their own (`letters.txt`). Then, from
`native/wm-engine`:

    SHAPE_COST=1 RESULTS=8 DUMP=<dir>/listed.tsv cargo run --release --example glide_beam_eval -- <dir>/strokes.jsonl <dir>/qwerty.json <word list> < /dev/null
    SHAPE_COST=1 RESULTS=8 ALL=1 DUMP=<dir>/missing.tsv cargo run --release --example glide_beam_eval -- <dir>/strokes.jsonl <dir>/qwerty.json <dir>/missing.txt < /dev/null
    SHAPE_COST=1 RESULTS=8 ALL=1 DUMP=<dir>/letters.tsv cargo run --release --example glide_beam_eval -- <dir>/strokes.jsonl <dir>/qwerty.json <dir>/letters.txt < /dev/null

and

    python -I literal_gate.py report <futo-swipes.txt.gz> <dir>

prints, for each share of the best reading's alignment cost the letters may
explain the stroke within, how often they would sit beside a right reading,
push the right word off a three-word strip, and be the word a list lacks.
"""

import gzip
import json
import math
import sys

STEP, REACH, CORNER, PAUSE, END_BACK = 0.2, 0.6, 35.0, 8.0, 0.2
SLOTS = 3


def load(path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        lines = f.read().splitlines()
    keys = []
    for entry in lines[0].removeprefix("#keys ").split(" "):
        letter, at = entry.split(":")
        x, y = at.split(",")
        keys.append((letter, int(x) / 1000, int(y) / 1000))
    strokes = []
    for line in lines[1:]:
        if not line.strip():
            continue
        fields = line.split(" ")
        word, w, h = fields[0], float(fields[1]), float(fields[2])
        kw = w * 0.1
        t, points = 0, []
        for p in fields[3:]:
            x, y, dt = p.split(",")
            t += int(dt)
            points.append((int(x) / 1000 * w, int(y) / 1000 * h, t))
        strokes.append((word, w, h, points))
    return keys, strokes


def resample(points, kw):
    px, py, pt = points[0][0] / kw, points[0][1] / kw, points[0][2]
    out, carried = [(px, py, pt)], 0.0
    for x, y, t in points[1:]:
        x, y = x / kw, y / kw
        seg = math.hypot(x - px, y - py)
        while seg > 0 and carried + seg >= STEP:
            r = (STEP - carried) / seg
            px, py, pt = px + (x - px) * r, py + (y - py) * r, pt + (t - pt) * r
            out.append((px, py, pt))
            seg, carried = math.hypot(x - px, y - py), 0.0
        carried += seg
        px, py, pt = x, y, t
    lx, ly = points[-1][0] / kw, points[-1][1] / kw
    if math.hypot(out[-1][0] - lx, out[-1][1] - ly) > STEP * 0.25:
        out.append((lx, ly, points[-1][2]))
    return out


def turn(a, b, c):
    ux, uy, vx, vy = b[0] - a[0], b[1] - a[1], c[0] - b[0], c[1] - b[1]
    nu, nv = math.hypot(ux, uy), math.hypot(vx, vy)
    if nu < 1e-6 or nv < 1e-6:
        return 0.0
    return math.acos(max(-1.0, min(1.0, (ux * vx + uy * vy) / (nu * nv))))


def letters(points, kw, centres):
    r = resample(points, kw)
    n = len(r)
    if n < 2:
        return ""
    w = max(1, round(REACH / STEP))
    turns = [0.0] * n
    for i in range(1, n - 1):
        turns[i] = turn(r[max(0, i - w)], r[i], r[min(n - 1, i + w)])
    marks = [(0, r[0])]
    for i in range(1, n - 1):
        lo, hi = max(0, i - w), min(n - 1, i + w)
        if turns[i] < math.radians(CORNER) or any(turns[j] > turns[i] for j in range(lo, hi + 1) if j != i):
            continue
        if any(turns[j] == turns[i] for j in range(lo, i)):
            continue
        marks.append((i, r[i]))
    steps = sorted(r[i + 1][2] - r[i][2] for i in range(n - 1))
    median = steps[len(steps) // 2]
    if median > 0 and n >= 3:
        def dwell(i):
            return (r[i + 1][2] - r[i - 1][2]) / 2
        for i in range(1, n - 1):
            if dwell(i) >= PAUSE * median and all(dwell(j) <= dwell(i) for j in range(max(1, i - 1), min(n - 2, i + 1) + 1)):
                marks.append((i, r[i]))
    marks.append((n - 1, r[max(0, n - 1 - round(END_BACK / STEP))]))
    marks.sort(key=lambda m: m[0])
    out = []
    for _, p in marks:
        key = min(centres, key=lambda c: (p[0] - c[1]) ** 2 + (p[1] - c[2]) ** 2)[0]
        if not out or out[-1] != key:
            out.append(key)
    word = "".join(out)
    return word if len(word) >= 2 else ""


def collapse(word):
    return "".join(c for i, c in enumerate(word) if i == 0 or word[i - 1] != c)


def read_all(path, keys):
    _, strokes = load(path)
    out = []
    for word, w, h, points in strokes:
        kw = w * 0.1
        centres = [(l, x * w / kw, y * h / kw) for l, x, y in keys]
        out.append((word, letters(points, kw, centres)))
    return out


def prepare(fixture, word_list, out):
    keys, strokes = load(fixture)
    with open(f"{out}/qwerty.json", "w") as f:
        json.dump({"keys": [{"letter": l, "cx": x, "cy": y} for l, x, y in keys]}, f)
    with open(f"{out}/strokes.jsonl", "w") as f:
        for i, (word, w, h, points) in enumerate(strokes):
            data = [{"x": x / w, "y": y / h, "t": t} for x, y, t in points]
            f.write(json.dumps({"word": word, "canvas_width": w, "canvas_height": h, "data": data, "session": str(i)}) + "\n")
    words = {s[0] for s in strokes}
    with open(word_list, encoding="utf-8") as f:
        kept = [l for l in f.read().splitlines() if l.startswith("#") or not l.strip() or l.split()[0].lower() not in words]
    with open(f"{out}/missing.txt", "w") as f:
        f.write("\n".join(kept) + "\n")
    read = read_all(fixture, keys)
    with open(f"{out}/letters.txt", "w") as f:
        f.write("".join(f"{l} 1\n" for l in sorted({l for _, l in read if l})))
    exact = sum(l == w for w, l in read) / len(read)
    less = sum(l == collapse(w) for w, l in read) / len(read)
    print(f"letters are the word on {exact:.4f}, the word less its doubled letters on {less:.4f}")


def dump(path):
    rows = []
    with open(path) as f:
        for line in f:
            fields = line.rstrip("\n").split("\t")
            pool = []
            for c in fields[4].split("|") if fields[4] else []:
                w, s, sh = c.rsplit(":", 2)
                pool.append((w, float(s), float(sh)))
            rows.append(pool)
    return rows


def report(fixture, out):
    keys, _ = load(fixture)
    read = read_all(fixture, keys)
    listed, missing, own = dump(f"{out}/listed.tsv"), dump(f"{out}/missing.tsv"), dump(f"{out}/letters.tsv")
    fit = [next((sh for w, _, sh in pool if w == l), None) for (_, l), pool in zip(read, own)]
    n = len(read)
    print("share  beside a right reading  pushed off  right on a missing word  of offers")
    for share in (0.3, 0.35, 0.4, 0.5, None):
        beside = pushed = offered = right = 0
        for (word, l), cost, ins, outs in zip(read, fit, listed, missing):
            if not l or cost is None:
                continue
            def offer(pool):
                if any(collapse(w) == l for w, _, _ in pool):
                    return False
                return share is None or not pool or (pool[0][2] > 0 and cost <= share * pool[0][2])
            if ins and offer(ins):
                beside += ins[0][0] == word
                strip = [w for w, _, _ in ins[:SLOTS]]
                pushed += word in strip and strip.index(word) == SLOTS - 1
            if offer(outs):
                offered += 1
                right += l == collapse(word)
        print(f"{share or 'never':<6} {beside / n:>22.3f}  {pushed:>10d}  {right / n:>23.3f}  {right / max(1, offered):>9.3f}")


if __name__ == "__main__":
    if sys.argv[1] == "prepare":
        prepare(*sys.argv[2:5])
    else:
        report(*sys.argv[2:4])
