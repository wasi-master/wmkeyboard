"""What the app already ships: its languages (read from the Kotlin sources) and its asset grids."""
import glob
import json
import os
import re

SCRIPT_DIR = "core/language/src/main/java/com/wasimaster/wmkeyboard/core"
ASSETS = "app/src/main/assets/layouts"


def _consts(root):
    out = {}
    for path in (f"{root}/{SCRIPT_DIR}/layout/AssetLayouts.kt", f"{root}/{SCRIPT_DIR}/layout/BuiltInLayouts.kt"):
        for name, value in re.findall(r'const val (\w+) = "([^"]+)"', open(path).read()):
            out[name] = value
    return out


def _resolve(tok, consts):
    tok = tok.strip()
    if tok.startswith('"'):
        return tok.strip('"')
    return consts.get(tok.split(".")[-1])


def _balanced(text, start):
    depth = 0
    in_str = False
    i = start
    while i < len(text):
        c = text[i]
        if in_str:
            if c == "\\":
                i += 1
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def _defs(text, consts):
    out = []
    for m in re.finditer(r"LanguageDef\(", text):
        end = _balanced(text, m.end() - 1)
        body = text[m.end():end]
        idm = re.search(r'\bid = "([^"]+)"', body)
        if not idm:
            continue
        name = re.search(r'englishName = "([^"]*)"', body)
        disp = re.search(r'displayName = "([^"]*)"', body)
        script = re.search(r"script = ScriptId\.(\w+)", body)
        locale = re.search(r'localeTag = "([^"]*)"', body)
        lay = re.search(r"layoutIds = listOf\(", body)
        ids = []
        if lay:
            lend = _balanced(body, lay.end() - 1)
            for tok in re.split(r",\s*", re.sub(r"//.*", "", body[lay.end():lend])):
                if tok.strip():
                    v = _resolve(tok, consts)
                    if v:
                        ids.append(v)
        out.append(
            dict(
                id=idm.group(1),
                name=name.group(1) if name else "",
                display=disp.group(1) if disp else "",
                script=script.group(1) if script else "LATIN",
                locale=locale.group(1) if locale else idm.group(1),
                layouts=ids,
            )
        )
    return out


def languages(root):
    consts = _consts(root)
    langs = {}
    hand = open(f"{root}/{SCRIPT_DIR}/script/Language.kt").read()
    corpus = open(f"{root}/{SCRIPT_DIR}/script/CorpusLanguages.kt").read()
    keyman = open(f"{root}/{SCRIPT_DIR}/script/KeymanLanguages.kt").read()
    for d in _defs(hand, consts) + _defs(corpus, consts):
        langs.setdefault(d["id"], dict(d, origin="hand"))
    for d in _defs(keyman, consts):
        langs.setdefault(d["id"], dict(d, origin="keyman"))
    m = re.search(r"val extraLayoutIds[^=]*=[^(]*mapOf\(", keyman)
    if m:
        end = _balanced(keyman, m.end() - 1)
        for key, body in re.findall(r'"([^"]+)" to listOf\(([^)]*)\)', keyman[m.end():end]):
            if key in langs:
                langs[key]["layouts"] += re.findall(r'"([^"]+)"', body)
    merged = {}
    m = re.search(r"val mergedInto[^=]*=[^(]*mapOf\(", keyman)
    if m:
        end = _balanced(keyman, m.end() - 1)
        merged = dict(re.findall(r'"([^"]+)" to "([^"]+)"', keyman[m.end():end]))
    return langs, merged


def assets(root):
    out = {}
    for path in sorted(glob.glob(f"{root}/{ASSETS}/*.wmlayout.json")):
        layout = json.load(open(path))["layout"]
        out[layout["id"]] = dict(layout, _file=os.path.basename(path))
    return out


FUNCTIONAL = {
    "shift", "delete", "space", "enter", "symbols", "symbols_shift", "language_switch", "emoji", "letters", "layer",
    "toolbox", "fn", "tool", "mod", "input_method_picker", "layout", "mode", "send_key",
}


def key_text(key):
    """What a key types, or None for a functional key and the comma and full stop slots."""
    action = key.get("action")
    if action and action.get("type") not in (None, "text", "keyman_key"):
        return None
    if key.get("role") in ("Comma", "Period"):
        return None
    return key.get("output") or key.get("label")


def letter_rows(layout):
    layers = layout.get("layers", {})
    base = layers.get("letters") or next(iter(layers.values()), {})
    rows = []
    for row in base.get("rows", []):
        cells = [t for t in (key_text(k) for k in row) if t and t.strip()]
        if cells:
            rows.append(cells)
    return rows
