#!/usr/bin/env python3
"""Runs the desktop theme copier over pictures and keeps what it says.

    run.py <themecopy_cli> <out_dir> <picture or folder>... [--width-dp 360]

For each picture, writes <out_dir>/<name>.json (the theme, or {} when no grid
was found), <name>_overlay.png (what was found) and <name>_redrawn.png (the
theme redrawn over the grid, labels left out). With --wmtheme, also
<name>.wmtheme.json, a file the app's Import button takes. Prints one line per
picture.
"""
import argparse
import base64
import io
import json
import os
import subprocess
import sys
import tempfile
import time

from PIL import Image, ImageOps

Image.MAX_IMAGE_PIXELS = None
EXTENSIONS = (".png", ".jpg", ".jpeg", ".webp", "")


def pictures(paths):
    for path in paths:
        if os.path.isdir(path):
            for name in sorted(os.listdir(path)):
                full = os.path.join(path, name)
                if os.path.isfile(full) and name.lower().endswith((".png", ".jpg", ".jpeg", ".webp")):
                    yield full
        else:
            yield path


def copy_one(cli, picture, out_dir, width_dp):
    name = os.path.splitext(os.path.basename(picture))[0]
    with tempfile.TemporaryDirectory() as work:
        image = ImageOps.exif_transpose(Image.open(picture)).convert("RGB")
        ppm = os.path.join(work, "in.ppm")
        image.save(ppm)
        prefix = os.path.join(work, "out")
        started = time.time()
        run = subprocess.run([cli, ppm, prefix, str(width_dp)], capture_output=True, text=True)
        elapsed = time.time() - started
        text = run.stdout.strip() or "{}"
        with open(os.path.join(out_dir, name + ".json"), "w") as f:
            f.write(text)
        for file in os.listdir(work):
            if file.startswith("out_"):
                Image.open(os.path.join(work, file)).save(os.path.join(out_dir, name + file[3:-4] + ".png"))
    theme = json.loads(text)
    return name, theme, elapsed, run.stderr.strip()


def wmtheme(theme, out_dir, name):
    """The theme as the app imports it: named, its pictures inside it."""
    report = theme["_copy"]["report"]
    theme = {k: v for k, v in theme.items() if k != "_copy"}
    theme["name"] = name
    theme["id"] = "copied"

    def embedded(slot):
        path = os.path.join(out_dir, f"{name}_{slot}.png")
        if not os.path.exists(path):
            return None
        data = io.BytesIO()
        Image.open(path).convert("RGB").save(data, "JPEG", quality=92)
        return base64.b64encode(data.getvalue()).decode()

    # By what the theme says, since an earlier run's file may still lie there.
    if report["board"] == "image":
        theme["backgroundImageBase64"] = embedded("backgroundImage")
    if "keyTextureScale" in theme:
        theme["assets"] = {"keyTexture": embedded("keyTexture")}
    with open(os.path.join(out_dir, name + ".wmtheme.json"), "w") as f:
        json.dump(theme, f)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("cli")
    parser.add_argument("out")
    parser.add_argument("pictures", nargs="+")
    parser.add_argument("--width-dp", type=float, default=360)
    parser.add_argument("--wmtheme", action="store_true")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    found = total = 0
    for picture in pictures(args.pictures):
        total += 1
        name, theme, elapsed, err = copy_one(args.cli, picture, args.out, args.width_dp)
        if not theme:
            print(f"{name:40} no grid {err}")
            continue
        found += 1
        if args.wmtheme:
            wmtheme(theme, args.out, name)
        report = theme["_copy"]["report"]
        sizing = theme["_copy"]["sizing"]
        print(
            f"{name:40} {report['confidence']:.2f} {report['grid'][:3]} {report['board']:6} rows={report['rows']} "
            f"num={int(sizing['numberRow'])} shape={theme['keyShape']:8} r={theme.get('keyCornerRadiusDp', '-')} "
            f"key={theme['keyBackground'] & 0xFFFFFFFF:08X} {elapsed * 1000:.0f}ms"
        )
    print(f"{found}/{total} found", file=sys.stderr)


if __name__ == "__main__":
    main()
