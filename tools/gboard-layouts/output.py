"""Writes generate.py's decisions into the repository."""
import json
import os

import ours

HEADER = '''package com.wasimaster.wmkeyboard.core.script

/**
 * The languages only Gboard's keyboards type. Generated; do not hand-edit.
 *
 * Produced by tools/gboard-layouts/generate.py from a Gboard APK. Every entry is
 * a language Gboard ships a keyboard for and nothing else here listed, with the
 * layout that keyboard became. The names are Gboard's own identifiers, spelled
 * out, so they are English names rather than endonyms; [KeymanLanguages] has the
 * same gap for the same reason.
 *
 * Kept apart from [LanguageRegistry]'s hand-written list for the reasons that
 * file gives: a generated file and a hand-maintained one should not share a path,
 * and one big list in one initializer runs into the JVM's method size limit.
 */
object GboardLanguages {
'''


def kt(text):
    return text.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def write_languages(root, d):
    langs = sorted(d.new_languages.values(), key=lambda l: l["id"])
    chunks = [langs[i:i + 100] for i in range(0, len(langs), 100)]
    out = [HEADER]
    for n, chunk in enumerate(chunks):
        out.append(f"    private val chunk{n}: List<LanguageDef> by lazy {{ listOf(\n")
        for lang in chunk:
            ids = d.extra[lang["id"]]
            out.append("        LanguageDef(\n")
            out.append(f'            id = "{kt(lang["id"])}",\n')
            out.append(f'            displayName = "{kt(lang["name"])}",\n')
            out.append(f'            englishName = "{kt(lang["name"])}",\n')
            out.append(f'            script = ScriptId.{lang["script"]},\n')
            out.append(f'            localeTag = "{kt(lang["locale"])}",\n')
            if len(ids) == 1:
                out.append(f'            layoutIds = listOf("{ids[0]}"),\n')
            else:
                out.append("            layoutIds = listOf(\n")
                for i in ids:
                    out.append(f'                "{i}",\n')
                out.append("            ),\n")
            out.append("        ),\n")
        out.append("    ) }\n\n")
    out.append("    val all: List<LanguageDef> by lazy {\n        buildList {\n")
    for n in range(len(chunks)):
        out.append(f"            addAll(chunk{n})\n")
    out.append("        }\n    }\n\n")
    out.append("    /**\n     * Keyboards for a language the app already carries, one language a line:\n")
    out.append("     * its id, a tab, then its layouts. A table rather than a mapOf so the\n")
    out.append("     * keyboard's process pays for one string and a short loop at startup, not\n")
    out.append("     * an initializer ART refuses to compile.\n     */\n")
    out.append("    val extraLayoutIds: Map<String, List<String>> by lazy {\n")
    out.append("        EXTRA_TABLE.lineSequence().filter { it.isNotEmpty() }.associate { line ->\n")
    out.append("            val fields = line.split('\\t')\n")
    out.append("            fields[0] to fields.drop(1)\n")
    out.append("        }\n    }\n\n")
    known = {l["id"] for l in langs}
    rows = [
        lang + "\\t" + "\\t".join(ids)
        for lang, ids in sorted(d.extra.items())
        if lang not in known
    ]
    out.append("    private const val EXTRA_TABLE: String =\n")
    # A const string is capped at 64 KB of UTF-8; the table is well under.
    for i, row in enumerate(rows):
        tail = " +" if i < len(rows) - 1 else ""
        out.append(f'        "{row}\\n"{tail}\n')
    out.append("}\n")
    path = f"{root}/{ours.SCRIPT_DIR}/script/GboardLanguages.kt"
    with open(path, "w") as fh:
        fh.write("".join(out))


def dumps(doc):
    return json.dumps(doc, ensure_ascii=False, indent=2) + "\n"


def write_layouts(root, d):
    for file_id, layout in d.new_layouts.items():
        doc = {"format": "wmkeyboard-layout", "version": 1, "layout": layout}
        with open(f"{root}/{ours.ASSETS}/{file_id}.wmlayout.json", "w") as fh:
            fh.write(dumps(doc))


def apply_merges(root, d):
    touched = 0
    added = 0
    for asset_id, plan in d.merges.items():
        path = f"{root}/{ours.ASSETS}/{d.assets[asset_id]['_file']}"
        text = open(path).read()
        doc = json.loads(text)
        changed = False
        for name, layer in doc["layout"]["layers"].items():
            if name.startswith("symbols") or name in ("number", "phone", "date", "time", "datetime", "fn"):
                continue
            for row in layer.get("rows", []):
                for key in row:
                    action = key.get("action")
                    if action and action.get("type") not in (None, "text"):
                        continue
                    if key.get("role"):
                        continue
                    typed = key.get("output") or key.get("label")
                    extra = plan.get(typed)
                    if not extra:
                        continue
                    have = key.get("longPress", [])
                    fresh = [a for a in extra if a not in have and a != typed and a != key.get("shiftLabel")]
                    if fresh:
                        key["longPress"] = have + fresh
                        changed = True
                        added += len(fresh)
        if changed:
            # Every shipped file is this one shape, so a merge's diff is its keys.
            trailing = "\n" if text.endswith("\n") else ""
            with open(path, "w") as fh:
                fh.write(json.dumps(doc, ensure_ascii=False, indent=2) + trailing)
            touched += 1
    print("merged", added, "letters into", touched, "layouts")


def write_all(root, d):
    write_layouts(root, d)
    write_languages(root, d)
    apply_merges(root, d)
