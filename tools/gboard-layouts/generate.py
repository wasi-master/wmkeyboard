#!/usr/bin/env python3
"""Compares Gboard's keyboards with the app's and writes what the app lacks.

    python3 -I generate.py <repo> gboard.json [--write]

Without --write it only prints the plan. With it, it writes:

- assets/layouts/gb_*.wmlayout.json for each keyboard the app does not have,
- GboardLanguages.kt for each language only Gboard has,
- appended long-press letters on the app's own layouts with the same or nearly
  the same keys. Only letters and marks are added, after what a key already has,
  so nothing a key offers moves.

The rules, in the order they apply, are in decide().
"""
import collections
import json
import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ours

GENERIC = {"qwertz", "azerty", "dvorak", "colemak", "dvorak_spanish", "pcqwerty"}
SKIP_IME = re.compile(
    r"transliteration|handwriting|morse|^ime_zh_|^ime_%s|^ime_ja_|international_phonetic|^ime_ko_10key"
)
ALIAS = {"iw": "he", "in": "id", "ji": "yi", "ydd": "yi", "jw": "jv", "fil": "tl", "no": "nb", "nde": "nd", "na": "nau"}
SUBTAG = {
    "LATIN": "Latn", "CYRILLIC": "Cyrl", "ARABIC": "Arab", "DEVANAGARI": "Deva", "BENGALI": "Beng", "GURMUKHI": "Guru",
    "GUJARATI": "Gujr", "ORIYA": "Orya", "TAMIL": "Taml", "TELUGU": "Telu", "KANNADA": "Knda", "MALAYALAM": "Mlym",
    "SINHALA": "Sinh", "THAI": "Thai", "LAO": "Laoo", "KHMER": "Khmr", "MYANMAR": "Mymr", "ETHIOPIC": "Ethi",
    "TIBETAN": "Tibt", "HEBREW": "Hebr", "GREEK": "Grek", "ARMENIAN": "Armn", "GEORGIAN": "Geor", "HANGUL": "Hang",
    "TIFINAGH": "Tfng", "NKO": "Nkoo", "CANADIAN_ABORIGINAL_SYLLABICS": "Cans", "CHEROKEE": "Cher", "OL_CHIKI": "Olck",
    "MEETEI_MAYEK": "Mtei", "VAI": "Vaii", "YI": "Yiii", "SYRIAC": "Syrc", "THAANA": "Thaa",
    "BATAK": "Batk", "BUHID": "Buhd", "CHAM": "Cham", "TAGALOG": "Tglg", "BALINESE": "Bali", "TAGBANWA": "Tagb",
    "CHAKMA": "Cakm", "JAVANESE": "Java", "BUGINESE": "Bugi", "HANUNOO": "Hano", "LEPCHA": "Lepc", "LIMBU": "Limb",
    "SUNDANESE": "Sund", "REJANG": "Rjng", "MONGOLIAN": "Mong", "NEWA": "Newa", "KAYAH_LI": "Kali", "SYLOTI_NAGRI": "Sylo",
    "TAI_THAM": "Lana", "TAI_VIET": "Tavt", "TAI_LE": "Tale", "NEW_TAI_LUE": "Talu", "SAURASHTRA": "Saur", "BAMUM": "Bamu",
    "COPTIC": "Copt", "LISU": "Lisu", "HANIFI_ROHINGYA": "Rohg", "ADLAM": "Adlm", "WARANG_CITI": "Wara",
}
PUNCT_COMMA = {",", "،", "、", "，", "፣", "၊", "་"}
PUNCT_PERIOD = {".", "۔", "।", "。", "።", "။", "።", "༎", "᱾"}


def script_ids(root):
    s = open(f"{root}/{ours.SCRIPT_DIR}/script/Script.kt").read()
    b = s[s.index("enum class ScriptId"):]
    b = b[: b.index("\n}")]
    b = re.sub(r"/\*.*?\*/", "", b, flags=re.S)
    b = re.sub(r"//.*", "", b)
    return re.findall(r"\b[A-Z][A-Z_0-9]+\b", b)


class Scripts:
    FIXED = {"CANADIAN": "CANADIAN_ABORIGINAL_SYLLABICS", "CJK": "HAN", "HIRAGANA": "JAPANESE", "KATAKANA": "JAPANESE",
             "OL": "OL_CHIKI", "MEETEI": "MEETEI_MAYEK", "KAYAH": "KAYAH_LI", "TAGALOG": "TAGALOG", "NEW": "NEW_TAI_LUE"}

    def __init__(self, ids):
        self.ids = sorted(ids, key=len, reverse=True)

    def of_char(self, ch):
        try:
            name = unicodedata.name(ch)
        except ValueError:
            return None
        spaced = name.replace("-", " ")
        for sid in self.ids:
            if spaced.startswith(sid.replace("_", " ") + " "):
                return sid
        first = spaced.split()[0]
        return self.FIXED.get(first)

    def of_rows(self, texts):
        count = collections.Counter()
        for t in texts:
            for ch in t:
                if unicodedata.category(ch)[0] in "LM":
                    s = self.of_char(ch)
                    if s:
                        count[s] += 1
        return count.most_common(1)[0][0] if count else None


def is_text(k):
    t = k.get("text")
    if not t or not t.strip():
        return False
    if t.startswith("SUB_CATEGORY_"):
        return False
    return not all(unicodedata.category(c) == "Co" for c in t)


def page_target(k):
    t = k.get("text") or ""
    return t if t.startswith("SUB_CATEGORY_") else None


def letter_grid(page):
    """Rows of what a Gboard page types, without its bottom row."""
    rows = []
    for row in page:
        if any(k.get("keycode") == "SPACE" for k in row):
            continue
        cells = [k["text"] for k in row if is_text(k)]
        if cells:
            rows.append(tuple(cells))
    return tuple(rows)


def our_grid(layout):
    rows = ours.letter_rows(layout)
    return tuple(tuple(r) for r in rows if not _is_bottom(r))


def _is_bottom(row):
    return len(row) <= 3


def flat(grid):
    return [c for r in grid for c in r]


def pretty_language(ime):
    name = re.sub(r"^ime_", "", ime)
    name = re.sub(r"_dynamic$", "", name)
    script = ""
    m = re.search(r"_(latin|devanagari|arabic|cyrillic|bengali|gujarati|kannada|telugu|ethiopic|tibetan|odia)(?:_|$)", name)
    if m:
        script = m.group(1).title()
        name = name.replace("_" + m.group(1), "")
    words = " ".join(w.title() for w in name.split("_") if w)
    return f"{words} ({script})" if script else words


def slug(text):
    s = re.sub(r"[^a-z0-9]+", "_", (text or "").lower()).strip("_")
    return s or "x"


def keep_alt(alt, latin_ok):
    """An alternate worth appending to one of our own keys: a letter or a mark."""
    if unicodedata.category(alt[0])[0] not in "LM":
        return False
    return latin_ok or not any("a" <= c.lower() <= "z" for c in alt)


def ethiopic_family(text):
    """A fidel's other forms. Gboard shows them in keys it fills once one is typed."""
    if len(text) != 1 or not 0x1200 <= ord(text) <= 0x135A:
        return []
    start = ord(text) - (ord(text) - 0x1200) % 8
    forms = []
    for code in range(start, start + 8):
        try:
            if unicodedata.name(chr(code)).startswith("ETHIOPIC SYLLABLE") and chr(code) != text:
                forms.append(chr(code))
        except ValueError:
            pass
    return forms


def build_key(k, shift=None, dynamic=False):
    text = k["text"]
    label = k.get("label") or text
    out = {"label": label}
    if label != text:
        out["output"] = text
    if shift and shift != text and shift != text.upper():
        out["shiftLabel"] = shift
    long = []
    for alt in list(k.get("long", [])) + (ethiopic_family(text) if dynamic else []):
        if alt != text and alt not in long:
            long.append(alt)
    if long:
        out["longPress"] = long
    hint = k.get("hint")
    if hint and long and hint != long[0]:
        out["hint"] = hint
    flick = {d: k[d] for d in ("left", "up", "right", "down") if isinstance(k.get(d), str) and k[d]}
    if flick:
        out["flick"] = flick
    return out


def width_of(k, unit):
    w = k.get("w", 100.0) * unit / 100
    return round(w, 2)


def with_width(key, w):
    if abs(w - 1) > 0.001:
        key["width"] = w
    return key


def row_unit(row):
    return 100.0 if max(k.get("w", 100.0) for k in row) < 10 else 1.0


def build_page(page, shift_page, next_page, index, total, dynamic=False):
    """One page of ours from one of theirs: its rows, then the house bottom row."""
    rows = []
    symbols_label = "?123"
    bottom_text = []
    has = set()
    grid_w = 0.0
    for r_i, row in enumerate(page):
        is_bottom = any(k.get("keycode") == "SPACE" for k in row)
        unit = row_unit(row)
        shift_row = None
        if shift_page and r_i < len(shift_page) and len(shift_page[r_i]) == len(row):
            shift_row = shift_page[r_i]
        if is_bottom:
            for k in row:
                code = k.get("keycode")
                if code == "DEL":
                    has.add("delete_bottom")
                elif not k.get("text") and code in (None, "") and k.get("label"):
                    symbols_label = k["label"]
                elif is_text(k):
                    bottom_text.append(k)
            continue
        cells = []
        for c_i, k in enumerate(row):
            code = k.get("keycode")
            w = width_of(k, unit)
            target = page_target(k)
            if target or (code is None and not is_text(k) and total > 1 and any(x.get("keycode") == "DEL" for x in row) and c_i == 0):
                label = f"{index + 1}/{total}"
                action = {"type": "layer", "layer": next_page} if next_page else {"type": "letters"}
                cells.append(with_width({"label": label, "action": action}, w))
            elif code in ("SHIFT_DOWN", "SHIFT_LEFT", "LOCK_SHIFT"):
                has.add("shift")
                cells.append(with_width({"label": "⇧", "action": {"type": "shift"}}, w))
            elif code == "DEL":
                has.add("delete")
                cells.append(with_width({"label": "⌫", "action": {"type": "delete"}}, w))
            elif code == "ENTER":
                has.add("enter")
                cells.append(with_width({"label": "⏎", "action": {"type": "enter"}}, w))
            elif is_text(k):
                shifted = None
                if shift_row and is_text(shift_row[c_i]):
                    shifted = shift_row[c_i]["text"]
                cells.append(with_width(build_key(k, shifted, dynamic), w))
        if cells:
            rows.append(cells)
            grid_w = max(grid_w, sum(c.get("width", 1) for c in cells))
    comma = None
    period = None
    extras = []
    for k in bottom_text:
        t = k["text"]
        if comma is None and t in PUNCT_COMMA:
            comma = k
        elif period is None and t in PUNCT_PERIOD:
            period = k
        else:
            extras.append(k)
    grid_w = max(grid_w, 6.5)
    comma_key = build_key(comma) if comma else {"label": ",", "longPress": ["!", "?"]}
    comma_key["role"] = "Comma"
    period_key = build_key(period) if period else {"label": ".", "longPress": ["…", ",", "?", "!", ":", ";"]}
    period_key["role"] = "Period"
    bottom = [
        {"label": symbols_label, "action": {"type": "symbols"}, "width": 1.5},
        comma_key,
        {"label": "🌐", "action": {"type": "language_switch"}},
    ]
    used = 1.5 + 1 + 1 + 1 + 1.5 + len(extras) * 0.75
    if "delete" not in has:
        used += 1
    space = {"label": " ", "action": {"type": "space"}, "width": round(max(grid_w - used, 2.0), 2)}
    bottom.append(space)
    for k in extras:
        key = build_key(k)
        if not key["label"].strip() or unicodedata.category(key["label"][0]) == "Cf":
            key["label"] = "◌│◌"
            key["output"] = k["text"]
        bottom.append(with_width(key, 0.75))
    bottom.append(period_key)
    if "delete" not in has:
        bottom.append({"label": "⌫", "action": {"type": "delete"}})
    bottom.append({"label": "⏎", "action": {"type": "enter"}, "width": 1.5})
    rows.append(bottom)
    return rows


def build_layers(entry):
    names = [p for p in entry["pages"] if p.startswith("SUB_CATEGORY")]
    order = ["base"] + names
    layers = {}
    total = len(order)
    for i, p in enumerate(order):
        name = "letters" if p == "base" else "page" + p.rsplit("_", 1)[-1]
        nxt = None
        if i + 1 < total:
            nxt = "page" + order[i + 1].rsplit("_", 1)[-1]
        shift = entry["pages"].get("shift") if p == "base" else None
        layers[name] = {"rows": build_page(entry["pages"][p], shift, nxt, i, total, bool(entry.get("dynamic")))}
    return layers


def alternates_of(entry):
    """Each key's long-press letters, by what the key types, across all its pages."""
    out = {}
    for name, page in entry["pages"].items():
        if name == "shift":
            continue
        for row in page:
            for k in row:
                if is_text(k) and k.get("long"):
                    bucket = out.setdefault(k["text"], [])
                    for alt in k["long"]:
                        if alt != k["text"] and alt not in bucket:
                            bucket.append(alt)
    return out


class Decider:
    def __init__(self, root, gboard):
        self.root = root
        self.scripts = Scripts(script_ids(root))
        self.langs, self.merged = ours.languages(root)
        self.assets = ours.assets(root)
        self.gboard = gboard
        self.report = collections.defaultdict(list)
        self.new_layouts = {}
        self.new_languages = {}
        self.extra = collections.defaultdict(list)
        self.merges = collections.defaultdict(dict)
        self.by_lang_assets = collections.defaultdict(list)
        for lang_id, lang in self.langs.items():
            for i in lang["layouts"]:
                if i in self.assets:
                    self.by_lang_assets[lang_id].append(i)

    def resolve_language(self, entry, script):
        tag = entry["language"]
        parts = tag.split("-")
        base = ALIAS.get(parts[0], parts[0])
        candidates = [tag, base]
        sub = SUBTAG.get(script)
        if sub:
            candidates.insert(0, f"{base}-{sub}")
        candidates += [i for i in self.langs if i.split("-")[0] == base]
        for c in candidates:
            lang = self.langs.get(c) or self.langs.get(self.merged.get(c, ""))
            if lang and lang["script"] == script:
                return lang["id"], False
        new_id = base if base not in self.langs and base not in self.merged else f"{base}-{sub or script.title()}"
        return new_id, True

    def mergeable(self, asset_id):
        a = self.assets[asset_id]
        name = asset_id.replace("asset_", "")
        if name.startswith(("kmn_", "gb_")) or name.endswith(("_t9", "_flick")) or "gboard_plus" in name:
            return False
        # Latin keys that spell another script: a letter added there spells nothing.
        if "_rom" in name or "phonetic" in name or "translit" in name:
            return False
        if a.get("handwriting") or a.get("composer") or a.get("keyman"):
            return False
        return True

    def run(self):
        by_lang = collections.defaultdict(list)
        for e in self.gboard:
            ime = str(e["ime"])
            base = e["pages"].get("base")
            if not base or not ime.startswith("ime_") or SKIP_IME.search(ime):
                if ime.startswith("ime_") and "transliteration" in ime:
                    self.report["engine:transliteration"].append(ime)
                elif ime.startswith("ime_"):
                    self.report["engine:other"].append(ime)
                continue
            grid = letter_grid(base)
            texts = flat(grid)
            if len(texts) < 6:
                self.report["too_few_keys"].append(ime)
                continue
            script = self.scripts.of_rows(texts)
            if script is None:
                self.report["no_script"].append(ime)
                continue
            if e.get("dynamic") and any(not (k.get("text") or "x").strip() for r in base[:-1] for k in r):
                # The blank keys are placeholders Gboard fills after a consonant.
                # Only the vowel signs live there, and those are on long-press.
                self.report["dynamic_blank_keys_dropped"].append(ime)
            if e.get("variant") in GENERIC or e.get("variant") == "clariti":
                self.report["generic_variant"].append(ime)
                continue
            if e["language"].split("-")[0] == "zz":
                continue
            lang_id, is_new = self.resolve_language(e, script)
            by_lang[lang_id].append((e, grid, script, is_new))
        for lang_id, items in sorted(by_lang.items()):
            self.decide(lang_id, items)
        self.add_pc_board()
        for asset_id in [a for a, plan in self.merges.items() if not plan]:
            del self.merges[asset_id]

    def add_pc_board(self):
        """Gboard's desktop-shaped board, once: it is the same keys in every language."""
        pc = next(e for e in self.gboard if e.get("variant") == "pcqwerty" and e["language"].startswith("en"))
        for name in ("base", "shift"):
            page = pc["pages"].get(name)
            fill = {"base": ["`1234567890-=", "qwertyuiop[]\\"], "shift": ["~!@#$%^&*()_+", "QWERTYUIOP{}|"]}[name]
            for row, chars in zip(page, fill):
                for k, c in zip(row, chars):
                    if not k.get("text"):
                        k["text"] = c
                        k["label"] = c
        self.add_layout("en", pc, "LATIN", False, {})

    def decide(self, lang_id, items):
        """One language: what to merge, what to add."""
        # Merged first, so a qwerty adding only long-press letters is known about
        # before asking whether the language still needs a qwerty.
        own = [i for i in self.by_lang_assets.get(lang_id, [])]
        grids = {i: our_grid(self.assets[i]) for i in own}
        seen_grids = {}
        for e, grid, script, is_new in items:
            seen_grids.setdefault(grid, []).append(e)
        specific = [g for g, es in seen_grids.items() if any(x.get("variant") != "qwerty" for x in es)]
        for grid, es in seen_grids.items():
            entry = next((x for x in es if x.get("variant") != "qwerty"), es[0])
            script = next(s for e, g, s, n in items if g == grid)
            is_new = next(n for e, g, s, n in items if g == grid)
            alts = {}
            for x in es:
                for t, a in alternates_of(x).items():
                    b = alts.setdefault(t, [])
                    for v in a:
                        if v not in b:
                            b.append(v)
            exact = [i for i, g in grids.items() if g == grid]
            close = []
            letters = set(flat(grid))
            for i, g in grids.items():
                if i in exact:
                    continue
                theirs = set(flat(g))
                if theirs and len(letters & theirs) >= 0.9 * max(len(letters), len(theirs)):
                    close.append(i)
            own_mergeable = [i for i in exact + close if self.mergeable(i)]
            added = 0
            latin_ok = script == "LATIN"
            for i in own_mergeable:
                added += self.note_merge(i, alts, latin_ok)
            is_qwerty_only = entry.get("variant") == "qwerty"
            builtin = self.builtin_has(lang_id, grid)
            if builtin or [i for i in exact if self.mergeable(i)]:
                self.report["same_grid"].append((entry["ime"], lang_id))
                continue
            if exact:
                # Only a converted Keyman grid is the same: it runs on its rules,
                # so it keeps them, and Gboard's joins it if it offers anything more.
                if not alts:
                    self.report["same_as_keyman"].append((entry["ime"], lang_id))
                    continue
            if is_qwerty_only and (specific or any(self.mergeable(i) for i in own)):
                self.report["qwerty_covered"].append((entry["ime"], lang_id))
                continue
            self.add_layout(lang_id, entry, script, is_new, alts)

    def builtin_has(self, lang_id, grid):
        return lang_id in BUILTIN_GRIDS and grid in BUILTIN_GRIDS[lang_id]

    def note_merge(self, asset_id, alts, latin_ok):
        added = 0
        plan = self.merges[asset_id]
        for text, values in alts.items():
            for v in values:
                if keep_alt(v, latin_ok):
                    bucket = plan.setdefault(text, [])
                    if v not in bucket:
                        bucket.append(v)
                        added += 1
        return added

    def add_layout(self, lang_id, entry, script, is_new, alts):
        if is_new and lang_id not in self.new_languages:
            name = pretty_language(entry["ime"])
            if "-" in lang_id and "(" not in name:
                name = f"{name} ({script.replace('_', ' ').title()})"
            self.new_languages[lang_id] = dict(
                id=lang_id,
                name=name,
                script=script,
                locale=lang_id,
            )
        lang_name = self.new_languages.get(lang_id, {}).get("name") or self.langs[lang_id]["name"]
        variant = slug(entry.get("variant") or entry["ime"].replace("ime_", ""))
        file_id = f"gb_{slug(lang_id)}_{variant}"
        n = 2
        while file_id in self.new_layouts:
            file_id = f"gb_{slug(lang_id)}_{variant}_{n}"
            n += 1
        label = entry.get("label") or ""
        own_name = label.lower() == pretty_language(entry["ime"]).lower() or label.lower() in lang_name.lower()
        title = lang_name if not label or own_name else f"{lang_name} {label}"
        layers = build_layers(entry)
        # Where several of Gboard's own keyboards share these keys, the key
        # offers what any of them does.
        for layer in layers.values():
            for row in layer["rows"]:
                for key in row:
                    typed = key.get("output") or key.get("label")
                    if "action" in key or typed not in alts:
                        continue
                    have = key.get("longPress", [])
                    extra = [a for a in alts[typed] if a not in have and a != typed]
                    if extra:
                        key["longPress"] = have + extra
        self.new_layouts[file_id] = dict(
            id=f"asset_{file_id}", name=f"{title} (Gboard)", langId=lang_id, version=1, layers=layers
        )
        self.extra[lang_id].append(f"asset_{file_id}")


BUILTIN_GRIDS = {}


def load_builtin_grids(root):
    src = open(f"{root}/{ours.SCRIPT_DIR}/layout/BuiltInLayouts.kt").read()
    owner = {"qwertyRows": "en", "azertyRows": "fr", "qwertzRows": "de", "spanishRows": "es", "dvorakRows": "en",
             "colemakRows": "en", "workmanRows": "en", "koreanRows": "ko", "russianRows": "ru", "arabicRows": "ar",
             "greekRows": "el", "hebrewRows": "he", "hindiRows": "hi", "probhatRows": "bn", "jatiyaRows": "bn",
             "bstiRows": "bn"}
    for name, lang in owner.items():
        m = re.search(rf"private val {name}\b[^=]*=\s*listOf\(", src)
        if not m:
            continue
        end = ours._balanced(src, m.end() - 1)
        body = src[m.end():end]
        rows = []
        for row in re.split(r"\n\s{4}listOf\(", "\n    " + body):
            labels = []
            parts = re.split(r"(?=\bKey\(\")", row)
            for part in parts:
                km = re.match(r'Key\("((?:[^"\\]|\\.)*)"', part)
                if km and "action =" not in part and "role =" not in part:
                    labels.append(km.group(1))
            if len(labels) > 3:
                rows.append(tuple(labels))
        BUILTIN_GRIDS.setdefault(lang, set()).add(tuple(rows))


def main():
    root, gboard_path = sys.argv[1:3]
    write = "--write" in sys.argv
    gboard = json.load(open(gboard_path))
    load_builtin_grids(root)
    d = Decider(root, gboard)
    d.run()
    counts = {k: len(set(map(str, v))) for k, v in d.report.items()}
    merged_keys = sum(len(v) for v in d.merges.values())
    print("report", counts)
    print("new layouts", len(d.new_layouts), "new languages", len(d.new_languages))
    print("merge plans", len(d.merges), "keys", merged_keys)
    json.dump(
        dict(
            report={k: sorted(set(map(str, v))) for k, v in d.report.items()},
            new_layouts={k: {"name": v["name"], "langId": v["langId"], "pages": list(v["layers"])} for k, v in d.new_layouts.items()},
            new_languages=d.new_languages,
            extra=d.extra,
            merges=d.merges,
        ),
        open("plan.json", "w"),
        ensure_ascii=False,
        indent=1,
    )
    if write:
        from output import write_all

        write_all(root, d)


if __name__ == "__main__":
    main()
