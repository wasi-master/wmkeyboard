#!/usr/bin/env python3
"""Reads every keyboard out of a Gboard base.apk into one JSON file.

    aapt2 dump resources base.apk > resources.txt
    python3 -I extract.py base.apk resources.txt gboard.json

The result is plain data: one entry per ime and variant, each with its pages of
rows of keys. compare.py and generate.py take it from there. The APK and this
JSON stay out of the repository.
"""
import json
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import resolve
from axml import Ref


def text_of(key):
    for name in ("press_data", "data", "key_data", "param_data"):
        if isinstance(key.get(name), str):
            return key[name]
    for action in key.get("_actions", []):
        if action.get("type") == "PRESS" and isinstance(action.get("data"), str):
            return action["data"]
    return None


def label_of(key, text):
    for name in ("press_label", "label", "key_label", "label_value", "param_label"):
        if isinstance(key.get(name), str):
            return key[name]
    return text


def split(key, name):
    value = key.get(name)
    if not isinstance(value, str) or not value:
        return []
    return [v for v in value.split(key.get("_splitter", ",")) if v]


def keycode_of(key):
    for name in ("keycode", "press_keycode", "keycode_press", "keycode_normal", "key_code", "param_keycode"):
        if isinstance(key.get(name), str):
            return key[name]
    for action in key.get("_actions", []):
        if isinstance(action.get("keycode"), str):
            return action["keycode"]
    return None


def plain(key):
    """A softkey as data."""
    if key is None:
        return None
    text = text_of(key)
    out = {"text": text, "label": label_of(key, text)}
    code = keycode_of(key)
    if code:
        out["keycode"] = code
    alts = split(key, "long_press_data")
    if alts:
        out["long"] = alts
        labels = split(key, "long_press_label")
        if labels and labels != alts:
            out["longLabels"] = labels
    if isinstance(key.get("hint_label"), str):
        out["hint"] = key["hint_label"]
    for src, dst in (
        ("slideup_data", "up"), ("slidedown_data", "down"), ("slideleft_data", "left"), ("slideright_data", "right"),
        ("slide_down_data", "down"), ("slide_up_data", "up"),
    ):
        if isinstance(key.get(src), str):
            out[dst] = key[src]
    return {k: v for k, v in out.items() if v is not None}


def page(apk, kb, active):
    rows = []
    for row in resolve.grid(apk, kb, active):
        cells = [{"w": c["weight"], **plain(c["key"])} for c in row if c["key"] is not None]
        if cells:
            rows.append(cells)
    return rows


def sub_pages(kb):
    states = set()
    for state, _ex, _k, _v in kb.mappings:
        for alt in (state or "").split(","):
            for s in alt.split("+"):
                if s.startswith("SUB_CATEGORY_"):
                    states.add(s)
    return sorted(states)


def main():
    apk_path, dump, out_path = sys.argv[1:4]
    zf = zipfile.ZipFile(apk_path)
    index = resolve.load_index(dump)
    strings = resolve.load_strings(dump)
    apk = resolve.Apk(zf, index)
    files = [f for f in zf.namelist() if f.startswith("res/") and f.endswith(".xml")]
    framework = []
    for f in files:
        try:
            doc = apk.doc(f)
        except Exception:
            continue
        if doc is not None and doc.tag == "framework":
            framework.append(f)
    out = []
    imes = {}
    for f in framework:
        for node in apk.doc(f).walk():
            if node.tag == "ime" and node.attrs.get("string_id"):
                sid = node.attrs["string_id"]
                imes.setdefault(sid if isinstance(sid, str) else hex(int(sid)), (node, f))
    for string_id, (node, f) in sorted(imes.items()):
        language = node.attrs.get("language") or node.attrs.get("language_tag") or ""
        env = dict(resolve.PHONE, language=language)
        keyboards = resolve.walk_keyboards(apk, node, env, {})
        if not keyboards:
            out.append({"ime": string_id, "language": language, "class": node.attrs.get("class"), "pages": {}, "variant": None})
        seen = set()
        for info in keyboards:
            mark = (info.get("variant"), tuple(info["defs"]), tuple(info["merges"]))
            if mark in seen:
                continue
            seen.add(mark)
            label = info.get("variant_label")
            entry = {
                "ime": string_id,
                "language": language,
                "class": node.attrs.get("class"),
                "variant": info.get("variant") or None,
                "label": strings.get(int(label), "") if isinstance(label, Ref) else "",
                "pkTag": info.get("pk_language_tag") or None,
                "pkType": info.get("pk_layout_type") or None,
                "dynamic": info.get("dynamic_layout") or None,
                "pages": {},
            }
            try:
                var_env = dict(env, variant=info.get("variant") or "")
                kb = resolve.keyboard_of(apk, info, var_env)
                entry["handlers"] = sorted({h.rsplit(".", 1)[-1] for h in kb.handlers})
                entry["kbClass"] = kb.attrs.get("class")
                entry["defs"] = [hex(int(d)) for d in info["defs"] + info["merges"]]
                entry["pages"]["base"] = page(apk, kb, frozenset())
                shifted = page(apk, kb, frozenset({"SHIFT"}))
                if shifted != entry["pages"]["base"]:
                    entry["pages"]["shift"] = shifted
                for s in sub_pages(kb):
                    entry["pages"][s] = page(apk, kb, frozenset({s}))
            except Exception as e:  # one broken ime must not stop the run
                entry["error"] = repr(e)
            out.append(entry)
    with open(out_path, "w") as fh:
        json.dump(out, fh, ensure_ascii=False)
    print(len(out), "entries from", len(imes), "imes")


if __name__ == "__main__":
    main()
