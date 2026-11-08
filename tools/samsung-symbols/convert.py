#!/usr/bin/env python3
"""Samsung Keyboard's symbol pages onto the shipped `sam_*` layouts.

The `sam_*` assets were generated with letters only, so their ?123 pages fell
back to WM Keyboard's own and looked like every other layout's. This reads the
Samsung extraction (`samsung-layouts/`, kept out of git, see its README) and
writes each layout's `symbols` and `symbols2` layers from the board Samsung
uses for that language and input type:

- page 1 is the board as dumped, page 2 is every key's `pages["1"]`;
- Samsung's page key becomes a `1/2` / `2/2` key that flips between them;
- the bottom row is the house symbols bottom row (ABC , 🌐 space . ⏎), which
  the keyboard rearranges by the user's own bottom-row settings anyway.

Phone-pad boards (one page) are skipped: those layouts keep the keypad's own.
`--english` also writes `sam_en_qwerty`, Samsung's English board, which was
trimmed as a copy of the plain QWERTY before it carried its own symbols.

Usage: python3 tools/samsung-symbols/convert.py [--english] [--dry-run]
"""
import collections
import csv
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "samsung-layouts"
ASSETS = ROOT / "app/src/main/assets/layouts"

BOTTOM = [
    {"label": "ABC", "action": {"type": "letters"}, "width": 1.5},
    {"label": ",", "longPress": ["!", "?"], "role": "Comma"},
    {"label": "🌐", "action": {"type": "language_switch"}},
    {"label": " ", "action": {"type": "space"}, "width": 4},
    {"label": ".", "longPress": ["…", ",", "?", "!", ":", ";"], "role": "Period"},
    {"label": "⏎", "action": {"type": "enter"}, "width": 1.5},
]
ZWNJ = 0x200C


def standard_hashes():
    by = collections.defaultdict(dict)
    with open(DATA / "index.tsv") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            if r["region"] == "GLOBAL" and r["board"] == "standard" and r["number_row"] == "0":
                by[(r["locale"], r["input_type"])][r["mode"]] = r["hash"]
    return by


def boards():
    out = {}
    with open(DATA / "boards.jsonl") as f:
        for line in f:
            b = json.loads(line)
            out[b["hash"]] = b
    return out


def char_key(k):
    """One character key of a page, or None for a key that is not one."""
    label = k.get("l")
    code = k.get("code")
    if code == ZWNJ:
        return {"label": "ZWNJ", "output": "‌"}
    if not label:
        return None
    key = {"label": label}
    if code and code > 0 and chr(code) != label:
        key["output"] = chr(code)
    pop = [p for p in k.get("pop") or [] if p and p != label]
    if pop:
        key["longPress"] = pop
    return key


def page(board, second):
    rows = []
    for row in board["rows"]:
        if any(k.get("fn") == "SPACE" for k in row):
            continue  # the bottom row: the house one goes on instead
        keys = []
        for k in row:
            fn = k.get("fn")
            if fn == "PAGE":
                keys.append({"label": "2/2" if second else "1/2", "action": {"type": "symbols"}, "width": 1.5})
            elif fn == "DEL":
                keys.append({"label": "⌫", "action": {"type": "delete"}, "width": 1.5})
            elif fn:
                continue
            else:
                src = (k.get("pages") or {}).get("1") if second else k
                if src is None:
                    src = k
                key = char_key(src)
                if key:
                    keys.append(key)
        if keys:
            rows.append(keys)
    rows.append(BOTTOM)
    return {"rows": rows}


def symbol_layers(board):
    if board.get("pages", 1) < 2:
        return None
    return {"symbols": page(board, False), "symbols2": page(board, True)}


def letters_layer(board):
    """Samsung's letter board in the house shape (only used for English)."""
    rows = []
    for row in board["rows"]:
        if any(k.get("fn") == "SPACE" for k in row):
            continue
        keys = []
        for k in row:
            fn = k.get("fn")
            if fn == "SHIFT":
                keys.append({"label": "⇧", "action": {"type": "shift"}, "width": 1.5})
            elif fn == "DEL":
                keys.append({"label": "⌫", "action": {"type": "delete"}, "width": 1.5})
            elif fn:
                continue
            else:
                key = char_key(k)
                if key:
                    keys.append(key)
        rows.append(keys)
    rows.append([dict(BOTTOM[0], label="?123", action={"type": "symbols"})] + BOTTOM[1:])
    return {"rows": rows}


def main():
    dry = "--dry-run" in sys.argv
    hashes = standard_hashes()
    all_boards = boards()
    with open(DATA / "COMPARISON.tsv") as f:
        targets = [r for r in csv.DictReader(f, delimiter="\t") if r["target"].startswith("asset_sam_")]
    written = skipped = 0
    for r in targets:
        path = ASSETS / (r["target"].removeprefix("asset_") + ".wmlayout.json")
        board = all_boards[hashes[(r["locale"], r["input_type"])]["symbols"]]
        layers = symbol_layers(board)
        if layers is None:
            skipped += 1
            continue
        doc = json.loads(path.read_text(encoding="utf-8"))
        doc["layout"]["layers"].update(layers)
        if not dry:
            path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        written += 1
    if "--english" in sys.argv:
        h = hashes[("en_US", "qwerty")]
        doc = {
            "format": "wmkeyboard-layout",
            "version": 1,
            "layout": {
                "id": "asset_sam_en_qwerty",
                "name": "English (Samsung)",
                "langId": "en",
                "version": 2,
                "layers": {"letters": letters_layer(all_boards[h["letters"]]), **symbol_layers(all_boards[h["symbols"]])},
            },
        }
        if not dry:
            (ASSETS / "sam_en_qwerty.wmlayout.json").write_text(
                json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        written += 1
    print(f"wrote {written}, skipped {skipped} (one-page keypads)")


if __name__ == "__main__":
    main()
