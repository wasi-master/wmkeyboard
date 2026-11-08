#!/usr/bin/env python3
"""Gboard's own symbol pages onto the shipped `gb_*` layouts.

    aapt2 dump resources base.apk > resources.txt
    python3 -I symbols.py base.apk resources.txt <repo> [--dry-run]

The `gb_*` assets were written with letters only (generate.py follows the
`prime` keyboards), so ?123 on them showed WM Keyboard's own pages. Gboard
keeps a `symbol` keyboard per ime: its base state is the first page and
`SUB_CATEGORY_1` (the =\\< key) the second, with the currency key and the
digits following the language. This reads those, matches each `gb_*` file back
to the ime and variant it came from (generate.py names it
`gb_<lang>_<slug(variant or ime)>`), and writes its `symbols` and `symbols2`
layers:

- Gboard's =\\< and ?123 keys flip between the two pages;
- its two bottom rows give way to the house symbols bottom row
  (ABC , 🌐 space . ⏎), which the keyboard rearranges by the user's settings;
- a key's long-press data is kept, its separate long-press labels are not.

Where several of Gboard's keyboards produce the file's variant for the same
language with different pages, the commonest rendering wins.
"""
import collections
import json
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import extract  # noqa: E402
import resolve  # noqa: E402
from axml import Ref  # noqa: E402

ALIAS = {"iw": "he", "in": "id", "ji": "yi", "ydd": "yi", "jw": "jv", "fil": "tl", "no": "nb", "nde": "nd", "na": "nau"}
BOTTOM_CODES = {"SWITCH_KEYBOARD", "SPACE", "ENTER"}
HOUSE_BOTTOM = [
    {"label": "ABC", "action": {"type": "letters"}, "width": 1.5},
    {"label": ",", "longPress": ["!", "?"], "role": "Comma"},
    {"label": "🌐", "action": {"type": "language_switch"}},
    {"label": " ", "action": {"type": "space"}, "width": 4},
    {"label": ".", "longPress": ["…", ",", "?", "!", ":", ";"], "role": "Period"},
    {"label": "⏎", "action": {"type": "enter"}, "width": 1.5},
]


def slug(text):
    s = re.sub(r"[^a-z0-9]+", "_", (text or "").lower()).strip("_")
    return s or "x"


def base_language(tag):
    b = str(tag).split("-")[0].lower()
    return ALIAS.get(b, b)


def symbol_keyboards(apk, node, env, params, out, depth=0):
    """Every `symbol` keyboard an ime reaches, as walk_keyboards finds the prime ones."""
    if depth > 10:
        return
    scope = {**env, **params}
    for c in resolve.children(node, scope):
        if c.tag == "include":
            href = resolve.subst(c.attrs.get("href"), params)
            if not isinstance(href, Ref):
                continue
            doc = apk.res(href)
            if doc is None:
                continue
            p = {**params, **doc.attrs}
            for k, v in c.attrs.items():
                if k != "href":
                    p[k] = resolve.subst(v, params)
            symbol_keyboards(apk, doc, env, p, out, depth + 1)
        elif c.tag == "keyboard_group":
            p = dict(params)
            for k, v in c.attrs.items():
                p[k] = resolve.subst(v, params)
            symbol_keyboards(apk, c, env, p, out, depth + 1)
        elif c.tag == "keyboard" and c.attrs.get("type") == "symbol":
            defs = [resolve.subst(c.attrs.get("def"), params)]
            merges = [
                resolve.subst(m.attrs.get("def"), params)
                for m in resolve.children(c, scope)
                if m.tag == "merge" and "def" in m.attrs
            ]
            out.append({
                "variant": params.get("variant"),
                "defs": [d for d in defs if isinstance(d, Ref)],
                "merges": [m for m in merges if isinstance(m, Ref)],
            })
        elif c.tag == "framework":
            symbol_keyboards(apk, c, env, params, out, depth + 1)


TEXT_FIELDS = ("press_data", "press_label", "label", "data", "long_press_data", "long_press_label")
RESOURCE_IDS = 0x7F000000


def literal_texts(kb, strings):
    """Key texts the symbol keyboards store as numbers: a digit as the integer
    itself (`press_data="1"` parses as 1), a backslash or quote as the id of a
    string resource. The prime keyboards never needed this."""
    for key in kb.softkeys.values():
        for name in TEXT_FIELDS:
            value = key.get(name)
            if isinstance(value, bool) or not isinstance(value, int):
                continue
            key[name] = strings.get(value, "") if value >= RESOURCE_IDS else str(value)


def read_all(apk_path, dump):
    zf = zipfile.ZipFile(apk_path)
    apk = resolve.Apk(zf, resolve.load_index(dump))
    strings = resolve.load_strings(dump)
    entries = []
    for f in zf.namelist():
        if not (f.startswith("res/") and f.endswith(".xml")):
            continue
        try:
            doc = apk.doc(f)
        except Exception:
            continue
        if doc is None or doc.tag != "framework":
            continue
        for node in doc.walk():
            if node.tag != "ime" or not node.attrs.get("string_id"):
                continue
            sid = node.attrs["string_id"]
            sid = sid if isinstance(sid, str) else hex(int(sid))
            language = node.attrs.get("language") or ""
            env = dict(resolve.PHONE, language=language)
            found = []
            symbol_keyboards(apk, node, env, {}, found)
            seen = set()
            for info in found:
                mark = (info["variant"], tuple(info["defs"]), tuple(info["merges"]))
                if mark in seen:
                    continue
                seen.add(mark)
                try:
                    kb = resolve.keyboard_of(apk, info, dict(env, variant=info["variant"] or ""))
                    literal_texts(kb, strings)
                    first = extract.page(apk, kb, frozenset())
                    second = extract.page(apk, kb, frozenset({"SUB_CATEGORY_1"}))
                except Exception:
                    continue
                entries.append({"ime": sid, "language": language, "variant": info["variant"], "first": first, "second": second})
    return entries


def house_page(rows, second):
    out = []
    for row in rows:
        if any(k.get("keycode") in BOTTOM_CODES for k in row):
            continue
        keys = []
        for k in row:
            code = k.get("keycode")
            width = round(k.get("w", 100.0) / 100.0, 2)
            if code == "SWITCH_SUB_CATEGORY":
                keys.append({"label": "?123" if second else "=\\<", "action": {"type": "symbols"}, "width": width})
            elif code == "DEL":
                keys.append({"label": "⌫", "action": {"type": "delete"}, "width": width})
            elif code:
                continue
            else:
                label = k.get("label") or k.get("text")
                if not label:
                    continue
                key = {"label": label}
                if k.get("text") and k["text"] != label:
                    key["output"] = k["text"]
                if width != 1.0:
                    key["width"] = width
                long = [v for v in k.get("long") or [] if v != label]
                if long:
                    key["longPress"] = long
                keys.append(key)
        if keys:
            out.append(keys)
    out.append(HOUSE_BOTTOM)
    return {"rows": out}


def render(entry):
    return json.dumps([[[k.get("label") for k in row] for row in entry[p]] for p in ("first", "second")], ensure_ascii=False)


def main():
    apk_path, dump, repo = sys.argv[1:4]
    dry = "--dry-run" in sys.argv
    entries = [e for e in read_all(apk_path, dump) if len(e["first"]) >= 3 and len(e["second"]) >= 3]
    by_variant = collections.defaultdict(list)
    for e in entries:
        by_variant[slug(e["variant"] or e["ime"].replace("ime_", ""))].append(e)
    assets = os.path.join(repo, "app/src/main/assets/layouts")
    written = unmatched = 0
    for name in sorted(os.listdir(assets)):
        if not (name.startswith("gb_") and name.endswith(".wmlayout.json")):
            continue
        path = os.path.join(assets, name)
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
        layout = doc["layout"]
        lang = layout["langId"]
        stem = name.split(".")[0]
        prefix = f"gb_{slug(lang)}_"
        variant = stem[len(prefix):] if stem.startswith(prefix) else ""
        candidates = by_variant.get(variant) or by_variant.get(re.sub(r"_\d+$", "", variant)) or []
        candidates = (
            [e for e in candidates if str(e["language"]).lower() == lang.lower()]
            or [e for e in candidates if base_language(e["language"]) == base_language(lang)]
            or candidates
        )
        if not candidates:
            unmatched += 1
            print("no Gboard symbol keyboard for", stem)
            continue
        commonest = collections.Counter(render(e) for e in candidates).most_common(1)[0][0]
        chosen = next(e for e in candidates if render(e) == commonest)
        layout["layers"]["symbols"] = house_page(chosen["first"], False)
        layout["layers"]["symbols2"] = house_page(chosen["second"], True)
        if not dry:
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
        written += 1
    print(f"wrote {written}, unmatched {unmatched}")


if __name__ == "__main__":
    main()
