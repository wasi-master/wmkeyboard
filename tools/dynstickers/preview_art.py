#!/usr/bin/env python3
"""Draws art.json back out as SVG, to look at what the converter made.

    python3 tools/dynstickers/preview_art.py OUT_DIR [--ids cake heart_red ...] [--sheet]

Writes OUT_DIR/<id>.svg for each drawing; with --sheet also OUT_DIR/sheet.png,
a labelled contact sheet (needs rsvg-convert and ImageMagick's montage), and
with --compare the Noto original beside each converted Noto drawing.

The SVG is rebuilt from the Lottie items alone, so it shows the paths,
paints, gradients and group transforms exactly as a player gets them.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "dynstickers", "art", "art.json")


def k(prop):
    return prop["k"] if isinstance(prop, dict) else prop


def path_d(sh):
    p = k(sh["ks"])
    v, i, o, closed = p["v"], p["i"], p["o"], p.get("c", False)
    if not v:
        return ""
    parts = [f"M{v[0][0]},{v[0][1]}"]
    n = len(v)
    for j in range(1, n):
        a, b = v[j - 1], v[j]
        parts.append(f"C{a[0] + o[j - 1][0]},{a[1] + o[j - 1][1]} {b[0] + i[j][0]},{b[1] + i[j][1]} {b[0]},{b[1]}")
    if closed:
        a, b = v[-1], v[0]
        parts.append(f"C{a[0] + o[-1][0]},{a[1] + o[-1][1]} {b[0] + i[0][0]},{b[1] + i[0][1]} {b[0]},{b[1]}Z")
    return "".join(parts)


def rgb(c):
    return "#%02x%02x%02x" % tuple(max(0, min(255, round(x * 255))) for x in c[:3])


class Writer:
    def __init__(self):
        self.defs = []
        self.n = 0

    def gradient(self, g):
        self.n += 1
        gid = f"g{self.n}"
        count = g["g"]["p"]
        vals = k(g["g"]["k"])
        stops = [vals[j * 4:j * 4 + 4] for j in range(count)]
        alphas = vals[count * 4:]
        alpha = {}
        for j in range(0, len(alphas) - 1, 2):
            alpha[alphas[j]] = alphas[j + 1]
        s, e = k(g["s"]), k(g["e"])
        stop_xml = "".join(
            f'<stop offset="{st[0]}" stop-color="{rgb(st[1:])}" stop-opacity="{alpha.get(st[0], 1)}"/>' for st in stops)
        if g.get("t", 1) == 1:
            self.defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{s[0]}" y1="{s[1]}" '
                             f'x2="{e[0]}" y2="{e[1]}">{stop_xml}</linearGradient>')
        else:
            r = math.dist(s, e)
            fx, fy = s
            if "h" in g:
                h = k(g["h"]) / 100 * r
                a = math.radians(k(g.get("a", {"k": 0})))
                fx, fy = s[0] + h * math.cos(a), s[1] + h * math.sin(a)
            self.defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{s[0]}" cy="{s[1]}" '
                             f'r="{r}" fx="{fx}" fy="{fy}">{stop_xml}</radialGradient>')
        return f"url(#{gid})"

    def items(self, items):
        """SVG for a list of shape items, topmost first (Lottie order)."""
        out = []
        for it in reversed(items):
            if it.get("ty") == "gr":
                out.append(self.group(it))
        return "".join(out)

    def group(self, g):
        it = g["it"]
        tr = next((x for x in it if x.get("ty") == "tr"), None)
        paths = [x for x in it if x.get("ty") == "sh"]
        paints = [x for x in it if x.get("ty") in ("fl", "st", "gf", "gs")]
        children = [x for x in it if x.get("ty") == "gr"]
        d = "".join(path_d(p) for p in paths)
        body = []
        if children:
            body.append(self.items(children))
        for paint in reversed(paints):
            op = k(paint.get("o", {"k": 100})) / 100
            t = paint["ty"]
            if t in ("fl", "gf"):
                fill = rgb(k(paint["c"])) if t == "fl" else self.gradient(paint)
                rule = "evenodd" if paint.get("r") == 2 else "nonzero"
                body.append(f'<path d="{d}" fill="{fill}" fill-opacity="{op}" fill-rule="{rule}"/>')
            else:
                stroke = rgb(k(paint["c"])) if t == "st" else self.gradient(paint)
                cap = {1: "butt", 2: "round", 3: "square"}.get(paint.get("lc"), "butt")
                join = {1: "miter", 2: "round", 3: "bevel"}.get(paint.get("lj"), "miter")
                body.append(f'<path d="{d}" fill="none" stroke="{stroke}" stroke-opacity="{op}" '
                            f'stroke-width="{k(paint["w"])}" stroke-linecap="{cap}" stroke-linejoin="{join}"/>')
        transform = ""
        if tr:
            p, a, s, r = k(tr["p"]), k(tr["a"]), k(tr["s"]), k(tr["r"])
            transform = (f' transform="translate({p[0]} {p[1]}) rotate({r}) scale({s[0] / 100} {s[1] / 100}) '
                         f'translate({-a[0]} {-a[1]})"')
        return f"<g{transform}>{''.join(body)}</g>"


def to_svg(art):
    w = Writer()
    body = w.items(art["shapes"])
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {art["w"]} {art["h"]}" '
            f'width="{art["w"] * 2}" height="{art["h"] * 2}"><defs>{"".join(w.defs)}</defs>{body}</svg>')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--ids", nargs="*")
    ap.add_argument("--sheet", action="store_true")
    ap.add_argument("--compare", action="store_true")
    ap.add_argument("--art", default=ART)
    args = ap.parse_args()
    with open(args.art, encoding="utf-8") as fh:
        library = json.load(fh)
    os.makedirs(args.out, exist_ok=True)
    ids = args.ids or sorted(library)
    sys.path.insert(0, HERE)
    from svg_to_lottie_shapes import NOTO, NOTO_COMMIT, CACHE
    pngs = []
    for art_id in ids:
        path = os.path.join(args.out, f"{art_id}.svg")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(to_svg(library[art_id]))
        if args.sheet:
            png = os.path.join(args.out, f"{art_id}.png")
            subprocess.run(["rsvg-convert", "-w", "160", "-h", "160", "-a", "-b", "#f4f4f4", path, "-o", png], check=True)
            if args.compare and art_id in NOTO:
                orig = os.path.join(CACHE, f"noto-{NOTO_COMMIT[:12]}", f"emoji_u{NOTO[art_id]}.svg")
                opng = os.path.join(args.out, f"{art_id}.orig.png")
                subprocess.run(["rsvg-convert", "-w", "160", "-h", "160", "-a", "-b", "#ffffff", orig, "-o", opng], check=True)
                pngs.append((f"{art_id} (Noto)", opng))
            pngs.append((art_id, png))
    if args.sheet and pngs:
        montage = shutil.which("montage") or "magick"
        cmd = [montage] if montage.endswith("montage") else [montage, "montage"]
        for label, png in pngs:
            cmd += ["-label", label, png]
        font = next((f for f in ("/System/Library/Fonts/Supplemental/Arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
                     if os.path.exists(f)), None)
        if font:
            cmd += ["-font", font]
        cmd += ["-tile", "10x", "-geometry", "160x160+6+6", "-pointsize", "11", os.path.join(args.out, "sheet.png")]
        subprocess.run(cmd, check=True)
        print(os.path.join(args.out, "sheet.png"))


if __name__ == "__main__":
    main()
