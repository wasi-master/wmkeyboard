#!/usr/bin/env python3
"""Regenerate VocabCatalog.kt from a wmkeyboard-data checkout.

The data repo carries one gzipped pack per word list at
`vocab/<lang>/<id>.wmvocab.json.gz`, with translation sidecars beside it at
`vocab/<lang>/<id>.tr.<code>.json.gz`. This reads each pack for its id, name
and word count, notes its compressed size and which sidecars exist, and
writes the Kotlin table between the GENERATED markers.

Usage:
  python3 tools/vocab/generate_catalog.py --data ../wmkeyboard-data
  python3 tools/vocab/generate_catalog.py --data ../wmkeyboard-data --check
"""

from __future__ import annotations

import argparse
import gzip
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "core/tools/src/main/java/com/wasimaster/wmkeyboard/core/vocab/VocabCatalog.kt"
START = "    // GENERATED — do not edit by hand; run tools/vocab/generate_catalog.py."
END = "    // END GENERATED"


def kotlin_string(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def entries(data: Path) -> list[dict]:
    out = []
    for pack_file in sorted((data / "vocab").glob("*/*.wmvocab.json.gz")):
        lang = pack_file.parent.name
        with gzip.open(pack_file, "rt", encoding="utf-8") as handle:
            pack = json.load(handle)
        meta = pack["pack"]
        pack_id = meta["id"]
        codes = sorted(
            p.name[len(pack_id) + 4 : -len(".json.gz")]
            for p in pack_file.parent.glob(f"{pack_id}.tr.*.json.gz")
        )
        out.append(
            {
                "id": pack_id,
                "name": meta["name"],
                "langId": meta.get("langId", lang),
                "sourceId": meta.get("sourceId") or pack_id,
                "wordCount": len(pack["words"]),
                "approxGzBytes": pack_file.stat().st_size,
                "translationCodes": codes,
            }
        )
    return out


def render(items: list[dict]) -> str:
    lines = ["    val entries: List<VocabCatalogEntry> = listOf("]
    for item in items:
        codes = ", ".join(kotlin_string(c) for c in item["translationCodes"])
        lines.append(
            "        VocabCatalogEntry(\n"
            f"            {kotlin_string(item['id'])}, {kotlin_string(item['name'])}, "
            f"{kotlin_string(item['langId'])}, {kotlin_string(item['sourceId'])},\n"
            f"            {item['wordCount']}, {item['approxGzBytes']}L,\n"
            f"            listOf({codes}),\n"
            "        ),"
        )
    lines.append("    )")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data", required=True, help="path to a wmkeyboard-data checkout")
    parser.add_argument("--check", action="store_true", help="verify without writing")
    args = parser.parse_args()
    items = entries(Path(args.data).expanduser().resolve())
    if not items:
        print("no packs found", file=sys.stderr)
        return 1
    source = CATALOG.read_text(encoding="utf-8")
    pattern = re.compile(re.escape(START) + r"\n.*?\n" + re.escape(END), re.S)
    if not pattern.search(source):
        print("markers not found in VocabCatalog.kt", file=sys.stderr)
        return 1
    updated = pattern.sub(START + "\n" + render(items) + "\n" + END, source)
    if args.check:
        if updated != source:
            print("VocabCatalog.kt is out of date")
            return 1
        print("VocabCatalog.kt is up to date")
        return 0
    CATALOG.write_text(updated, encoding="utf-8")
    print(f"wrote {len(items)} entries to {CATALOG.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
