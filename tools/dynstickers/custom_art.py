"""Our own drawings for the art library, as SVG (128 x 128 view box).

svg_to_lottie_shapes.py converts these exactly like the Noto Emoji ones, so
they come out in the same shape items. They fill the gaps an emoji set
leaves for stickers and memes: things to write on (a ribbon banner, a wooden
sign, a calendar page, a mug), certificate parts (laurels, a seal, a
rosette), hand-drawn doodles, comic bursts, and the bits post and quote
cards are made of.

Drawn in the same manner as the emoji they sit beside: flat colour, soft
two-stop gradients, a darker shade for the side in shadow, no outlines except
on the doodles and the comic pieces, which are meant to look drawn.

ART maps art id -> SVG text. Generators build the repetitive ones (leaves
along a branch, scallops round a seal) so the numbers stay readable.
"""

from __future__ import annotations

import math


def svg(body: str, defs: str = "") -> str:
    d = f"<defs>{defs}</defs>" if defs else ""
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128">{d}{body}</svg>'


def f(v: float) -> str:
    return f"{v:.2f}".rstrip("0").rstrip(".")


def star_points(cx, cy, outer, inner, n, rot=-90.0):
    pts = []
    for i in range(n * 2):
        r = outer if i % 2 == 0 else inner
        a = math.radians(rot + i * 180.0 / n)
        pts.append(f"{f(cx + r * math.cos(a))},{f(cy + r * math.sin(a))}")
    return " ".join(pts)


def scallop_path(cx, cy, r, bumps, depth):
    """A circle with [bumps] round scallops, as a path."""
    parts = []
    for i in range(bumps):
        a0 = 2 * math.pi * i / bumps
        a1 = 2 * math.pi * (i + 1) / bumps
        am = (a0 + a1) / 2
        p0 = (cx + r * math.cos(a0), cy + r * math.sin(a0))
        p1 = (cx + r * math.cos(a1), cy + r * math.sin(a1))
        c = (cx + (r + depth) * math.cos(am), cy + (r + depth) * math.sin(am))
        if i == 0:
            parts.append(f"M{f(p0[0])},{f(p0[1])}")
        parts.append(f"Q{f(c[0])},{f(c[1])} {f(p1[0])},{f(p1[1])}")
    return "".join(parts) + "Z"


def leaf(x, y, length, width, angle, fill):
    """A pointed leaf whose base is at (x, y), pointing at [angle] degrees."""
    a = math.radians(angle)
    tip = (x + length * math.cos(a), y + length * math.sin(a))
    nx, ny = -math.sin(a), math.cos(a)
    m1 = (x + 0.5 * length * math.cos(a) + width * nx, y + 0.5 * length * math.sin(a) + width * ny)
    m2 = (x + 0.5 * length * math.cos(a) - width * nx, y + 0.5 * length * math.sin(a) - width * ny)
    return (f'<path fill="{fill}" d="M{f(x)},{f(y)}Q{f(m1[0])},{f(m1[1])} {f(tip[0])},{f(tip[1])}'
            f'Q{f(m2[0])},{f(m2[1])} {f(x)},{f(y)}Z"/>')


def laurel_branch(mirror: bool) -> str:
    """One branch of a laurel wreath, curving up the left side (or the right, mirrored)."""
    cx, cy, r = 64.0, 66.0, 48.0
    out = []
    stem = []
    steps = 11
    for i in range(steps):
        t = math.radians(98 + i * 14.0)  # from the bottom, round the left, up to the top
        stem.append((cx + r * math.cos(t), cy + r * math.sin(t)))
    d = f"M{f(stem[0][0])},{f(stem[0][1])}" + "".join(f"L{f(p[0])},{f(p[1])}" for p in stem[1:])
    out.append(f'<path fill="none" stroke="#A8800F" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round" d="{d}"/>')
    for i, (px, py) in enumerate(stem[1:], start=1):
        t = 98 + i * 14.0
        tangent = t + 90  # along the stem, towards the top
        size = 21 - i * 0.7
        light = "#F7C948" if i % 2 else "#F2B81B"
        dark = "#D99A0B" if i % 2 else "#E3A60F"
        out.append(leaf(px, py, size, 6.2, tangent - 38, dark))
        out.append(leaf(px, py, size * 0.96, 6.0, tangent + 30, light))
    tip = stem[-1]
    out.append(leaf(tip[0], tip[1], 18, 5.6, 98 + (steps - 1) * 14.0 + 90, "#F7C948"))
    g = "".join(out)
    if mirror:
        return f'<g transform="translate(128 0) scale(-1 1)">{g}</g>'
    return f"<g>{g}</g>"


ART: dict[str, str] = {}

# ---- certificate and awards -------------------------------------------------

ART["laurel"] = svg(laurel_branch(False) + laurel_branch(True))

ART["seal_gold"] = svg(
    '<path fill="#C62828" d="M44,78L30,122L44,114L52,126L62,86Z"/>'
    '<path fill="#E53935" d="M84,78L98,122L84,114L76,126L66,86Z"/>'
    f'<path fill="url(#sg)" d="{scallop_path(64, 56, 40, 20, 5)}"/>'
    '<circle cx="64" cy="56" r="31" fill="#FFF3C4"/>'
    '<circle cx="64" cy="56" r="27" fill="url(#sg2)"/>'
    f'<polygon fill="#FFF8E1" points="{star_points(64, 56, 16, 7, 5)}"/>',
    '<linearGradient id="sg" x1="64" y1="10" x2="64" y2="102" gradientUnits="userSpaceOnUse">'
    '<stop offset="0" stop-color="#FFD54F"/><stop offset="1" stop-color="#F9A825"/></linearGradient>'
    '<linearGradient id="sg2" x1="64" y1="29" x2="64" y2="83" gradientUnits="userSpaceOnUse">'
    '<stop offset="0" stop-color="#FBC02D"/><stop offset="1" stop-color="#F57F17"/></linearGradient>',
)

ART["rosette_blue"] = svg(
    '<path fill="#1565C0" d="M46,74L34,122L48,113L56,126L64,82Z"/>'
    '<path fill="#1E88E5" d="M82,74L94,122L80,113L72,126L64,82Z"/>'
    f'<path fill="#42A5F5" d="{scallop_path(64, 52, 38, 24, 6)}"/>'
    f'<path fill="#1E88E5" d="{scallop_path(64, 52, 30, 20, 4)}"/>'
    '<circle cx="64" cy="52" r="24" fill="#FFFFFF"/>'
    '<circle cx="64" cy="52" r="20" fill="#E3F2FD"/>'
    f'<polygon fill="#FFC107" points="{star_points(64, 52, 13, 5.6, 5)}"/>',
)

# ---- things to write on -----------------------------------------------------

ART["ribbon_banner"] = svg(
    # Tails, their folds, then the band on top.
    '<path fill="#D32F2F" d="M2,52L32,52L32,96L2,96L14,74Z"/>'
    '<path fill="#D32F2F" d="M126,52L96,52L96,96L126,96L114,74Z"/>'
    '<path fill="#8E1414" d="M18,80L32,96L32,72Z"/>'
    '<path fill="#8E1414" d="M110,80L96,96L96,72Z"/>'
    '<path fill="url(#rb)" d="M18,36Q64,24 110,36L110,80Q64,68 18,80Z"/>'
    '<path fill="#FFFFFF" fill-opacity="0.2" d="M18,36Q64,24 110,36L110,43Q64,31 18,43Z"/>'
    '<path fill="#000000" fill-opacity="0.08" d="M18,74Q64,62 110,74L110,80Q64,68 18,80Z"/>',
    '<linearGradient id="rb" x1="64" y1="28" x2="64" y2="80" gradientUnits="userSpaceOnUse">'
    '<stop offset="0" stop-color="#FF5A52"/><stop offset="1" stop-color="#E53935"/></linearGradient>',
)

ART["sign_wood"] = svg(
    '<rect x="58" y="70" width="12" height="56" rx="2" fill="#795548"/>'
    '<rect x="58" y="70" width="5" height="56" rx="2" fill="#8D6E63"/>'
    '<rect x="6" y="18" width="116" height="62" rx="8" fill="#8D5B3A"/>'
    '<rect x="10" y="22" width="108" height="54" rx="6" fill="url(#sw)"/>'
    '<path fill="none" stroke="#B9844E" stroke-width="1.6" stroke-linecap="round" '
    'd="M18,36Q44,32 70,37T110,35M16,52Q40,48 66,53T112,51M20,66Q50,62 80,67T108,65"/>'
    '<circle cx="18" cy="30" r="2.4" fill="#5D4037"/><circle cx="110" cy="30" r="2.4" fill="#5D4037"/>'
    '<circle cx="18" cy="68" r="2.4" fill="#5D4037"/><circle cx="110" cy="68" r="2.4" fill="#5D4037"/>',
    '<linearGradient id="sw" x1="64" y1="22" x2="64" y2="76" gradientUnits="userSpaceOnUse">'
    '<stop offset="0" stop-color="#D7A26B"/><stop offset="1" stop-color="#C08552"/></linearGradient>',
)

ART["calendar_page"] = svg(
    '<rect x="14" y="16" width="100" height="104" rx="12" fill="#CFD8DC"/>'
    '<rect x="14" y="12" width="100" height="102" rx="12" fill="#FFFFFF"/>'
    '<path fill="url(#cp)" d="M26,12H102A12,12 0 0 1 114,24V40H14V24A12,12 0 0 1 26,12Z"/>'
    '<rect x="34" y="4" width="8" height="18" rx="4" fill="#546E7A"/>'
    '<rect x="86" y="4" width="8" height="18" rx="4" fill="#546E7A"/>'
    '<rect x="14" y="40" width="100" height="3" fill="#E0E0E0"/>',
    '<linearGradient id="cp" x1="64" y1="12" x2="64" y2="40" gradientUnits="userSpaceOnUse">'
    '<stop offset="0" stop-color="#FF5A4E"/><stop offset="1" stop-color="#E53935"/></linearGradient>',
)

ART["mug"] = svg(
    '<path fill="none" stroke="#E0E0E0" stroke-width="10" d="M92,52Q120,52 118,74Q116,96 90,94"/>'
    '<path fill="url(#mg)" d="M14,34H96V102Q96,122 76,122H34Q14,122 14,102Z"/>'
    '<ellipse cx="55" cy="34" rx="41" ry="8" fill="#F5F5F5"/>'
    '<ellipse cx="55" cy="35" rx="35" ry="5.5" fill="#6D4C41"/>'
    '<ellipse cx="50" cy="34" rx="16" ry="2" fill="#8D6E63"/>'
    '<path fill="#FFFFFF" fill-opacity="0.5" d="M22,44H30V104Q30,112 36,116Q22,114 22,102Z"/>',
    '<linearGradient id="mg" x1="14" y1="78" x2="96" y2="78" gradientUnits="userSpaceOnUse">'
    '<stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#E6E6E6"/></linearGradient>',
)

ART["steam"] = svg(
    '<g fill="none" stroke="#B0BEC5" stroke-width="7" stroke-linecap="round" stroke-opacity="0.85">'
    '<path d="M38,118C26,100 50,92 38,72S46,44 38,28"/>'
    '<path d="M64,112C52,94 76,86 64,66S72,38 64,14"/>'
    '<path d="M90,118C78,100 102,92 90,72S98,44 90,28"/></g>',
)

ART["blob_pink"] = svg(
    '<path fill="url(#bp)" d="M64,8C92,6 120,24 122,56C124,88 104,118 70,120C36,122 8,104 6,70C4,36 32,10 64,8Z"/>',
    '<radialGradient id="bp" cx="48" cy="40" r="90" gradientUnits="userSpaceOnUse">'
    '<stop offset="0" stop-color="#FFC1DA"/><stop offset="1" stop-color="#FF8AB8"/></radialGradient>',
)

ART["blob_yellow"] = svg(
    '<path fill="url(#by)" d="M60,6C94,2 124,26 122,62C120,96 96,124 62,122C28,120 4,96 6,62C8,30 28,10 60,6Z"/>',
    '<radialGradient id="by" cx="46" cy="38" r="92" gradientUnits="userSpaceOnUse">'
    '<stop offset="0" stop-color="#FFF59D"/><stop offset="1" stop-color="#FFD54F"/></radialGradient>',
)

ART["blob_mint"] = svg(
    '<path fill="url(#bm)" d="M66,8C98,10 122,34 120,66C118,98 92,122 60,120C28,118 6,92 8,60C10,28 36,6 66,8Z"/>',
    '<radialGradient id="bm" cx="48" cy="40" r="92" gradientUnits="userSpaceOnUse">'
    '<stop offset="0" stop-color="#C8F7E1"/><stop offset="1" stop-color="#7EDDB4"/></radialGradient>',
)

ART["brush_stroke"] = svg(
    '<path fill="#FF4F8B" d="M6,48C24,40 50,42 72,40C92,38 110,36 122,42C126,52 124,64 120,74'
    'C102,80 78,78 56,82C36,86 18,88 8,82C2,72 2,58 6,48Z"/>'
    '<path fill="#FF7AA8" d="M14,50C34,46 58,46 80,44C98,42 112,42 118,46L118,52C100,50 76,52 54,54C36,56 22,58 14,58Z"/>'
    '<path fill="#E9306F" d="M10,76C26,80 46,76 66,74C88,72 106,72 118,70L118,74C104,78 82,78 60,82C40,86 22,86 10,80Z"/>',
)

ART["tape"] = svg(
    '<path fill="#FFE082" fill-opacity="0.85" d="M8,44L14,40L8,36L120,36L114,40L120,44L114,48L120,52L120,92'
    'L114,88L120,84L8,84L14,88L8,92L8,52L14,48Z"/>'
    '<g fill="#FFFFFF" fill-opacity="0.35"><rect x="24" y="36" width="10" height="56"/><rect x="50" y="36" width="10" height="56"/>'
    '<rect x="76" y="36" width="10" height="56"/><rect x="102" y="36" width="10" height="56"/></g>',
)

ART["warning_stripes"] = svg(
    '<rect x="4" y="40" width="120" height="48" rx="6" fill="#FFCA28"/>'
    '<g clip-path="url(#ws)" fill="#263238">'
    + "".join(f'<polygon points="{x},88 {x + 14},88 {x + 38},40 {x + 24},40"/>' for x in range(-30, 130, 28))
    + "</g>"
    '<rect x="4" y="40" width="120" height="48" rx="6" fill="none" stroke="#263238" stroke-width="4"/>',
    '<clipPath id="ws"><rect x="4" y="40" width="120" height="48" rx="6"/></clipPath>',
)

ART["warning_sign"] = svg(
    '<path fill="#263238" d="M64,6C68,6 71,8 73,12L124,104C128,112 123,120 114,120H14C5,120 0,112 4,104L55,12C57,8 60,6 64,6Z"/>'
    '<path fill="url(#wsg)" d="M64,18L114,110H14Z"/>'
    '<rect x="58" y="44" width="12" height="40" rx="6" fill="#263238"/>'
    '<circle cx="64" cy="97" r="7" fill="#263238"/>',
    '<linearGradient id="wsg" x1="64" y1="18" x2="64" y2="110" gradientUnits="userSpaceOnUse">'
    '<stop offset="0" stop-color="#FFE57F"/><stop offset="1" stop-color="#FFC400"/></linearGradient>',
)

# ---- cards ------------------------------------------------------------------

ART["quote_marks"] = svg(
    '<path fill="#FFB300" d="M10,92C10,62 22,40 50,30L54,40C38,48 32,58 32,66C44,66 54,74 54,88'
    'C54,102 44,112 32,112C18,112 10,104 10,92Z"/>'
    '<path fill="#FFB300" d="M70,92C70,62 82,40 110,30L114,40C98,48 92,58 92,66C104,66 114,74 114,88'
    'C114,102 104,112 92,112C78,112 70,104 70,92Z"/>',
)

ART["verified_badge"] = svg(
    f'<path fill="#1D9BF0" d="{scallop_path(64, 64, 50, 12, 9)}"/>'
    '<path fill="none" stroke="#FFFFFF" stroke-width="12" stroke-linecap="round" stroke-linejoin="round" d="M40,66L57,82L89,48"/>',
)

ART["avatar"] = svg(
    '<circle cx="64" cy="64" r="60" fill="url(#av)"/>'
    '<circle cx="64" cy="50" r="22" fill="#FFFFFF" fill-opacity="0.9"/>'
    '<path fill="#FFFFFF" fill-opacity="0.9" d="M22,108C28,86 44,78 64,78C84,78 100,86 106,108C96,118 82,124 64,124C46,124 32,118 22,108Z"/>',
    '<linearGradient id="av" x1="20" y1="10" x2="108" y2="118" gradientUnits="userSpaceOnUse">'
    '<stop offset="0" stop-color="#9FA8DA"/><stop offset="1" stop-color="#5C6BC0"/></linearGradient>',
)

ART["news_globe"] = svg(
    '<circle cx="64" cy="64" r="58" fill="#1565C0"/>'
    '<g fill="none" stroke="#90CAF9" stroke-width="4">'
    '<circle cx="64" cy="64" r="56"/><ellipse cx="64" cy="64" rx="24" ry="56"/>'
    '<path d="M8,64H120M16,36H112M16,92H112M64,8V120"/></g>',
)

# ---- doodles ----------------------------------------------------------------

ART["doodle_heart"] = svg(
    '<path fill="none" stroke="#FF3D71" stroke-width="7" stroke-linecap="round" stroke-linejoin="round" '
    'd="M66,112C40,92 12,72 14,44C16,24 36,14 52,22C60,26 64,34 64,40C66,30 74,20 90,18'
    'C108,18 118,32 114,52C110,74 88,94 62,114"/>',
)

ART["doodle_star"] = svg(
    '<path fill="none" stroke="#FFB300" stroke-width="7" stroke-linecap="round" stroke-linejoin="round" '
    'd="M64,10L78,46L116,48L86,72L98,110L64,88L30,110L42,72L12,48L50,46L62,14"/>',
)

ART["doodle_underline"] = svg(
    '<path fill="none" stroke="#FF5722" stroke-width="8" stroke-linecap="round" stroke-linejoin="round" '
    'd="M6,62C26,52 44,72 64,62S104,52 122,62M14,78C40,72 80,70 116,76"/>',
)

ART["doodle_swirl"] = svg(
    '<path fill="none" stroke="#7C4DFF" stroke-width="7" stroke-linecap="round" '
    'd="M8,96C30,96 42,84 42,68C42,52 28,48 24,60C20,74 40,86 60,76C82,66 76,40 60,40'
    'C46,40 48,62 66,66C88,70 104,52 104,36C104,24 112,18 120,20"/>',
)

ART["doodle_sparkle"] = svg(
    '<g fill="none" stroke="#FFC107" stroke-width="6" stroke-linecap="round" stroke-linejoin="round">'
    '<path d="M50,14C52,40 58,48 84,50C58,52 52,60 50,86C48,60 42,52 16,50C42,48 48,40 50,14Z"/>'
    '<path d="M98,74C99,88 102,92 116,93C102,94 99,98 98,112C97,98 94,94 80,93C94,92 97,88 98,74Z"/></g>',
)

ART["doodle_arrow"] = svg(
    '<g fill="none" stroke="#FFFFFF" stroke-width="8" stroke-linecap="round" stroke-linejoin="round">'
    '<path d="M14,22C24,62 52,92 106,102"/><path d="M84,84L108,102L80,114"/></g>',
)

ART["doodle_arrow_black"] = svg(
    '<g fill="none" stroke="#212121" stroke-width="8" stroke-linecap="round" stroke-linejoin="round">'
    '<path d="M14,22C24,62 52,92 106,102"/><path d="M84,84L108,102L80,114"/></g>',
)

ART["doodle_crown"] = svg(
    '<path fill="#FFD54F" stroke="#F57F17" stroke-width="5" stroke-linejoin="round" '
    'd="M16,98L10,38L40,62L64,22L88,62L118,38L112,98Z"/>'
    '<path fill="none" stroke="#F57F17" stroke-width="5" stroke-linecap="round" d="M18,86H110"/>'
    '<circle cx="10" cy="36" r="6" fill="#FF7043"/><circle cx="64" cy="20" r="6" fill="#FF7043"/><circle cx="118" cy="36" r="6" fill="#FF7043"/>',
)

ART["motion_lines"] = svg(
    '<g fill="none" stroke="#90A4AE" stroke-width="8" stroke-linecap="round">'
    '<path d="M28,36H108"/><path d="M8,64H100"/><path d="M36,92H116"/></g>',
)

ART["sweat_drop"] = svg(
    '<path fill="url(#sd)" d="M64,8C76,32 100,56 100,82C100,104 84,120 64,120C44,120 28,104 28,82C28,56 52,32 64,8Z"/>'
    '<path fill="#FFFFFF" fill-opacity="0.6" d="M46,84C46,70 52,60 58,52C54,64 54,76 56,88C57,96 46,96 46,84Z"/>',
    '<linearGradient id="sd" x1="64" y1="8" x2="64" y2="120" gradientUnits="userSpaceOnUse">'
    '<stop offset="0" stop-color="#81D4FA"/><stop offset="1" stop-color="#039BE5"/></linearGradient>',
)

ART["comic_burst"] = svg(
    f'<polygon fill="#212121" points="{star_points(64, 64, 63, 40, 14, -80)}"/>'
    f'<polygon fill="#FFEB3B" points="{star_points(64, 64, 56, 35, 14, -80)}"/>'
    f'<polygon fill="#FF9800" points="{star_points(64, 64, 40, 26, 14, -73)}"/>',
)

ART["comic_burst_red"] = svg(
    f'<polygon fill="#212121" points="{star_points(64, 64, 63, 42, 12, -76)}"/>'
    f'<polygon fill="#FF1744" points="{star_points(64, 64, 56, 37, 12, -76)}"/>'
    f'<polygon fill="#FF616F" points="{star_points(64, 64, 38, 27, 12, -70)}"/>',
)

ART["zigzag"] = svg(
    '<path fill="none" stroke="#00BCD4" stroke-width="8" stroke-linecap="round" stroke-linejoin="round" '
    'd="M6,72L22,52L38,72L54,52L70,72L86,52L102,72L122,52"/>',
)

ART["sun_doodle"] = svg(
    '<circle cx="64" cy="64" r="26" fill="#FFD54F" stroke="#FF8F00" stroke-width="5"/>'
    '<g stroke="#FF8F00" stroke-width="7" stroke-linecap="round">'
    + "".join(
        f'<path d="M{f(64 + 38 * math.cos(math.radians(a)))},{f(64 + 38 * math.sin(math.radians(a)))}'
        f'L{f(64 + 56 * math.cos(math.radians(a)))},{f(64 + 56 * math.sin(math.radians(a)))}"/>'
        for a in range(0, 360, 45))
    + "</g>",
)

ART["moon_stars"] = svg(
    '<path fill="url(#ms)" d="M74,10C46,14 26,38 26,66C26,98 52,122 84,118C64,110 52,90 52,66C52,42 60,22 74,10Z"/>'
    f'<polygon fill="#FFF59D" points="{star_points(92, 40, 12, 5, 5)}"/>'
    f'<polygon fill="#FFF59D" points="{star_points(108, 78, 8, 3.4, 5)}"/>'
    f'<polygon fill="#FFF59D" points="{star_points(80, 92, 6, 2.6, 5)}"/>',
    '<linearGradient id="ms" x1="30" y1="20" x2="80" y2="118" gradientUnits="userSpaceOnUse">'
    '<stop offset="0" stop-color="#FFE082"/><stop offset="1" stop-color="#FFB300"/></linearGradient>',
)

ART["confetti_strip"] = svg(
    "".join(
        f'<rect x="{x}" y="{y}" width="{w}" height="5" rx="2.5" fill="{c}" transform="rotate({r} {x + w / 2} {y + 2.5})"/>'
        for x, y, w, r, c in [
            (8, 20, 16, 30, "#E91E63"), (34, 10, 12, -20, "#FFC107"), (58, 26, 14, 50, "#03A9F4"),
            (82, 12, 16, -35, "#8BC34A"), (104, 30, 12, 20, "#9C27B0"), (18, 54, 12, -50, "#FF5722"),
            (44, 46, 16, 15, "#00BCD4"), (70, 58, 12, -15, "#E91E63"), (96, 50, 16, 40, "#FFC107"),
            (10, 88, 16, 10, "#8BC34A"), (36, 80, 12, 60, "#9C27B0"), (60, 94, 16, -40, "#03A9F4"),
            (86, 84, 12, 25, "#FF5722"), (108, 100, 14, -20, "#00BCD4"), (24, 112, 12, 35, "#FFC107"),
            (72, 116, 14, 5, "#E91E63"),
        ])
    + "".join(
        f'<circle cx="{x}" cy="{y}" r="3" fill="{c}"/>'
        for x, y, c in [(28, 34, "#03A9F4"), (74, 40, "#FF5722"), (118, 66, "#E91E63"), (50, 104, "#8BC34A"),
                        (100, 118, "#9C27B0"), (6, 70, "#FFC107")])
)

ART["paper_plane"] = svg(
    '<path fill="#E3F2FD" d="M6,58L122,10L92,112Z"/>'
    '<path fill="#90CAF9" d="M6,58L56,74L122,10Z"/>'
    '<path fill="#64B5F6" d="M56,74L60,108L76,84Z"/>'
    '<path fill="#BBDEFB" d="M56,74L92,112L122,10Z"/>',
)

ART["heart_bubble"] = svg(
    '<path fill="#FFFFFF" d="M64,10C96,10 120,30 120,56C120,82 96,102 64,102C56,102 48,101 42,98L18,114L24,90'
    'C14,80 8,68 8,56C8,30 32,10 64,10Z"/>'
    '<path fill="#FF4D6D" d="M64,82C48,70 36,62 36,48C36,40 42,34 50,34C56,34 61,38 64,44C67,38 72,34 78,34'
    'C86,34 92,40 92,48C92,62 80,70 64,82Z"/>',
)

ART["polaroid"] = svg(
    '<rect x="12" y="6" width="104" height="120" rx="3" fill="#000000" fill-opacity="0.12" transform="translate(2 2)"/>'
    '<rect x="12" y="6" width="104" height="120" rx="3" fill="#FFFFFF"/>'
    '<rect x="20" y="14" width="88" height="80" fill="#ECEFF1"/>',
)


def ids() -> list[str]:
    return sorted(ART)
