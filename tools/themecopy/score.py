#!/usr/bin/env python3
"""Scores run.py's output against fuzz.py's answers.

    score.py <fuzz_dir> <run_out_dir> [--show field]

Prints, per field, how many cases got it right, and with --show which ones missed.
"""
import argparse
import json
import os
import sys
from collections import defaultdict

import cv2
import numpy as np


def lab(c):
    m = np.array([[c]], dtype=np.float32) / 255
    return cv2.cvtColor(m, cv2.COLOR_RGB2Lab)[0, 0]


def de(a, b):
    return float(np.linalg.norm(lab(a) - lab(b)))


def rgb(v):
    return [(v >> 16) & 255, (v >> 8) & 255, v & 255]


def alpha(v):
    return ((v >> 24) & 255) / 255


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("fuzz")
    parser.add_argument("out")
    parser.add_argument("--show")
    args = parser.parse_args()
    passes = defaultdict(int)
    totals = defaultdict(int)
    misses = defaultdict(list)

    def check(field, ok, name, detail=""):
        totals[field] += 1
        passes[field] += bool(ok)
        if not ok:
            misses[field].append(f"{name} {detail}")

    names = sorted(f[:-11] for f in os.listdir(args.fuzz) if f.endswith(".truth.json"))
    for name in names:
        t = json.load(open(os.path.join(args.fuzz, name + ".truth.json")))
        path = os.path.join(args.out, name + ".json")
        c = json.load(open(path)) if os.path.exists(path) else {}
        check("found", bool(c), name, f"{t['screen']} {t['shape']} {t['board']}")
        if not c:
            continue
        sizing = c["_copy"]["sizing"]
        report = c["_copy"]["report"]
        face_visible = t["shape"] != "NONE"
        tol = 6 + (10 if t["jpeg"] and t["jpeg"] < 70 else 0)
        check("numberRow", sizing["numberRow"] == t["numberRow"], name)
        check("boardKind", report["board"] == t["board"].lower(), name, f"{report['board']} vs {t['board']} res={report['boardResidual']}")
        if t["board"] == "SOLID" and not t["barOwn"]:
            check("boardColour", de(rgb(c["boardBackground"]), t["boardColour"]) < tol, name, f"{rgb(c['boardBackground'])} vs {t['boardColour']}")
        if face_visible and t["keyAlpha"] == 1.0 and not t["perKey"]:
            check("keyColour", de(rgb(c["keyBackground"]), t["keyColour"]) < tol, name, f"{rgb(c['keyBackground'])} vs {t['keyColour']}")
        if face_visible and t["keyAlpha"] < 1.0:
            check("keyAlpha", abs(alpha(c["keyBackground"]) - t["keyAlpha"]) < 0.2, name, f"{alpha(c['keyBackground']):.2f} vs {t['keyAlpha']:.2f}")
        check("keyText", de(rgb(c["keyText"]), t["keyText"]) < tol + 6, name, f"{rgb(c['keyText'])} vs {t['keyText']}")
        if face_visible:
            check("modifier", de(rgb(c["modifierKeyBackground"]), t["modifier"]) < tol, name, f"{rgb(c['modifierKeyBackground'])} vs {t['modifier']}")
            check("enter", de(rgb(c["enterKeyBackground"]), t["enter"]) < tol, name, f"{rgb(c['enterKeyBackground'])} vs {t['enter']}")
            check("enterText", de(rgb(c["enterKeyText"]), t["enterText"]) < tol + 8, name, f"{rgb(c['enterKeyText'])} vs {t['enterText']}")
        same_family = {"ROUNDED": {"ROUNDED", "SHARP", "PILL", "CIRCLE", "SQUIRCLE"}, "SHARP": {"SHARP", "ROUNDED"}, "PILL": {"PILL", "CIRCLE", "ROUNDED"}, "CIRCLE": {"PILL", "CIRCLE", "ROUNDED"}, "SQUIRCLE": {"SQUIRCLE", "ROUNDED"}}
        check("shape", c["keyShape"] == t["shape"] or c["keyShape"] in same_family.get(t["shape"], set()), name, f"{c['keyShape']} vs {t['shape']} r={t['radiusDp']} border={t['border']:.1f}")
        over = c.get("keyOverrides", {})
        round_kinds = {"PILL", "CIRCLE"}

        def own_shape(field, key_id):
            # Only a shape that differs from the board's has anything to say.
            want = t.get(field)
            if not want or want == t["shape"] or t["shape"] == "NONE":
                return
            got = over.get(key_id, {}).get("shape", c["keyShape"])
            ok = got == want or (got in round_kinds and want in round_kinds) or (got in {"ROUNDED", "SHARP"} and want in {"ROUNDED", "SHARP"})
            check(field, ok, name, f"{got} vs {want} board={t['shape']}")

        own_shape("enterShape", "ENTER")
        own_shape("modifierShape", "SYMBOLS")
        check("shapeExact", c["keyShape"] == t["shape"], name, f"{c['keyShape']} vs {t['shape']} r={t['radiusDp']}")
        if t["shape"] == "ROUNDED" and c["keyShape"] == "ROUNDED":
            check("radius", abs(c.get("keyCornerRadiusDp", 0) - t["radiusDp"]) <= 3, name, f"{c.get('keyCornerRadiusDp')} vs {t['radiusDp']}")
        check("border", (c.get("keyBorderWidthDp", 0) > 0) == (t["border"] > 0), name, f"{c.get('keyBorderWidthDp', 0)} vs {t['border']:.1f}")
        if t["border"] > 0 and c.get("keyBorderColor"):
            check("borderColour", de(rgb(c["keyBorderColor"]), t["borderColour"]) < tol + 6, name)
        check("shadow", (c.get("keyElevationDp", 0) > 0) == (t["shadow"] > 0), name, f"{c.get('keyElevationDp', 0)} vs {t['shadow']:.1f}")
        check("hints", ("hintText" in c) == t["hints"], name)
        if t["barHeightDp"] > 0:
            check("barOwn", ("suggestionBarBackground" in c) == t["barOwn"] or t["board"] != "SOLID", name)
            check("chips", ("toolCircleBackground" in c) == t["chips"], name, f"barOwn={t['barOwn']} {t['board']}")
        wdp = t["widthDp"]
        ratio = 1
        if t["screen"] == "landscape":
            ratio = None
        # A circle's box is not what shows, so nothing can read its size off it.
        if ratio and t["shape"] != "CIRCLE":
            s = wdp / 360  # run with --width-dp matching is the caller's choice
            check("keyHeight", abs(sizing["keyHeightDp"] * s - t["keyHeightDp"]) <= 3, name, f"{sizing['keyHeightDp'] * s:.1f} vs {t['keyHeightDp']}")
            check("gapH", abs(sizing["keyGapHorizontalDp"] * s - t["gapHDp"]) <= 2, name, f"{sizing['keyGapHorizontalDp'] * s:.1f} vs {t['gapHDp']}")
            check("gapV", abs(sizing["keyGapVerticalDp"] * s - t["gapVDp"]) <= 2.5, name, f"{sizing['keyGapVerticalDp'] * s:.1f} vs {t['gapVDp']}")
            check("bottom", abs(sizing["bottomGapDp"] * s - t["bottomDp"]) <= 4, name, f"{sizing['bottomGapDp'] * s:.1f} vs {t['bottomDp']}")
    for field in totals:
        print(f"{field:14} {passes[field]:4}/{totals[field]:<4} {100 * passes[field] / totals[field]:5.1f}%")
    if args.show:
        for line in misses[args.show][:60]:
            print(line)


if __name__ == "__main__":
    main()
