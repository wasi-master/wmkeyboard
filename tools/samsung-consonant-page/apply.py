#!/usr/bin/env python3
"""Mark the vowel-sign page of each paginated Samsung Indic layout.

Samsung's Indic keyboards swap their vowel keys for vowel signs the moment a
consonant is typed: after क, the keys that read अ आ इ read क् का कि. Our
conversions of those boards (the `sam_*` assets) already carry the vowel signs,
on the page reached by the `1/4` key, `page2`. This script tells the keyboard
which page that is, by setting `afterConsonant` on the letters layer, and gives
each vowel-sign key the independent vowel it stands in for as its hold, so
कई can still be typed from the consonant page.

A layout qualifies when its `page2` has the same shape as its letters and at
least nine of its keys type a single Indic mark (a vowel sign, virama or
bindu) where the letters type something else. Re-running changes nothing.

Usage: python3 -I tools/samsung-consonant-page/apply.py [layouts dir]
"""

import json
import sys
import unicodedata
from pathlib import Path

PAGE = "page2"
MIN_MARKS = 9


def mark_of(key):
    """The single Indic combining mark this key types, or None."""
    if key.get("action"):
        return None
    text = key.get("output", key.get("label", ""))
    if len(text) != 1 or not 0x0900 <= ord(text) <= 0x0DFF:
        return None
    return text if unicodedata.category(text) in ("Mn", "Mc") else None


def text_of(key):
    if key.get("action"):
        return None
    return key.get("output", key.get("label", "")) or None


def pairs(letters, page):
    """(letters key, page key) for every seat both grids have, or None if their shapes differ."""
    if [len(r) for r in letters["rows"]] != [len(r) for r in page["rows"]]:
        return None
    return [(a, b) for ra, rb in zip(letters["rows"], page["rows"]) for a, b in zip(ra, rb)]


def apply(path):
    original = path.read_text(encoding="utf-8")
    doc = json.loads(original)
    layers = doc["layout"]["layers"]
    letters, page = layers.get("letters"), layers.get(PAGE)
    if letters is None or page is None:
        return False
    seats = pairs(letters, page)
    if seats is None:
        return False
    signs = [(a, b) for a, b in seats if mark_of(b) and text_of(a) and text_of(a) != mark_of(b)]
    if len(signs) < MIN_MARKS:
        return False
    letters["afterConsonant"] = PAGE
    for vowel_key, sign_key in signs:
        vowel = text_of(vowel_key)
        if mark_of(vowel_key) or sign_key.get("longPress"):
            continue
        sign_key["longPress"] = [vowel]
        sign_key["hideHint"] = True
    updated = json.dumps(doc, ensure_ascii=False, indent=2) + "\n"
    if updated != original:
        path.write_text(updated, encoding="utf-8")
    return True


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("app/src/main/assets/layouts")
    marked = [p.name for p in sorted(root.glob("sam_*.wmlayout.json")) if apply(p)]
    print(f"{len(marked)} layouts have a consonant page")
    for name in marked:
        print(" ", name)


if __name__ == "__main__":
    main()
