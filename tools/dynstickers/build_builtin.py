#!/usr/bin/env python3
"""Writes the built-in dynamic sticker pack.

    python3 tools/dynstickers/build_builtin.py

writes, under app/src/main/assets/dynstickers/builtin/:

    pack.json          {"format": "wmkeyboard-dynstickers", "version": 1, "pack": {...}}
    scenes/<id>.json   one DynScene per template

Every template is a scene (DynScene.kt), so each one opens in the builder
and can be copied and changed; the keyboard compiles it with
SceneToLottie on first use. Field names and enum values here are the Kotlin
serial names exactly; anything at its Kotlin default is left out.

The manifest carries each template's slots and photo slots, worked out here
the way SceneToLottie.compile works them out (BuiltinPackTest checks the two
agree), so the keyboard can match messages without compiling anything.

Output is deterministic: the same script writes the same bytes.

Placing words on our own drawings (a sign's board, a mug's side) reads the
drawing's size and crop origin from art.json, so run svg_to_lottie_shapes.py
first when the art changes.
"""

from __future__ import annotations

import json
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "dynstickers")
OUT = os.path.join(ASSETS, "builtin")
ART_JSON = os.path.join(ASSETS, "art", "art.json")

S = 512  # canvas size
FPS = 30

PUFF = "DynaPuff-Bold"
PUFF_REGULAR = "DynaPuff"
ANTON = "Anton"
SANS = "sans-serif"
SANS_MEDIUM = "sans-serif-medium"
SANS_BLACK = "sans-serif-black"
SERIF = "serif"

with open(ART_JSON, encoding="utf-8") as fh:
    ART = json.load(fh)


# ---- colours -------------------------------------------------------------------

def c(hex_rgb: str, alpha: float = 1.0) -> int:
    """'#RRGGBB' as the ARGB Long the scene stores."""
    h = hex_rgb.lstrip("#")
    return (round(alpha * 255) << 24) | int(h, 16)


WHITE = c("#FFFFFF")
BLACK = c("#000000")
SHADOW = c("#000000", 0.28)
SOFT_SHADOW = c("#000000", 0.18)


class Pal:
    def __init__(self, fill, stroke, bg, bg2, accent, ink):
        self.fill = c(fill)
        self.stroke = c(stroke)
        self.bg = c(bg)
        self.bg2 = c(bg2)
        self.accent = c(accent)
        self.ink = c(ink)


PAL = {
    "pink": Pal("#FFFFFF", "#FF3D7F", "#FFC2D9", "#FF6FA3", "#FFD23F", "#C2185B"),
    "sun": Pal("#FFE14D", "#F2641D", "#FFF3B0", "#FFC93C", "#FF8A3D", "#B4500F"),
    "sky": Pal("#FFFFFF", "#2F7DF6", "#C7E6FF", "#6FB8FF", "#FFD23F", "#1B4FA8"),
    "mint": Pal("#FFFFFF", "#0FA37F", "#BDF4DE", "#5FD6A8", "#FFD23F", "#0B7A5E"),
    "grape": Pal("#F7EDFF", "#7B3FE4", "#E6D2FF", "#B286FF", "#FFD23F", "#5B21B6"),
    "fire": Pal("#FFF1A8", "#E8312A", "#FFC9B0", "#FF7A45", "#FFD23F", "#B3261E"),
    "night": Pal("#FFF4BF", "#2A3A8F", "#3F51B5", "#1A237E", "#FFE57F", "#1A237E"),
    "lime": Pal("#EEFFB0", "#3C8D2F", "#DDF7B5", "#8BD650", "#FFD23F", "#2E6B23"),
    "coral": Pal("#FFFFFF", "#FF6B4A", "#FFD3C4", "#FF9472", "#FFD23F", "#C4421F"),
    "berry": Pal("#FF5C8A", "#FFFFFF", "#FFD6E2", "#FF8FB0", "#FFD23F", "#B0144B"),
    "teal": Pal("#FFFFFF", "#0E9AA7", "#C5F1F4", "#4FC9D3", "#FFD23F", "#0A6D76"),
    "gold": Pal("#FFE27A", "#A8620F", "#FFF0C2", "#FFC94A", "#FF8A3D", "#7A4508"),
    "rose": Pal("#FFFFFF", "#E11D48", "#FFD1DC", "#FF6B8B", "#FFD23F", "#9F1239"),
    "choco": Pal("#FFF3E0", "#6D4C41", "#F1DDC9", "#C79A73", "#FFB74D", "#4E342E"),
    "ink": Pal("#2B2B2B", "#FFFFFF", "#F2F2F2", "#DADADA", "#FFD23F", "#2B2B2B"),
    "orange": Pal("#FFFFFF", "#FF7A00", "#FFE0B8", "#FFA64D", "#FFD23F", "#B85400"),
    "lilac": Pal("#FFFFFF", "#9B5DE5", "#EADCFB", "#C39BF2", "#F15BB5", "#5E2BA6"),
    "cherry": Pal("#FFE3EC", "#C9184A", "#FFCCD8", "#FF4D6D", "#FFD23F", "#800F2F"),
}

CONFETTI = [c("#FF4F8B"), c("#FFC83D"), c("#3EC1F3"), c("#7ED957"), c("#A66CFF"), c("#FF7A45")]
RAINBOW = [c("#F44336"), c("#FF9800"), c("#FFEB3B"), c("#4CAF50"), c("#2196F3"), c("#9C27B0")]


# ---- scene building ---------------------------------------------------------------

DEFAULTS = {
    "x": 0.5, "y": 0.5, "w": 0.8, "h": 0.3, "rotation": 0.0, "opacity": 1.0, "flipX": False,
    "slot": None, "role": "main", "label": "", "text": "", "font": "DynaPuff", "minSize": 0.0, "maxSize": 0.0,
    "align": 2, "vAlign": 1, "case": "upper", "lineHeight": 1.1, "tracking": 0.0, "maxLines": 3, "minChars": 1,
    "maxChars": 60, "fill": WHITE, "fill2": None, "stroke": None, "strokeWidth": 0.0, "shadow": None,
    "shadowDistance": 0.0, "shadowBlur": 0.0, "shape": None, "points": 5, "innerRatio": 0.45, "corner": 0.0,
    "palette": [], "art": None, "image": None, "fit": "cover", "animations": [],
}
ORDER = list(DEFAULTS)
ANIM_DEFAULTS = {"delay": 0, "duration": 15, "loop": True, "strength": 1.0, "direction": 0}


def A(preset, delay=0, dur=15, loop=True, strength=1.0, direction=0):
    out = {"preset": preset}
    for k, v in (("delay", delay), ("duration", dur), ("loop", loop), ("strength", strength), ("direction", direction)):
        if v != ANIM_DEFAULTS[k]:
            out[k] = v
    return out


def rnd(v):
    if isinstance(v, float):
        r = round(v, 4)
        return int(r) if r == int(r) and abs(r) >= 1 else r
    return v


def el(id_, type_, **kw):
    e = {"id": id_, "type": type_}
    for k in ORDER:
        if k not in kw:
            continue
        v = kw[k]
        d = DEFAULTS[k]
        if v is None and d is None:
            continue
        if isinstance(v, float) and isinstance(d, float) and abs(v - d) < 1e-9:
            continue
        if v == d and type(v) is type(d):
            continue
        e[k] = [rnd(x) for x in v] if isinstance(v, list) and k == "palette" else rnd(v)
    unknown = set(kw) - set(ORDER)
    if unknown:
        raise ValueError(f"{id_}: unknown fields {unknown}")
    return e


class Scene:
    def __init__(self, frames=60, background=None):
        self.frames = frames
        self.elements = []
        self.background = background
        self._n = {}

    def nid(self, base):
        self._n[base] = self._n.get(base, 0) + 1
        return f"{base}{self._n[base]}"

    def add(self, e):
        if any(x["id"] == e["id"] for x in self.elements):
            raise ValueError(f"duplicate element id {e['id']}")
        self.elements.append(e)
        return e

    # -- element kinds

    def text(self, sample, x, y, w, h, *, slot="text", role="main", label="", font=PUFF, size=(34, 130),
             chars=(1, 32), lines=3, fill=WHITE, stroke=None, sw=0.0, shadow=SHADOW, sd=5.0, sb=6.0,
             case="upper", align=2, valign=1, lh=1.0, tracking=0.0, anims=(), rotation=0.0, id_=None, opacity=1.0):
        return self.add(el(
            id_ or self.nid("t"), "text", x=x, y=y, w=w, h=h, rotation=float(rotation), opacity=float(opacity),
            slot=slot, role=role, label=label, text=sample, font=font,
            minSize=float(size[0]), maxSize=float(size[1]), align=align, vAlign=valign, case=case,
            lineHeight=float(lh), tracking=float(tracking), maxLines=lines, minChars=chars[0], maxChars=chars[1],
            fill=fill, stroke=stroke, strokeWidth=float(sw) if stroke is not None else 0.0,
            shadow=shadow, shadowDistance=float(sd) if shadow is not None else 0.0,
            shadowBlur=float(sb) if shadow is not None else 0.0, animations=list(anims)))

    def words(self, words, x, y, w, h, **kw):
        """Fixed words: a text element with no slot."""
        kw.setdefault("shadow", None)
        kw["slot"] = None
        e = self.text(words, x, y, w, h, **kw)
        e.pop("slot", None)
        e.pop("role", None)
        for k in ("minChars", "maxChars", "label"):
            e.pop(k, None)
        return e

    def shape(self, kind, x, y, w, h, *, fill=WHITE, fill2=None, stroke=None, sw=0.0, points=None, inner=None,
              corner=0.0, palette=None, rotation=0.0, opacity=1.0, anims=(), shadow=None, sd=0.0, sb=0.0, id_=None,
              flip=False):
        kw = dict(x=x, y=y, w=w, h=h, rotation=float(rotation), opacity=float(opacity), flipX=flip, fill=fill,
                  fill2=fill2, stroke=stroke, strokeWidth=float(sw) if stroke is not None else 0.0, shape=kind,
                  corner=float(corner), animations=list(anims), shadow=shadow,
                  shadowDistance=float(sd) if shadow is not None else 0.0,
                  shadowBlur=float(sb) if shadow is not None else 0.0)
        if points is not None:
            kw["points"] = points
        if inner is not None:
            kw["innerRatio"] = float(inner)
        if palette:
            kw["palette"] = list(palette)
        return self.add(el(id_ or self.nid(kind), "shape", **kw))

    def art(self, art_id, x, y, w, h, *, rotation=0.0, opacity=1.0, anims=(), flip=False, id_=None, shadow=None,
            sd=0.0, sb=0.0):
        if art_id not in ART:
            raise ValueError(f"unknown art {art_id}")
        return self.add(el(id_ or self.nid("a_" + art_id), "art", x=x, y=y, w=w, h=h, rotation=float(rotation),
                           opacity=float(opacity), flipX=flip, art=art_id, animations=list(anims), shadow=shadow,
                           shadowDistance=float(sd) if shadow is not None else 0.0,
                           shadowBlur=float(sb) if shadow is not None else 0.0))

    def photo(self, slot, x, y, w, h, *, label="Photo", corner=0.0, rotation=0.0, fit="cover"):
        return self.add(el(self.nid("p"), "photo", x=x, y=y, w=w, h=h, rotation=float(rotation), slot=slot,
                           label=label, corner=float(corner), fit=fit))

    def doc(self):
        out = {}
        if self.frames != 90:
            out["frames"] = self.frames
        if self.background is not None:
            out["background"] = self.background
        out["elements"] = self.elements
        return out


class Placed:
    """Where an art element's drawing lands, to put things on parts of it (its source units → canvas fractions)."""

    def __init__(self, art_id, x, y, w, h):
        a = ART[art_id]
        k = min(w * S / a["w"], h * S / a["h"])
        self.k = k
        self.left = x * S - a["w"] * k / 2
        self.top = y * S - a["h"] * k / 2
        self.x0 = a.get("x0", 0)
        self.y0 = a.get("y0", 0)

    def pt(self, sx, sy):
        return ((self.left + (sx - self.x0) * self.k) / S, (self.top + (sy - self.y0) * self.k) / S)

    def box(self, sx0, sy0, sx1, sy1):
        """(cx, cy, w, h) in canvas fractions of a rectangle in the drawing's own units."""
        x0, y0 = self.pt(sx0, sy0)
        x1, y1 = self.pt(sx1, sy1)
        return ((x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0)


# ---- motion helpers -----------------------------------------------------------


def spin_for(frames, points, steps=1):
    """A spin that turns [steps] points' worth over [frames], so a [points]-fold shape loops without a jump."""
    return A("spin", 0, round(frames * points / steps))


def pop(delay=0, dur=12):
    return A("pop_in", delay, dur, loop=False)


def twinkle_set(sc, spots, color, period=30, size=0.09, kind="sparkle", stagger=True):
    """Sparkles at [spots] (x, y[, scale]) twinkling out of step."""
    n = len(spots)
    for i, spot in enumerate(spots):
        x, y = spot[0], spot[1]
        k = spot[2] if len(spot) > 2 else 1.0
        phase = -round(period * i / max(1, n)) if stagger else 0
        sc.shape(kind, x, y, size * k, size * k, fill=color, anims=[A("twinkle", phase, period)])


def float_hearts(sc, spots, colors, period=60):
    for i, (x, y, k) in enumerate(spots):
        sc.shape("heart", x, y, 0.1 * k, 0.09 * k, fill=colors[i % len(colors)],
                 anims=[A("float", -round(period * i / len(spots)), period), A("pulse", -i * 7, 30, strength=0.6)])


# ---- main text -------------------------------------------------------------------
# One slot, "text". `lines` 1: a box the whole message wraps in; 2 or 3: one
# element per line, the words split over them.

PAL_STROKE = object()  # main_text's stroke default: the palette's


def main_text(sc, sample, x, y, w, h, pal, lines=1, size=(34, 130), chars=(1, 32), anims=None, font=PUFF,
              case="upper", sw=13.0, max_lines=3, fills=None, stroke=PAL_STROKE, shadow=SHADOW, rotation=0.0,
              line_anims=None, gap=1.0, line_chars=(1, 16)):
    anims = [pop(4, 12)] if anims is None else anims
    stroke = pal.stroke if stroke is PAL_STROKE else stroke
    if lines == 1:
        return [sc.text(sample, x, y, w, h, fill=fills[0] if fills else pal.fill, stroke=stroke, sw=sw,
                        size=size, chars=chars, lines=max_lines, font=font, case=case, anims=anims,
                        shadow=shadow, rotation=rotation)]
    parts = sample.split("|")
    assert len(parts) == lines, sample
    out = []
    lh = h / lines
    for i, part in enumerate(parts):
        ly = y - h / 2 + lh * (i + 0.5)
        la = line_anims[i] if line_anims else [pop(4 + i * 5, 12)]
        fill = fills[i % len(fills)] if fills else pal.fill
        out.append(sc.text(part, x, ly, w, lh * gap, fill=fill, stroke=stroke, sw=sw, size=size,
                           chars=line_chars, lines=1, font=font, case=case, anims=la, shadow=shadow,
                           rotation=rotation))
    return out


# ---- archetypes --------------------------------------------------------------------
# Each draws one composition into a fresh Scene and returns it, with
# whether its motion is only an entrance (so it is sent as a still).


def a_burst(sample, pal, art=None, lines=1, points=14, spin=True, sparkle=None, **kw):
    sc = Scene(120 if spin else 60)
    sc.shape("burst", 0.5, 0.5, 0.94, 0.94, fill=pal.bg, fill2=pal.bg2, points=points,
             anims=[pop(0, 10)] + ([spin_for(120, points, 2)] if spin else [A("pulse", 0, 30, strength=0.5)]))
    twinkle_set(sc, [(0.14, 0.18), (0.86, 0.2, 0.8), (0.12, 0.8, 0.7), (0.88, 0.84)], sparkle or pal.accent)
    if art:
        sc.art(art, 0.8, 0.78, 0.26, 0.26, anims=[pop(14, 12), A("wiggle", 0, 30)])
    main_text(sc, sample, 0.5, 0.5, 0.8, 0.44, pal, lines=lines, **kw)
    return sc, False


def a_art_top(sample, pal, art, lines=1, motion="bounce", art_size=0.5, extra=None, **kw):
    sc = Scene(60)
    loop = {"bounce": A("bounce", 0, 30), "wiggle": A("wiggle", 0, 30), "swing": A("swing", 0, 60),
            "float": A("float", 0, 60), "jelly": A("jelly", 0, 30), "pulse": A("pulse", 0, 30),
            "heartbeat": A("heartbeat", 0, 30), "shake": A("shake", 0, 30), "spin": A("spin", 0, 60)}[motion]
    if extra == "sparkles":
        twinkle_set(sc, [(0.16, 0.14), (0.84, 0.18, 0.8), (0.2, 0.5, 0.6), (0.82, 0.5, 0.7)], pal.accent)
    elif extra == "hearts":
        float_hearts(sc, [(0.15, 0.2, 1), (0.86, 0.16, 0.8), (0.12, 0.52, 0.7), (0.88, 0.5, 0.9)], [pal.stroke, pal.bg2])
    elif extra == "confetti":
        sc.shape("confetti", 0.5, 0.34, 0.95, 0.6, points=8, palette=CONFETTI, anims=[A("float", 0, 60), A("twinkle", 0, 30, strength=0.5)])
    sc.art(art, 0.5, 0.31, art_size, art_size * 0.9, anims=[pop(0, 12), loop])
    main_text(sc, sample, 0.5, 0.77, 0.94, 0.36, pal, lines=lines, **kw)
    return sc, False


def a_corners(sample, pal, arts, lines=1, motion="wiggle", **kw):
    sc = Scene(60)
    first, second = arts[0], arts[1] if len(arts) > 1 else arts[0]
    m = {"wiggle": "wiggle", "swing": "swing", "bounce": "bounce", "float": "float", "pulse": "pulse"}[motion]
    dur = 60 if m in ("swing", "float") else 30
    sc.art(first, 0.19, 0.2, 0.3, 0.3, rotation=-12, anims=[pop(0, 12), A(m, 0, dur)])
    sc.art(second, 0.81, 0.8, 0.3, 0.3, rotation=10, anims=[pop(6, 12), A(m, -dur // 2, dur)])
    main_text(sc, sample, 0.5, 0.5, 0.9, 0.42, pal, lines=lines, **kw)
    return sc, False


def a_banner(sample, pal, top_art=None, **kw):
    sc = Scene(60)
    sc.shape("confetti", 0.5, 0.5, 0.98, 0.98, points=9, palette=CONFETTI,
             anims=[A("float", 0, 60, strength=1.4), A("twinkle", -10, 30, strength=0.4)])
    bx, by = 0.5, 0.62 if top_art else 0.52
    sc.art("ribbon_banner", bx, by, 1.0, 0.62, anims=[A("drop_in", 0, 18, loop=False)])
    band = Placed("ribbon_banner", bx, by, 1.0, 0.62)
    cx, cy, w, h = band.box(22, 36, 106, 74)
    if top_art:
        sc.art(top_art, 0.5, 0.25, 0.42, 0.36, anims=[pop(14, 12), A("bounce", 0, 30)])
    sc.text(sample, cx, cy - h * 0.02, w * 0.98, h * 0.86, fill=WHITE, stroke=c("#9E1B1B"), sw=9.0,
            size=(24, 86), chars=kw.get("chars", (1, 24)), lines=2, shadow=None, anims=[pop(12, 12)])
    return sc, False


def a_bubble(sample, pal, art=None, lines=1, typewriter=False, **kw):
    sc = Scene(60)
    sc.shape("bubble", 0.5, 0.44, 0.9, 0.7, fill=WHITE, stroke=pal.ink, sw=10.0, shadow=SOFT_SHADOW, sd=6, sb=4,
             anims=[pop(0, 12), A("jelly", 0, 60, strength=0.5)])
    if art:
        sc.art(art, 0.83, 0.84, 0.28, 0.28, anims=[pop(10, 12), A("bounce", 0, 30)])
    anims = [A("typewriter", 8, 24, loop=False)] if typewriter else [pop(6, 12)]
    main_text(sc, sample, 0.5, 0.41, 0.74, 0.44, pal, lines=lines, fills=[pal.ink], stroke=None, sw=0.0,
              shadow=None, anims=anims, **kw)
    return sc, False


def a_heart(sample, pal, lines=1, art=None, **kw):
    sc = Scene(60)
    sc.shape("heart", 0.5, 0.52, 0.98, 0.92, fill=pal.bg2, fill2=pal.stroke, anims=[pop(0, 12), A("heartbeat", 0, 30)])
    float_hearts(sc, [(0.12, 0.14, 0.9), (0.9, 0.12, 1.1), (0.08, 0.6, 0.7), (0.92, 0.66, 0.8)], [pal.stroke, pal.bg2, c("#FFFFFF")])
    if art:
        sc.art(art, 0.78, 0.82, 0.24, 0.24, anims=[pop(14, 12), A("wiggle", 0, 30)])
    main_text(sc, sample, 0.5, 0.47, 0.72, 0.4, pal, lines=lines, fills=[WHITE], stroke=pal.ink, **kw)
    return sc, False


def a_sign(sample, pal, art=None, **kw):
    sc = Scene(60)
    sc.art("sign_wood", 0.5, 0.52, 0.96, 0.96, anims=[A("drop_in", 0, 20, loop=False)])
    board = Placed("sign_wood", 0.5, 0.52, 0.96, 0.96)
    cx, cy, w, h = board.box(14, 24, 114, 74)
    if art:
        sc.art(art, 0.86, 0.82, 0.24, 0.24, anims=[pop(16, 12), A("bounce", 0, 30)])
    sc.text(sample, cx, cy, w * 0.92, h * 0.84, fill=c("#4E2A12"), stroke=c("#F3D2A2"), sw=6.0, size=(24, 96),
            chars=kw.get("chars", (1, 30)), lines=3, shadow=None, anims=[A("drop_in", 0, 20, loop=False)])
    return sc, True and art is None


def a_calendar(sample, pal, art=None, **kw):
    sc = Scene(60)
    sc.art("calendar_page", 0.48, 0.53, 0.88, 0.88, anims=[pop(0, 12)])
    page = Placed("calendar_page", 0.48, 0.53, 0.88, 0.88)
    cx, cy, w, h = page.box(18, 46, 110, 110)
    if art:
        # On the header's top corner, clear of the words.
        ax, ay = page.pt(112, 14)
        sc.art(art, min(ax, 0.86), max(ay, 0.13), 0.24, 0.24, rotation=12, anims=[pop(12, 12), A("wiggle", 0, 30)])
        still = False
    else:
        still = True
    sc.text(sample, cx, cy, w * 0.94, h * 0.9, fill=pal.stroke, stroke=None, size=(24, 110),
            chars=kw.get("chars", (1, 24)), lines=2, shadow=None, anims=[pop(6, 12)])
    return sc, still


def a_mug(sample, pal, **kw):
    sc = Scene(60)
    sc.art("steam", 0.42, 0.17, 0.34, 0.26, opacity=0.9, anims=[A("float", 0, 60), A("twinkle", 0, 60, strength=0.4)])
    sc.art("mug", 0.5, 0.6, 0.84, 0.72, anims=[pop(0, 12)])
    mug = Placed("mug", 0.5, 0.6, 0.84, 0.72)
    cx, cy, w, h = mug.box(20, 46, 92, 116)
    sc.text(sample, cx, cy, w * 0.92, h * 0.84, fill=pal.stroke, stroke=None, size=(20, 88),
            chars=kw.get("chars", (1, 24)), lines=3, shadow=None, anims=[pop(6, 12)])
    return sc, False


def a_rainbow(sample, pal, lines=1, **kw):
    sc = Scene(60)
    sc.shape("rainbow", 0.5, 0.36, 0.92, 0.48, anims=[pop(0, 14)])
    sc.shape("cloud", 0.17, 0.53, 0.3, 0.18, fill=WHITE, shadow=SOFT_SHADOW, sd=3, sb=3, anims=[pop(8, 12), A("float", 0, 60)])
    sc.shape("cloud", 0.83, 0.53, 0.3, 0.18, fill=WHITE, shadow=SOFT_SHADOW, sd=3, sb=3, anims=[pop(10, 12), A("float", -30, 60)])
    main_text(sc, sample, 0.5, 0.76, 0.94, 0.34, pal, lines=lines, **kw)
    return sc, False


def a_stack(sample, pal, fills, **kw):
    """2 or 3 lines sliding in from alternate sides, each its own colour."""
    parts = sample.split("|")
    n = len(parts)
    sc = Scene(60)
    twinkle_set(sc, [(0.1, 0.12), (0.9, 0.14, 0.8), (0.1, 0.88, 0.8), (0.9, 0.86)], pal.accent)
    anims = [[A("slide_in", 2 + i * 6, 14, loop=False, direction=i % 2), A("wiggle", -i * 10, 30, strength=0.4)] for i in range(n)]
    main_text(sc, sample, 0.5, 0.5, 0.92, 0.3 * n, pal, lines=n, fills=fills, line_anims=anims, **kw)
    return sc, False


def a_comic(sample, pal, art=None, red=False, **kw):
    sc = Scene(60)
    sc.art("comic_burst_red" if red else "comic_burst", 0.5, 0.5, 1.0, 1.0, anims=[A("zoom_in", 0, 10, loop=False), A("pulse", 0, 30, strength=0.4)])
    if art:
        sc.art(art, 0.82, 0.82, 0.3, 0.3, anims=[pop(10, 10), A("shake", 0, 30)])
    main_text(sc, sample, 0.5, 0.5, 0.66, 0.36, pal, fills=[WHITE], stroke=c("#1E1E1E"), sw=16.0,
              anims=[A("zoom_in", 4, 10, loop=False), A("shake", 0, 30, strength=0.6)], **kw)
    return sc, False


def a_neon(sample, pal, glow, **kw):
    sc = Scene(60)
    sc.shape("rect", 0.5, 0.5, 0.96, 0.62, fill=c("#1C1733"), fill2=c("#2B2150"), corner=60.0, anims=[pop(0, 10)])
    sc.shape("rect", 0.5, 0.5, 0.9, 0.54, fill=c("#000000", 0.0), stroke=glow, sw=6.0, corner=46.0,
             shadow=glow, sd=0, sb=18, anims=[A("twinkle", 0, 60, strength=0.25)])
    sc.text(sample, 0.5, 0.5, 0.8, 0.42, fill=c("#FFFFFF"), stroke=glow, sw=8.0, shadow=glow, sd=0, sb=22,
            size=(28, 120), chars=kw.get("chars", (1, 30)), lines=2, font=PUFF,
            anims=[A("fade_in", 0, 10, loop=False), A("twinkle", 0, 60, strength=0.15)])
    return sc, False


def a_badge(sample, pal, art="rosette_blue", **kw):
    sc = Scene(120)
    sc.shape("rays", 0.5, 0.36, 0.74, 0.74, fill=pal.bg2, points=14, opacity=0.8, anims=[spin_for(120, 14, 1)])
    sc.art(art, 0.5, 0.36, 0.48, 0.52, anims=[pop(0, 12), A("swing", 0, 60, strength=0.4)])
    main_text(sc, sample, 0.5, 0.79, 0.94, 0.3, pal, **kw)
    return sc, False


def a_cloud(sample, pal, art=None, rain=False, **kw):
    sc = Scene(60)
    sc.shape("cloud", 0.5, 0.48, 0.98, 0.66, fill=WHITE, fill2=c("#E3F0FF"), shadow=SOFT_SHADOW, sd=6, sb=6,
             anims=[pop(0, 12), A("float", 0, 60)])
    if rain:
        for i, x in enumerate((0.3, 0.5, 0.7)):
            sc.shape("drop", x, 0.86, 0.05, 0.08, fill=c("#4FA3F7"), anims=[A("float", -i * 10, 30, strength=1.6), A("twinkle", -i * 10, 30)])
    if art:
        sc.art(art, 0.83, 0.18, 0.24, 0.24, anims=[pop(10, 12), A("float", -20, 60)])
    main_text(sc, sample, 0.5, 0.52, 0.7, 0.36, pal, fills=[pal.ink], stroke=WHITE, sw=6.0, shadow=None,
              anims=[pop(6, 12), A("float", 0, 60)], **kw)
    return sc, False


def a_reaction(sample, pal, art, motion="shake", lines=1, extra=None, **kw):
    sc = Scene(60)
    loops = {"shake": [A("shake", 0, 30)], "rock": [A("swing", 0, 30, strength=0.8)], "jelly": [A("jelly", 0, 30)],
             "spin": [A("spin", 0, 60)], "bounce": [A("bounce", 0, 30)], "pulse": [A("pulse", 0, 30, strength=1.4)],
             "wiggle": [A("wiggle", 0, 30)], "float": [A("float", 0, 60)], "heartbeat": [A("heartbeat", 0, 30)]}[motion]
    if extra == "tears":
        sc.art("sweat_drops", 0.16, 0.22, 0.2, 0.2, anims=[A("twinkle", 0, 30)])
    elif extra == "lines":
        sc.art("motion_lines", 0.14, 0.4, 0.22, 0.18, anims=[A("twinkle", 0, 15)])
        sc.art("motion_lines", 0.86, 0.4, 0.22, 0.18, flip=True, anims=[A("twinkle", -7, 15)])
    elif extra == "sparkles":
        twinkle_set(sc, [(0.14, 0.14), (0.86, 0.16, 0.8), (0.12, 0.5, 0.6), (0.88, 0.52, 0.7)], pal.accent)
    elif extra == "hearts":
        float_hearts(sc, [(0.14, 0.18, 1), (0.88, 0.14, 0.8), (0.1, 0.52, 0.7), (0.9, 0.5, 0.9)], [c("#FF3D71"), c("#FF8FB1")])
    sc.art(art, 0.5, 0.37, 0.64, 0.64, anims=[pop(0, 12)] + loops)
    main_text(sc, sample, 0.5, 0.8, 0.96, 0.3, pal, lines=lines, anims=[A("slide_in", 6, 12, loop=False, direction=3)], **kw)
    return sc, False


def a_pill(sample, pal, art=None, **kw):
    sc = Scene(60)
    sc.shape("rect", 0.5, 0.5, 0.96, 0.4, fill=pal.bg2, fill2=pal.stroke, corner=102.0, shadow=SOFT_SHADOW, sd=6, sb=6,
             anims=[A("zoom_in", 0, 10, loop=False)])
    x = 0.5
    w = 0.86
    if art:
        sc.art(art, 0.14, 0.5, 0.3, 0.3, rotation=-10, anims=[pop(8, 12), A("wiggle", 0, 30)])
        x, w = 0.58, 0.7
    sc.text(sample, x, 0.5, w, 0.28, fill=WHITE, stroke=None, shadow=c("#000000", 0.2), sd=3, sb=2,
            size=(24, 96), chars=kw.get("chars", (1, 28)), lines=2, anims=[pop(4, 12)])
    return sc, art is None


def a_sticky(sample, pal, color="#FFE37A", **kw):
    sc = Scene(45)
    sc.shape("rect", 0.5, 0.53, 0.82, 0.8, fill=c(color), fill2=c("#FFD34D") if color == "#FFE37A" else None,
             rotation=-4, shadow=SOFT_SHADOW, sd=8, sb=8, anims=[pop(0, 12)])
    sc.art("tape", 0.5, 0.15, 0.38, 0.14, rotation=-6, anims=[pop(8, 10)])
    sc.text(sample, 0.5, 0.55, 0.7, 0.66, fill=c("#3A3A3A"), stroke=None, shadow=None, font=PUFF_REGULAR,
            case="keep", size=(26, 92), chars=kw.get("chars", (1, 60)), lines=5, rotation=-4,
            anims=[A("typewriter", 10, 20, loop=False)])
    return sc, True


def a_stamp(sample, pal, color, **kw):
    sc = Scene(30)
    col = c(color)
    sc.shape("rect", 0.5, 0.5, 0.86, 0.42, fill=c("#FFFFFF", 0.0), stroke=col, sw=12.0, corner=24.0, rotation=-10,
             anims=[A("zoom_in", 0, 8, loop=False)])
    sc.shape("rect", 0.5, 0.5, 0.78, 0.33, fill=c("#FFFFFF", 0.0), stroke=col, sw=4.0, corner=14.0, rotation=-10,
             anims=[A("zoom_in", 0, 8, loop=False)])
    sc.text(sample, 0.5, 0.5, 0.7, 0.28, fill=col, stroke=None, shadow=None, size=(24, 140), font=ANTON,
            chars=kw.get("chars", (1, 18)), lines=1, rotation=-10, tracking=40.0,
            anims=[A("zoom_in", 0, 8, loop=False)])
    return sc, True


def a_lightning(sample, pal, **kw):
    sc = Scene(60)
    sc.shape("lightning", 0.14, 0.3, 0.3, 0.52, fill=c("#FFD600"), stroke=c("#E65100"), sw=6.0, rotation=-14,
             anims=[pop(6, 10), A("twinkle", 0, 20)])
    sc.shape("lightning", 0.86, 0.7, 0.3, 0.52, fill=c("#FFD600"), stroke=c("#E65100"), sw=6.0, rotation=14,
             anims=[pop(10, 10), A("twinkle", -10, 20)])
    main_text(sc, sample, 0.5, 0.5, 0.76, 0.46, pal, anims=[A("zoom_in", 0, 10, loop=False), A("shake", 0, 20, strength=0.5)], **kw)
    return sc, False


def a_sunrise(sample, pal, art="sun_face", lines=1, **kw):
    sc = Scene(120)
    sc.shape("rays", 0.5, 0.32, 0.78, 0.78, fill=c("#FFD54F"), points=16, opacity=0.85, anims=[spin_for(120, 16, 1)])
    sc.art(art, 0.5, 0.32, 0.42, 0.42, anims=[pop(0, 14), A("float", 0, 60)])
    main_text(sc, sample, 0.5, 0.78, 0.94, 0.34, pal, lines=lines, **kw)
    return sc, False


def a_night(sample, pal, lines=1, art="moon_stars", **kw):
    sc = Scene(60)
    twinkle_set(sc, [(0.1, 0.1), (0.32, 0.06, 0.6), (0.9, 0.12, 0.9), (0.94, 0.44, 0.6), (0.06, 0.46, 0.7),
                     (0.62, 0.1, 0.5)], c("#FFE57F"), period=30, size=0.07)
    sc.art(art, 0.5, 0.29, 0.44, 0.44, anims=[pop(0, 12), A("swing", 0, 60, strength=0.5)])
    if art != "zzz":
        sc.art("zzz", 0.78, 0.18, 0.18, 0.18, anims=[A("float", 0, 60), A("twinkle", 0, 60, strength=0.5)])
    main_text(sc, sample, 0.5, 0.75, 0.94, 0.38, pal, lines=lines, **kw)
    return sc, False


def a_doodle(sample, pal, doodle="doodle_underline", art=None, **kw):
    sc = Scene(45)
    sc.text(sample, 0.5, 0.44, 0.86, 0.46, fill=pal.ink, stroke=WHITE, sw=10.0, shadow=SOFT_SHADOW, sd=3, sb=3,
            font=PUFF, case=kw.pop("case", "keep"), size=(30, 130), chars=kw.get("chars", (1, 28)), lines=2,
            anims=[A("typewriter", 0, 16, loop=False)])
    sc.art(doodle, 0.5, 0.76, 0.7, 0.2, anims=[A("fade_in", 14, 12, loop=False)])
    if art:
        sc.art(art, 0.85, 0.16, 0.24, 0.24, rotation=12, anims=[pop(18, 10)])
    return sc, True


def a_confetti(sample, pal, art="party_popper", lines=1, **kw):
    sc = Scene(60)
    sc.shape("confetti", 0.5, 0.42, 0.98, 0.8, points=12, palette=CONFETTI, anims=[A("float", 0, 60, strength=1.5), A("twinkle", -15, 30, strength=0.4)])
    sc.art(art, 0.18, 0.8, 0.3, 0.3, anims=[pop(0, 10), A("wiggle", 0, 30)])
    sc.art(art, 0.82, 0.8, 0.3, 0.3, flip=True, anims=[pop(4, 10), A("wiggle", -15, 30)])
    main_text(sc, sample, 0.5, 0.44, 0.92, 0.48, pal, lines=lines, **kw)
    return sc, False


def a_cake(sample, pal, art="cake", lines=1, **kw):
    sc = Scene(60)
    sc.art("balloon", 0.12, 0.26, 0.2, 0.36, rotation=-10, anims=[A("float", 0, 60), A("swing", 0, 60, strength=0.4)])
    sc.art("balloon", 0.88, 0.22, 0.2, 0.36, rotation=10, anims=[A("float", -30, 60), A("swing", -30, 60, strength=0.4)])
    sc.art(art, 0.5, 0.33, 0.5, 0.5, anims=[pop(0, 12), A("jelly", 0, 30, strength=0.5)])
    twinkle_set(sc, [(0.32, 0.08, 0.6), (0.68, 0.1, 0.7)], pal.accent, size=0.07)
    main_text(sc, sample, 0.5, 0.78, 0.96, 0.36, pal, lines=lines, **kw)
    return sc, False


def a_balloons(sample, pal, lines=1, **kw):
    sc = Scene(60)
    for i, (x, y, r) in enumerate([(0.2, 0.26, -12), (0.5, 0.2, 0), (0.8, 0.26, 12)]):
        sc.art("balloon", x, y, 0.28, 0.42, rotation=r, anims=[A("drop_in", i * 4, 18, loop=False, strength=-1.0), A("float", -i * 20, 60), A("swing", -i * 20, 60, strength=0.4)])
    main_text(sc, sample, 0.5, 0.72, 0.94, 0.4, pal, lines=lines, **kw)
    return sc, False


def a_frame_hearts(sample, pal, lines=1, **kw):
    """A ring of small hearts round the words."""
    sc = Scene(60)
    import math
    n = 12
    for i in range(n):
        t = 2 * math.pi * i / n
        x = 0.5 + 0.4 * math.cos(t)
        y = 0.5 + 0.38 * math.sin(t)
        sc.shape("heart", x, y, 0.1, 0.09, fill=pal.stroke if i % 2 else pal.bg2,
                 anims=[pop(i * 2, 10), A("pulse", -i * 5, 30, strength=1.2)])
    main_text(sc, sample, 0.5, 0.5, 0.66, 0.42, pal, lines=lines, **kw)
    return sc, False


def a_flowers(sample, pal, art="bouquet", lines=1, **kw):
    sc = Scene(60)
    sc.shape("ellipse", 0.5, 0.42, 0.86, 0.8, fill=pal.bg, opacity=0.9, anims=[pop(0, 12)])
    sc.art(art, 0.5, 0.36, 0.56, 0.56, anims=[pop(4, 12), A("swing", 0, 60, strength=0.5)])
    twinkle_set(sc, [(0.16, 0.16), (0.84, 0.14, 0.8), (0.86, 0.56, 0.6)], pal.accent)
    main_text(sc, sample, 0.5, 0.79, 0.96, 0.32, pal, lines=lines, **kw)
    return sc, False


def a_trophy(sample, pal, art="trophy", **kw):
    sc = Scene(60)
    sc.shape("confetti", 0.5, 0.36, 0.96, 0.66, points=10, palette=CONFETTI, anims=[A("float", 0, 60, strength=1.3)])
    sc.art("laurel", 0.5, 0.36, 0.62, 0.6, anims=[pop(0, 12)])
    sc.art(art, 0.5, 0.36, 0.34, 0.36, anims=[pop(6, 12), A("bounce", 0, 30)])
    main_text(sc, sample, 0.5, 0.8, 0.96, 0.3, pal, **kw)
    return sc, False


ARCHETYPES = {
    "burst": a_burst, "art_top": a_art_top, "corners": a_corners, "banner": a_banner, "bubble": a_bubble,
    "heart": a_heart, "sign": a_sign, "calendar": a_calendar, "mug": a_mug, "rainbow": a_rainbow,
    "stack": a_stack, "comic": a_comic, "neon": a_neon, "badge": a_badge, "cloud": a_cloud,
    "reaction": a_reaction, "pill": a_pill, "sticky": a_sticky, "stamp": a_stamp, "lightning": a_lightning,
    "sunrise": a_sunrise, "night": a_night, "doodle": a_doodle, "confetti": a_confetti, "cake": a_cake,
    "balloons": a_balloons, "frame_hearts": a_frame_hearts, "flowers": a_flowers, "trophy": a_trophy,
}

# ---- the pack ------------------------------------------------------------------------

TEMPLATES: list[dict] = []
SCENES: dict[str, dict] = {}


def split_fits(words, rules):
    """TextFit.splitLines' yes or no: can [words] be laid over len(rules) lines inside their character ranges."""
    n = len(rules)
    if not words or n == 0:
        return False
    if n == 1:
        length = len(" ".join(words))
        lo, hi = rules[0]
        return lo <= length <= max(lo, hi)
    if len(words) < n:
        return False

    def go(start, line):
        if line == n - 1:
            length = len(" ".join(words[start:]))
            return rules[line][0] <= length <= rules[line][1]
        for end in range(start + 1, len(words) - (n - 1 - line) + 1):
            length = len(" ".join(words[start:end]))
            if rules[line][0] <= length <= rules[line][1] and go(end, line + 1):
                return True
        return False

    return go(0, 0)


DROPPED: list[str] = []


def register(tid, name, scene, still, kind="sticker", phrases=(), concepts=(), generic=False, max_words=0, alt=""):
    if tid in SCENES:
        raise ValueError(f"duplicate template {tid}")
    main = next((s for s in slots_of(scene) if s["id"] == "main"), None)
    if main is not None:
        rules = [(r.get("minChars", 1), r.get("maxChars", 60)) for r in main["lines"]]
        kept = []
        for p in dict.fromkeys(phrases):
            if split_fits(p.split(), rules):
                kept.append(p)
            else:
                DROPPED.append(f"{tid}: {p!r}")
        phrases = kept
    SCENES[tid] = scene.doc()
    TEMPLATES.append({
        "id": tid, "name": name, "kind": kind, "still": still, "scene": scene,
        "phrases": list(dict.fromkeys(phrases)), "concepts": list(dict.fromkeys(concepts)),
        "generic": generic, "maxWords": max_words, "alt": alt,
    })


def T(tid, name, arch, sample, pal, phrases, concepts=(), alt="", max_words=0, **kw):
    """A phrase template: archetype [arch] drawn with [sample] in palette [pal]."""
    scene, still = ARCHETYPES[arch](sample, PAL[pal], **kw)
    register(tid, name, scene, still, phrases=phrases, concepts=concepts, alt=alt, max_words=max_words)


# Phrase groups. Bengali in Bengali script and as people type it in Latin
# letters; DynText.normalize drops apostrophes, so "can't" is "cant".
THANKS = ["thanks", "thank you", "thank u", "thanks a lot", "thanks so much", "thank you so much", "thx", "ty",
          "tysm", "tnx", "thanx", "many thanks", "thankyou", "ধন্যবাদ", "dhonnobad", "dhonnobaad", "dhonyobad",
          "thanks re", "অনেক ধন্যবাদ", "onek dhonnobad"]
THANKS_2W = [p for p in THANKS if " " in p]
WELCOME = ["youre welcome", "you are welcome", "welcome", "anytime", "my pleasure", "ur welcome", "yw"]
MORNING = ["good morning", "morning", "gm", "good mrng", "gud morning", "goodmorning", "শুভ সকাল", "shubho shokal",
           "subho sokal", "suprobhat", "সুপ্রভাত", "rise and shine"]
AFTERNOON = ["good afternoon", "afternoon", "শুভ দুপুর", "shubho dupur"]
EVENING = ["good evening", "evening", "শুভ সন্ধ্যা", "shubho shondha", "subho sondhya"]
NIGHT = ["good night", "goodnight", "gn", "night night", "nighty night", "gud night", "শুভ রাত্রি", "shubho ratri",
         "subho ratri", "শুভ রাত", "shubho raat"]
SLEEP = ["sleep well", "sweet dreams", "sleep tight", "nighty night", "go to sleep", "ঘুমাও", "ghumao"]
BIRTHDAY = ["happy birthday", "hbd", "happy bday", "happy birthday to you", "bday", "birthday", "শুভ জন্মদিন",
            "shubho jonmodin", "subho jonmodin", "shuvo jonmodin", "many happy returns", "hb"]
ANNIVERSARY = ["happy anniversary", "anniversary", "শুভ বিবাহবার্ষিকী", "shubho bibaho barshiki"]
LOVE = ["love you", "i love you", "ily", "love u", "luv u", "luv you", "i love u", "love ya", "ভালোবাসি",
        "bhalobashi", "valobashi", "valobasi", "ami tomake bhalobashi", "আমি তোমাকে ভালোবাসি"]
LOVE_TOO = ["love you too", "love u too", "i love you too", "ily too", "ily2", "me too", "আমিও ভালোবাসি", "amio bhalobashi"]
MISS = ["miss you", "i miss you", "miss u", "missing you", "miss ya", "তোমাকে মিস করছি", "miss korchi", "mone porche"]
HUGS = ["hugs", "hug", "sending hugs", "big hug", "virtual hug", "hugs and kisses"]
KISSES = ["kisses", "kiss", "xoxo", "mwah", "muah", "kisses for you"]
SORRY = ["sorry", "im sorry", "so sorry", "my bad", "i am sorry", "sry", "soz", "apologies", "দুঃখিত", "dukkhito",
         "sorry re", "maaf koro", "মাফ করো"]
CONGRATS = ["congrats", "congratulations", "congratz", "congrats to you", "অভিনন্দন", "ovinondon", "abhinandan",
            "obhinondon", "well deserved"]
PROUD = ["proud of you", "so proud of you", "im proud of you", "so proud", "proud"]
GOOD_JOB = ["good job", "great job", "well done", "nice work", "good work", "nicely done", "bravo", "শাবাশ", "shabash"]
LUCK = ["good luck", "best of luck", "all the best", "fingers crossed", "শুভকামনা", "shubhokamona", "best wishes"]
OMW = ["on my way", "omw", "coming", "on the way", "be right there", "আসছি", "ashchi", "aschi", "asteci"]
CALL = ["call me", "call me back", "give me a call", "ring me", "ফোন দাও", "phone dao", "call dio"]
WHERE = ["where are you", "where r u", "wya", "where you at", "kothay tumi", "কোথায় তুমি", "koi tumi", "kothay"]
HERE = ["im here", "i am here", "here", "im outside", "here now", "পৌঁছে গেছি", "pouche gechi"]
LOL = ["lol", "lmao", "lmfao", "rofl", "haha", "hahaha", "hehe", "lolol", "so funny", "হাহা", "hasi pacche"]
OMG = ["omg", "oh my god", "oh my", "omfg", "oh my gosh", "oh no", "হায় আল্লাহ", "hay allah", "omg omg"]
WOW = ["wow", "woah", "whoa", "wowww", "wow wow", "অসাধারণ", "oshadharon"]
YAY = ["yay", "yaay", "woohoo", "hooray", "yippee", "lets go", "yayy"]
YIKES = ["yikes", "eek", "eep", "awkward", "cringe"]
OOPS = ["oops", "whoops", "oopsie", "oopsies", "my mistake"]
UGH = ["ugh", "ughh", "ugh fine", "so annoying", "annoying"]
MEH = ["meh", "whatever", "idc", "i dont care", "if you say so"]
HMM = ["hmm", "hmmm", "let me think", "thinking", "interesting", "hm"]
SAME = ["same", "me too", "same here", "relatable", "mood"]
DITTO = ["ditto", "agreed", "true", "exactly", "facts"]
NICE = ["nice", "noice", "very nice", "nice one", "niiice"]
COOL = ["cool", "very cool", "cool cool", "so cool", "kool", "chill"]
AWESOME = ["awesome", "epic", "lit", "fire", "so good", "dope", "darun", "দারুণ"]
AMAZING = ["amazing", "incredible", "fantastic", "brilliant", "unbelievable", "অসাধারণ"]
PERFECT = ["perfect", "flawless", "spot on", "just right", "chef's kiss"]
GREAT = ["great", "thats great", "sounds great", "sounds good", "good", "excellent", "bhalo", "ভালো"]
YES = ["yes", "yep", "yeah", "yup", "yas", "yesss", "absolutely", "হ্যাঁ", "ha", "hae", "ji"]
NO = ["no", "nope", "nah", "no thanks", "never", "না", "na", "nah bro"]
MAYBE = ["maybe", "perhaps", "possibly", "we will see", "might", "hoyto", "হয়তো"]
SURE = ["sure", "of course", "ofc", "sure thing", "definitely", "obviously", "obossoi", "অবশ্যই"]
OKAY = ["okay", "ok", "okie", "okk", "okay okay", "alright", "aight", "ঠিক আছে", "thik ache", "thik ase", "accha", "আচ্ছা"]
K = ["k", "kk", "kay", "mk"]
NO_WORRIES = ["no worries", "dont worry", "its fine", "all good", "its okay", "chinta koro na", "চিন্তা করো না"]
NO_PROBLEM = ["no problem", "np", "no prob", "no probs", "সমস্যা নেই", "shomossha nei", "problem nai"]
CHEERS = ["cheers", "cheers mate", "bottoms up", "toast", "to us"]
FEEL_BETTER = ["feel better", "get well soon", "get well", "feel better soon", "take rest", "সুস্থ হয়ে ওঠো", "shustho hoye otho"]
CANT_WAIT = ["cant wait", "can not wait", "so excited", "excited", "counting down"]
HUNGRY = ["hungry", "im hungry", "starving", "so hungry", "lets eat", "খিদে পেয়েছে", "khide peyeche", "khida lagse"]
COFFEE = ["coffee", "coffee time", "need coffee", "but first coffee", "coffee break", "tea time", "cha", "চা"]
BREAKFAST = ["breakfast", "brunch", "breakfast time", "nasta", "নাস্তা"]
DINNER = ["dinner", "lunch", "dinner time", "lunch time", "food", "khabar", "খাবার"]
BUSY = ["busy", "so busy", "in a meeting", "cant talk", "working", "busy rn", "ব্যস্ত", "bebosto"]
BORED = ["bored", "so bored", "im bored", "boring", "nothing to do"]
WAIT_WHAT = ["wait what", "what", "wut", "huh", "excuse me", "ki", "কি"]
NO_WAY = ["no way", "nooo", "no way bro", "impossible", "shut up"]
FOR_REAL = ["for real", "fr", "seriously", "srsly", "deadass", "for real tho", "sotti", "সত্যি"]
REALLY = ["really", "rly", "really really", "are you serious", "sotti naki", "সত্যি নাকি"]
HI = ["hi", "hello", "hey", "hii", "heyy", "hey there", "hiya", "hola", "হ্যালো", "assalamualaikum", "salam"]
BYE = ["bye", "goodbye", "see you", "see ya", "cya", "bye bye", "later", "বিদায়", "biday", "take care bye"]
NEW_YEAR = ["happy new year", "new year", "hny", "শুভ নববর্ষ", "shubho noboborsho", "subho noboborsho"]
EID = ["eid mubarak", "ঈদ মোবারক", "eid mobarak", "happy eid"]
TAKE_CARE = ["take care", "stay safe", "be safe", "নিজের যত্ন নিও", "take care of yourself"]
PLEASE = ["please", "pls", "plz", "pretty please", "pleaseee", "please please"]
GOT_THIS = ["you got this", "you can do it", "believe in yourself", "go for it", "keep going"]
LATER = ["brb", "ttyl", "talk later", "be right back", "talk to you later", "pore kotha bolbo"]
LETS_GO = ["lets go", "lets gooo", "lesgo", "lfg", "here we go", "cholo", "চলো"]
BFF = ["bff", "best friend", "besties", "bestie", "friends forever", "dost", "বন্ধু", "bondhu"]
YOU_ROCK = ["you rock", "you're the best", "youre the best", "legend", "you rule"]
SLAY = ["slay", "queen", "king", "iconic", "slayed"]
BRUH = ["bruh", "bro", "dude", "bruh moment", "bhai", "ভাই"]
WEEKEND = ["weekend", "happy weekend", "its the weekend", "weekend vibes", "weekend mode"]
DAYS = {
    "monday": ["monday", "happy monday", "monday mood", "সোমবার", "shombar"],
    "tuesday": ["tuesday", "happy tuesday", "মঙ্গলবার", "mongolbar"],
    "wednesday": ["wednesday", "happy wednesday", "hump day", "বুধবার", "budhbar"],
    "thursday": ["thursday", "happy thursday", "বৃহস্পতিবার", "brihoshpotibar"],
    "friday": ["friday", "happy friday", "tgif", "jumma mubarak", "শুক্রবার", "shukrobar"],
    "saturday": ["saturday", "happy saturday", "শনিবার", "shonibar"],
    "sunday": ["sunday", "happy sunday", "sunday funday", "রবিবার", "robibar"],
}


def build_phrases():
    # thanks
    T("thanks_burst", "Thanks", "burst", "Thanks", "pink", THANKS, ["heart", "pleading_face"], "Thanks in bubble letters on a spinning burst", art="heart_red")
    T("thanks_pray", "Thank you, hands together", "art_top", "Thank you", "sun", THANKS, ["pleading_face"], "Thank you under praying hands", art="pray", motion="pulse", extra="sparkles")
    T("thanks_flowers", "Thank you, flowers", "flowers", "Thank you", "rose", THANKS, ["flower", "rose"], "Thank you under a bouquet", art="bouquet")
    T("thank_you_hearts", "Thank you, hearts", "heart", "Thank|you", "pink", THANKS_2W, ["heart"], "Thank you on a beating heart", lines=2, size=(30, 120))
    T("thanks_banner", "Thanks banner", "banner", "Thank you", "fire", THANKS, ["celebration"], "Thank you on a ribbon banner with confetti")
    T("thanks_stack", "Thank you so much", "stack", "Thank|you so|much", "lilac", ["thank you so much", "thanks so much", "thank you very much", "thanks a lot"], ["heart"], "Thank you so much, a line at a time", fills=[c("#FF5C8A"), c("#FFC83D"), c("#3EC1F3")], stroke=c("#3A2A63"), size=(30, 110))
    T("thanks_neon", "Thanks, neon", "neon", "Thanks", "night", THANKS, ["awesome"], "Thanks in neon lights", glow=c("#FF4FD8"))
    # welcome
    T("welcome_pill", "You're welcome", "pill", "You're welcome", "mint", WELCOME, ["smiling_blush"], "You're welcome on a mint pill", art="face_smile")
    T("welcome_bubble", "Anytime", "bubble", "Anytime!", "sky", WELCOME, ["smiling_blush"], "Anytime in a speech bubble", art="face_hug")
    # mornings
    T("morning_sunrise", "Good morning sun", "sunrise", "Good morning", "sun", MORNING, ["sun"], "Good morning under a smiling sun with turning rays", art="sun_face")
    T("morning_mug", "Good morning mug", "mug", "Good morning", "choco", MORNING, ["coffee", "sun"], "Good morning written on a steaming mug")
    T("morning_flower", "Good morning flower", "stack", "Good|morning", "sun", [p for p in MORNING if " " in p], ["sun", "flower"], "Good morning in two bright lines", fills=[c("#FFE14D"), c("#FFFFFF")], stroke=c("#F2641D"), size=(30, 120))
    T("morning_sunflower", "Morning sunflower", "flowers", "Morning!", "sun", MORNING, ["sun", "flower"], "Morning under a sunflower", art="sunflower")
    T("morning_coffee_corners", "Rise and shine", "corners", "Good morning", "orange", MORNING, ["sun", "coffee"], "Good morning between a sun and a coffee", arts=["sun", "coffee"], motion="wiggle")
    T("afternoon", "Good afternoon", "art_top", "Good afternoon", "orange", AFTERNOON, ["sun"], "Good afternoon under a sun behind a cloud", art="sun_cloud", motion="float")
    T("evening_sky", "Good evening", "art_top", "Good evening", "grape", EVENING, ["relieved"], "Good evening under the crescent moon", art="moon", motion="swing", extra="sparkles")
    T("evening_tea", "Evening tea", "corners", "Good evening", "choco", EVENING, ["coffee"], "Good evening between tea and a cookie", arts=["tea", "cookie"])
    # nights
    T("night_moon", "Good night moon", "night", "Good night", "night", NIGHT, ["sigh"], "Good night under a moon and twinkling stars")
    T("night_sleepy", "Good night, sleepy", "reaction", "Good night", "night", NIGHT, ["sigh"], "Good night with a sleeping face", art="face_sleeping", motion="float")
    T("night_two_lines", "Good night stars", "stack", "Good|night", "night", [p for p in NIGHT if " " in p], ["sigh"], "Good night in two lines with stars", fills=[c("#FFF4BF"), c("#C9D2FF")], stroke=c("#1A237E"), size=(30, 130))
    T("night_cloud", "Night cloud", "cloud", "Good night", "night", NIGHT, ["sigh"], "Good night on a floating cloud", art="moon_face")
    T("sleep_well", "Sleep well", "night", "Sleep well", "grape", SLEEP, ["sigh"], "Sleep well under the moon", art="moon_face")
    T("sweet_dreams", "Sweet dreams", "cloud", "Sweet dreams", "lilac", SLEEP + ["sweet dreams"], ["sigh"], "Sweet dreams on a cloud", art="zzz")
    # birthdays
    T("bday_cake", "Birthday cake", "cake", "Happy birthday", "pink", BIRTHDAY, ["birthday", "cake", "celebration"], "Happy birthday under a cake and balloons")
    T("bday_balloons", "Birthday balloons", "balloons", "Happy birthday", "sky", BIRTHDAY, ["birthday", "balloon"], "Happy birthday under three balloons")
    T("bday_banner", "Birthday banner", "banner", "Happy birthday", "fire", BIRTHDAY, ["birthday", "celebration"], "Happy birthday on a ribbon banner under a cake", top_art="cupcake")
    T("bday_confetti", "Birthday confetti", "confetti", "Happy|birthday", "sun", [p for p in BIRTHDAY if " " in p], ["birthday", "celebration"], "Happy birthday with party poppers and confetti", lines=2, fills=[c("#FF5C8A"), c("#3EC1F3")], stroke=WHITE, sw=14.0, size=(30, 120))
    T("bday_party_face", "Birthday party face", "reaction", "Happy birthday!", "grape", BIRTHDAY, ["birthday", "celebration"], "Happy birthday with a party face", art="party_face", motion="rock", extra="sparkles")
    T("bday_gift", "Birthday gift", "corners", "Happy birthday", "berry", BIRTHDAY, ["birthday", "gift"], "Happy birthday between a gift and a balloon", arts=["gift", "balloon"], motion="bounce")
    T("anniversary_ring", "Anniversary", "frame_hearts", "Happy anniversary", "rose", ANNIVERSARY, ["heart", "celebration"], "Happy anniversary in a ring of hearts")
    T("anniversary_cheers", "Anniversary cheers", "art_top", "Happy anniversary", "gold", ANNIVERSARY, ["celebration"], "Happy anniversary under clinking glasses", art="clinking_glasses", motion="swing", extra="sparkles")
    # weekdays
    day_art = {"monday": "coffee", "tuesday": "taco", "wednesday": "face_upside_down", "thursday": "light_bulb",
               "friday": "party_popper", "saturday": "beach", "sunday": "pancakes"}
    day_pal = {"monday": "sky", "tuesday": "orange", "wednesday": "mint", "thursday": "grape", "friday": "fire",
               "saturday": "teal", "sunday": "sun"}
    for day, phrases in DAYS.items():
        T(f"day_{day}", day.title(), "calendar", f"Happy {day.title()}", day_pal[day], phrases,
          ["celebration"] if day in ("friday", "saturday") else [], f"Happy {day.title()} on a calendar page",
          art=day_art[day])
    T("weekend_vibes", "Weekend", "art_top", "Weekend!", "teal", WEEKEND, ["celebration", "cool"], "Weekend under a beach", art="beach", motion="float", extra="sparkles")
    T("weekend_dance", "Weekend dance", "reaction", "Happy weekend", "berry", WEEKEND, ["celebration"], "Happy weekend with a dancer", art="dancer", motion="rock")
    # love
    T("love_heart", "Love you heart", "heart", "Love you", "rose", LOVE, ["heart", "heart_eyes"], "Love you on a beating heart")
    T("love_eyes", "Love you, heart eyes", "reaction", "Love you", "pink", LOVE, ["heart_eyes", "heart"], "Love you with a heart-eyed face", art="face_heart_eyes", motion="heartbeat", extra="hearts")
    T("love_three_lines", "I love you", "stack", "I|love|you", "rose", ["i love you", "i love u", "ami tomake bhalobashi"], ["heart"], "I love you, a word at a time", fills=[c("#FFFFFF"), c("#FF3D71"), c("#FFFFFF")], stroke=c("#9F1239"), size=(30, 120))
    T("love_hearts_ring", "Love you ring", "frame_hearts", "Love you", "pink", LOVE, ["heart"], "Love you in a ring of hearts")
    T("love_doodle", "Love you doodle", "doodle", "love you", "rose", LOVE, ["heart"], "Love you handwritten with a doodled heart", doodle="doodle_heart", case="keep")
    T("love_you_too", "Love you too", "art_top", "Love you too", "berry", LOVE_TOO, ["heart"], "Love you too under two hearts", art="hearts_two", motion="heartbeat", extra="hearts")
    T("miss_you_cloud", "Miss you", "cloud", "Miss you", "lilac", MISS, ["pleading_face", "heart"], "Miss you on a cloud with a pleading face", art="face_pleading")
    T("miss_you_heart", "Miss you heart", "art_top", "Miss you", "rose", MISS, ["heart", "crying"], "Miss you under a heart with an arrow", art="heart_arrow", motion="swing", extra="hearts")
    T("hugs_face", "Hugs", "reaction", "Hugs!", "orange", HUGS, ["hug"], "Hugs with a hugging face", art="face_hug", motion="jelly", extra="hearts")
    T("hugs_bear", "Bear hug", "art_top", "Sending hugs", "choco", HUGS, ["hug", "heart"], "Sending hugs under a teddy bear", art="teddy_bear", motion="jelly", extra="hearts")
    T("kisses", "Kisses", "corners", "Kisses", "rose", KISSES, ["blow_kiss", "heart"], "Kisses between kiss marks", arts=["kiss_mark", "face_kiss"], motion="pulse")
    # sorry
    T("sorry_pleading", "Sorry", "reaction", "Sorry", "lilac", SORRY, ["pleading_face", "displeasure"], "Sorry with a pleading face", art="face_pleading", motion="float")
    T("sorry_rain", "Sorry cloud", "cloud", "I'm sorry", "sky", SORRY, ["crying", "displeasure"], "I'm sorry on a raining cloud", rain=True)
    T("sorry_flowers", "Sorry flowers", "flowers", "Sorry", "coral", SORRY, ["flower", "pleading_face"], "Sorry under a tulip", art="tulip")
    T("my_bad", "My bad", "doodle", "my bad", "coral", ["my bad", "my mistake", "oops my bad", "sorry my bad"], ["facepalm"], "My bad handwritten with a sweat drop", doodle="doodle_underline", art="face_sweat_smile")
    # congrats
    T("congrats_trophy", "Congrats trophy", "trophy", "Congrats!", "gold", CONGRATS, ["congratulations", "celebration"], "Congrats under a trophy in a laurel wreath")
    T("congrats_confetti", "Congratulations", "confetti", "Congratulations!", "sun", CONGRATS, ["congratulations", "celebration"], "Congratulations with party poppers and confetti", art="party_popper", size=(28, 120))
    T("congrats_banner", "Congrats banner", "banner", "Congrats", "fire", CONGRATS, ["congratulations"], "Congrats on a ribbon with a medal", top_art="medal")
    T("proud_of_you", "Proud of you", "badge", "Proud of you", "sky", PROUD, ["congratulations", "heart"], "Proud of you under a rosette", art="rosette_blue")
    T("good_job_thumbs", "Good job", "art_top", "Good job!", "lime", GOOD_JOB, ["congratulations", "awesome"], "Good job under a thumbs up", art="thumbs_up", motion="bounce", extra="sparkles")
    T("well_done_clap", "Well done", "reaction", "Well done", "orange", GOOD_JOB, ["congratulations"], "Well done with clapping hands", art="clap", motion="pulse", extra="lines")
    T("good_luck_clover", "Good luck clover", "art_top", "Good luck", "lime", LUCK, ["good_luck"], "Good luck under a four-leaf clover", art="clover", motion="spin", extra="sparkles")
    T("good_luck_fingers", "Fingers crossed", "corners", "Good luck!", "mint", LUCK, ["good_luck"], "Good luck between crossed fingers and a star", arts=["crossed_fingers", "star_glowing"])
    # on the way, calls
    T("omw_car", "On my way", "art_top", "On my way", "sky", OMW, [], "On my way under a car", art="car", motion="shake", extra=None)
    T("omw_runner", "On my way, running", "reaction", "Coming!", "orange", OMW, [], "Coming with a runner and speed lines", art="runner", motion="bounce", extra="lines")
    T("call_me", "Call me", "corners", "Call me", "teal", CALL, ["waving"], "Call me between two phones", arts=["telephone", "phone"], motion="wiggle")
    T("where_are_you", "Where are you", "bubble", "Where are you?", "coral", WHERE, ["thinking"], "Where are you in a speech bubble with a pin", art="pin")
    T("im_here", "I'm here", "art_top", "I'm here!", "berry", HERE, ["waving"], "I'm here under a map pin", art="pin", motion="bounce")
    # reactions
    T("lol_joy", "LOL", "reaction", "LOL", "sun", LOL, ["laugh", "laughing_crying"], "LOL with a face crying with laughter", art="face_joy", motion="shake", extra="tears")
    T("lol_rofl", "Rolling laughing", "reaction", "Hahaha", "orange", LOL, ["laugh", "laughing_crying"], "Hahaha with a face rolling on the floor", art="face_rofl", motion="rock", extra="lines")
    T("lol_comic", "LOL comic", "comic", "LOL", "sun", LOL, ["laugh"], "LOL on a comic burst", art="face_joy")
    T("lmao_cat", "LMAO cat", "reaction", "LMAO", "grape", LOL, ["laugh"], "LMAO with a cat crying with laughter", art="cat_joy", motion="jelly", extra="tears")
    T("omg_comic", "OMG", "comic", "OMG", "fire", OMG, ["explosion", "embarrassed"], "OMG on a red comic burst", art="face_scream", red=True)
    T("omg_lightning", "OMG lightning", "lightning", "OMG!", "sun", OMG, ["explosion"], "OMG between lightning bolts")
    T("wow_stars", "Wow", "reaction", "WOW", "grape", WOW, ["starstruck", "awesome"], "Wow with a star-struck face", art="face_star_struck", motion="pulse", extra="sparkles")
    T("wow_comic", "Wow comic", "comic", "WOW!", "sky", WOW, ["starstruck"], "Wow on a comic burst", art="face_open_mouth")
    T("yay_party", "Yay", "confetti", "Yay!", "pink", YAY, ["celebration"], "Yay with party poppers and confetti")
    T("yay_hands", "Hooray", "art_top", "Hooray!", "sun", YAY, ["celebration", "awesome"], "Hooray under raised hands", art="raised_hands", motion="bounce", extra="confetti")
    T("yikes", "Yikes", "reaction", "Yikes", "teal", YIKES, ["embarrassed"], "Yikes with a grimacing face", art="face_grimace", motion="shake")
    T("oops", "Oops", "reaction", "Oops!", "coral", OOPS, ["embarrassed", "quiet"], "Oops with a hand over the mouth", art="face_hand_mouth", motion="wiggle")
    T("ugh", "Ugh", "reaction", "Ugh", "ink", UGH, ["annoyed", "sigh"], "Ugh with a weary face", art="face_weary", motion="shake")
    T("meh", "Meh", "doodle", "meh", "ink", MEH, ["whatever", "eye_roll"], "Meh handwritten with a neutral face", doodle="zigzag", art="face_neutral")
    T("hmm", "Hmm", "reaction", "Hmm...", "grape", HMM, ["thinking"], "Hmm with a thinking face", art="face_thinking", motion="rock")
    T("same", "Same", "doodle", "SAME", "coral", SAME, ["relieved"], "Same, underlined", doodle="doodle_underline", art="raising_hand", case="upper")
    T("ditto", "Ditto", "stamp", "DITTO", "ink", DITTO, [], "Ditto stamped", color="#7B3FE4")
    T("nice", "Nice", "art_top", "Nice!", "lime", NICE, ["awesome", "cool"], "Nice under an OK hand", art="ok_hand", motion="wiggle", extra="sparkles")
    T("cool_shades", "Cool", "reaction", "Cool", "sky", COOL, ["cool"], "Cool with a face in sunglasses", art="face_cool", motion="rock", extra="sparkles")
    T("cool_ice", "Cool cool", "pill", "Cool cool cool", "teal", COOL, ["cool"], "Cool cool cool on a pill with a snowflake", art="snowflake")
    T("awesome_fire", "Awesome", "art_top", "Awesome!", "fire", AWESOME, ["awesome"], "Awesome under a flame", art="fire", motion="jelly", extra="sparkles")
    T("awesome_100", "100", "burst", "Awesome", "fire", AWESOME, ["awesome"], "Awesome on a burst with a hundred points", art="hundred")
    T("amazing", "Amazing", "rainbow", "Amazing", "grape", AMAZING, ["starstruck", "awesome"], "Amazing under a rainbow")
    T("perfect", "Perfect", "art_top", "Perfect", "mint", PERFECT, ["awesome"], "Perfect under an OK hand", art="ok_hand", motion="pulse", extra="sparkles")
    T("great_thumbs", "Great", "reaction", "Great!", "sky", GREAT, ["awesome"], "Great with a thumbs up", art="thumbs_up", motion="bounce", extra="sparkles")
    # answers
    T("yes_check", "Yes", "art_top", "YES!", "lime", YES, ["awesome"], "Yes under a check mark", art="check_mark", motion="bounce")
    T("yes_stamp", "Yes stamp", "stamp", "YES", "lime", YES, [], "Yes stamped in green", color="#2E9E44")
    T("no_cross", "No", "art_top", "NO", "fire", NO, ["displeasure"], "No under a cross mark", art="cross_mark", motion="shake")
    T("nope_stamp", "Nope", "stamp", "NOPE", "fire", NO, ["displeasure"], "Nope stamped in red", color="#D62839")
    T("maybe", "Maybe", "reaction", "Maybe?", "lilac", MAYBE, ["thinking"], "Maybe with a raised eyebrow", art="face_raised_brow", motion="rock")
    T("sure", "Sure", "pill", "Sure!", "sky", SURE, ["awesome"], "Sure on a blue pill with a thumbs up", art="thumbs_up")
    T("okay_hand", "Okay", "art_top", "Okay", "orange", OKAY, ["awesome"], "Okay under an OK hand", art="ok_hand", motion="wiggle")
    T("okay_bubble", "OK bubble", "bubble", "OK!", "mint", OKAY, [], "OK in a speech bubble", art="face_smile")
    T("k", "K", "stamp", "K.", "ink", K, ["whatever"], "K stamped in black", color="#2B2B2B")
    T("no_worries", "No worries", "cloud", "No worries", "sky", NO_WORRIES, ["relieved"], "No worries on a cloud", art="face_relieved")
    T("no_problem", "No problem", "pill", "No problem", "lime", NO_PROBLEM, ["relieved"], "No problem on a green pill", art="victory")
    T("cheers", "Cheers", "art_top", "Cheers!", "gold", CHEERS, ["celebration"], "Cheers under clinking glasses", art="clinking_glasses", motion="swing", extra="sparkles")
    T("cheers_beer", "Cheers mugs", "corners", "Cheers", "orange", CHEERS, ["celebration"], "Cheers between drinks", arts=["beers", "tropical_drink"], motion="swing")
    T("feel_better", "Feel better", "art_top", "Feel better soon", "mint", FEEL_BETTER, ["hug", "heart"], "Feel better soon under a bandaged face", art="face_bandage", motion="float", extra="hearts")
    T("get_well", "Get well soon", "flowers", "Get well soon", "lilac", FEEL_BETTER, ["flower", "heart"], "Get well soon under a bouquet", art="bouquet")
    T("cant_wait", "Can't wait", "stack", "Can't|wait!", "fire", CANT_WAIT, ["celebration", "starstruck"], "Can't wait in two jumping lines", fills=[c("#FFF1A8"), c("#FFFFFF")], stroke=c("#E8312A"), size=(30, 130))
    # food and moods
    T("hungry_pizza", "Hungry", "reaction", "Hungry!", "orange", HUNGRY, ["yum"], "Hungry with a slice of pizza", art="pizza", motion="wiggle", extra="sparkles")
    T("hungry_drool", "Starving", "corners", "Starving", "fire", HUNGRY, ["yum"], "Starving between a burger and a drooling face", arts=["burger", "face_drool"], motion="bounce")
    T("coffee_time", "Coffee time", "mug", "Coffee time", "choco", COFFEE, ["coffee"], "Coffee time on a steaming mug")
    T("coffee_first", "But first, coffee", "art_top", "But first, coffee", "choco", COFFEE, ["coffee"], "But first, coffee under a coffee cup", art="coffee", motion="float")
    T("breakfast", "Breakfast", "corners", "Breakfast!", "sun", BREAKFAST, ["yum"], "Breakfast between pancakes and a fried egg", arts=["pancakes", "cooking"], motion="wiggle")
    T("dinner", "Dinner time", "art_top", "Dinner time", "coral", DINNER, ["yum"], "Dinner time under a plate", art="noodles", motion="bounce", extra="sparkles")
    T("busy", "Busy", "sign", "Busy", "choco", BUSY, [], "Busy on a wooden sign", art="laptop")
    T("bored", "Bored", "reaction", "So bored", "ink", BORED, ["sigh", "whatever"], "So bored with a yawning face", art="face_yawn", motion="float")
    T("wait_what", "Wait what", "reaction", "Wait, what?", "sky", WAIT_WHAT, ["thinking", "explosion"], "Wait, what? with a raised eyebrow", art="face_raised_brow", motion="shake", extra="lines")
    T("no_way", "No way", "comic", "No way!", "fire", NO_WAY, ["explosion", "starstruck"], "No way on a comic burst", art="face_exploding", red=True)
    T("for_real", "For real", "reaction", "For real?", "teal", FOR_REAL, ["thinking"], "For real? with a face with a raised eyebrow", art="eyes", motion="shake")
    T("really", "Really?", "bubble", "Really?!", "grape", REALLY, ["thinking", "eye_roll"], "Really?! in a speech bubble", art="face_eye_roll")
    # greetings
    T("hi_wave", "Hi", "art_top", "Hi!", "sun", HI, ["waving"], "Hi under a waving hand", art="wave", motion="swing", extra="sparkles")
    T("hello_bubble", "Hello", "bubble", "Hello!", "sky", HI, ["waving", "grinning"], "Hello in a speech bubble", art="face_grin")
    T("hey_neon", "Hey neon", "neon", "Hey!", "night", HI, ["waving"], "Hey in neon lights", glow=c("#3EF1FF"))
    T("bye_wave", "Bye", "reaction", "Bye!", "coral", BYE, ["waving"], "Bye with a waving hand", art="wave", motion="rock")
    T("see_you", "See you", "art_top", "See you soon", "teal", BYE, ["waving"], "See you soon under a paper plane", art="airplane", motion="float")
    T("new_year", "Happy new year", "confetti", "Happy new year", "gold", NEW_YEAR, ["celebration"], "Happy new year with poppers and confetti", art="champagne")
    T("eid_mubarak", "Eid Mubarak", "night", "Eid Mubarak", "gold", EID, ["celebration"], "Eid Mubarak under a crescent moon and stars", art="moon")
    T("noboborsho", "Shubho Noboborsho", "flowers", "Shubho Noboborsho", "fire", NEW_YEAR, ["celebration", "flower"], "Shubho Noboborsho under hibiscus", art="hibiscus")
    T("take_care", "Take care", "heart", "Take care", "mint", TAKE_CARE, ["heart", "hug"], "Take care on a heart", art="face_hug")
    T("please", "Please", "reaction", "Please?", "lilac", PLEASE, ["pleading_face"], "Please with a pleading face", art="face_pleading", motion="pulse", extra="sparkles")
    T("you_got_this", "You got this", "art_top", "You got this!", "orange", GOT_THIS, ["awesome", "good_luck"], "You got this under a flexed arm", art="muscle", motion="pulse", extra="sparkles")
    T("brb", "BRB", "bubble", "BRB", "teal", LATER, [], "BRB typed into a speech bubble", art="alarm_clock", typewriter=True)
    T("lets_go", "Let's go", "art_top", "Let's go!", "fire", LETS_GO, ["celebration", "awesome"], "Let's go under a rocket", art="rocket", motion="shake", extra="sparkles")
    T("bff", "BFF", "frame_hearts", "BFF", "lilac", BFF, ["heart", "hug"], "BFF in a ring of hearts")
    T("you_rock", "You rock", "art_top", "You rock!", "grape", YOU_ROCK, ["awesome", "cool"], "You rock under a rock-on hand", art="rock_on", motion="wiggle", extra="sparkles")
    T("slay_crown", "Slay", "art_top", "Slay", "gold", SLAY, ["crown", "awesome"], "Slay under a crown", art="crown", motion="float", extra="sparkles")
    T("bruh", "Bruh", "reaction", "Bruh", "ink", BRUH, ["facepalm", "whatever"], "Bruh with someone facepalming", art="facepalm", motion="rock")
    # more of the common ones
    T("thanks_sticky", "Thanks note", "sticky", "Thank you!", "sun", THANKS, ["heart"], "Thank you written on a sticky note")
    T("morning_sticky", "Morning note", "sticky", "Good morning!", "sun", MORNING, ["sun"], "Good morning on a sticky note", color="#FFD6E7")
    T("love_neon", "Love neon", "neon", "Love you", "rose", LOVE, ["heart"], "Love you in pink neon", glow=c("#FF3D71"))
    T("night_neon", "Good night neon", "neon", "Good night", "night", NIGHT, ["sigh"], "Good night in blue neon", glow=c("#6C8CFF"))
    T("bday_neon", "Birthday neon", "neon", "Happy birthday", "pink", BIRTHDAY, ["birthday"], "Happy birthday in neon lights", glow=c("#FFD23F"))
    T("congrats_pill", "Congrats pill", "pill", "Congrats!", "gold", CONGRATS, ["congratulations"], "Congrats on a gold pill with a party popper", art="party_popper")
    T("sorry_sticky", "Sorry note", "sticky", "I'm sorry", "sky", SORRY, ["pleading_face"], "I'm sorry on a blue sticky note", color="#CFE8FF")
    T("lol_stack", "Hahaha lines", "stack", "Ha|ha|ha", "sun", ["ha ha ha", "lol lol lol", "haha haha haha"], ["laugh"], "Ha ha ha in three bouncing lines", fills=[c("#FFE14D"), c("#FF8A3D"), c("#FF5C8A")], stroke=c("#5B2A00"), size=(30, 120))
    T("ok_sign", "OK sign", "sign", "OK", "choco", OKAY, [], "OK on a wooden sign", art="ok_hand")
    T("miss_you_letter", "Miss you letter", "corners", "Miss you", "pink", MISS, ["heart"], "Miss you between a love letter and a heart", arts=["love_letter", "heart_growing"], motion="float")
    T("hi_bubble_cat", "Hey cat", "bubble", "Hey!", "pink", HI, ["waving"], "Hey in a speech bubble with a cat", art="cat_smile")
    T("yes_burst", "Yes burst", "burst", "Yes!", "lime", YES, ["awesome"], "Yes on a spinning burst", art="check_mark")
    T("wow_rainbow", "Wow rainbow", "rainbow", "Wow!", "sky", WOW, ["starstruck"], "Wow under a rainbow")
    T("cool_corners", "Cool shades", "corners", "So cool", "teal", COOL, ["cool"], "So cool between sunglasses and ice cream", arts=["face_cool", "ice_cream"], motion="swing")
    T("good_night_bear", "Night bear", "art_top", "Good night", "night", NIGHT, ["sigh"], "Good night under a teddy bear", art="teddy_bear", motion="float", extra="sparkles")
    T("hbd_short", "HBD", "burst", "HBD!", "pink", ["hbd", "happy bday", "bday"], ["birthday"], "HBD on a spinning burst", art="cake")
    T("love_you_sign", "Love sign", "sign", "Love you", "choco", LOVE, ["heart"], "Love you on a wooden sign", art="heart_red")
    T("thanks_comic", "Thanks comic", "comic", "Thanks!", "sun", THANKS, ["awesome"], "Thanks on a comic burst", art="thumbs_up")


# ---- generic templates -------------------------------------------------------------


def build_generic():
    sc, still = a_pill("Your text", PAL["grape"])
    register("any_pill", "Pill", sc, still, generic=True, alt="Any text on a gradient pill")
    sc, still = a_burst("Your text", PAL["pink"], spin=True, chars=(1, 40))
    register("any_burst", "Bubble letters", sc, still, generic=True, alt="Any text in bubble letters on a spinning burst")
    sc, still = a_bubble("Your text", PAL["sky"], chars=(1, 48))
    register("any_bubble", "Speech bubble", sc, still, generic=True, alt="Any text in a speech bubble")
    sc = Scene(60)
    twinkle_set(sc, [(0.12, 0.2), (0.88, 0.22, 0.8), (0.14, 0.8, 0.7), (0.86, 0.78)], c("#FFD23F"))
    sc.text("Your text", 0.5, 0.5, 0.92, 0.6, fill=WHITE, stroke=c("#2B2B2B"), sw=14.0, size=(30, 140), chars=(1, 40),
            lines=3, anims=[pop(0, 12), A("rainbow", 0, 60)])
    register("any_rainbow", "Rainbow letters", sc, False, generic=True, alt="Any text in colour-changing letters")
    sc, still = a_sticky("Your text", PAL["sun"], chars=(1, 60))
    register("any_sticky", "Sticky note", sc, still, generic=True, alt="Any text written on a sticky note")
    sc, still = a_neon("Your text", PAL["night"], glow=c("#3EF1FF"), chars=(1, 36))
    register("any_neon", "Neon", sc, still, generic=True, alt="Any text in neon lights")


# ---- memes ---------------------------------------------------------------------------

MEME_FILL = WHITE
MEME_STROKE = BLACK


def meme_text(sc, sample, x, y, w, h, *, slot, role, label, size=(22, 72), chars=(1, 80), lines=3, valign=1,
              fill=MEME_FILL, stroke=MEME_STROKE, sw=8.0, font=ANTON, case="upper", align=2, shadow=None, rotation=0.0):
    return sc.text(sample, x, y, w, h, slot=slot, role=role, label=label, font=font, size=size, chars=chars,
                   lines=lines, fill=fill, stroke=stroke, sw=sw, shadow=shadow, case=case, valign=valign, align=align,
                   anims=[], rotation=rotation)


def placeholder(sc, x, y, w, h, corner=0.0, rotation=0.0):
    """What a photo slot shows before a picture is chosen: a grey panel with a camera."""
    sc.shape("rect", x, y, w, h, fill=c("#CFD8DC"), fill2=c("#B0BEC5"), corner=corner, rotation=rotation)
    sc.art("camera", x, y, min(w, h) * 0.32, min(w, h) * 0.32, opacity=0.55, rotation=rotation)


def meme(tid, name, sc, alt, phrases=(), concepts=(), max_words=0):
    # Generic: a meme format takes whatever the message says. The matcher
    # still keeps memes out unless they are asked for (Options.memes).
    register(tid, name, sc, True, kind="meme", phrases=phrases, concepts=concepts, generic=True,
             max_words=max_words, alt=alt)


def build_memes():
    # Photo formats ------------------------------------------------------------
    sc = Scene(1)
    placeholder(sc, 0.5, 0.5, 1.0, 1.0)
    sc.photo("photo", 0.5, 0.5, 1.0, 1.0)
    meme_text(sc, "Top text", 0.5, 0.13, 0.94, 0.22, slot="top", role="top", label="Top text", valign=0)
    # No sample: a message without a "|" fills the top alone rather than leaving placeholder words below.
    meme_text(sc, "", 0.5, 0.87, 0.94, 0.22, slot="bottom", role="bottom", label="Bottom text", valign=2)
    meme("meme_impact", "Top and bottom", sc, "A photo with white outlined words at the top and bottom")

    sc = Scene(1)
    sc.shape("rect", 0.5, 0.5, 1.0, 1.0, fill=WHITE)
    placeholder(sc, 0.5, 0.64, 1.0, 0.72)
    sc.photo("photo", 0.5, 0.64, 1.0, 0.72)
    meme_text(sc, "When the caption goes above", 0.5, 0.14, 0.92, 0.24, slot="caption", role="caption", label="Caption",
              font=SANS_MEDIUM, fill=c("#111111"), stroke=None, sw=0, case="keep", size=(18, 48), lines=4)
    meme("meme_caption_above", "Caption above", sc, "A white caption bar above a photo")

    sc = Scene(1)
    sc.shape("rect", 0.5, 0.5, 1.0, 1.0, fill=WHITE)
    placeholder(sc, 0.5, 0.36, 1.0, 0.72)
    sc.photo("photo", 0.5, 0.36, 1.0, 0.72)
    meme_text(sc, "And the caption below", 0.5, 0.86, 0.92, 0.24, slot="caption", role="caption", label="Caption",
              font=SANS_MEDIUM, fill=c("#111111"), stroke=None, sw=0, case="keep", size=(18, 48), lines=4)
    meme("meme_caption_below", "Caption below", sc, "A photo with a white caption bar below it")

    sc = Scene(1)
    placeholder(sc, 0.5, 0.5, 1.0, 1.0)
    sc.photo("photo", 0.5, 0.5, 1.0, 1.0)
    meme_text(sc, "This", 0.24, 0.2, 0.42, 0.16, slot="label1", role="label", label="First label", size=(18, 56), lines=2)
    meme_text(sc, "That", 0.76, 0.46, 0.42, 0.16, slot="label2", role="label", label="Second label", size=(18, 56), lines=2)
    meme_text(sc, "Me", 0.3, 0.8, 0.42, 0.16, slot="label3", role="label", label="Third label", size=(18, 56), lines=2)
    meme("meme_labels", "Labels", sc, "A photo with three labels placed over it")

    sc = Scene(1)
    sc.shape("rect", 0.5, 0.5, 1.0, 1.0, fill=WHITE)
    placeholder(sc, 0.5, 0.62, 1.0, 0.76)
    sc.photo("photo", 0.5, 0.62, 1.0, 0.76)
    sc.words("POV:", 0.11, 0.12, 0.16, 0.12, font=SANS_BLACK, fill=c("#111111"), case="keep", size=(18, 44), align=0)
    meme_text(sc, "you open the group chat", 0.605, 0.12, 0.73, 0.2, slot="caption", role="caption", label="Caption",
              font=SANS_MEDIUM, fill=c("#111111"), stroke=None, sw=0, case="keep", size=(18, 44), lines=3, align=0)
    meme("meme_pov", "POV", sc, "A POV caption above a photo")

    sc = Scene(1)
    sc.shape("rect", 0.5, 0.5, 1.0, 1.0, fill=c("#111111"))
    placeholder(sc, 0.255, 0.58, 0.49, 0.76)
    sc.photo("before", 0.255, 0.58, 0.49, 0.76, label="Before")
    placeholder(sc, 0.745, 0.58, 0.49, 0.76)
    sc.photo("after", 0.745, 0.58, 0.49, 0.76, label="After")
    meme_text(sc, "Before", 0.255, 0.1, 0.46, 0.14, slot="before_label", role="label", label="Left", size=(18, 54), lines=1)
    meme_text(sc, "After", 0.745, 0.1, 0.46, 0.14, slot="after_label", role="label", label="Right", size=(18, 54), lines=1)
    meme("meme_before_after", "Before and after", sc, "Two photos side by side under Before and After")

    sc = Scene(1)
    sc.shape("rect", 0.5, 0.5, 1.0, 1.0, fill=WHITE)
    sc.shape("rect", 0.5, 0.5, 1.0, 0.006, fill=c("#DDDDDD"))
    placeholder(sc, 0.25, 0.25, 0.5, 0.5)
    sc.photo("top_photo", 0.25, 0.25, 0.5, 0.5, label="Top photo")
    placeholder(sc, 0.25, 0.75, 0.5, 0.5)
    sc.photo("bottom_photo", 0.25, 0.75, 0.5, 0.5, label="Bottom photo")
    meme_text(sc, "Not this", 0.75, 0.25, 0.44, 0.42, slot="top", role="top", label="Top text", font=SANS_BLACK,
              fill=c("#111111"), stroke=None, sw=0, case="keep", size=(18, 54), lines=4)
    meme_text(sc, "This", 0.75, 0.75, 0.44, 0.42, slot="bottom", role="bottom", label="Bottom text", font=SANS_BLACK,
              fill=c("#111111"), stroke=None, sw=0, case="keep", size=(18, 54), lines=4)
    meme("meme_two_panel", "Two panels", sc, "Two photos stacked, each with words beside it")

    sc = Scene(1)
    sc.shape("rect", 0.5, 0.5, 1.0, 1.0, fill=WHITE)
    placeholder(sc, 0.255, 0.5, 0.49, 0.72)
    sc.photo("left", 0.255, 0.5, 0.49, 0.72, label="Left photo")
    placeholder(sc, 0.745, 0.5, 0.49, 0.72)
    sc.photo("right", 0.745, 0.5, 0.49, 0.72, label="Right photo")
    meme_text(sc, "Me", 0.255, 0.92, 0.46, 0.12, slot="left_label", role="label", label="Left", font=SANS_BLACK,
              fill=c("#111111"), stroke=None, sw=0, case="keep", size=(18, 48), lines=1)
    meme_text(sc, "Also me", 0.745, 0.92, 0.46, 0.12, slot="right_label", role="label", label="Right", font=SANS_BLACK,
              fill=c("#111111"), stroke=None, sw=0, case="keep", size=(18, 48), lines=1)
    meme("meme_me_also_me", "Me / also me", sc, "Two photos labelled Me and Also me")

    sc = Scene(1)
    pol = Placed("polaroid", 0.5, 0.5, 0.92, 0.96)
    sc.art("polaroid", 0.5, 0.5, 0.92, 0.96, rotation=-3)
    px, py, pw, ph = pol.box(20, 14, 108, 94)
    placeholder(sc, px, py, pw, ph, rotation=-3)
    sc.photo("photo", px, py, pw, ph, rotation=-3)
    cx, cy, cw, ch = pol.box(16, 96, 112, 124)
    meme_text(sc, "best day ever", cx, cy, cw * 0.92, ch * 0.9, slot="caption", role="caption", label="Caption",
              font=PUFF_REGULAR, fill=c("#2B2B2B"), stroke=None, sw=0, case="keep", size=(18, 52), lines=2, rotation=-3)
    sc.art("tape", 0.5, 0.06, 0.34, 0.12, rotation=4)
    meme("meme_polaroid", "Polaroid", sc, "A photo in an instant-print frame with a handwritten caption")

    # Text-only formats ----------------------------------------------------------
    sc = Scene(1)
    sc.shape("rect", 0.5, 0.5, 0.98, 0.86, fill=WHITE, corner=34.0, stroke=c("#E1E8ED"), sw=3.0)
    sc.art("avatar", 0.15, 0.21, 0.17, 0.17)
    meme_text(sc, "Your Name", 0.49, 0.18, 0.46, 0.08, slot="name", role="name", label="Name", font=SANS_BLACK,
              fill=c("#0F1419"), stroke=None, sw=0, case="keep", size=(16, 36), lines=1, align=0, chars=(1, 30))
    sc.art("verified_badge", 0.77, 0.18, 0.06, 0.06)
    meme_text(sc, "@yourhandle", 0.46, 0.25, 0.4, 0.06, slot="handle", role="label", label="Handle", font=SANS,
              fill=c("#536471"), stroke=None, sw=0, case="keep", size=(14, 28), lines=1, align=0, chars=(1, 30))
    meme_text(sc, "just typed this and it already looks like a post", 0.5, 0.55, 0.86, 0.44, slot="text",
              role="main", label="Post", font=SANS, fill=c("#0F1419"), stroke=None, sw=0, case="keep",
              size=(18, 56), lines=6, align=0, valign=0, chars=(1, 200))
    sc.words("12:00 · Today", 0.27, 0.86, 0.4, 0.05, font=SANS, fill=c("#536471"), case="keep", size=(12, 22), align=0)
    meme("text_post", "Post card", sc, "A social post card with a name, handle and the text")

    sc = Scene(1)
    sc.shape("rect", 0.5, 0.5, 0.98, 0.9, fill=c("#2B2150"), fill2=c("#5B3FA0"), corner=40.0)
    sc.art("quote_marks", 0.18, 0.2, 0.2, 0.18)
    meme_text(sc, "Every day is a fresh start", 0.5, 0.48, 0.8, 0.44, slot="text", role="main", label="Quote",
              font=SERIF, fill=WHITE, stroke=None, sw=0, case="keep", size=(22, 56), lines=6, chars=(1, 160))
    meme_text(sc, "— Someone wise", 0.62, 0.8, 0.6, 0.08, slot="name", role="name", label="Who said it",
              font=SANS_MEDIUM, fill=c("#FFD23F"), stroke=None, sw=0, case="keep", size=(14, 30), lines=1, align=1, chars=(1, 40))
    meme("text_quote", "Quote card", sc, "A quote in white on a purple card with its author")

    sc = Scene(1)
    sc.shape("bubble", 0.39, 0.27, 0.74, 0.38, fill=c("#E9E9EB"), flip=False)
    meme_text(sc, "you up?", 0.39, 0.24, 0.64, 0.22, slot="them", role="label", label="Their message", font=SANS,
              fill=c("#111111"), stroke=None, sw=0, case="keep", size=(18, 60), lines=3, chars=(1, 80))
    sc.shape("bubble", 0.61, 0.73, 0.74, 0.38, fill=c("#1E88FF"), flip=True)
    meme_text(sc, "no", 0.61, 0.7, 0.64, 0.22, slot="text", role="main", label="Your reply", font=SANS,
              fill=WHITE, stroke=None, sw=0, case="keep", size=(18, 60), lines=3, chars=(1, 80))
    meme("text_chat", "Chat bubbles", sc, "A message and a reply in chat bubbles")

    sc = Scene(1)
    sc.shape("rect", 0.5, 0.5, 1.0, 0.86, fill=c("#0B1F3A"), corner=18.0)
    sc.art("news_globe", 0.14, 0.24, 0.16, 0.16)
    sc.shape("rect", 0.56, 0.24, 0.72, 0.12, fill=c("#D7262E"))
    sc.words("BREAKING NEWS", 0.56, 0.24, 0.66, 0.09, font=ANTON, fill=WHITE, size=(16, 44), tracking=60.0)
    sc.shape("rect", 0.5, 0.56, 0.92, 0.36, fill=WHITE)
    meme_text(sc, "Local person types a message", 0.5, 0.56, 0.86, 0.32, slot="text", role="main", label="Headline",
              font=ANTON, fill=c("#111111"), stroke=None, sw=0, case="upper", size=(20, 60), lines=3, chars=(1, 90))
    sc.shape("rect", 0.5, 0.84, 1.0, 0.08, fill=c("#FFD23F"))
    sc.words("LIVE  •  MORE AT ELEVEN", 0.5, 0.84, 0.9, 0.06, font=ANTON, fill=c("#111111"), size=(12, 26))
    meme("text_news", "News headline", sc, "Your words as a breaking news headline")

    sc = Scene(1)
    sc.shape("rect", 0.5, 0.5, 0.98, 0.7, fill=WHITE, corner=28.0, stroke=c("#E5E5E5"), sw=3.0)
    sc.words("Nobody:", 0.3, 0.27, 0.46, 0.1, font=SANS_MEDIUM, fill=c("#111111"), case="keep", size=(16, 44), align=0)
    sc.words("Me:", 0.17, 0.5, 0.2, 0.1, font=SANS_MEDIUM, fill=c("#111111"), case="keep", size=(16, 44), align=0)
    meme_text(sc, "typing this at 3am", 0.58, 0.58, 0.62, 0.3, slot="text", role="main", label="Me", font=SANS_MEDIUM,
              fill=c("#111111"), stroke=None, sw=0, case="keep", size=(16, 44), lines=4, align=0, valign=0, chars=(1, 90))
    meme("text_nobody_me", "Nobody / me", sc, "Nobody: and Me: with your words")

    sc = Scene(1)
    sc.shape("rect", 0.5, 0.5, 0.96, 0.96, fill=c("#FFF8E7"), corner=30.0)
    meme_text(sc, "Top 3 things", 0.5, 0.14, 0.86, 0.14, slot="title", role="main", label="Title", font=ANTON,
              fill=c("#E8590C"), stroke=None, sw=0, size=(20, 56), lines=1, chars=(1, 40))
    for i, (sample, color) in enumerate((("Coffee", "#FFD23F"), ("Naps", "#C0C0C0"), ("Memes", "#E3A06B"))):
        y = 0.36 + i * 0.22
        sc.shape("ellipse", 0.13, y, 0.12, 0.12, fill=c(color))
        sc.words(str(i + 1), 0.13, y, 0.1, 0.1, font=ANTON, fill=c("#3A2A00"), size=(16, 40))
        meme_text(sc, sample, 0.58, y, 0.72, 0.16, slot=f"item{i + 1}", role="label", label=f"Number {i + 1}",
                  font=SANS_BLACK, fill=c("#222222"), stroke=None, sw=0, case="keep", size=(16, 44), lines=2,
                  align=0, chars=(1, 50))
    meme("text_top3", "Top 3", sc, "A top three list with a title")

    sc = Scene(1)
    sc.shape("rect", 0.25, 0.5, 0.48, 0.96, fill=c("#E6F4FF"), corner=24.0)
    sc.shape("rect", 0.75, 0.5, 0.48, 0.96, fill=c("#FFE8E0"), corner=24.0)
    sc.words("EXPECTATION", 0.25, 0.1, 0.42, 0.08, font=ANTON, fill=c("#1B4FA8"), size=(14, 40))
    sc.words("REALITY", 0.75, 0.1, 0.42, 0.08, font=ANTON, fill=c("#C4421F"), size=(14, 40))
    sc.art("star_glowing", 0.25, 0.32, 0.2, 0.2)
    sc.art("face_melting", 0.75, 0.32, 0.2, 0.2)
    meme_text(sc, "Productive day", 0.25, 0.66, 0.42, 0.44, slot="text", role="main", label="Expectation",
              font=SANS_BLACK, fill=c("#1B2A44"), stroke=None, sw=0, case="keep", size=(16, 48), lines=5, chars=(1, 70))
    meme_text(sc, "Nap", 0.75, 0.66, 0.42, 0.44, slot="reality", role="bottom", label="Reality",
              font=SANS_BLACK, fill=c("#44201B"), stroke=None, sw=0, case="keep", size=(16, 48), lines=5, chars=(1, 70))
    meme("text_expectation", "Expectation / reality", sc, "Expectation and reality side by side")

    sc = Scene(1)
    sc.art("warning_sign", 0.5, 0.27, 0.46, 0.42)
    sc.shape("rect", 0.5, 0.6, 0.94, 0.12, fill=c("#FFC400"), corner=10.0)
    sc.words("WARNING", 0.5, 0.6, 0.6, 0.1, font=ANTON, fill=c("#111111"), size=(16, 54), tracking=80.0)
    meme_text(sc, "May start talking about cats at any moment", 0.5, 0.82, 0.92, 0.26, slot="text", role="main",
              label="Warning", font=ANTON, fill=c("#111111"), stroke=c("#FFFFFF"), sw=6.0, size=(16, 48), lines=3,
              chars=(1, 90))
    meme("text_warning", "Warning sign", sc, "A warning sign with your words")

    sc = Scene(1)
    sc.shape("rect", 0.5, 0.5, 1.0, 0.8, fill=c("#FFFBEF"), corner=8.0)
    sc.shape("rect", 0.5, 0.5, 0.94, 0.74, fill=c("#FFFFFF", 0.0), stroke=c("#C9A227"), sw=6.0, corner=6.0)
    sc.shape("rect", 0.5, 0.5, 0.9, 0.7, fill=c("#FFFFFF", 0.0), stroke=c("#C9A227"), sw=2.0, corner=4.0)
    sc.words("CERTIFICATE", 0.5, 0.2, 0.7, 0.08, font=SERIF, fill=c("#7A5B00"), size=(16, 44), tracking=120.0)
    sc.words("of achievement", 0.5, 0.27, 0.5, 0.05, font=SERIF, fill=c("#7A5B00"), case="keep", size=(12, 26))
    sc.words("proudly presented to", 0.5, 0.35, 0.5, 0.04, font=SANS, fill=c("#6B6B6B"), case="keep", size=(10, 20))
    meme_text(sc, "Your Name", 0.5, 0.44, 0.76, 0.1, slot="name", role="name", label="Name", font=PUFF,
              fill=c("#1F2A44"), stroke=None, sw=0, case="keep", size=(18, 52), lines=1, chars=(1, 30))
    sc.shape("rect", 0.5, 0.5, 0.56, 0.004, fill=c("#C9A227"))
    meme_text(sc, "for replying within a minute", 0.5, 0.6, 0.72, 0.12, slot="text", role="main", label="For",
              font=SERIF, fill=c("#3A3A3A"), stroke=None, sw=0, case="keep", size=(14, 32), lines=2, chars=(1, 80))
    sc.art("seal_gold", 0.8, 0.74, 0.2, 0.22)
    sc.art("laurel", 0.2, 0.74, 0.2, 0.2)
    meme("text_certificate", "Certificate", sc, "A certificate of achievement with a name and what it is for")


# ---- manifest ------------------------------------------------------------------------


def slots_of(scene: Scene):
    """The text slots SceneToLottie.compile derives from [scene], in the manifest's shape."""
    order: dict[str, list] = {}
    for e in scene.elements:
        if e["type"] == "text" and e.get("slot") is not None:
            order.setdefault(e["slot"], []).append(e)
    main = next((s for s, els in order.items() if els[0].get("role", "main") == "main"), None)
    if main is None and order:
        main = next(iter(order))
    out = []
    for slot, els in order.items():
        first = els[0]
        if slot == main:
            layers = [f"_txt_{i}" for i in range(len(els))]
        elif len(els) == 1:
            layers = [slot]
        else:
            layers = [f"{slot}_{i}" for i in range(len(els))]
        role = "main" if slot == main else (first.get("role", "main") if first.get("role", "main") != "main" else "label")
        lines = []
        for e in els:
            rule = {}
            if e.get("minChars", 1) != 1:
                rule["minChars"] = e["minChars"]
            if e.get("maxChars", 60) != 60:
                rule["maxChars"] = e["maxChars"]
            if e.get("minSize", 0):
                rule["minFont"] = e["minSize"]
            if e.get("maxSize", 0):
                rule["maxFont"] = e["maxSize"]
            lines.append(rule)
        s = {"id": "main" if slot == main else slot, "layers": layers, "lines": lines,
             "case": first.get("case", "upper")}
        if role != "main":
            s["role"] = role
        if first.get("label"):
            s["label"] = first["label"]
        sample = " ".join(e.get("text", "") for e in els)
        if sample:
            s["sample"] = sample
        out.append(s)
    return out


def photos_of(scene: Scene):
    out = []
    for e in scene.elements:
        if e["type"] != "photo":
            continue
        slot = e.get("slot") or e["id"]
        p = {"id": slot, "asset": f"photo_{slot}"}
        if e.get("label"):
            p["label"] = e["label"]
        if e.get("fit", "cover") != "cover":
            p["fit"] = e["fit"]
        out.append(p)
    return out


def manifest():
    templates = []
    for t in TEMPLATES:
        match = {}
        if t["phrases"]:
            match["phrases"] = t["phrases"]
        if t["concepts"]:
            match["concepts"] = t["concepts"]
        if t["generic"]:
            match["generic"] = True
        if t["maxWords"]:
            match["maxWords"] = t["maxWords"]
        entry = {"id": t["id"], "name": t["name"], "scene": f"scenes/{t['id']}.json"}
        if t["kind"] != "sticker":
            entry["kind"] = t["kind"]
        if match:
            entry["match"] = match
        entry["slots"] = slots_of(t["scene"])
        photos = photos_of(t["scene"])
        if photos:
            entry["photos"] = photos
        if t["alt"]:
            entry["altText"] = t["alt"]
        if t["still"]:
            entry["stillOnly"] = True
        templates.append(entry)
    return {
        "format": "wmkeyboard-dynstickers",
        "version": 1,
        "pack": {
            "id": "builtin",
            "name": "Built-in",
            "author": "WM Keyboard",
            "version": "1",
            "license": "Templates and our own drawings: MIT, like the app. Noto Emoji art: Apache-2.0.",
            "description": "Stickers that write your words, and meme formats",
            "templates": templates,
        },
    }


def main():
    build_phrases()
    build_generic()
    build_memes()
    for t in TEMPLATES:
        if not slots_of(t["scene"]):
            raise SystemExit(f"{t['id']} has no text slot")
    if os.path.isdir(os.path.join(OUT, "scenes")):
        shutil.rmtree(os.path.join(OUT, "scenes"))
    os.makedirs(os.path.join(OUT, "scenes"), exist_ok=True)
    total = 0
    for tid, doc in SCENES.items():
        text = json.dumps(doc, ensure_ascii=False, separators=(",", ":"))
        total += len(text.encode())
        with open(os.path.join(OUT, "scenes", f"{tid}.json"), "w", encoding="utf-8") as fh:
            fh.write(text + "\n")
    text = json.dumps(manifest(), ensure_ascii=False, indent=1)
    with open(os.path.join(OUT, "pack.json"), "w", encoding="utf-8") as fh:
        fh.write(text + "\n")
    kinds = {}
    for t in TEMPLATES:
        key = "meme" if t["kind"] == "meme" else ("generic" if t["generic"] else "sticker")
        kinds[key] = kinds.get(key, 0) + 1
    print(f"{len(TEMPLATES)} templates {kinds}; scenes {total:,} bytes, pack.json {len(text.encode()):,} bytes")
    if DROPPED:
        print(f"{len(DROPPED)} phrases left off templates whose lines cannot take them:")
        for d in DROPPED:
            print("  " + d)


if __name__ == "__main__":
    main()
