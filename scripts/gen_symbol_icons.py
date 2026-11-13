#!/usr/bin/env python3
"""
Generates the Material Symbols vectors in :core:icons.

Two outputs, both under core/icons/src/main/java/com/wasimaster/wmkeyboard/core/icons/:

1. symbols/<style>/Icons.kt — one extension property per bundled-icon name the
   app imports, e.g. `val Icons.Outlined.Delete`, drawn from Material Symbols
   instead of the frozen Material Icons set. The package mirrors
   androidx.compose.material.icons, so moving a file over is an import rewrite:

       androidx.compose.material.icons.outlined.Delete
    -> com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete

   and every `Icons.Outlined.Delete` call site stays as written.

2. SymbolIcons.kt — Symbols-only glyphs the old set never had (sticker, the
   spirit level, keyboard_external_input, …), under the names in EXTRA below.

Usage:
    scripts/gen_symbol_icons.py            # regenerate from the imports in app/ and feature/
    scripts/gen_symbol_icons.py --names    # only PluginSymbols.kt and the SymbolNames.kt index
    scripts/gen_symbol_icons.py --rewrite  # also rewrite those imports to the generated package

Style: Material Symbols Rounded, weight 400, grade 0, optical size 24 — the
defaults fonts.google.com serves. `Icons.Filled.*` draws the FILL=1 variant.
Fetched SVGs are cached in build/symbol-icons-cache/.
"""

import json
import os
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "core/icons/src/main/java/com/wasimaster/wmkeyboard/core/icons"
CACHE = ROOT / "build/symbol-icons-cache"
PKG = "com.wasimaster.wmkeyboard.core.icons"
STYLE = "materialsymbolsrounded"
SCAN_DIRS = ["app/src", "feature/ime/src"]

IMPORT_RE = re.compile(
    r"^import androidx\.compose\.material\.icons\.((?:automirrored\.)?(?:outlined|filled|rounded))\.([A-Z]\w*)$",
    re.M,
)
# Both the bundled-icon imports and the ones already moved over, so a rerun after
# --rewrite still regenerates every icon in use.
SCAN_RE = re.compile(
    r"^import (?:androidx\.compose\.material\.icons|com\.wasimaster\.wmkeyboard\.core\.icons\.symbols)"
    r"\.((?:automirrored\.)?(?:outlined|filled|rounded))\.([A-Z]\w*)$",
    re.M,
)

# Old Material Icons names whose drawing Symbols kept under another name. Symbols
# dropped the separate *_outline / *_border icons: an outline is the FILL=0 face
# of the plain name.
RENAMED = {
    "chat_bubble_outline": "chat_bubble",
    "check_box_outline_blank": "check_box_outline_blank",
    "delete_outline": "delete",
    "error_outline": "error",
    "favorite_border": "favorite",
    "help_outline": "help",
    "mail_outline": "mail",
    "person_outline": "person",
    "play_circle_outline": "play_circle",
    "remove_circle_outline": "do_not_disturb_on",
    "star_border": "star",
    "star_outline": "star",
    "work_outline": "work",
    "outlined_flag": "flag",
    "mic_none": "mic",
    "crop_16_9": "crop_16_9",
}

# Symbols-only glyphs for SymbolIcons: Kotlin name -> (symbol name, fill, autoMirror).
EXTRA = {
    "Sticker": ("sticker", 0, False),
    "StickerAdd": ("sticker_add", 0, False),
    "GifBox": ("gif_box", 0, False),
    # Icon variants (IconDefaults.variants): the GIF tool's lettering without
    # the box, large and at the box's own letter size.
    "Gif": ("gif", 0, False),
    "Gif2": ("gif_2", 0, False),
    "SmartToy": ("smart_toy", 0, False),
    "SettingsFilled": ("settings", 1, False),
    "MicFilled": ("mic", 1, False),
    "AnimatedImages": ("animated_images", 0, False),
    "TrackpadInput": ("trackpad_input", 0, False),
    "MobileHandLeft": ("mobile_hand_left", 0, False),
    "ToolsLevel": ("tools_level", 0, False),
    "Dictionary": ("dictionary", 0, False),
    "KeyboardExternalInput": ("keyboard_external_input", 0, False),
    "PartlyCloudyDay": ("partly_cloudy_day", 0, False),
    "ConvertToText": ("convert_to_text", 0, False),
    "TranslateIndic": ("translate_indic", 0, False),
    "Shift": ("shift", 0, False),
    "ShiftFilled": ("shift", 1, False),
    "ShiftLockFilled": ("shift_lock", 1, False),
    "KeyboardPreviousLanguage": ("keyboard_previous_language", 0, False),
    "KeyboardKeys": ("keyboard_keys", 0, False),
}


# Glyphs offered to plugins by name (a manifest's `tool.icon`, an action's
# `icon`) beyond what the app itself draws: the things a plugin is likely to
# be about. Drawn into symbols/PluginSymbols.kt. Every name the app draws is
# offered too; SymbolNames.kt indexes both.
PLUGIN_SYMBOLS = [
    "account_balance", "account_balance_wallet", "article", "bakery_dining", "ballot",
    "barcode", "barcode_reader", "biotech", "book", "calendar_today", "casino", "coffee",
    "commute", "construction", "cookie", "credit_card", "directions", "directions_bike",
    "directions_bus", "directions_transit", "directions_walk", "eco", "emoji_events",
    "encrypted", "enhanced_encryption", "event_available", "event_note", "g_translate",
    "groups", "headphones", "health_and_safety", "history_edu", "hotel", "how_to_vote",
    "kitchen", "leaderboard", "list_alt", "live_tv", "local_bar", "local_cafe",
    "local_grocery_store", "local_offer", "local_pharmacy", "local_pizza", "local_shipping",
    "local_taxi", "luggage", "lunch_dining", "magic_button", "map", "medical_services",
    "medication", "menu_book", "monitoring", "navigation", "newspaper", "nutrition",
    "package_2", "percent", "podcasts", "point_of_sale", "poll", "price_check", "quiz",
    "receipt", "receipt_long", "recycling", "request_quote", "restaurant", "rocket_launch",
    "route", "router", "rss_feed", "savings", "shopping_basket", "short_text", "skillet",
    "storefront", "subject", "subway", "task_alt", "tips_and_updates", "train", "tram",
    "barcode_scanner", "view_timeline", "remove_shopping_cart",
    "wallet",
]


def snake(name: str) -> str:
    s = re.sub(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", "_", name)
    s = re.sub(r"(?<=[A-Za-z])(?=[0-9])", "_", s)
    s = s.lower()
    return {"crop_169": "crop_16_9", "rotate_90_degrees_cw": "rotate_90_degrees_cw"}.get(s, s)


def fetch(symbol: str, fill: int) -> str:
    CACHE.mkdir(parents=True, exist_ok=True)
    variant = "fill1" if fill else "default"
    f = CACHE / f"{symbol}.{variant}.svg"
    if not f.exists():
        url = f"https://fonts.gstatic.com/s/i/short-term/release/{STYLE}/{symbol}/{variant}/24px.svg"
        with urllib.request.urlopen(url) as r:
            f.write_bytes(r.read())
    svg = f.read_text()
    if 'viewBox="0 -960 960 960"' not in svg:
        raise SystemExit(f"{symbol}: unexpected viewBox")
    paths = re.findall(r'<path d="([^"]+)"', svg)
    if len(paths) != 1:
        raise SystemExit(f"{symbol}: expected one path, got {len(paths)}")
    return paths[0]


def scan() -> dict:
    used = {}
    for d in SCAN_DIRS:
        for p in (ROOT / d).rglob("*.kt"):
            for style, name in SCAN_RE.findall(p.read_text()):
                used.setdefault(style, set()).add(name)
    return used


def kt_style(style: str) -> str:
    return ".".join(part.capitalize() if part != "automirrored" else "AutoMirrored" for part in style.split("."))


def gen_extensions(used: dict) -> None:
    for style, names in sorted(used.items()):
        receiver = "Icons." + kt_style(style)
        fill = 1 if style.endswith("filled") else 0
        mirror = style.startswith("automirrored")
        pkg = f"{PKG}.symbols.{style}"
        out = [
            "// Generated by scripts/gen_symbol_icons.py from Material Symbols (Apache 2.0). Do not edit.",
            '@file:Suppress("ObjectPropertyName", "TopLevelPropertyNaming", "VariableNaming", "MaxLineLength")',
            "",
            f"package {pkg}",
            "",
            "import androidx.compose.material.icons.Icons",
            "import androidx.compose.ui.graphics.vector.ImageVector",
            f"import {PKG}.symbolVector",
            "",
        ]
        for name in sorted(names):
            old = snake(name)
            symbol = RENAMED.get(old, old)
            path = fetch(symbol, fill)
            backing = "_" + name[0].lower() + name[1:]
            out += [
                f"/** Material Symbols `{symbol}`{' (filled)' if fill else ''}. */",
                f"val {receiver}.{name}: ImageVector",
                f'    get() = {backing} ?: symbolVector("{kt_style(style)}.{name}", """{path}""", autoMirror = {str(mirror).lower()})',
                f"        .also {{ {backing} = it }}",
                f"private var {backing}: ImageVector? = null",
                "",
            ]
        dest = OUT / "symbols" / style.replace(".", "/") / "Icons.kt"
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text("\n".join(out))
        print(f"{dest.relative_to(ROOT)}: {len(names)} icons")


def gen_extra() -> None:
    out = [
        "// Generated by scripts/gen_symbol_icons.py from Material Symbols (Apache 2.0). Do not edit.",
        '@file:Suppress("MaxLineLength")',
        "",
        "package " + PKG,
        "",
        "import androidx.compose.ui.graphics.vector.ImageVector",
        "",
        "/**",
        " * Material Symbols glyphs the old Material Icons set never had: a real",
        " * sticker, a spirit level, a keyboard plugged in from outside, a filled shift.",
        " * Everything the old set *did* have is drawn from Symbols too, through the",
        " * extension properties in the `symbols` package; this object is only the extras.",
        " */",
        "object SymbolIcons {",
    ]
    for name, (symbol, fill, mirror) in EXTRA.items():
        path = fetch(symbol, fill)
        out += [
            "",
            f"    /** `{symbol}`{' (filled)' if fill else ''}. */",
            f"    val {name}: ImageVector by lazy {{",
            f'        symbolVector("{name}", """{path}""", autoMirror = {str(mirror).lower()})',
            "    }",
        ]
    out += ["}", ""]
    (OUT / "SymbolIcons.kt").write_text("\n".join(out))
    print(f"SymbolIcons.kt: {len(EXTRA)} icons")


def rewrite_imports() -> None:
    n = 0
    for d in SCAN_DIRS:
        for p in (ROOT / d).rglob("*.kt"):
            s = p.read_text()
            t = IMPORT_RE.sub(lambda m: f"import {PKG}.symbols.{m.group(1)}.{m.group(2)}", s)
            if t != s:
                p.write_text(t)
                n += 1
    print(f"rewrote imports in {n} files")


def kotlin_name(symbol: str) -> str:
    return "".join(part.capitalize() if not part[0].isdigit() else part for part in symbol.split("_"))


def gen_plugin_symbols() -> list:
    """symbols/PluginSymbols.kt: the PLUGIN_SYMBOLS glyphs, by Kotlin name."""
    out = [
        "// Generated by scripts/gen_symbol_icons.py from Material Symbols (Apache 2.0). Do not edit.",
        '@file:Suppress("MaxLineLength")',
        "",
        "package " + PKG + ".symbols",
        "",
        "import androidx.compose.ui.graphics.vector.ImageVector",
        f"import {PKG}.symbolVector",
        "",
        "/**",
        " * Material Symbols glyphs the app does not draw itself but a plugin may name",
        " * for its tool or its actions (see [com.wasimaster.wmkeyboard.core.icons.SymbolNames]).",
        " */",
        "object PluginSymbols {",
    ]
    names = []
    for symbol in PLUGIN_SYMBOLS:
        try:
            path = fetch(symbol, 0)
        except Exception as e:  # noqa: BLE001 - a name Google dropped is skipped, not fatal
            print(f"skip {symbol}: {e}")
            continue
        name = kotlin_name(symbol)
        names.append((symbol, name))
        out += [
            "",
            f"    /** `{symbol}`. */",
            f"    val {name}: ImageVector by lazy {{",
            f'        symbolVector("{name}", """{path}""", autoMirror = false)',
            "    }",
        ]
    out += ["}", ""]
    (OUT / "symbols" / "PluginSymbols.kt").write_text("\n".join(out))
    print(f"symbols/PluginSymbols.kt: {len(names)} icons")
    return names


SYMBOL_DOC_RE = re.compile(r"/\*\* Material Symbols `([a-z0-9_]+)`(?: \(filled\))?\. \*/\nval Icons\.([A-Za-z.]+)\.(\w+): ImageVector")
EXTRA_DOC_RE = re.compile(r"/\*\* `([a-z0-9_]+)`(?: \(filled\))?\. \*/\n    val (\w+): ImageVector")


def gen_names(plugin: list) -> None:
    """SymbolNames.kt: every bundled glyph by its Material Symbols name."""
    entries = {}
    # Outlined first: a name drawn in several styles answers with its outline,
    # the look of the tool icons around it.
    for style in ["outlined", "automirrored/outlined"]:
        src = (OUT / "symbols" / style / "Icons.kt").read_text()
        for symbol, receiver, name in SYMBOL_DOC_RE.findall(src):
            entries.setdefault(symbol, f"Icons.{receiver}.{name}")
    for symbol, name in EXTRA_DOC_RE.findall((OUT / "SymbolIcons.kt").read_text()):
        entries.setdefault(symbol, f"SymbolIcons.{name}")
    for symbol, name in plugin:
        entries.setdefault(symbol, f"PluginSymbols.{name}")
    out = [
        "// Generated by scripts/gen_symbol_icons.py. Do not edit.",
        '@file:Suppress("CyclomaticComplexMethod", "LongMethod")',
        "",
        "package " + PKG,
        "",
        "import androidx.compose.material.icons.Icons",
        "import androidx.compose.ui.graphics.vector.ImageVector",
        f"import {PKG}.symbols.PluginSymbols",
        f"import {PKG}.symbols.automirrored.outlined.*",
        f"import {PKG}.symbols.outlined.*",
        "",
        "/**",
        " * Every Material Symbols glyph this build carries, by its Symbols name",
        " * (`kitchen`, `directions_transit`): the outlined set the app draws, the",
        " * extras in [SymbolIcons], and the ones kept for plugins in [PluginSymbols].",
        " * What a plugin's `tool.icon` and its actions' icons are looked up in.",
        " */",
        "object SymbolNames {",
        "    /** Every name [byName] answers, A to Z. */",
        "    val names: List<String> = listOf(",
    ]
    for symbol in sorted(entries):
        out.append(f'        "{symbol}",')
    out += [
        "    )",
        "",
        "    /** The glyph named [name] (lowercase, underscores), or null when this build has none by that name. */",
        "    fun byName(name: String): ImageVector? = when (name.trim().lowercase()) {",
    ]
    for symbol in sorted(entries):
        out.append(f'        "{symbol}" -> {entries[symbol]}')
    out += ["        else -> null", "    }", "}", ""]
    (OUT / "SymbolNames.kt").write_text("\n".join(out))
    print(f"SymbolNames.kt: {len(entries)} names")


if __name__ == "__main__":
    if "--names" in sys.argv:
        # Only the plugin glyphs and the name index: no refetch of the rest.
        gen_names(gen_plugin_symbols())
        sys.exit(0)
    used = scan()
    gen_extensions(used)
    gen_extra()
    gen_names(gen_plugin_symbols())
    if "--rewrite" in sys.argv:
        rewrite_imports()
