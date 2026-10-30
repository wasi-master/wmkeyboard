#!/usr/bin/env python3
"""Draws keyboards from random themes, with the answers beside them.

    fuzz.py <out_dir> [--count 200] [--seed 1]

Each case is <name>.png and <name>.truth.json. Colours, gradients, photos, every
key outline ThemeRendering.kt has, borders, shadows, translucent keys, per-key
colours, bars, hints, number rows, phones, tablets, landscape, crops and JPEG.
Outlines use the proportions of ThemeRendering.kt.
"""
import argparse
import json
import math
import os
import random

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

SCREENS = [
    ("phone", 1080, 2400, 360), ("phone", 1080, 2340, 392), ("phone", 720, 1600, 360), ("phone", 1440, 3200, 412),
    ("phone", 1170, 2532, 390), ("phone", 828, 1792, 414), ("tablet", 1600, 2560, 800), ("tablet", 2048, 2732, 1024),
    ("landscape", 2400, 1080, 800), ("landscape", 1920, 1080, 720),
]
FONTS = [
    "/System/Library/Fonts/Helvetica.ttc", "/System/Library/Fonts/HelveticaNeue.ttc", "/System/Library/Fonts/Avenir.ttc",
    "/System/Library/Fonts/Supplemental/Arial.ttf", "/System/Library/Fonts/Supplemental/Georgia.ttf",
    "/System/Library/Fonts/Supplemental/Courier New.ttf", "/System/Library/Fonts/Supplemental/Verdana.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf", "/System/Library/Fonts/Supplemental/Verdana Bold.ttf",
]
FONTS = [f for f in FONTS if os.path.exists(f)]
SHAPES = ["ROUNDED", "ROUNDED", "ROUNDED", "ROUNDED", "SHARP", "PILL", "CUT", "SQUIRCLE", "ARCH", "LEAF", "SLANT", "HEXAGON", "SCALLOP", "TICKET", "CIRCLE", "NONE"]


def outline(shape, w, h, r):
    m = min(w, h)

    def arc(cx, cy, rx, ry, a0, a1, n=24):
        return [(cx + rx * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]

    def rounded(a, b, c, d):
        return arc(a, a, a, a, 180, 270) + arc(w - b, b, b, b, 270, 360) + arc(w - c, h - c, c, c, 0, 90) + arc(d, h - d, d, d, 90, 180)

    if shape == "ROUNDED":
        return rounded(r, r, r, r)
    if shape == "SHARP":
        return [(0, 0), (w, 0), (w, h), (0, h)]
    if shape == "PILL":
        return rounded(m / 2, m / 2, m / 2, m / 2)
    if shape == "CUT":
        return [(r, 0), (w - r, 0), (w, r), (w, h - r), (w - r, h), (r, h), (0, h - r), (0, r)]
    if shape == "SQUIRCLE":
        pts = []
        for i in range(64):
            t = 2 * math.pi * i / 64
            c, s = math.cos(t), math.sin(t)
            pts.append((w / 2 + w / 2 * math.copysign(abs(c) ** 0.5, c), h / 2 + h / 2 * math.copysign(abs(s) ** 0.5, s)))
        return pts
    if shape == "ARCH":
        return rounded(m / 2, m / 2, m * 0.12, m * 0.12)
    if shape == "LEAF":
        return rounded(m / 2, m * 0.08, m / 2, m * 0.08)
    if shape == "SLANT":
        lean = min(h * 0.22, w * 0.3)
        return [(lean / 2, 0), (w + lean / 2, 0), (w - lean / 2, h), (-lean / 2, h)]
    if shape == "HEXAGON":
        cut = min(w * 0.16, h * 0.5)
        return [(cut, 0), (w - cut, 0), (w, h / 2), (w - cut, h), (cut, h), (0, h / 2)]
    if shape == "SCALLOP":
        d = m / 7
        across, down = max(2, round((w - 2 * d) / (2 * d))), max(1, round((h - 2 * d) / (2 * d)))
        sx, sy = (w - 2 * d) / across, (h - 2 * d) / down
        pts = []
        for i in range(across):
            pts += arc(d + (i + 0.5) * sx, d, sx / 2, d, 180, 360, 10)
        for i in range(down):
            pts += arc(w - d, d + (i + 0.5) * sy, d, sy / 2, 270, 450, 10)
        for i in reversed(range(across)):
            pts += arc(d + (i + 0.5) * sx, h - d, sx / 2, d, 0, 180, 10)
        for i in reversed(range(down)):
            pts += arc(d, d + (i + 0.5) * sy, d, sy / 2, 90, 270, 10)
        return pts
    if shape == "TICKET":
        q = m * 0.25
        return arc(0, 0, q, q, 90, 0, 12) + arc(w, 0, q, q, 180, 90, 12) + arc(w, h, q, q, 270, 180, 12) + arc(0, h, q, q, 360, 270, 12)
    if shape == "CIRCLE":
        if max(w, h) > m * 1.6:
            return rounded(m / 2, m / 2, m / 2, m / 2)
        return arc(w / 2, h / 2, m / 2, m / 2, 0, 360, 48)
    raise ValueError(shape)


def shape_mask(shape, w, h, r, pad=0):
    """A coverage mask, drawn at 4x for its edges."""
    s = 4
    size = (int(w * s + 2 * pad * s), int(h * s + 2 * pad * s))
    image = Image.new("L", size, 0)
    points = [((x + pad) * s, (y + pad) * s) for x, y in outline(shape, w, h, r)]
    ImageDraw.Draw(image).polygon(points, fill=255)
    return np.asarray(image.resize((int(w + 2 * pad), int(h + 2 * pad)), Image.BOX), dtype=np.float32) / 255


def over(canvas, x, y, mask, colour, alpha=1.0):
    h, w = mask.shape
    x0, y0, x1, y1 = max(0, x), max(0, y), min(canvas.shape[1], x + w), min(canvas.shape[0], y + h)
    if x1 <= x0 or y1 <= y0:
        return
    m = mask[y0 - y:y1 - y, x0 - x:x1 - x, None] * alpha
    canvas[y0:y1, x0:x1] = canvas[y0:y1, x0:x1] * (1 - m) + np.array(colour, dtype=np.float32) * m


def rand_colour(rng, dark=None, sat=None):
    import colorsys

    hue = rng.random()
    s = rng.random() * 0.7 if sat is None else sat
    v = (rng.uniform(0.05, 0.35) if dark else rng.uniform(0.75, 1.0)) if dark is not None else rng.random()
    return [int(c * 255) for c in colorsys.hsv_to_rgb(hue, s, v)]


def photo(rng, w, h):
    """Something photo-like: soft colour fields, then hard-edged shapes on them."""
    small = np.zeros((6, 6, 3), dtype=np.float32)
    for y in range(6):
        for x in range(6):
            small[y, x] = rand_colour(rng)
    image = cv2.resize(small, (w, h), interpolation=cv2.INTER_CUBIC).clip(0, 255)
    for _ in range(rng.randint(10, 60)):
        c = [float(v) for v in rand_colour(rng)]
        x, y = rng.randrange(w), rng.randrange(h)
        size = rng.randint(w // 40, w // 6)
        if rng.random() < 0.5:
            cv2.circle(image, (x, y), size, c, -1, cv2.LINE_AA)
        else:
            cv2.rectangle(image, (x, y), (x + size, y + size * rng.randint(1, 3)), c, -1, cv2.LINE_AA)
    return image


def gradient(kind, stops, angle, w, h):
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    closed = kind == "SWEEP"
    if kind == "LINEAR":
        rad = math.radians(angle)
        dx, dy = math.cos(rad), math.sin(rad)
        half = (abs(dx) * w + abs(dy) * h) / 2
        t = ((xs - w / 2) * dx + (ys - h / 2) * dy) / (2 * half) + 0.5
    elif kind == "RADIAL":
        t = np.hypot(xs - w / 2, ys - h / 2) / (max(w, h) * 0.75)
    else:
        t = ((np.degrees(np.arctan2(ys - h / 2, xs - w / 2)) - angle) % 360) / 360
    t = t.clip(0, 1)
    spans = len(stops) if closed else len(stops) - 1
    at = t * spans
    i = np.minimum(at.astype(int), spans - 1)
    f = (at - i)[..., None]
    arr = np.array(stops, dtype=np.float32)
    j = (i + 1) % len(stops) if closed else i + 1
    return arr[i] * (1 - f) + arr[j] * f


def make_case(rng):
    kind, W, H, width_dp = rng.choice(SCREENS)
    dp = W / width_dp
    dark = rng.random() < 0.6
    board_kind = rng.choices(["SOLID", "LINEAR", "RADIAL", "SWEEP", "IMAGE"], [5, 2, 1, 1, 2])[0]
    board_colour = rand_colour(rng, dark=dark)
    shape = rng.choice(SHAPES)
    key_h_dp = rng.randint(38, 62) if kind == "phone" else rng.randint(40, 70)
    if kind == "landscape":
        key_h_dp = rng.randint(30, 44)
    gap_h_dp = rng.choice([0, 2, 3, 5, 5, 5, 6, 8, 10])
    gap_v_dp = rng.choice([0, 3, 4, 6, 8, 8, 8, 10, 12])
    if shape == "NONE":
        gap_h_dp = gap_v_dp = 0
    radius_dp = rng.randint(0, 18)
    key_colour = rand_colour(rng, dark=dark)
    if rng.random() < 0.5:  # usually keys stand off the board by brightness
        key_colour = [int(min(255, max(0, b + (40 if dark else -30) + rng.randint(-8, 8)))) for b in board_colour]
    key_alpha = 1.0
    if board_kind in ("IMAGE", "LINEAR") and rng.random() < 0.5 and shape != "NONE":
        key_alpha = rng.uniform(0.2, 0.75)
    key_text = rand_colour(rng, dark=not dark)
    modifier = rand_colour(rng, dark=dark) if rng.random() < 0.6 else key_colour
    enter = rand_colour(rng, sat=rng.uniform(0.4, 1.0))
    enter_text = rand_colour(rng, dark=not (sum(enter) > 380))
    border = rng.random() < 0.25 and shape not in ("NONE",)
    border_px = max(1, round(rng.choice([1, 1.5, 2, 3]) * dp)) if border else 0
    border_colour = rand_colour(rng, dark=not dark, sat=rng.uniform(0, 0.8))
    shadow = rng.random() < 0.2 and shape != "NONE" and gap_v_dp >= 4
    shadow_px = max(2, round(rng.uniform(1, 3) * dp)) if shadow else 0
    shadow_colour = [int(c * 0.5) for c in key_colour]
    # Keys of a class that wear their own outline, the way Gboard's enter does.
    enter_shape = rng.choice(["PILL", "CIRCLE", "ROUNDED", "SHARP"]) if rng.random() < 0.3 and shape != "NONE" else None
    modifier_shape = rng.choice(["PILL", "ROUNDED", "SHARP"]) if rng.random() < 0.2 and shape != "NONE" else None
    number_row = rng.random() < 0.5
    hints = rng.random() < 0.3
    hint_colour = [int((a + b) / 2) for a, b in zip(key_colour, key_text)]
    capitals = rng.random() < 0.25
    font_path = rng.choice(FONTS)
    font_scale = rng.uniform(0.75, 1.3)
    bar_own = rng.random() < 0.35
    bar_colour = rand_colour(rng, dark=dark)
    bar_h_dp = rng.choice([0, 40, 44, 48, 56])
    chips = rng.random() < 0.5 and bar_h_dp > 0
    chip_colour = rand_colour(rng, dark=dark)
    icon_colour = rand_colour(rng, dark=not dark, sat=rng.uniform(0, 0.4))
    bottom_dp = rng.choice([0, 8, 16, 24, 32, 48])
    side_dp = rng.choice([0, 0, 0, 4, 8, 16])
    per_key = rng.random() < 0.08 and key_alpha == 1.0 and shape != "NONE"
    full = rng.random() < 0.7
    app_colour = rand_colour(rng, dark=rng.random() < 0.5)
    stops = [rand_colour(rng) for _ in range(rng.choice([2, 2, 3, 4]))]
    angle = rng.choice([0, 45, 90, 135, 180, 225, 270, 315, rng.uniform(0, 360)])

    letters = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
    if capitals:
        letters = [r.upper() for r in letters]
    rows = (["1234567890"] if number_row else []) + letters

    grid_w = W - 2 * side_dp * dp
    pitch = grid_w / 10
    key_h = key_h_dp * dp
    gap_h, gap_v = gap_h_dp * dp, gap_v_dp * dp
    row_pitch = key_h + gap_v
    n_rows = len(rows) + 1
    kb_h = int(n_rows * row_pitch + bar_h_dp * dp + bottom_dp * dp + gap_v)
    if kb_h >= H:
        return None
    top = H - kb_h if full else 0
    canvas_h = H if full else kb_h
    canvas = np.zeros((canvas_h, W, 3), dtype=np.float32)
    canvas[:] = app_colour
    box_h = kb_h
    if board_kind == "SOLID":
        canvas[top:top + box_h] = board_colour
    elif board_kind == "IMAGE":
        canvas[top:top + box_h] = photo(rng, W, box_h)
    else:
        canvas[top:top + box_h] = gradient(board_kind, stops, angle, W, box_h)

    bar_h = int(bar_h_dp * dp)
    if bar_h and bar_own:
        canvas[top:top + bar_h] = bar_colour
    font_px = int(23 * dp * font_scale * min(1.0, key_h_dp / 44))
    font = ImageFont.truetype(font_path, max(8, font_px))
    hint_font = ImageFont.truetype(font_path, max(6, int(font_px * 0.4)))
    icon_font = ImageFont.truetype(font_path, max(8, int(bar_h * 0.45))) if bar_h else None

    label_layer = []
    tool_count = 7
    if bar_h:
        cell = W / tool_count
        chip_w = int(min(cell - 6 * dp, bar_h * 0.85))
        chip_h = int(bar_h * 0.8)
        chip_mask = shape_mask("PILL", chip_w, chip_h, 0)
        for i in range(tool_count):
            cx = int(cell * (i + 0.5))
            if chips:
                over(canvas, cx - chip_w // 2, top + (bar_h - chip_h) // 2, chip_mask, chip_colour)
            label_layer.append((cx, top + bar_h // 2, "&#@*%+="[i], icon_font, icon_colour))

    per_key_colours = {}

    def paint(x, y, w, h, fill, alpha=1.0, shape=shape):
        x, y, w, h = int(round(x)), int(round(y)), int(round(w)), int(round(h))
        if shape == "NONE":
            return
        radius = radius_dp * dp
        if shape == "CUT":
            radius = max(2 * dp, min(14 * dp, radius))
        radius = min(radius, min(w, h) / 2)
        if shadow_px:
            over(canvas, x, y + shadow_px, shape_mask(shape, w, h, radius), shadow_colour)
        if border_px:
            over(canvas, x, y, shape_mask(shape, w, h, radius), border_colour)
            over(canvas, x + border_px, y + border_px, shape_mask(shape, w - 2 * border_px, h - 2 * border_px, max(0, radius - border_px)), fill, alpha)
        else:
            over(canvas, x, y, shape_mask(shape, w, h, radius), fill, alpha)

    y = top + bar_h + gap_v
    x0 = side_dp * dp
    letter_rects = []
    for index, row in enumerate(rows):
        n = len(row)
        offset = x0 + (10 - n) / 2 * pitch
        is_last = index == len(rows) - 1
        h_this = key_h
        for i, ch in enumerate(row):
            x = offset + i * pitch + gap_h / 2
            fill = key_colour
            if per_key and ch.isalpha():
                fill = rand_colour(rng)
                per_key_colours[ch.lower()] = fill
            paint(x, y, pitch - gap_h, h_this, fill, key_alpha)
            letter_rects.append((x, y, pitch - gap_h, h_this))
            label_layer.append((x + (pitch - gap_h) / 2, y + h_this / 2, ch, font, key_text))
            if hints and not (ch.isdigit()):
                label_layer.append((x + (pitch - gap_h) * 0.8, y + h_this * 0.2, "1234567890"[i % 10], hint_font, hint_colour))
        if is_last:
            paint(x0 + gap_h / 2, y, 1.5 * pitch - gap_h, key_h, modifier, shape=modifier_shape or shape)
            paint(x0 + 8.5 * pitch + gap_h / 2, y, 1.5 * pitch - gap_h, key_h, modifier, shape=modifier_shape or shape)
            label_layer.append((x0 + 0.75 * pitch, y + key_h / 2, "^", font, key_text))
            label_layer.append((x0 + 9.25 * pitch, y + key_h / 2, "<", font, key_text))
        y += row_pitch
    # Bottom row: mode, comma, space, period, enter.
    bx = x0
    for label, units, fill in (("?", 1.5, modifier), (",", 1, key_colour), ("", 5, key_colour), (".", 1, key_colour), (">", 1.5, enter)):
        own = enter_shape if fill is enter else (modifier_shape if fill is modifier else None)
        paint(bx + gap_h / 2, y, units * pitch - gap_h, key_h, fill, key_alpha if fill is key_colour else 1.0, shape=own or shape)
        if label:
            label_layer.append((bx + units * pitch / 2, y + key_h / 2, label, font, enter_text if fill is enter else key_text))
        bx += units * pitch

    image = Image.fromarray(canvas.clip(0, 255).astype(np.uint8))
    draw = ImageDraw.Draw(image)
    for x, yy, text, f, colour in label_layer:
        draw.text((x, yy), text, font=f, fill=tuple(int(c) for c in colour), anchor="mm")

    truth = {
        "screen": kind, "W": W, "H": image.height, "widthDp": width_dp, "full": full,
        "board": board_kind, "boardColour": board_colour, "stops": stops, "angle": angle,
        "shape": shape, "radiusDp": radius_dp, "keyColour": key_colour, "keyAlpha": key_alpha, "keyText": key_text,
        "modifier": modifier, "enter": enter, "enterText": enter_text,
        "border": border_px / dp, "borderColour": border_colour, "shadow": shadow_px / dp, "shadowColour": shadow_colour,
        "numberRow": number_row, "hints": hints, "hintColour": hint_colour,
        "barHeightDp": bar_h_dp, "barOwn": bar_own and bar_h_dp > 0, "barColour": bar_colour,
        "chips": chips, "chipColour": chip_colour, "iconColour": icon_colour,
        "keyHeightDp": key_h_dp, "gapHDp": gap_h_dp, "gapVDp": gap_v_dp, "bottomDp": bottom_dp, "sideDp": side_dp,
        "perKey": per_key_colours, "enterShape": enter_shape, "modifierShape": modifier_shape,
    }
    return image, truth


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("--count", type=int, default=200)
    parser.add_argument("--seed", type=int, default=1)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    rng = random.Random(args.seed)
    made = 0
    while made < args.count:
        case = make_case(rng)
        if case is None:
            continue
        image, truth = case
        name = f"f{made:04d}"
        quality = rng.choice([None, None, None, 92, 75, 55])
        scale = rng.choice([1, 1, 1, 0.75, 0.5])
        if scale != 1:
            image = image.resize((int(image.width * scale), int(image.height * scale)), Image.LANCZOS)
        truth["jpeg"] = quality
        truth["scale"] = scale
        if quality:
            image.save(os.path.join(args.out, name + ".jpg"), quality=quality)
        else:
            image.save(os.path.join(args.out, name + ".png"))
        with open(os.path.join(args.out, name + ".truth.json"), "w") as f:
            json.dump(truth, f)
        made += 1


if __name__ == "__main__":
    main()
