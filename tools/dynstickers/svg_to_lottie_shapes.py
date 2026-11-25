#!/usr/bin/env python3
"""Builds the art library dynamic stickers draw from.

    python3 tools/dynstickers/svg_to_lottie_shapes.py

writes app/src/main/assets/dynstickers/art/art.json:

    {"<id>": {"w": W, "h": H, "x0": X, "y0": Y, "shapes": [<Lottie shape items, topmost first>]}, ...}

which DynStickerStore.loadArt reads and SceneToLottie places as an ART
element (scaled to fit the element's box, centred on it).

Two sources, converted the same way:

* Noto Emoji's colour SVGs (googlefonts/noto-emoji, `2D/svg/`, Apache-2.0),
  fetched at the pinned commit below into `.cache/` (git-ignored; nothing of
  theirs is committed raw). Only the drawings listed in NOTO are taken.
* Our own drawings, written as SVG in custom_art.py.

What the conversion keeps: every path, rect, circle, ellipse, line, polygon
and polyline, with group and element transforms baked into the points;
`<use>`; fills, strokes, opacity, fill rules; linear gradients exactly (an
affine map of a linear gradient is a linear gradient, so the end points are
solved for); radial gradients exactly when their transform is a rotation and
a scale (the paths are drawn in the gradient's own space under a group
transform), else as the nearest circle; clip paths, by intersecting the
geometry with shapely (the clipped part comes out as polygons). Dropped,
with a warning: masks, filters, blend modes (multiply is drawn normally) and
dashes.

Each drawing is cropped to what it draws, plus a small margin, so its w×h is
its real aspect and an ART element's box is filled by it.

Needs shapely (`pip install shapely`) for the clipped drawings.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "dynstickers", "art", "art.json")
CACHE = os.path.join(HERE, ".cache")

NOTO_COMMIT = "99701e07d3d1fe1bdbcdb14964547f0fd592966f"  # 2026-10-26
NOTO_URL = "https://raw.githubusercontent.com/googlefonts/noto-emoji/{commit}/2D/svg/emoji_u{name}.svg"

# Art id -> Noto file stem (code points in hex, `_`-joined, no VS16).
NOTO: dict[str, str] = {
    # party
    "cake": "1f382", "cupcake": "1f9c1", "shortcake": "1f370", "candle": "1f56f", "balloon": "1f388",
    "party_popper": "1f389", "confetti_ball": "1f38a", "gift": "1f381", "ribbon": "1f380",
    "party_face": "1f973", "champagne": "1f37e", "clinking_glasses": "1f942", "beers": "1f37b",
    "firecracker": "1f9e8", "bell": "1f514", "lollipop": "1f36d", "chocolate": "1f36b",
    # hearts
    "heart_red": "2764", "heart_orange": "1f9e1", "heart_yellow": "1f49b", "heart_green": "1f49a",
    "heart_blue": "1f499", "heart_purple": "1f49c", "heart_pink": "1fa77", "heart_white": "1f90d",
    "heart_black": "1f5a4", "heart_sparkling": "1f496", "heart_growing": "1f497", "hearts_two": "1f495",
    "heart_arrow": "1f498", "heart_gift": "1f49d", "heart_broken": "1f494", "hearts_revolving": "1f49e",
    "heart_beating": "1f493", "heart_exclamation": "2763", "kiss_mark": "1f48b", "heart_hands": "1faf6",
    "ring": "1f48d",
    # sky and weather
    "star": "2b50", "star_glowing": "1f31f", "sparkles": "2728", "dizzy": "1f4ab", "sun": "2600",
    "sun_face": "1f31e", "cloud": "2601", "cloud_rain": "1f327", "sun_cloud": "26c5", "rainbow": "1f308",
    "moon": "1f319", "moon_face": "1f31b", "zzz": "1f4a4", "umbrella": "2602", "umbrella_rain": "2614",
    "snowflake": "2744", "lightning": "26a1", "droplet": "1f4a7", "planet": "1fa90", "globe": "1f30d",
    "fire": "1f525", "collision": "1f4a5", "dash": "1f4a8", "sweat_drops": "1f4a6", "anger": "1f4a2",
    # food and drink
    "coffee": "2615", "tea": "1f375", "cooking": "1f373", "croissant": "1f950", "pancakes": "1f95e",
    "pizza": "1f355", "burger": "1f354", "donut": "1f369", "ice_cream": "1f366", "popcorn": "1f37f",
    "cookie": "1f36a", "fries": "1f35f", "noodles": "1f35c", "sushi": "1f363", "taco": "1f32e",
    "salad": "1f957", "apple": "1f34e", "strawberry": "1f353", "watermelon": "1f349", "banana": "1f34c",
    "avocado": "1f951", "honey": "1f36f", "bubble_tea": "1f9cb", "cup_straw": "1f964", "wine": "1f377",
    "cocktail": "1f378", "tropical_drink": "1f379", "plate": "1f37d",
    # flowers
    "rose": "1f339", "tulip": "1f337", "sunflower": "1f33b", "bouquet": "1f490", "blossom": "1f338",
    "daisy": "1f33c", "hibiscus": "1f33a", "clover": "1f340", "palm": "1f334",
    # hands
    "thumbs_up": "1f44d", "thumbs_down": "1f44e", "clap": "1f44f", "wave": "1f44b", "pray": "1f64f",
    "ok_hand": "1f44c", "muscle": "1f4aa", "crossed_fingers": "1f91e", "raised_hands": "1f64c",
    "victory": "270c", "handshake": "1f91d", "fist": "1f44a", "rock_on": "1f918", "call_me": "1f919",
    "point_left": "1f448", "point_right": "1f449", "point_up": "1f446", "point_down": "1f447",
    "love_you_hand": "1f91f",
    # faces
    "face_grin": "1f600", "face_smile": "1f60a", "face_joy": "1f602", "face_rofl": "1f923",
    "face_sweat_smile": "1f605", "face_sob": "1f62d", "face_cry": "1f622", "face_heart_eyes": "1f60d",
    "face_hearts": "1f970", "face_kiss": "1f618", "face_rage": "1f621", "face_angry": "1f620",
    "face_cool": "1f60e", "face_thinking": "1f914", "face_sleeping": "1f634", "face_scream": "1f631",
    "face_open_mouth": "1f62e", "face_wink": "1f609", "face_hug": "1f917", "face_eye_roll": "1f644",
    "face_grimace": "1f62c", "face_pleading": "1f97a", "face_yum": "1f60b", "face_exploding": "1f92f",
    "face_angel": "1f607", "face_star_struck": "1f929", "face_neutral": "1f610", "face_flushed": "1f633",
    "face_drool": "1f924", "face_sick": "1f912", "face_huff": "1f624", "face_weary": "1f629",
    "face_expressionless": "1f611", "face_melting": "1fae0", "face_yawn": "1f971", "face_unamused": "1f612",
    "face_tongue_wink": "1f61c", "face_zany": "1f92a", "face_upside_down": "1f643", "face_smile_tear": "1f972",
    "face_sad_relieved": "1f625", "face_anxious": "1f630", "face_raised_brow": "1f928", "face_salute": "1fae1",
    "face_peek": "1fae3", "face_hand_mouth": "1f92d", "face_relieved": "1f60c", "face_smirk": "1f60f",
    "face_no_mouth": "1f636", "face_sleepy": "1f62a", "face_sneeze": "1f927", "face_bandage": "1f915",
    "face_nauseated": "1f922", "face_hot": "1f975", "face_cold": "1f976", "clown": "1f921", "alien": "1f47d",
    "robot": "1f916", "ghost": "1f47b", "skull": "1f480", "eyes": "1f440", "poop": "1f4a9",
    "cat_heart_eyes": "1f63b", "cat_joy": "1f639", "cat_cry": "1f63f", "cat_smile": "1f63a",
    # people
    "shrug": "1f937", "facepalm": "1f926", "raising_hand": "1f64b", "dancer": "1f483", "man_dancing": "1f57a",
    "runner": "1f3c3", "walker": "1f6b6", "baby": "1f476",
    # animals
    "cat": "1f431", "dog": "1f436", "bear": "1f43b", "panda": "1f43c", "unicorn": "1f984", "chick": "1f425",
    "butterfly": "1f98b", "bunny": "1f430", "frog": "1f438", "teddy_bear": "1f9f8",
    # things
    "crown": "1f451", "hundred": "1f4af", "trophy": "1f3c6", "medal": "1f947", "sports_medal": "1f3c5",
    "music_notes": "1f3b6", "music_note": "1f3b5", "speech_balloon": "1f4ac", "thought_balloon": "1f4ad",
    "check_mark": "2705", "cross_mark": "274c", "question": "2753", "exclamation": "2757", "warning": "26a0",
    "light_bulb": "1f4a1", "rocket": "1f680", "calendar": "1f4c5", "briefcase": "1f4bc", "graduation_cap": "1f393",
    "alarm_clock": "23f0", "phone": "1f4f1", "telephone": "1f4de", "car": "1f697", "house": "1f3e0",
    "airplane": "2708", "beach": "1f3d6", "pin": "1f4cd", "camera": "1f4f8", "books": "1f4da", "bed": "1f6cc",
    "newspaper": "1f4f0", "scroll": "1f4dc", "pen": "1f58a", "basketball": "1f3c0", "soccer": "26bd",
    "game": "1f3ae", "headphones": "1f3a7", "laptop": "1f4bb", "key": "1f511", "lock": "1f512",
    "unlock": "1f513", "money_bag": "1f4b0", "money": "1f4b5", "cart": "1f6d2", "package": "1f4e6",
    "baby_bottle": "1f37c", "megaphone": "1f4e3", "love_letter": "1f48c", "hourglass": "23f3",
    "pushpin": "1f4cc",
}

MARGIN = 2.0
DECIMALS = 2

warnings: list[str] = []


def warn(msg: str) -> None:
    warnings.append(msg)


# ---- matrices ----------------------------------------------------------------
# (a, b, c, d, e, f): x' = a x + c y + e, y' = b x + d y + f, as in SVG.

IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def mul(m, n):
    """m · n: n applies first."""
    a, b, c, d, e, f = m
    A, B, C, D, E, F = n
    return (a * A + c * B, b * A + d * B, a * C + c * D, b * C + d * D, a * E + c * F + e, b * E + d * F + f)


def apply(m, x, y):
    return (m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5])


def apply_vec(m, x, y):
    return (m[0] * x + m[2] * y, m[1] * x + m[3] * y)


def det(m):
    return m[0] * m[3] - m[1] * m[2]


def inverse(m):
    a, b, c, d, e, f = m
    k = det(m)
    if abs(k) < 1e-12:
        return None
    ia, ib, ic, id_ = d / k, -b / k, -c / k, a / k
    return (ia, ib, ic, id_, -(ia * e + ic * f), -(ib * e + id_ * f))


def translate(x, y):
    return (1.0, 0.0, 0.0, 1.0, x, y)


def scale(x, y):
    return (x, 0.0, 0.0, y, 0.0, 0.0)


TRANSFORM = re.compile(r"(matrix|translate|scale|rotate|skewX|skewY)\s*\(([^)]*)\)")
NUMBER = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def parse_transform(text: str | None):
    m = IDENTITY
    if not text:
        return m
    for name, args in TRANSFORM.findall(text):
        v = [float(n) for n in NUMBER.findall(args)]
        if name == "matrix" and len(v) == 6:
            t = tuple(v)
        elif name == "translate":
            t = translate(v[0], v[1] if len(v) > 1 else 0.0)
        elif name == "scale":
            t = scale(v[0], v[1] if len(v) > 1 else v[0])
        elif name == "rotate":
            r = math.radians(v[0])
            t = (math.cos(r), math.sin(r), -math.sin(r), math.cos(r), 0.0, 0.0)
            if len(v) == 3:
                t = mul(translate(v[1], v[2]), mul(t, translate(-v[1], -v[2])))
        elif name == "skewX":
            t = (1.0, 0.0, math.tan(math.radians(v[0])), 1.0, 0.0, 0.0)
        elif name == "skewY":
            t = (1.0, math.tan(math.radians(v[0])), 0.0, 1.0, 0.0, 0.0)
        else:
            continue
        m = mul(m, t)
    return m


# ---- geometry ----------------------------------------------------------------
# A subpath: {"start": (x, y), "segs": [(c1, c2, p), ...], "closed": bool},
# every segment a cubic; a straight one has c1 at its start and c2 at its end.


class Scanner:
    def __init__(self, text: str):
        self.s = text
        self.i = 0

    def skip(self):
        while self.i < len(self.s) and self.s[self.i] in " \t\r\n,":
            self.i += 1

    def done(self):
        self.skip()
        return self.i >= len(self.s)

    def peek_command(self):
        self.skip()
        if self.i < len(self.s) and self.s[self.i].isalpha() and self.s[self.i] not in "eE":
            return self.s[self.i]
        return None

    def command(self):
        c = self.peek_command()
        if c:
            self.i += 1
        return c

    def number(self):
        self.skip()
        m = NUMBER.match(self.s, self.i)
        if not m:
            raise ValueError(f"number expected at {self.i}: {self.s[self.i:self.i + 20]!r}")
        self.i = m.end()
        return float(m.group(0))

    def flag(self):
        self.skip()
        ch = self.s[self.i]
        if ch not in "01":
            raise ValueError(f"flag expected at {self.i}")
        self.i += 1
        return ch == "1"

    def has_number(self):
        self.skip()
        return self.i < len(self.s) and NUMBER.match(self.s, self.i) is not None


def line_seg(p0, p1):
    return (p0, p1, p1)


def quad_to_cubic(p0, q, p1):
    c1 = (p0[0] + 2 / 3 * (q[0] - p0[0]), p0[1] + 2 / 3 * (q[1] - p0[1]))
    c2 = (p1[0] + 2 / 3 * (q[0] - p1[0]), p1[1] + 2 / 3 * (q[1] - p1[1]))
    return (c1, c2, p1)


def arc_to_cubics(p0, rx, ry, phi_deg, large, sweep, p1):
    x1, y1 = p0
    x2, y2 = p1
    if (x1, y1) == (x2, y2):
        return []
    if rx == 0 or ry == 0:
        return [line_seg(p0, p1)]
    rx, ry = abs(rx), abs(ry)
    phi = math.radians(phi_deg)
    cp, sp = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p = cp * dx + sp * dy
    y1p = -sp * dx + cp * dy
    lam = x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2
    if lam > 1:
        s = math.sqrt(lam)
        rx *= s
        ry *= s
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        co = -co
    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    cx = cp * cxp - sp * cyp + (x1 + x2) / 2
    cy = sp * cxp + cp * cyp + (y1 + y2) / 2

    def ang(ux, uy, vx, vy):
        return math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)

    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    n = max(1, math.ceil(abs(dt) / (math.pi / 2) - 1e-9))
    d = dt / n
    k = 4 / 3 * math.tan(d / 4)

    def to(ux, uy):
        return (cx + rx * ux * cp - ry * uy * sp, cy + rx * ux * sp + ry * uy * cp)

    out = []
    for i in range(n):
        a1 = t1 + i * d
        a2 = a1 + d
        c1 = to(math.cos(a1) - k * math.sin(a1), math.sin(a1) + k * math.cos(a1))
        c2 = to(math.cos(a2) + k * math.sin(a2), math.sin(a2) - k * math.cos(a2))
        end = to(math.cos(a2), math.sin(a2)) if i < n - 1 else p1
        out.append((c1, c2, end))
    return out


def parse_path(d: str):
    sc = Scanner(d)
    subpaths = []
    cur = None
    pos = (0.0, 0.0)
    start = (0.0, 0.0)
    last_c = None  # last cubic control, for S
    last_q = None  # last quadratic control, for T
    cmd = None
    while not sc.done():
        c = sc.peek_command()
        if c:
            sc.command()
            cmd = c
        elif cmd is None:
            raise ValueError("path data does not start with a command")
        elif cmd in "Mm":
            cmd = "L" if cmd == "M" else "l"
        rel = cmd.islower()
        op = cmd.upper()

        def pt():
            x = sc.number()
            y = sc.number()
            return (pos[0] + x, pos[1] + y) if rel else (x, y)

        if op == "Z":
            if cur is not None:
                cur["closed"] = True
                pos = start
                cur = None
            last_c = last_q = None
            cmd = None
            continue
        if op == "M":
            p = pt()
            cur = {"start": p, "segs": [], "closed": False}
            subpaths.append(cur)
            pos = start = p
            last_c = last_q = None
            continue
        if cur is None:
            # A drawing command after Z continues from the subpath's start.
            cur = {"start": pos, "segs": [], "closed": False}
            subpaths.append(cur)
            start = pos
        if op == "L":
            p = pt()
            cur["segs"].append(line_seg(pos, p))
            pos = p
            last_c = last_q = None
        elif op == "H":
            x = sc.number()
            p = (pos[0] + x if rel else x, pos[1])
            cur["segs"].append(line_seg(pos, p))
            pos = p
            last_c = last_q = None
        elif op == "V":
            y = sc.number()
            p = (pos[0], pos[1] + y if rel else y)
            cur["segs"].append(line_seg(pos, p))
            pos = p
            last_c = last_q = None
        elif op == "C":
            c1 = pt()
            c2 = pt()
            p = pt()
            cur["segs"].append((c1, c2, p))
            last_c, last_q = c2, None
            pos = p
        elif op == "S":
            c1 = (2 * pos[0] - last_c[0], 2 * pos[1] - last_c[1]) if last_c else pos
            c2 = pt()
            p = pt()
            cur["segs"].append((c1, c2, p))
            last_c, last_q = c2, None
            pos = p
        elif op == "Q":
            q = pt()
            p = pt()
            cur["segs"].append(quad_to_cubic(pos, q, p))
            last_q, last_c = q, None
            pos = p
        elif op == "T":
            q = (2 * pos[0] - last_q[0], 2 * pos[1] - last_q[1]) if last_q else pos
            p = pt()
            cur["segs"].append(quad_to_cubic(pos, q, p))
            last_q, last_c = q, None
            pos = p
        elif op == "A":
            rx = sc.number()
            ry = sc.number()
            phi = sc.number()
            large = sc.flag()
            sweep = sc.flag()
            p = pt()
            cur["segs"].extend(arc_to_cubics(pos, rx, ry, phi, large, sweep, p))
            pos = p
            last_c = last_q = None
        else:
            raise ValueError(f"unknown path command {cmd}")
    return [sp for sp in subpaths if sp["segs"]]


KAPPA = 0.5522847498


def ellipse_path(cx, cy, rx, ry):
    k = KAPPA
    pts = [(cx + rx, cy), (cx, cy + ry), (cx - rx, cy), (cx, cy - ry)]
    segs = [
        ((cx + rx, cy + k * ry), (cx + k * rx, cy + ry), pts[1]),
        ((cx - k * rx, cy + ry), (cx - rx, cy + k * ry), pts[2]),
        ((cx - rx, cy - k * ry), (cx - k * rx, cy - ry), pts[3]),
        ((cx + k * rx, cy - ry), (cx + rx, cy - k * ry), pts[0]),
    ]
    return [{"start": pts[0], "segs": segs, "closed": True}]


def rect_path(x, y, w, h, rx, ry):
    if w <= 0 or h <= 0:
        return []
    rx = min(rx, w / 2)
    ry = min(ry, h / 2)
    if rx <= 0 or ry <= 0:
        p = [(x, y), (x + w, y), (x + w, y + h), (x, y + h)]
        return [{"start": p[0], "segs": [line_seg(p[0], p[1]), line_seg(p[1], p[2]), line_seg(p[2], p[3])], "closed": True}]
    d = (f"M{x + rx},{y}H{x + w - rx}A{rx},{ry} 0 0 1 {x + w},{y + ry}V{y + h - ry}"
         f"A{rx},{ry} 0 0 1 {x + w - rx},{y + h}H{x + rx}A{rx},{ry} 0 0 1 {x},{y + h - ry}"
         f"V{y + ry}A{rx},{ry} 0 0 1 {x + rx},{y}Z")
    return parse_path(d)


def points_path(text, closed):
    v = [float(n) for n in NUMBER.findall(text or "")]
    pts = list(zip(v[0::2], v[1::2]))
    if len(pts) < 2:
        return []
    segs = [line_seg(pts[i], pts[i + 1]) for i in range(len(pts) - 1)]
    return [{"start": pts[0], "segs": segs, "closed": closed}]


def transform_subpaths(subpaths, m):
    out = []
    for sp in subpaths:
        out.append({
            "start": apply(m, *sp["start"]),
            "segs": [(apply(m, *c1), apply(m, *c2), apply(m, *p)) for c1, c2, p in sp["segs"]],
            "closed": sp["closed"],
        })
    return out


def bbox_of(subpaths):
    xs, ys = [], []
    for sp in subpaths:
        xs.append(sp["start"][0])
        ys.append(sp["start"][1])
        for c1, c2, p in sp["segs"]:
            for q in (c1, c2, p):
                xs.append(q[0])
                ys.append(q[1])
    if not xs:
        return None
    return (min(xs), min(ys), max(xs), max(ys))


def flatten(sp, step=0.5):
    """A subpath as a list of points, curves cut into pieces about [step] long."""
    pts = [sp["start"]]
    p0 = sp["start"]
    for c1, c2, p in sp["segs"]:
        if c1 == p0 and c2 == p:
            pts.append(p)
        else:
            length = math.dist(p0, c1) + math.dist(c1, c2) + math.dist(c2, p)
            n = max(2, math.ceil(length / step))
            for i in range(1, n + 1):
                t = i / n
                u = 1 - t
                pts.append((
                    u * u * u * p0[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t * t * t * p[0],
                    u * u * u * p0[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t * t * t * p[1],
                ))
        p0 = p
    return pts


# ---- styles ------------------------------------------------------------------

INHERITED = {
    "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap",
    "stroke-linejoin", "stroke-miterlimit", "visibility", "clip-rule", "stroke-dasharray",
}
OWN = {"opacity", "display", "clip-path", "mask", "mix-blend-mode", "filter"}
DEFAULTS = {"fill": "#000", "fill-opacity": "1", "fill-rule": "nonzero", "stroke": "none", "stroke-width": "1",
            "stroke-opacity": "1", "stroke-linecap": "butt", "stroke-linejoin": "miter", "stroke-miterlimit": "4",
            "visibility": "visible", "clip-rule": "nonzero"}

NAMED = {
    "black": "#000000", "white": "#ffffff", "red": "#ff0000", "green": "#008000", "blue": "#0000ff",
    "yellow": "#ffff00", "orange": "#ffa500", "gray": "#808080", "grey": "#808080", "pink": "#ffc0cb",
    "purple": "#800080", "brown": "#a52a2a", "gold": "#ffd700", "silver": "#c0c0c0", "navy": "#000080",
    "transparent": None, "none": None,
}


def tag(el):
    t = el.tag
    return t.split("}", 1)[1] if "}" in t else t


def href(el):
    for k, v in el.attrib.items():
        if k.endswith("href"):
            return v.lstrip("#")
    return None


def parse_css(text):
    rules = {}
    for selectors, body in re.findall(r"([^{}]+)\{([^}]*)\}", text or ""):
        props = parse_style(body)
        for sel in selectors.split(","):
            sel = sel.strip()
            if sel.startswith("."):
                rules.setdefault(sel[1:], {}).update(props)
    return rules


def parse_style(text):
    out = {}
    for part in (text or "").split(";"):
        if ":" in part:
            k, v = part.split(":", 1)
            out[k.strip()] = v.strip()
    return out


def own_props(el, css):
    props = {}
    for k in INHERITED | OWN:
        if k in el.attrib:
            props[k] = el.attrib[k].strip()
    for cls in (el.attrib.get("class") or "").split():
        props.update(css.get(cls, {}))
    props.update(parse_style(el.attrib.get("style")))
    return props


def parse_color(text):
    """(r, g, b) 0..1, or None for none; 'url' tuples pass through."""
    if text is None:
        return None
    t = text.strip()
    if t.startswith("url("):
        m = re.match(r"url\(\s*#?([^)\s]+)\s*\)", t)
        return ("url", m.group(1)) if m else None
    t = t.lower()
    if t in NAMED:
        t = NAMED[t]
        if t is None:
            return None
    if t == "currentcolor":
        t = "#000000"
    if t.startswith("#"):
        h = t[1:]
        if len(h) == 3:
            h = "".join(ch * 2 for ch in h)
        if len(h) >= 6:
            return (int(h[0:2], 16) / 255, int(h[2:4], 16) / 255, int(h[4:6], 16) / 255)
    m = re.match(r"rgba?\(([^)]*)\)", t)
    if m:
        parts = [p.strip() for p in m.group(1).split(",")]
        vals = [float(p.rstrip("%")) * (2.55 if p.endswith("%") else 1) / 255 for p in parts[:3]]
        return tuple(vals)
    warn(f"colour {text!r} read as black")
    return (0.0, 0.0, 0.0)


def num(text, default=0.0, ref=None):
    if text is None:
        return default
    t = str(text).strip()
    if t.endswith("%"):
        return float(t[:-1]) / 100 * (ref if ref is not None else 1)
    m = NUMBER.match(t)
    return float(m.group(0)) if m else default


# ---- the walk ----------------------------------------------------------------


class Doc:
    """One SVG file: its elements by id, its CSS, its gradients and clip paths."""

    def __init__(self, root, name):
        self.root = root
        self.name = name
        self.ids = {}
        css = ""
        for el in root.iter():
            if "id" in el.attrib:
                self.ids[el.attrib["id"]] = el
            if tag(el) == "style":
                css += el.text or ""
        self.css = parse_css(css)
        self.items = []

    # -- gradients

    def gradient(self, gid):
        """Attributes and stops of a gradient, following its href chain."""
        el = self.ids.get(gid)
        if el is None or tag(el) not in ("linearGradient", "radialGradient"):
            return None
        chain = []
        seen = set()
        while el is not None and id(el) not in seen:
            seen.add(id(el))
            chain.append(el)
            ref = href(el)
            el = self.ids.get(ref) if ref else None
        attrs = {}
        for g in reversed(chain):
            attrs.update(g.attrib)
        stops = []
        for g in chain:
            found = [s for s in g if tag(s) == "stop"]
            if found:
                last = 0.0
                for s in found:
                    p = own_props(s, self.css)
                    off = num(s.attrib.get("offset", p.get("offset", "0")), 0.0, 1.0)
                    off = min(1.0, max(last, off))
                    last = off
                    color = parse_color(s.attrib.get("stop-color", p.get("stop-color", "#000")))
                    if color is None or isinstance(color, tuple) and color and color[0] == "url":
                        color = (0.0, 0.0, 0.0)
                    alpha = num(s.attrib.get("stop-opacity", p.get("stop-opacity", "1")), 1.0)
                    stops.append((off, color, alpha))
                break
        if not stops:
            return None
        return {"type": tag(chain[0]), "attrs": attrs, "stops": stops}

    def paint(self, value, opacity, m, local_subpaths):
        """A paint for a fill or stroke value, or None."""
        color = parse_color(value)
        if color is None:
            return None
        if isinstance(color, tuple) and color and color[0] == "url":
            g = self.gradient(color[1])
            if g is None:
                el = self.ids.get(color[1])
                warn(f"{self.name}: paint server #{color[1]} ({tag(el) if el is not None else 'missing'}) dropped")
                return None
            a = g["attrs"]
            units = a.get("gradientUnits", "objectBoundingBox")
            gm = parse_transform(a.get("gradientTransform"))
            if units == "objectBoundingBox":
                bb = bbox_of(local_subpaths)
                if bb is None:
                    return None
                bw = max(bb[2] - bb[0], 1e-6)
                bh = max(bb[3] - bb[1], 1e-6)
                base = mul(translate(bb[0], bb[1]), scale(bw, bh))
                ref_w = ref_h = 1.0
            else:
                base = IDENTITY
                ref_w = ref_h = 128.0
            full = mul(m, mul(base, gm))
            if g["type"] == "linearGradient":
                p = (num(a.get("x1"), 0.0, ref_w), num(a.get("y1"), 0.0, ref_h),
                     num(a.get("x2"), ref_w if units == "objectBoundingBox" else 1.0, ref_w),
                     num(a.get("y2"), 0.0, ref_h))
                if "x2" not in a:
                    p = (p[0], p[1], 1.0 if units == "objectBoundingBox" else ref_w, p[3])
                return {"kind": "linear", "stops": g["stops"], "opacity": opacity, "m": full,
                        "p1": (p[0], p[1]), "p2": (p[2], p[3])}
            cx = num(a.get("cx"), 0.5 if units == "objectBoundingBox" else 64.0, ref_w)
            cy = num(a.get("cy"), 0.5 if units == "objectBoundingBox" else 64.0, ref_h)
            r = num(a.get("r"), 0.5 if units == "objectBoundingBox" else 64.0, ref_w)
            fx = num(a.get("fx"), cx, ref_w) if "fx" in a else cx
            fy = num(a.get("fy"), cy, ref_h) if "fy" in a else cy
            return {"kind": "radial", "stops": g["stops"], "opacity": opacity, "m": full,
                    "c": (cx, cy), "r": r, "f": (fx, fy)}
        return {"kind": "solid", "color": color, "opacity": opacity}

    # -- clip paths

    def clip_geometry(self, cid, m):
        el = self.ids.get(cid)
        if el is None or tag(el) != "clipPath":
            warn(f"{self.name}: clip #{cid} missing")
            return None
        if el.attrib.get("clipPathUnits") == "objectBoundingBox":
            warn(f"{self.name}: bounding-box clip #{cid} ignored")
            return None
        m = mul(m, parse_transform(el.attrib.get("transform")))
        geoms = []

        def collect(node, mm, props):
            t = tag(node)
            nm = mul(mm, parse_transform(node.attrib.get("transform")))
            p = dict(props)
            p.update({k: v for k, v in own_props(node, self.css).items() if k in INHERITED})
            if t == "use":
                ref = self.ids.get(href(node) or "")
                if ref is not None:
                    um = mul(nm, translate(num(node.attrib.get("x")), num(node.attrib.get("y"))))
                    collect(ref, um, p)
                return
            if t == "g":
                for ch in node:
                    collect(ch, nm, p)
                return
            local = self.geometry(node)
            if local:
                rule = p.get("clip-rule", "nonzero")
                geoms.append(to_shapely(transform_subpaths(local, nm), rule))

        for ch in el:
            collect(ch, m, {})
        geoms = [g for g in geoms if g is not None and not g.is_empty]
        if not geoms:
            return None
        from shapely.ops import unary_union
        return unary_union(geoms)

    # -- elements

    def geometry(self, el):
        t = tag(el)
        a = el.attrib
        try:
            if t == "path":
                return parse_path(a.get("d", ""))
            if t == "rect":
                rx = a.get("rx")
                ry = a.get("ry")
                rxv = num(rx if rx is not None else ry)
                ryv = num(ry if ry is not None else rx)
                return rect_path(num(a.get("x")), num(a.get("y")), num(a.get("width")), num(a.get("height")), rxv, ryv)
            if t == "circle":
                r = num(a.get("r"))
                return ellipse_path(num(a.get("cx")), num(a.get("cy")), r, r) if r > 0 else []
            if t == "ellipse":
                rx, ry = num(a.get("rx")), num(a.get("ry"))
                return ellipse_path(num(a.get("cx")), num(a.get("cy")), rx, ry) if rx > 0 and ry > 0 else []
            if t == "polygon":
                return points_path(a.get("points"), True)
            if t == "polyline":
                return points_path(a.get("points"), False)
            if t == "line":
                p0 = (num(a.get("x1")), num(a.get("y1")))
                p1 = (num(a.get("x2")), num(a.get("y2")))
                return [{"start": p0, "segs": [line_seg(p0, p1)], "closed": False}]
        except (ValueError, IndexError) as e:
            warn(f"{self.name}: bad {t}: {e}")
        return []

    def walk(self, el, m, props, opacity, clip, depth=0):
        if depth > 40:
            return
        t = tag(el)
        if t in ("defs", "clipPath", "mask", "linearGradient", "radialGradient", "style", "title", "desc",
                 "metadata", "symbol", "pattern", "filter"):
            return
        own = own_props(el, self.css)
        if own.get("display") == "none":
            return
        p = dict(props)
        p.update({k: v for k, v in own.items() if k in INHERITED})
        op = opacity * num(own.get("opacity"), 1.0)
        nm = mul(m, parse_transform(el.attrib.get("transform")))
        if "mask" in own and own["mask"] != "none":
            warn(f"{self.name}: mask ignored")
        if own.get("mix-blend-mode") not in (None, "normal"):
            warn(f"{self.name}: blend {own['mix-blend-mode']} drawn as normal")
        if "filter" in own and own["filter"] != "none":
            warn(f"{self.name}: filter ignored")
        c = clip
        cp = own.get("clip-path")
        if cp and cp != "none":
            mm = re.match(r"url\(\s*#?([^)\s]+)\s*\)", cp)
            if mm:
                g = self.clip_geometry(mm.group(1), nm)
                if g is not None:
                    c = g if c is None else c.intersection(g)
        if t == "use":
            ref = self.ids.get(href(el) or "")
            if ref is not None:
                um = mul(nm, translate(num(el.attrib.get("x")), num(el.attrib.get("y"))))
                self.walk(ref, um, p, op, c, depth + 1)
            return
        if t in ("svg", "g", "a", "switch"):
            for ch in el:
                self.walk(ch, nm, p, op, c, depth + 1)
            return
        local = self.geometry(el)
        if not local or p.get("visibility") == "hidden":
            return
        world = transform_subpaths(local, nm)
        fill = self.paint(p.get("fill", DEFAULTS["fill"]), op * num(p.get("fill-opacity"), 1.0), nm, local)
        if t in ("line", "polyline"):
            fill = None
        stroke = self.paint(p.get("stroke", DEFAULTS["stroke"]), op * num(p.get("stroke-opacity"), 1.0), nm, local)
        width = num(p.get("stroke-width"), 1.0) * math.sqrt(abs(det(nm)))
        if stroke is not None and width <= 0:
            stroke = None
        if p.get("stroke-dasharray") not in (None, "none") and stroke is not None:
            warn(f"{self.name}: dashes drawn solid")
        rule = "evenodd" if p.get("fill-rule") == "evenodd" else "nonzero"
        stroke_style = {
            "width": width,
            "cap": p.get("stroke-linecap", "butt"),
            "join": p.get("stroke-linejoin", "miter"),
            "miter": num(p.get("stroke-miterlimit"), 4.0),
        }
        if c is not None:
            self.add_clipped(world, fill, stroke, rule, stroke_style, c)
            return
        if fill is not None:
            self.items.append({"subpaths": world, "paint": fill, "rule": rule, "local": local, "lm": nm})
        if stroke is not None:
            self.items.append({"subpaths": world, "paint": stroke, "stroke": stroke_style})

    def add_clipped(self, world, fill, stroke, rule, stroke_style, clip):
        if fill is not None:
            g = to_shapely(world, rule)
            if g is not None:
                g = g.intersection(clip)
                sub = from_shapely(g)
                if sub:
                    self.items.append({"subpaths": sub, "paint": fill, "rule": "evenodd"})
        if stroke is not None:
            from shapely.geometry import LineString, LinearRing
            cap = {"round": 1, "square": 3}.get(stroke_style["cap"], 2)
            join = {"round": 1, "bevel": 3}.get(stroke_style["join"], 2)
            from shapely.ops import unary_union
            parts = []
            for sp in world:
                pts = flatten(sp)
                if len(pts) < 2:
                    continue
                line = LinearRing(pts) if sp["closed"] and len(pts) > 2 else LineString(pts)
                parts.append(line.buffer(stroke_style["width"] / 2, cap_style=cap, join_style=join,
                                         mitre_limit=stroke_style["miter"]))
            if parts:
                g = unary_union(parts).intersection(clip)
                sub = from_shapely(g)
                if sub:
                    solid = dict(stroke)
                    self.items.append({"subpaths": sub, "paint": solid, "rule": "evenodd"})


def to_shapely(subpaths, rule):
    """Filled subpaths as a shapely geometry, honouring the fill rule closely enough for drawings."""
    from shapely.geometry import Polygon
    from shapely.ops import unary_union
    rings = []
    for sp in subpaths:
        pts = flatten(sp)
        if len(pts) < 3:
            continue
        poly = Polygon(pts)
        if not poly.is_valid:
            poly = poly.buffer(0)
        if poly.is_empty:
            continue
        signed = 0.0
        for i in range(len(pts)):
            x0, y0 = pts[i]
            x1, y1 = pts[(i + 1) % len(pts)]
            signed += x0 * y1 - x1 * y0
        rings.append((poly, signed >= 0))
    if not rings:
        return None
    if rule == "evenodd":
        g = rings[0][0]
        for poly, _ in rings[1:]:
            g = g.symmetric_difference(poly)
        return g
    # Nonzero, for outlines with holes: the rings wound like the biggest one
    # add, the ones wound against it cut.
    main = max(rings, key=lambda r: r[0].area)[1]
    add = [p for p, ccw in rings if ccw == main]
    cut = [p for p, ccw in rings if ccw != main]
    g = unary_union(add)
    if cut:
        g = g.difference(unary_union(cut))
    return g


def from_shapely(g):
    """Polygons back into subpaths, simplified."""
    g = g.simplify(0.05, preserve_topology=True)
    polys = []
    if g.geom_type == "Polygon":
        polys = [g]
    elif g.geom_type in ("MultiPolygon", "GeometryCollection"):
        polys = [p for p in g.geoms if p.geom_type == "Polygon"]
    out = []
    for poly in polys:
        if poly.area < 0.05:
            continue
        for ring in [poly.exterior] + list(poly.interiors):
            pts = list(ring.coords)[:-1]
            if len(pts) < 3:
                continue
            segs = [line_seg(pts[i], pts[(i + 1) % len(pts)]) for i in range(len(pts))]
            out.append({"start": pts[0], "segs": segs[:-1], "closed": True})
    return out


def convert_svg(text, name):
    root = ET.fromstring(text)
    doc = Doc(root, name)
    vb = [float(v) for v in NUMBER.findall(root.attrib.get("viewBox", "0 0 128 128"))]
    m = IDENTITY
    if len(vb) == 4 and (vb[0] or vb[1]):
        m = translate(-vb[0], -vb[1])
    doc.walk(root, m, dict(DEFAULTS), 1.0, None)
    return doc.items


# ---- to Lottie ---------------------------------------------------------------


def rnd(v, d=DECIMALS):
    r = round(v, d)
    if r == 0:
        return 0
    return int(r) if r == int(r) else r


def ks(value):
    return {"a": 0, "k": value}


def lottie_path(sp):
    """A subpath as a Lottie `sh` item (vertices with tangents relative to them)."""
    segs = list(sp["segs"])
    start = sp["start"]
    closed = sp["closed"]
    if closed and segs and math.dist(segs[-1][2], start) < 1e-6:
        # The last segment lands on the start: it closes the path itself.
        verts = [start] + [s[2] for s in segs[:-1]]
        outs = [s[0] for s in segs]
        ins = [segs[-1][1]] + [s[1] for s in segs[:-1]]
    else:
        verts = [start] + [s[2] for s in segs]
        outs = [s[0] for s in segs] + [verts[-1]]
        ins = [verts[0]] + [s[1] for s in segs]
    v = [[rnd(x), rnd(y)] for x, y in verts]
    o = [[rnd(c[0] - p[0]), rnd(c[1] - p[1])] for c, p in zip(outs, verts)]
    i = [[rnd(c[0] - p[0]), rnd(c[1] - p[1])] for c, p in zip(ins, verts)]
    return {"ty": "sh", "ks": ks({"i": i, "o": o, "v": v, "c": closed})}


def gradient_stops(stops):
    k = []
    for off, (r, g, b), _ in stops:
        k += [rnd(off, 3), rnd(r, 3), rnd(g, 3), rnd(b, 3)]
    if any(a < 0.999 for _, _, a in stops):
        for off, _, a in stops:
            k += [rnd(off, 3), rnd(a, 3)]
    return {"p": len(stops), "k": ks(k)}


TR = {"ty": "tr", "p": ks([0, 0]), "a": ks([0, 0]), "s": ks([100, 100]), "r": ks(0), "o": ks(100)}


def similarity_parts(m):
    """(angle°, sx, sy) when m's linear part is a rotation times a scale, else None."""
    a, b, c, d = m[0], m[1], m[2], m[3]
    if abs(a * c + b * d) > 1e-6 * max(1.0, a * a + b * b + c * c + d * d):
        return None
    sx = math.hypot(a, b)
    sy = math.hypot(c, d)
    if sx < 1e-9 or sy < 1e-9:
        return None
    theta = math.atan2(b, a)
    # The second column is the first turned a quarter: positive sy; else a mirror.
    expected = (-math.sin(theta), math.cos(theta))
    sign = 1 if c * expected[0] + d * expected[1] >= 0 else -1
    return math.degrees(theta), sx, sy * sign


def paint_items(item):
    """The paint items for [item], and the group transform its paths need (or None)."""
    p = item["paint"]
    opacity = rnd(max(0.0, min(1.0, p["opacity"])) * 100, 1)
    stroke = item.get("stroke")
    if p["kind"] == "solid":
        r, g, b = p["color"]
        color = ks([rnd(r, 3), rnd(g, 3), rnd(b, 3), 1])
        if stroke:
            return [{"ty": "st", "c": color, "o": ks(opacity), "w": ks(rnd(stroke["width"])),
                     "lc": {"butt": 1, "round": 2, "square": 3}.get(stroke["cap"], 1),
                     "lj": {"miter": 1, "round": 2, "bevel": 3}.get(stroke["join"], 1),
                     "ml": rnd(stroke["miter"])}], None
        return [{"ty": "fl", "c": color, "o": ks(opacity), "r": 2 if item.get("rule") == "evenodd" else 1}], None
    m = p["m"]
    common = {"o": ks(opacity), "g": gradient_stops(p["stops"])}
    if p["kind"] == "linear":
        # Solve for the end points that give the same gradient after m.
        (x1, y1), (x2, y2) = p["p1"], p["p2"]
        dx, dy = x2 - x1, y2 - y1
        dd = dx * dx + dy * dy
        inv = inverse(m)
        s = apply(m, x1, y1)
        if inv is None or dd < 1e-12:
            e = apply(m, x2, y2)
        else:
            # t(q) = (Minv q - p1)·d / |d|²  →  t = (q - s)·w with w = Linv^T d / |d|².
            wx = (inv[0] * dx + inv[1] * dy) / dd
            wy = (inv[2] * dx + inv[3] * dy) / dd
            ww = wx * wx + wy * wy
            e = (s[0] + wx / ww, s[1] + wy / ww) if ww > 1e-12 else apply(m, x2, y2)
        gf = {"ty": "gs" if stroke else "gf", **common, "t": 1, "s": ks([rnd(s[0]), rnd(s[1])]), "e": ks([rnd(e[0]), rnd(e[1])])}
        transform = None
    else:
        parts = similarity_parts(m)
        cx, cy = p["c"]
        r = p["r"]
        fx, fy = p["f"]
        if parts is not None and not stroke:
            # Draw in the gradient's own space; the group carries m.
            sx_e, sy_e = cx + r, cy
            f_local = (fx, fy)
            centre = (cx, cy)
            transform = m
            end = (sx_e, sy_e)
            radius = r
        else:
            centre = apply(m, cx, cy)
            radius = r * math.sqrt(abs(det(m)))
            end = (centre[0] + radius, centre[1])
            f_local = apply(m, fx, fy)
            transform = None
        gf = {"ty": "gs" if stroke else "gf", **common, "t": 2, "s": ks([rnd(centre[0]), rnd(centre[1])]),
              "e": ks([rnd(end[0]), rnd(end[1])])}
        fd = math.dist(f_local, centre)
        if radius > 0 and fd > 1e-3:
            gf["h"] = ks(rnd(min(99.0, fd / radius * 100), 1))
            gf["a"] = ks(rnd(math.degrees(math.atan2(f_local[1] - centre[1], f_local[0] - centre[0])), 1))
    gf["r"] = 2 if item.get("rule") == "evenodd" else 1
    if stroke:
        gf.update({"w": ks(rnd(stroke["width"])), "lc": {"butt": 1, "round": 2, "square": 3}.get(stroke["cap"], 1),
                   "lj": {"miter": 1, "round": 2, "bevel": 3}.get(stroke["join"], 1), "ml": rnd(stroke["miter"])})
        gf.pop("r", None)
    return [gf], transform


def group_for(item):
    paints, transform = paint_items(item)
    if transform is not None:
        inv = inverse(transform)
        subpaths = transform_subpaths(item["subpaths"], inv)
        angle, sx, sy = similarity_parts(transform)
        tr = {"ty": "tr", "p": ks([rnd(transform[4]), rnd(transform[5])]), "a": ks([0, 0]),
              "s": ks([rnd(sx * 100, 3), rnd(sy * 100, 3)]), "r": ks(rnd(angle, 3)), "o": ks(100)}
    else:
        subpaths = item["subpaths"]
        tr = TR
    return {"ty": "gr", "it": [lottie_path(sp) for sp in subpaths] + paints + [tr]}


def shift_item(item, dx, dy):
    t = translate(dx, dy)
    item = dict(item)
    item["subpaths"] = transform_subpaths(item["subpaths"], t)
    p = dict(item["paint"])
    if "m" in p:
        p["m"] = mul(t, p["m"])
    item["paint"] = p
    return item


def build_art(items):
    """Cropped Lottie art from converted items: {"w", "h", "shapes"}."""
    boxes = []
    for it in items:
        bb = bbox_of(it["subpaths"])
        if bb is None:
            continue
        pad = it["stroke"]["width"] / 2 if it.get("stroke") else 0.0
        boxes.append((bb[0] - pad, bb[1] - pad, bb[2] + pad, bb[3] + pad))
    if not boxes:
        return None
    x0 = min(b[0] for b in boxes) - MARGIN
    y0 = min(b[1] for b in boxes) - MARGIN
    x1 = max(b[2] for b in boxes) + MARGIN
    y1 = max(b[3] for b in boxes) + MARGIN
    shifted = [shift_item(it, -x0, -y0) for it in items]
    groups = [group_for(it) for it in shifted]
    # Lottie draws its first item on top; SVG paints its first element first.
    groups.reverse()
    # x0/y0: where the crop starts in the source's own units, so a template
    # can place words on a part of a drawing (a sign's board) by the
    # drawing's coordinates. Players ignore them.
    return {"w": rnd(x1 - x0, 1), "h": rnd(y1 - y0, 1), "x0": rnd(x0, 1), "y0": rnd(y0, 1), "shapes": groups}


# ---- sources -----------------------------------------------------------------


def noto_svg(stem, offline):
    folder = os.path.join(CACHE, f"noto-{NOTO_COMMIT[:12]}")
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, f"emoji_u{stem}.svg")
    if not os.path.exists(path):
        if offline:
            raise SystemExit(f"{path} is not cached and --offline was given")
        url = NOTO_URL.format(commit=NOTO_COMMIT, name=stem)
        req = urllib.request.Request(url, headers={"User-Agent": "dynstickers-art-builder"})
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
        if b"<svg" not in data[:4096]:
            raise SystemExit(f"{url} did not return an SVG")
        tmp = path + ".part"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, path)
    with open(path, encoding="utf-8") as f:
        return f.read()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--offline", action="store_true", help="use only what .cache holds")
    ap.add_argument("--only", nargs="*", help="convert just these ids (for checking); writes nothing")
    args = ap.parse_args(argv)

    sys.path.insert(0, HERE)
    import custom_art  # noqa: E402

    library = {}
    ids = sorted(set(NOTO) | set(custom_art.ART))
    if args.only:
        ids = [i for i in ids if i in args.only]
    clash = set(NOTO) & set(custom_art.ART)
    if clash:
        raise SystemExit(f"ids in both sources: {sorted(clash)}")
    for art_id in ids:
        if art_id in NOTO:
            text = noto_svg(NOTO[art_id], args.offline)
            name = f"noto {NOTO[art_id]} ({art_id})"
        else:
            text = custom_art.ART[art_id]
            name = f"custom {art_id}"
        items = convert_svg(text, name)
        art = build_art(items)
        if art is None:
            raise SystemExit(f"{name} drew nothing")
        library[art_id] = art

    for w in sorted(set(warnings)):
        print("warning:", w, file=sys.stderr)
    if args.only:
        print(json.dumps(library, separators=(",", ":"))[:2000])
        return
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    text = json.dumps(library, separators=(",", ":"), sort_keys=True, ensure_ascii=False)
    with open(args.out + ".part", "w", encoding="utf-8") as f:
        f.write(text)
        f.write("\n")
    os.replace(args.out + ".part", args.out)
    print(f"{len(library)} drawings ({sum(1 for i in library if i in NOTO)} Noto, "
          f"{sum(1 for i in library if i not in NOTO)} ours), {len(text.encode()):,} bytes -> "
          f"{os.path.relpath(args.out, ROOT)}")


if __name__ == "__main__":
    main()
