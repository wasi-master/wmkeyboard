#!/usr/bin/env python3
"""Draw the "Add repository to WM Keyboard" badge an addon repository puts in its README.

Same canvas as the Google Play, F-Droid and Obtainium "Get it on" badges
(646 x 250 with the transparent margin built in), so all of them line up at
one `height` side by side. Drawn at 2x for sharp edges on dense screens.

    python3 docs/scripts/add_repo_badge.py <Inter variable .ttf>

Inter is the one in the official addon repository (fonts/inter.ttf). Writes
docs/public/badges/add-repository.png; the page that explains it is
docs/src/content/docs/development/addon-repos/badge.mdx.
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
ICON = ROOT / "play/store-listing/icon-512.png"
OUT = ROOT / "docs/public/badges/add-repository.png"

S = 2  # scale
W, H = 646 * S, 250 * S
# The black plate, where the Play and Obtainium badges put theirs.
X0, Y0, X1, Y1 = 42 * S, 42 * S, 604 * S, 207 * S
RADIUS = 16 * S
BORDER = 3 * S


def font(path, size, weight):
    f = ImageFont.truetype(path, size * S)
    f.set_variation_by_name(weight)
    return f


def main(font_path):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((X0, Y0, X1, Y1), RADIUS, fill=(166, 166, 166, 255))
    d.rounded_rectangle((X0 + BORDER, Y0 + BORDER, X1 - BORDER, Y1 - BORDER), RADIUS - BORDER, fill=(0, 0, 0, 255))

    small = font(font_path, 25, "SemiBold")
    big = font(font_path, 54, "Regular")
    kicker, name = "ADD REPOSITORY TO", "WM Keyboard"

    icon_size = 104 * S
    gap = 22 * S
    text_w = max(d.textlength(kicker, font=small), d.textlength(name, font=big))
    left = (X0 + X1 - (icon_size + gap + text_w)) // 2

    # The launcher icon, cropped in on its glyph (at full size the glyph is a
    # fifth of the tile and unreadable this small) and cut to a rounded square.
    icon = Image.open(ICON).convert("RGBA").crop((112, 104, 400, 392)).resize((icon_size, icon_size), Image.LANCZOS)
    mask = Image.new("L", icon.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, icon_size - 1, icon_size - 1), 26 * S, fill=255)
    icon.putalpha(mask)
    img.alpha_composite(icon, (int(left), (Y0 + Y1 - icon_size) // 2))

    tx = left + icon_size + gap
    d.text((tx, Y0 + 30 * S), kicker, font=small, fill="white")
    d.text((tx, Y0 + 60 * S), name, font=big, fill="white")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT, optimize=True)
    print(OUT)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
