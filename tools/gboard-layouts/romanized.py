#!/usr/bin/env python3
"""Writes the phonetic layouts for the languages Gboard types by transliteration.

    python3 -I romanized.py <repo>

Gboard's conversion data isn't in its APK, so the rules are this app's own
(native/wm-engine/src/minor_phonetic.rs). This writes each language's rom_*
asset, a QWERTY grid on the transliterating composer, and RomanizedLanguages.kt.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ours

EXISTING = """ahr awa bfy bgc bhb bho bjj bns brx dhd doi gbm gdx gno hlb hne hoj kfq knn kok kru ks-Deva lmn mag mai mjl
mtr mup noe rwr sck sd-Deva sjp swv vah wbr wry xnr bpy brx-Beng ctg mni-Beng sat-Beng trp unr kok-Knda tcy bcc bft
bgp bgq brh dcc doi-Arab gju hnd hno ks mve phr ps scl sd skr yi mn sat mni""".split()
NEW = {
    "bgq-Deva": ("Bagri (Devanagari)", "DEVANAGARI"),
    "gju-Deva": ("Gujari (Devanagari)", "DEVANAGARI"),
    "rkt-Deva": ("Rangpuri (Devanagari)", "DEVANAGARI"),
    "grt-Beng": ("Garo (Bengali)", "BENGALI"),
    "bhb-Gujr": ("Bhili (Gujarati)", "GUJARATI"),
    "hlb-Orya": ("Halbi (Odia)", "ORIYA"),
    "lmn-Knda": ("Lambadi (Kannada)", "KANNADA"),
    "lmn-Telu": ("Lambadi (Telugu)", "TELUGU"),
    "gno-Telu": ("Gondi (Telugu)", "TELUGU"),
}


def letter(c, digit=None):
    key = {"label": c}
    if digit:
        key["longPress"] = [digit]
    return key


def rows():
    top = [letter(c, d) for c, d in zip("qwertyuiop", "1234567890")]
    home = [letter(c) for c in "asdfghjkl"]
    low = [{"label": "⇧", "action": {"type": "shift"}, "width": 1.5}]
    low += [letter(c) for c in "zxcvbnm"]
    low += [{"label": "⌫", "action": {"type": "delete"}, "width": 1.5}]
    bottom = [
        {"label": "?123", "action": {"type": "symbols"}, "width": 1.5},
        {"label": ",", "longPress": ["!", "?"], "role": "Comma"},
        {"label": "🌐", "action": {"type": "language_switch"}},
        {"label": " ", "action": {"type": "space"}, "width": 4},
        {"label": ".", "longPress": ["…", ",", "?", "!", ":", ";"], "role": "Period"},
        {"label": "⏎", "action": {"type": "enter"}, "width": 1.5},
    ]
    return [top, home, low, bottom]


def main():
    root = sys.argv[1]
    consts = ours._consts(root)
    base = f"{root}/{ours.SCRIPT_DIR}/script/"
    known = {}
    for name in ["Language.kt", "CorpusLanguages.kt", "KeymanLanguages.kt", "GboardLanguages.kt", "SamsungLanguages.kt"]:
        for d in ours._defs(open(base + name).read(), consts):
            known.setdefault(d["id"], d)
    names = {i: known[i]["name"] for i in EXISTING}
    names.update({i: v[0] for i, v in NEW.items()})
    layout_of = {}
    for lang_id, name in names.items():
        file_id = "rom_" + lang_id.lower().replace("-", "_")
        layout_of[lang_id] = f"asset_{file_id}"
        doc = {
            "format": "wmkeyboard-layout",
            "version": 1,
            "layout": {
                "id": f"asset_{file_id}",
                "name": f"{name} phonetic",
                "langId": lang_id,
                "version": 1,
                "composer": "TRANSLITERATE",
                "layers": {"letters": {"rows": rows()}},
            },
        }
        with open(f"{root}/{ours.ASSETS}/{file_id}.wmlayout.json", "w") as fh:
            fh.write(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
    out = [
        "package com.wasimaster.wmkeyboard.core.script\n\n",
        "/**\n * The phonetic layouts tools/gboard-layouts/romanized.py writes, for languages\n",
        " * Gboard types by transliteration. Generated; do not hand-edit.\n *\n",
        " * [all] holds the script-and-language pairs nothing else listed; the rest of\n",
        " * the layouts join a language we already carry through [extraLayoutIds].\n */\n",
        "object RomanizedLanguages {\n\n    val all: List<LanguageDef> by lazy {\n        listOf(\n",
    ]
    for lang_id, (name, script) in NEW.items():
        out += [
            "            LanguageDef(\n",
            f'                id = "{lang_id}",\n',
            f'                displayName = "{name}",\n',
            f'                englishName = "{name}",\n',
            f"                script = ScriptId.{script},\n",
            f'                localeTag = "{lang_id}",\n',
            f'                layoutIds = listOf("{layout_of[lang_id]}"),\n',
            "            ),\n",
        ]
    out += ["        )\n    }\n\n", "    val extraLayoutIds: Map<String, List<String>> by lazy {\n        mapOf(\n"]
    for lang_id in EXISTING:
        out.append(f'            "{lang_id}" to listOf("{layout_of[lang_id]}"),\n')
    out += ["        )\n    }\n}\n"]
    with open(base + "RomanizedLanguages.kt", "w") as fh:
        fh.write("".join(out))
    print(len(names), "layouts,", len(NEW), "new languages")


if __name__ == "__main__":
    main()
