#!/usr/bin/env python3
"""Scores the copier against the app's own built-in themes, on pictures of them
rendered by the real keyboard, with the right answers read out of the Kotlin.

    builtins.py <run_out_dir> [--show field]

run_out_dir holds run.py's output for pictures named wm_builtin_<name>.png, or
<name>.png. fuzz.py paints with its own idea of the renderer. This is the check
that an answer is right, rather than close to the copier's own redraw of itself.
"""
import argparse
import json
import os
import re
from collections import defaultdict

import cv2
import numpy as np

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
THEMES = "core/theme/src/main/java/com/wasimaster/wmkeyboard/core/theme"
FIELDS = ["boardBackground", "keyBackground", "keyText", "modifierKeyBackground", "modifierKeyText",
          "enterKeyBackground", "enterKeyText", "keyBorderColor"]


def block_at(text, start):
    depth = 0
    for i in range(start, len(text)):
        depth += text[i] == "("
        depth -= text[i] == ")"
        if depth == 0:
            return text[start:i]
    return text[start:]


def read_truth():
    out = {}
    for name in ("PaletteThemes.kt", "ThemeSpec.kt"):
        text = open(os.path.join(ROOT, THEMES, name)).read()
        for m in re.finditer(r'id = "builtin_(\w+)"', text):
            open_at = text.rfind("(", 0, m.start())
            block = re.sub(r"//[^\n]*", "", block_at(text, open_at))
            t = {}
            for field in FIELDS:
                v = re.search(rf"\b{field} = 0x([0-9A-Fa-f]{{8}})", block)
                if v:
                    t[field] = int(v.group(1), 16)
            for field in ("keyCornerRadiusDp", "keyBorderWidthDp"):
                v = re.search(rf"\b{field} = ([0-9.]+)", block)
                if v:
                    t[field] = float(v.group(1))
            v = re.search(r"\bkeyShape = KeyShapeKind\.(\w+)", block)
            t["shape"] = v.group(1) if v else "ROUNDED"
            t["gradient"] = "boardGradient =" in block
            t["image"] = "backgroundImage" in block or "frost" in block.lower()
            out[m.group(1)] = t
    return out


def rgb(v):
    return np.array([(v >> 16) & 255, (v >> 8) & 255, v & 255], dtype=np.float32)


def de(a, b):
    la = cv2.cvtColor(np.array([[a]], dtype=np.float32) / 255, cv2.COLOR_RGB2Lab)[0, 0]
    lb = cv2.cvtColor(np.array([[b]], dtype=np.float32) / 255, cv2.COLOR_RGB2Lab)[0, 0]
    return float(np.linalg.norm(la - lb))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("--show")
    args = parser.parse_args()
    truth = read_truth()
    passes, totals, misses = defaultdict(int), defaultdict(int), defaultdict(list)
    errors = defaultdict(list)

    def check(field, ok, name, detail=""):
        totals[field] += 1
        passes[field] += bool(ok)
        if not ok:
            misses[field].append(f"{name} {detail}")

    seen = 0
    for f in sorted(os.listdir(args.out)):
        if not f.endswith(".json") or f.endswith(".wmtheme.json"):
            continue
        name = f[:-5].replace("wm_builtin_", "")
        t = truth.get(name)
        if not t or t["image"]:
            continue
        c = json.load(open(os.path.join(args.out, f)))
        seen += 1
        check("found", "_copy" in c, name)
        if "_copy" not in c:
            continue
        for field, key in (("boardBackground", "boardBackground"), ("keyBackground", "keyBackground"),
                           ("keyText", "keyText"), ("modifierKeyBackground", "modifierKeyBackground"),
                           ("enterKeyBackground", "enterKeyBackground"), ("enterKeyText", "enterKeyText")):
            if field not in t or (field == "boardBackground" and t["gradient"]) or key not in c:
                continue
            e = de(rgb(c[key]), rgb(t[field]))
            errors[field].append(e)
            check(field, e < 5, name, f"dE {e:.1f}")
        got = c.get("keyShape")
        same = got == t["shape"] or {got, t["shape"]} <= {"ROUNDED", "SHARP"}
        check("shape", same, name, f"{got} vs {t['shape']}")
        if "keyCornerRadiusDp" in t and got == "ROUNDED":
            check("radius", abs(c.get("keyCornerRadiusDp", 0) - t["keyCornerRadiusDp"]) <= 2, name,
                  f"{c.get('keyCornerRadiusDp')} vs {t['keyCornerRadiusDp']}")
        has = c.get("keyBorderWidthDp", 0) > 0
        check("border", has == (t.get("keyBorderWidthDp", 0) > 0), name, f"{c.get('keyBorderWidthDp', 0)} vs {t.get('keyBorderWidthDp', 0)}")
    print(f"{seen} themes with answers, of {len(truth)} parsed")
    for field in totals:
        extra = f"  median dE {np.median(errors[field]):.1f}" if errors[field] else ""
        print(f"{field:24} {passes[field]:3}/{totals[field]:<3} {100 * passes[field] / totals[field]:5.1f}%{extra}")
    if args.show:
        for line in misses[args.show]:
            print(line)


if __name__ == "__main__":
    main()
