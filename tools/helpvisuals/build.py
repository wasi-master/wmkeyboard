#!/usr/bin/env python3
"""Turns rendered screens into the help pictures a setting's "?" dialog shows.

    ./gradlew :app:testEnDebugUnitTest -Pwmkb.docShots=true --tests "*HelpShots*" \
        -Pwmkb.helpShots.only='<regex>'
    python3 tools/helpvisuals/build.py [keys or regexes...] [--out DIR] [--sheet DIR]
    python3 tools/helpvisuals/build.py --take <key> <recording> [--dark <recording>]

HelpShots (app/src/docShots) leaves whole screens under app/build/helpshots/,
one directory per setting and look, with a small JSON beside each saying where
the keyboard is and how the picture wants to be cut. This script:

1. cuts each frame: the keyboard, or the part of the keyboard that differs
   between the off and on frames, or the line of text in the field set right
   over that part, or the whole screen;
2. scales it to at most 720 px wide and writes a lossy WebP (a still), or an
   animated WebP (a clip), named by the SHA-256 of its own bytes;
3. drops a comparison whose frames came out identical, and says so: the
   setting has nothing to show in that state, and gets no picture;
4. writes the files to <out>/help/files/, one pack per look to
   <out>/help/packs/, and the index the app ships to
   app/src/main/assets/help/visuals.tsv.

<out> is the checkout of the data repository the app downloads from
(../wmkeyboard-data beside this one, unless --out says otherwise). Nothing is
committed or pushed there: that stays a deliberate step.

The index is rebuilt from tools/helpvisuals/built.json, which remembers every
visual built so far. So a run that renders ten new settings adds ten, and the
other pictures stay as they are without their screens having to be rendered
again. `--forget <key>` takes a visual out.

`--take` files a recording made on a phone as a clip, for what the JVM cannot
draw: any video ffmpeg reads, cut and scaled the same way.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

from PIL import Image, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
RENDERS = os.path.join(REPO, "app", "build", "helpshots")
INDEX = os.path.join(REPO, "app", "src", "main", "assets", "help", "visuals.tsv")
BUILT = os.path.join(HERE, "built.json")
DEFAULT_OUT = os.path.join(os.path.dirname(REPO), "wmkeyboard-data")

THEMES = ("light", "dark")
DP = 2.75  # the render device: 440 dpi
MAX_WIDTH = 720
STILL_QUALITY = 82
CLIP_QUALITY = 68
CLIP_WIDTH = 540
# A clip rests on its last frame this long before it loops.
CLIP_REST_MS = 1400
# Pixels that differ by less than this are the same pixel: dithering, not a change.
DIFF_THRESHOLD = 18
# A difference smaller than this many pixels is a cursor, not a setting.
MIN_DIFF_AREA = 900
DIFF_MARGIN = 36
# A cut narrower than this share of the screen keeps its own width; anything
# wider is the whole width, so a row is never cut mid-key.
FULL_WIDTH_ABOVE = 0.55
MIN_CUT = (300, 150)
# A cut's top and bottom move out to the nearest row of one flat colour, the
# gap between two rows of keys, looked for this far, so no key is cut through.
SNAP_REACH = 190
SNAP_KEEP = 8
# Pixels of one row that differ by less than this are one colour.
FLAT = 10
# A keyboard cut keeps this much of the flat band above its first drawn row.
TOP_KEEP = 20


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def run(*cmd):
    done = subprocess.run(cmd, capture_output=True, text=True)
    if done.returncode != 0:
        sys.exit(f"{' '.join(cmd[:3])}… failed:\n{done.stderr[-2000:]}")


def frames_of(key, theme):
    """The frame JSONs of one visual in one look, in order; [] when it was not rendered."""
    directory = os.path.join(RENDERS, key, theme)
    if not os.path.isdir(directory):
        return []
    metas = []
    for name in sorted(os.listdir(directory), key=lambda n: (len(n), n)):
        if re.fullmatch(r"\d+\.json", name):
            with open(os.path.join(directory, name)) as f:
                meta = json.load(f)
            meta["dir"] = directory
            meta["index"] = int(name.split(".")[0])
            metas.append(meta)
    if metas and len(metas) != metas[0]["frames"]:
        return []  # a frame failed: an incomplete visual is not built
    return metas


def keyboard_top(metas):
    """Where the tallest keyboard among the frames starts, with the room asked for above it."""
    return max(0, min(m["keyboardTop"] - round(m.get("above", 0) * DP) for m in metas))


def diff_box(images, region):
    """What differs between the frames inside [region], as one box; None when nothing does."""
    box = None
    first = images[0].crop(region)
    for other in images[1:]:
        diff = ImageChops.difference(first, other.crop(region)).convert("L").point(lambda v: 255 if v > DIFF_THRESHOLD else 0)
        found = diff.getbbox()
        if found is None:
            continue
        box = found if box is None else (min(box[0], found[0]), min(box[1], found[1]), max(box[2], found[2]), max(box[3], found[3]))
    if box is None or (box[2] - box[0]) * (box[3] - box[1]) < MIN_DIFF_AREA:
        return None
    return (box[0] + region[0], box[1] + region[1], box[2] + region[0], box[3] + region[1])


def flat_row(images, y, x0, x1):
    """Whether row [y] is one colour from [x0] to [x1] in every frame."""
    for image in images:
        for low, high in image.crop((x0, y, x1, y + 1)).getextrema():
            if high - low > FLAT:
                return False
    return True


def snapped(box, images, floor, ceiling):
    """[box] with its top and bottom moved out to the nearest flat rows, within [floor] and [ceiling]."""
    x0, y0, x1, y1 = box
    width = images[0].width
    for y in range(y0, max(floor, y0 - SNAP_REACH) - 1, -1):
        if flat_row(images, y, 0, width):
            y0 = max(floor, y - SNAP_KEEP)
            break
    for y in range(y1, min(ceiling, y1 + SNAP_REACH)):
        if flat_row(images, y, 0, width):
            y1 = min(ceiling, y + SNAP_KEEP)
            break
    return (x0, y0, x1, y1)


def widened(box, size):
    """[box] with a side margin, no narrower than a picture can be read at, and full width past half a screen."""
    w, h = size
    x0, y0, x1, y1 = box
    x0, x1 = x0 - DIFF_MARGIN, x1 + DIFF_MARGIN
    if x1 - x0 < MIN_CUT[0]:
        grow = (MIN_CUT[0] - (x1 - x0)) // 2
        x0, x1 = x0 - grow, x1 + grow
    if y1 - y0 < MIN_CUT[1]:
        grow = (MIN_CUT[1] - (y1 - y0)) // 2
        y0, y1 = y0 - grow, y1 + grow
    if x1 - x0 > w * FULL_WIDTH_ABOVE:
        x0, x1 = 0, w
    return (max(0, x0), max(0, y0), min(w, x1), min(h, y1))


def keyboard_cut(metas, images):
    """The keyboard from its first drawn row down: the flat band over it is left out."""
    w, h = images[0].size
    top = keyboard_top(metas)
    y = top
    while y < h - 1 and flat_row(images, y, 0, w):
        y += 1
    return (0, max(top, y - TOP_KEEP), w, h)


def cuts(metas, images):
    """
    The boxes every frame of a visual is cut to, stacked top to bottom into one
    picture, so its frames line up. One box as a rule; two for a change that
    lands in the text, the line in the field set right over the keyboard's part.
    None when the frames show no difference.
    """
    crop = metas[0]["crop"]
    w, h = images[0].size
    if crop == "screen":
        return [(0, 0, w, h)]
    if crop == "keyboard" or len(images) < 2:
        return [keyboard_cut(metas, images)]
    top = keyboard_top(metas)
    keys = diff_box(images, (0, top, w, h))
    text = diff_box(images, (0, 0, w, top)) if top > 0 else None
    if keys is not None:
        keys = widened(snapped(keys, images, top, h), (w, h))
    if crop == "text" or keys is None:
        if text is None:
            return None if keys is None else [keys]
        field = snapped(text, images, 0, top)
        field = (0, max(0, field[1] - DIFF_MARGIN), w, min(top, field[3] + DIFF_MARGIN))
        # The words changed and the keys did not: the strip the words came from still says where they were typed.
        below = (0, keys[1], w, keys[3]) if keys is not None else (0, keyboard_cut(metas, images)[1], w, min(h, keyboard_cut(metas, images)[1] + 2 * MIN_CUT[1]))
        return [field, below]
    return [keys]


def cut(image, boxes):
    """[image] cut to [boxes], one above the other."""
    if len(boxes) == 1:
        return image.crop(boxes[0])
    parts = [image.crop(box) for box in boxes]
    out = Image.new("RGB", (max(p.width for p in parts), sum(p.height for p in parts)))
    y = 0
    for part in parts:
        out.paste(part, (0, y))
        y += part.height
    return out


def scaled(image, limit):
    if image.width <= limit:
        return image
    return image.resize((limit, round(image.height * limit / image.width)), Image.LANCZOS)


def encode_still(image, work):
    png = os.path.join(work, "still.png")
    webp = os.path.join(work, "still.webp")
    image.save(png)
    run("cwebp", "-quiet", "-q", str(STILL_QUALITY), "-m", "6", "-sharp_yuv", png, "-o", webp)
    return webp


def encode_clip(images, durations, work):
    """An animated WebP. Runs of identical frames become one frame held longer."""
    kept = []
    for image, ms in zip(images, durations):
        if kept and ImageChops.difference(kept[-1][0], image).getbbox() is None:
            kept[-1][1] += ms
        else:
            kept.append([image, ms])
    kept[-1][1] += CLIP_REST_MS
    args = ["img2webp", "-loop", "0", "-lossy", "-q", str(CLIP_QUALITY), "-m", "6", "-kmin", "9", "-kmax", "40"]
    for n, (image, ms) in enumerate(kept):
        png = os.path.join(work, f"clip{n:04d}.png")
        image.save(png)
        args += ["-d", str(max(20, ms)), png]
    webp = os.path.join(work, "clip.webp")
    run(*args, "-o", webp)
    return webp


def store(webp, out):
    digest = sha256(webp)
    target = os.path.join(out, "help", "files", digest[:2], f"{digest}.webp")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    if not os.path.exists(target):
        shutil.copyfile(webp, target)
    return {"sha": digest, "bytes": os.path.getsize(target)}


def build_visual(key, out, work):
    """One visual in both looks; None with a reason when it cannot be built."""
    looks = {}
    shape = None
    for theme in THEMES:
        metas = frames_of(key, theme)
        if not metas:
            continue
        kind = metas[0]["kind"]
        if kind == "clip":
            meta = metas[0]
            names = sorted(n for n in os.listdir(meta["dir"]) if re.fullmatch(rf"{meta['index']}\.\d+\.png", n))
            if not names or len(names) != len(meta["durations"]):
                return None, "the clip's frames and durations do not match"
            images = [Image.open(os.path.join(meta["dir"], n)).convert("RGB") for n in names]
            boxes = [keyboard_cut([meta], images)] if meta["crop"] != "screen" else [(0, 0) + images[0].size]
            images = [scaled(cut(i, boxes), CLIP_WIDTH) for i in images]
            files = [store(encode_clip(images, meta["durations"], work), out)]
            size = [images[0].size]
        else:
            images = [Image.open(os.path.join(m["dir"], f"{m['index']}.png")).convert("RGB") for m in metas]
            boxes = cuts(metas, images)
            if boxes is None:
                return None, f"the {theme} frames are identical: nothing to show"
            images = [scaled(cut(i, boxes), MAX_WIDTH) for i in images]
            files = [store(encode_still(i, work), out) for i in images]
            size = [i.size for i in images]
        looks[theme] = files
        this = (kind, [m["label"] for m in metas], size)
        # Both looks are one layout: the same cut, so the dialog needs one size.
        if shape is None:
            shape = this
        elif shape[0] != this[0] or shape[1] != this[1]:
            return None, "light and dark disagree about the frames"
    if not looks:
        return None, "not rendered"
    kind, labels, sizes = shape
    frames = []
    for n, label in enumerate(labels):
        frames.append({
            "label": label,
            "width": sizes[n][0],
            "height": sizes[n][1],
            **{theme: looks[theme][n] for theme in looks},
        })
    return {"kind": kind, "frames": frames}, None


def take(key, light, dark, out, work):
    """A phone recording as a clip: frames out of the video, then the same encoder."""
    looks = {}
    size = None
    for theme, video in (("light", light), ("dark", dark)):
        if not video:
            continue
        frames_dir = os.path.join(work, theme)
        os.makedirs(frames_dir)
        run("ffmpeg", "-loglevel", "error", "-i", video, "-vf", f"fps=12,scale={CLIP_WIDTH}:-2", os.path.join(frames_dir, "%04d.png"))
        names = sorted(os.listdir(frames_dir))
        if not names:
            sys.exit(f"{video}: no frames")
        images = [Image.open(os.path.join(frames_dir, n)).convert("RGB") for n in names]
        looks[theme] = [store(encode_clip(images, [83] * len(images), work), out)]
        size = images[0].size
    return {"kind": "clip", "frames": [{"label": None, "width": size[0], "height": size[1], **{t: looks[t][0] for t in looks}}]}


def write_pack(theme, built, out):
    """Every picture of one look in a zip whose bytes depend only on the pictures."""
    names = sorted({f[theme]["sha"] for v in built.values() for f in v["frames"] if theme in f})
    if not names:
        return None
    packs = os.path.join(out, "help", "packs")
    os.makedirs(packs, exist_ok=True)
    staged = os.path.join(packs, f".{theme}.zip")
    with zipfile.ZipFile(staged, "w", zipfile.ZIP_STORED) as archive:
        for name in names:
            source = os.path.join(out, "help", "files", name[:2], f"{name}.webp")
            if not os.path.exists(source):
                sys.exit(f"{source} is in built.json but not on disk; render and build that visual again, or --forget it")
            info = zipfile.ZipInfo(f"{name}.webp", date_time=(1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o644 << 16
            with open(source, "rb") as f:
                archive.writestr(info, f.read())
    digest = sha256(staged)
    final = os.path.join(packs, f"wmkb-help-{theme}-{digest[:12]}.zip")
    os.replace(staged, final)
    # One pack per look: an older one is only ever asked for by an older app,
    # which falls back to single files.
    for name in os.listdir(packs):
        if name.startswith(f"wmkb-help-{theme}-") and os.path.join(packs, name) != final:
            os.remove(os.path.join(packs, name))
    return {"sha": digest, "bytes": os.path.getsize(final)}


def write_index(built, packs):
    lines = ["# Written by tools/helpvisuals/build.py. Do not edit.", "wmhelp\t1"]
    for theme in THEMES:
        if packs.get(theme):
            lines.append(f"pack\t{theme}\t{packs[theme]['sha']}\t{packs[theme]['bytes']}")
    for key in sorted(built):
        visual = built[key]
        lines.append(f"visual\t{key}\t{visual['kind']}")
        for frame in visual["frames"]:
            cells = ["frame", frame["label"] or "-", str(frame["width"]), str(frame["height"])]
            for theme in THEMES:
                file = frame.get(theme)
                cells += [file["sha"], str(file["bytes"])] if file else ["-", "0"]
            lines.append("\t".join(cells))
    os.makedirs(os.path.dirname(INDEX), exist_ok=True)
    with open(INDEX, "w") as f:
        f.write("\n".join(lines) + "\n")


def contact_sheet(built, out, sheet):
    """Every picture of every visual on one page per visual, for a person to look over."""
    os.makedirs(sheet, exist_ok=True)
    for key, visual in built.items():
        tiles = []
        for theme in THEMES:
            for frame in visual["frames"]:
                if theme in frame:
                    name = frame[theme]["sha"]
                    tiles.append(Image.open(os.path.join(out, "help", "files", name[:2], f"{name}.webp")).convert("RGB"))
        if not tiles:
            continue
        gap = 12
        width = max(t.width for t in tiles)
        page = Image.new("RGB", (width, sum(t.height for t in tiles) + gap * (len(tiles) - 1)), (128, 128, 128))
        y = 0
        for tile in tiles:
            page.paste(tile, (0, y))
            y += tile.height + gap
        page.save(os.path.join(sheet, f"{key}.png"))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("only", nargs="*", help="keys or regexes; default every rendered visual")
    parser.add_argument("--out", default=DEFAULT_OUT, help="the data repository checkout")
    parser.add_argument("--sheet", help="also write one PNG per visual here, to look over")
    parser.add_argument("--forget", action="append", default=[], help="take a visual out of the index")
    parser.add_argument("--take", nargs=2, metavar=("KEY", "VIDEO"), help="file a phone recording as a clip")
    parser.add_argument("--dark", help="with --take: the same recording in the dark look")
    args = parser.parse_args()
    if not os.path.isdir(args.out):
        sys.exit(f"{args.out} is not a directory; pass --out <data repository checkout>")

    built = {}
    if os.path.exists(BUILT):
        with open(BUILT) as f:
            built = json.load(f)
    for key in args.forget:
        built.pop(key, None)

    dropped = []
    with tempfile.TemporaryDirectory() as work:
        if args.take:
            built[args.take[0]] = take(args.take[0], args.take[1], args.dark, args.out, work)
        elif os.path.isdir(RENDERS):
            patterns = [re.compile(p) for p in args.only]
            for key in sorted(os.listdir(RENDERS)):
                if patterns and not any(p.search(key) for p in patterns):
                    continue
                visual, why = build_visual(key, args.out, work)
                if visual is None:
                    dropped.append((key, why))
                    built.pop(key, None)
                else:
                    built[key] = visual

    packs = {theme: write_pack(theme, built, args.out) for theme in THEMES}
    write_index(built, packs)
    with open(BUILT, "w") as f:
        json.dump(built, f, indent=1, sort_keys=True)
        f.write("\n")
    if args.sheet:
        contact_sheet(built, args.out, args.sheet)

    for key, why in dropped:
        print(f"no picture  {key}: {why}")
    kinds = {}
    for visual in built.values():
        kinds[visual["kind"]] = kinds.get(visual["kind"], 0) + 1
    print(f"{len(built)} visuals ({', '.join(f'{n} {k}' for k, n in sorted(kinds.items())) or 'none'})")
    for theme in THEMES:
        if packs[theme]:
            print(f"{theme} pack: {packs[theme]['bytes'] / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
