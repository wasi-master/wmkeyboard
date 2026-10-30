#!/usr/bin/env python3
"""Writes a handwriting pad for each language a recogniser reads.

Gboard has `ime_%s_handwriting`, one writing pad per language. Ours is a layout
flag over a row of punctuation, so a pad is a small file. A language gets one
when ML Kit has a model for it in the script we write it in, or PaddleOCR's
line recogniser or zinnia reads it; `HandwritingPadsTest` holds this to what
`HandwritingModels` routes.

    python3 -I tools/gboard-layouts/handwriting.py <repo>
"""
import json
import pathlib
import re
import sys

ISO = {
    "Latin": "Latn", "Cyrillic": "Cyrl", "Greek": "Grek", "Armenian": "Armn", "Georgian": "Geor", "Arabic": "Arab",
    "Hebrew": "Hebr", "Devanagari": "Deva", "Bengali": "Beng", "Gurmukhi": "Guru", "Gujarati": "Gujr", "Oriya": "Orya",
    "Tamil": "Taml", "Telugu": "Telu", "Kannada": "Knda", "Malayalam": "Mlym", "Sinhala": "Sinh", "Thai": "Thai",
    "Lao": "Laoo", "Khmer": "Khmr", "Myanmar": "Mymr", "Ethiopic": "Ethi", "Tibetan": "Tibt",
}

# ML Kit's own subtags for two of ours (HandwritingModels.SUBTAG_ALIASES).
ALIASES = {"nb": "no", "tl": "fil"}

# Already have a pad of their own.
SKIP = {"ko", "ja", "zh"}

# Scripts with a comma or a full stop of their own.
COMMA = {"Arabic": "،", "Myanmar": "၊", "Ethiopic": "፣"}
PERIOD = {
    "Devanagari": "।", "Bengali": "।", "Gurmukhi": "।", "Oriya": "।", "Myanmar": "။", "Ethiopic": "።", "Khmer": "។",
    "Tibetan": "།", "Armenian": "։",
}
PERIOD_BY_ID = {"ur": "۔", "pnb": "۔", "skr": "۔", "ps": "۔"}


def key(label, **fields):
    return {"label": label, **fields}


def pad(language):
    name, english, script, lang_id = language["name"], language["english"], language["script"], language["id"]
    native = name.split(" · ")[0]
    title = f"{english} Handwriting" if native == english else f"{native} ({english} Handwriting)"
    period = PERIOD_BY_ID.get(lang_id, PERIOD.get(script, "."))
    comma = COMMA.get(script, ",")
    others = [p for p in ("…", ",", "?", "!", ":", ";") if p != period]
    row = [
        key("?123", action={"type": "symbols"}, width=1.5),
        key(comma, longPress=["!", "?"], role="Comma"),
        key("🌐", action={"type": "language_switch"}),
        key(" ", action={"type": "space"}, width=3.5),
        key(period, longPress=others, role="Period"),
        key("⌫", action={"type": "delete"}, width=1.25),
        key("⏎", action={"type": "enter"}, width=1.25),
    ]
    return {
        "format": "wmkeyboard-layout",
        "version": 1,
        "layout": {
            "id": f"asset_hw_{lang_id}",
            "name": title,
            "langId": lang_id,
            "tabletExpand": False,
            "handwriting": True,
            "version": 2,
            "layers": {"letters": {"rows": [row]}},
        },
    }


def main(repo):
    root = pathlib.Path(repo)
    languages = json.loads((root / "docs/src/data/languages.json").read_text(encoding="utf-8"))
    ink = root / "core/intelligence/src/main/java/com/wasimaster/wmkeyboard/core/handwriting"
    tags = dict(re.findall(r'"([A-Za-z0-9-]+)" to "(\w+)"', (ink / "InkModelTags.kt").read_text(encoding="utf-8")))
    by_subtag = {}
    for tag, script in tags.items():
        by_subtag.setdefault(tag.split("-")[0], set()).add(script)
    # Each ink pack's language set, with the script its ScriptId names.
    packs = re.findall(
        r"ScriptId\.(\w+),\s+setOf\(([^)]*)\)", (ink / "InkOcrModels.kt").read_text(encoding="utf-8"),
    )
    ink_ocr = {(script.lower(), lang) for script, langs in packs for lang in re.findall(r'"([\w-]+)"', langs)}

    chosen = []
    for language in languages:
        lang_id, script = language["id"], language["script"]
        if lang_id in SKIP:
            continue
        iso = ISO.get(script)
        ml_kit = iso is not None and iso in by_subtag.get(ALIASES.get(lang_id, lang_id), ())
        zinnia = lang_id == "yue"
        if ml_kit or zinnia or (script.lower(), lang_id) in ink_ocr:
            chosen.append(language)
    chosen.sort(key=lambda language: language["id"])

    out = root / "app/src/main/assets/layouts"
    for old in out.glob("hw_*.wmlayout.json"):
        old.unlink()
    for language in chosen:
        text = json.dumps(pad(language), ensure_ascii=False, separators=(",", ":"))
        (out / f"hw_{language['id']}.wmlayout.json").write_text(text + "\n", encoding="utf-8")

    ids = " ".join(language["id"] for language in chosen)
    lines = []
    line = ""
    for lang_id in ids.split(" "):
        if len(line) + len(lang_id) > 100:
            lines.append(line)
            line = ""
        line += lang_id + " "
    lines.append(line.rstrip())
    table = "\n".join(f'        "{part}" +' for part in lines).rstrip(" +")
    kotlin = f'''package com.wasimaster.wmkeyboard.core.script

/**
 * The languages with a handwriting pad of their own, which
 * tools/gboard-layouts/handwriting.py writes. Generated; do not hand-edit.
 *
 * One line of text rather than a collection literal because the registry reads
 * it in the keyboard's process at every start ([LanguageRegistry]).
 */
object HandwritingLanguages {{

    private val ids: Set<String> by lazy {{
        (
{table}
        ).split(' ').toHashSet()
    }}

    /** The pad of the language [languageId], or nothing. */
    fun layoutIds(languageId: String): List<String> =
        if (languageId in ids) listOf("asset_hw_$languageId") else emptyList()
}}
'''
    (root / "core/language/src/main/java/com/wasimaster/wmkeyboard/core/script/HandwritingLanguages.kt").write_text(kotlin, encoding="utf-8")
    print(len(chosen), "pads")
    print(sorted({l["script"] for l in chosen}))
    print([l["id"] for l in chosen if l["script"] != "Latin"][:120])


if __name__ == "__main__":
    main(sys.argv[1])
