#!/usr/bin/env python3
"""Generate WM Keyboard grid layouts from Thumb-Key's keyboard tables (issue #1409).

Usage:
    python3 scripts/gen_thumbkey_layouts.py <thumb-key checkout> [--out DIR] [--dry-run]

Reads the letter tables of Thumb-Key (https://github.com/dessalines/thumb-key,
AGPL-3.0) as data and writes one `.wmlayout.json` per selected keyboard into
`app/src/main/assets/layouts`. Generated against Thumb-Key commit
7d53b2e7377593af4adb038026f60eaa52408440.

Only the tables are taken. The reader below is our own: a small evaluator for
the subset of Kotlin the `keyboards/*.kt` files are written in (`val` and
`fun` declarations, constructor calls with named and positional arguments,
`listOf`, `.copy(...)`, string literals), run over every file at once so the
shared items in CommonKeys.kt and the NUMERIC_* grids resolve by name.

What comes out, per keyboard:
- `letters`: Thumb-Key's main grid as laid out (3x3 + the fourth column, then
  the space/return row), `bottomRowAsLaidOut`. The shifted grid is compared
  with a plain uppercase of the main one; only where they differ does a key
  get `shiftLabel` / `flickShift`.
- `symbols`: the keyboard's numeric grid.
- `langId` and `composer` copied from the language's existing asset named in
  KEYBOARDS, so Korean jamo, Avro and kana keep their composers.

It also rewrites `core/language/.../script/GridLayouts.kt`, which puts every
grid on the shelf of the language its langId names; a shipped layout that no
language lists shows on no Languages screen.

Every Thumb-Key action is mapped explicitly (see `map_action`); one the table
does not know stops the run. Arms deliberately left out (sketch mode, hide
letters, the space bar's left/right arrows, the local-currency arm) are
listed in DROPPED and counted in the summary.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

THUMBKEY_COMMIT = "7d53b2e7377593af4adb038026f60eaa52408440"

REPO = Path(__file__).resolve().parent.parent
LAYOUTS = REPO / "app" / "src" / "main" / "assets" / "layouts"

# ---------------------------------------------------------------------------
# Which keyboards, and where they go.
#
# Thumb-Key file stem -> (output file stem, existing asset stem that langId and
# composer are copied from). The main ThumbKey and MessagEase keyboard of every
# language, newest version only, plus the multi-language ThumbKey files.
# ENMessagEase regenerates en_flick in place (same id, same name).
# ---------------------------------------------------------------------------
KEYBOARDS: dict[str, tuple[str, str]] = {
    "ARThumbKeyLevant": ("ar_levant_thumbkey", "ar_letters"),
    "BNThumbKey": ("bn_thumbkey", "bn_unijoy"),
    "BNThumbKeyAvro": ("bn_avro_thumbkey", "bn_avro_flick"),
    "BRFRThumbKeyV3": ("br_fr_thumbkey", "br_breton"),
    "BYThumbKey": ("be_thumbkey", "be_jcuken"),
    "CAThumbKey": ("iu_thumbkey", "iu_syllabics"),
    "DAThumbKeyV3": ("da_thumbkey", "da_qwerty"),
    "DAThumbKeyMultiLingual": ("da_multilingual_thumbkey", "da_qwerty"),
    "DEENAEThumbKey": ("de_en_thumbkey", "de_swiss"),
    "DEMessagEase": ("de_messagease", "de_swiss"),
    "DEThumbKey": ("de_thumbkey", "de_swiss"),
    "DEThumbKeyMultiLingual": ("de_multilingual_thumbkey", "de_swiss"),
    "ENCZThumbKey": ("en_cs_thumbkey", "en_flick"),
    "ENDENLThumbKey": ("en_de_nl_thumbkey", "en_flick"),
    "ENDEThumbKeyV2": ("en_de_thumbkey", "en_flick"),
    "ENEEThumbKey": ("en_ee_thumbkey", "en_flick"),
    "ENEOThumbKey": ("en_eo_thumbkey", "en_flick"),
    "ENESCAThumbKey": ("en_es_ca_thumbkey", "en_flick"),
    "ENHRMessagEase": ("en_hr_messagease", "en_flick"),
    "ENITThumbKey": ("en_it_thumbkey", "en_flick"),
    "ENLAThumbKey": ("en_la_thumbkey", "en_flick"),
    "ENLVThumbKey": ("en_lv_thumbkey", "en_flick"),
    "ENMIThumbKey": ("en_mi_thumbkey", "en_flick"),
    "ENMessagEase": ("en_flick", "en_flick"),
    "ENNOThumbKey": ("en_nb_thumbkey", "en_flick"),
    "ENPHMessagEase": ("en_tl_messagease", "en_flick"),
    "ENPLThumbKey": ("en_pl_thumbkey", "en_flick"),
    "ENROThumbKey": ("en_ro_thumbkey", "en_flick"),
    "ENSKThumbKey": ("en_sk_thumbkey", "en_flick"),
    "ENThumbKey": ("en_thumbkey", "en_flick"),
    "EOCyrillicThumbKey": ("eo_cyrillic_thumbkey", "eo_esperanto"),
    "EOENDEThumbKey": ("eo_en_de_thumbkey", "eo_esperanto"),
    "ESCAMessagEase": ("es_ca_messagease", "es_latam"),
    "ESCAThumbKey": ("es_ca_thumbkey", "es_latam"),
    "ESEOThumbKey": ("es_eo_thumbkey", "es_latam"),
    "ESMessagEase": ("es_messagease", "es_latam"),
    "ESThumbKey": ("es_thumbkey", "es_latam"),
    "EUESThumbKey": ("eu_es_thumbkey", "eu_basque"),
    "EUThumbKey": ("eu_thumbkey", "eu_basque"),
    "EuropeThumbKey": ("en_europe_thumbkey", "en_flick"),
    "FAThumbKey": ("fa_thumbkey", "fa_standard"),
    "FIEEMessagEase": ("fi_et_messagease", "fi_qwerty"),
    "FIMessagEase": ("fi_messagease", "fi_qwerty"),
    "FIThumbKey": ("fi_thumbkey", "fi_qwerty"),
    "FRMessagEase": ("fr_messagease", "fr_swiss"),
    "FRThumbKeyV3": ("fr_thumbkey", "fr_swiss"),
    "GRThumbKey": ("el_thumbkey", "el_t9"),
    "GlagoliticThumbKey": ("cu_glagolitic_thumbkey", "cu"),
    "HEMessagEase": ("he_messagease", "he_si1452"),
    "HEThumbKey": ("he_thumbkey", "he_si1452"),
    "HIThumbKey": ("hi_thumbkey", "hi_devanagari"),
    "HRThumbKey": ("hr_thumbkey", "hr_qwertz"),
    "HUThumbKey": ("hu_thumbkey", "hu_qwertz"),
    "ITMessagEase": ("it_messagease", "it_qwerty"),
    "ITThumbKey": ("it_thumbkey", "it_qwerty"),
    "JAHiraganaThumbKey": ("ja_hiragana_thumbkey", "ja_kana_jis"),
    "JAKanaThumbKey": ("ja_kana_thumbkey", "ja_kana_jis"),
    "JAKatakanaThumbKey": ("ja_katakana_thumbkey", "ja_kana_jis"),
    "KAThumbKey": ("ka_thumbkey", "ka_standard"),
    "KNThumbKey": ("kn_thumbkey", "kn_inscript"),
    "KRThumbKey": ("ko_thumbkey", "ko_danmoeum"),
    "KZThumbKey": ("kk_thumbkey", "kk_kazakh"),
    "LTThumbKey": ("lt_thumbkey", "lt_qwerty"),
    "LVLTGThumbKey": ("lv_ltg_thumbkey", "lv_qwerty"),
    "MYThumbKey": ("my_thumbkey", "my_myanmar3"),
    "NLThumbKey": ("nl_thumbkey", "nl_qwerty"),
    "NOThumbKey": ("nb_thumbkey", "nb_qwerty"),
    "PLMessagEase": ("pl_messagease", "pl_qwerty"),
    "PLThumbKey": ("pl_thumbkey", "pl_qwerty"),
    "PTENThumbKey": ("pt_en_thumbkey", "pt_qwerty"),
    "PTThumbKey": ("pt_thumbkey", "pt_qwerty"),
    "RUMessagEase": ("ru_messagease", "ru_one_row"),
    "RUThumbKey": ("ru_thumbkey", "ru_one_row"),
    "SKThumbKeyV3": ("sk_thumbkey", "sk_qwertz"),
    "SRLatnEnDeThumbKey": ("sr_latn_en_de_thumbkey", "sr_latin"),
    "SRLatnThumbKey": ("sr_latn_thumbkey", "sr_latin"),
    "SRThumbKey": ("sr_thumbkey", "sr_jcuken"),
    "SVMessagEase": ("sv_messagease", "sv_qwerty"),
    "SVThumbKey": ("sv_thumbkey", "sv_qwerty"),
    "ScandinavianMessagEase": ("sv_scandinavian_messagease", "sv_qwerty"),
    "THThumbKey": ("th_thumbkey", "th_kedmanee"),
    "TOKThumbKey": ("tok_thumbkey", "tok"),
    "TRThumbKey": ("tr_thumbkey", "tr_turkishq"),
    "UKBYRUThumbKey": ("uk_be_ru_thumbkey", "uk_jcuken"),
    "UKThumbKey": ("uk_thumbkey", "uk_jcuken"),
    "VNThumbKey": ("vi_thumbkey", "vi_vietnamese"),
}

# Words in a file name that mark a variant rather than a language's main grid.
SKIP_WORDS = [
    "TypeSplit", "TypesSplit", "Programmer", "TwoHands", "Wide", "Symbols", "SymNum",
    "Writer", "Compose", "Left", "Flipped", "Inverted", "Words", "Shift", "Compact",
    "Hyper", "Columnar", "Whale", "Marlin", "Minnow", "Rsinoa", "QBased", "Qwertyfour",
    "QwertEase", "Arti", "Owl", "Dvorak", "Frappe", "T9",
    # Variants of a language that has a main grid of its own above.
    "Samsung", "Extended", "KhamChueam", "Nordic", "Norm", "DataDriven", "Sitelen",
    "OneThumb", "KanaStandard", "4x3",
]
NOT_A_LANGUAGE = {"MATHThumbKey", "CommonKeys"}

# ---------------------------------------------------------------------------
# Kotlin subset reader
# ---------------------------------------------------------------------------

TOKEN_RE = re.compile(
    r"""
    (?P<ws>\s+)
  | (?P<lc>//[^\n]*)
  | (?P<bc>/\*.*?\*/)
  | (?P<str>"(?:\\.|[^"\\])*")
  | (?P<chr>'(?:\\.|[^'\\])+')
  | (?P<num>\d+(?:\.\d+)?[fFL]?)
  | (?P<id>[A-Za-z_][A-Za-z0-9_]*)
  | (?P<op>\?\.|\?:|::|->|==|!=|<=|>=|&&|\|\||[-+*/%=(){}\[\],.:;<>?!@&|^~])
    """,
    re.S | re.X,
)

ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "\\": "\\", '"': '"', "'": "'", "$": "$"}


def unescape(body: str) -> str:
    out, i = [], 0
    while i < len(body):
        c = body[i]
        if c == "\\":
            n = body[i + 1]
            if n == "u":
                out.append(chr(int(body[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append(ESCAPES[n])
            i += 2
            continue
        if c == "$" and i + 1 < len(body) and (body[i + 1] == "{" or body[i + 1].isalpha()):
            raise ValueError(f"string template in {body!r}")
        out.append(c)
        i += 1
    return "".join(out)


def tokenize(src: str):
    toks, pos = [], 0
    while pos < len(src):
        m = TOKEN_RE.match(src, pos)
        if not m:
            raise ValueError(f"cannot tokenize at {src[pos:pos + 40]!r}")
        pos = m.end()
        kind = m.lastgroup
        if kind in ("ws", "lc", "bc"):
            continue
        text = m.group()
        # A literal's text sits in a third slot, so a string ")" is never
        # mistaken for the bracket it spells.
        if kind == "str":
            toks.append(("str", "\0str", text[1:-1]))
        elif kind == "chr":
            toks.append(("chr", "\0chr", text[1:-1]))
        else:
            toks.append((kind, text))
    toks.append(("eof", ""))
    return toks


class Unsupported:
    """An expression the reader does not evaluate (a lambda, an if, a lookup)."""

    def __init__(self, src: str):
        self.src = src

    def __repr__(self):
        return f"Unsupported({self.src[:60]!r})"


class Sym:
    """A bare or dotted name that is not a declaration: an enum value or object."""

    def __init__(self, name: str):
        self.name = name

    @property
    def last(self) -> str:
        return self.name.rsplit(".", 1)[-1]

    def __repr__(self):
        return f"Sym({self.name})"


class Call:
    def __init__(self, fn, args):
        self.fn, self.args = fn, args  # args: list of (name|None, node)


class Name:
    def __init__(self, name):
        self.name = name


class Lit:
    def __init__(self, value):
        self.value = value


class Attr:
    def __init__(self, obj, name):
        self.obj, self.name = obj, name


class Plus:
    def __init__(self, a, b):
        self.a, self.b = a, b


class Opaque:
    def __init__(self, src):
        self.src = src


class Parser:
    def __init__(self, toks):
        self.toks, self.i = toks, 0

    def peek(self, k=0):
        return self.toks[self.i + k]

    def next(self):
        t = self.toks[self.i]
        self.i += 1
        return t

    def expect(self, text):
        t = self.next()
        if t[1] != text:
            raise ValueError(f"expected {text!r}, got {t!r}")
        return t

    def skip_balanced(self):
        """Skip one bracketed group starting at the current opener."""
        pairs = {"(": ")", "{": "}", "[": "]"}
        stack = [pairs[self.next()[1]]]
        while stack:
            t = self.next()
            if t[0] == "eof":
                raise ValueError("unbalanced brackets")
            if t[0] == "op" and t[1] in pairs:
                stack.append(pairs[t[1]])
            elif t[0] == "op" and t[1] in ")}]":
                if t[1] != stack.pop():
                    raise ValueError("mismatched brackets")

    def skip_to_arg_end(self, start):
        """Skip to the next ',' or closing ')' at this depth; return the skipped source."""
        self.i = start
        depth = 0
        while True:
            t = self.peek()
            if t[0] == "eof":
                raise ValueError("ran off the end")
            if t[0] == "op" and t[1] in "({[":
                depth += 1
            elif t[0] == "op" and t[1] in ")}]":
                if depth == 0:
                    break
                depth -= 1
            elif t[0] == "op" and t[1] == "," and depth == 0:
                break
            self.i += 1
        return " ".join(tok[1] for tok in self.toks[start:self.i])

    # expr := term ('+' term)*
    def expr(self):
        node = self.postfix()
        while self.peek()[1] == "+":
            self.next()
            node = Plus(node, self.postfix())
        if self.peek()[1] in ("?.", "?:", "->", "==", "!=", "&&", "||", "-", "*", "/", "<", ">", "!"):
            raise ValueError(f"unsupported operator {self.peek()[1]!r}")
        return node

    def postfix(self):
        node = self.primary()
        while True:
            t = self.peek()
            if t[1] == "." and self.peek(1)[0] == "id":
                self.next()
                name = self.next()[1]
                if isinstance(node, Name):
                    node = Name(node.name + "." + name)
                else:
                    node = Attr(node, name)
            elif t[1] == "(":
                node = Call(node, self.args())
            elif t[1] == "{":
                raise ValueError("lambda")
            else:
                return node

    def args(self):
        self.expect("(")
        out = []
        while self.peek()[1] != ")":
            name = None
            if self.peek()[0] == "id" and self.peek(1)[1] == "=" and self.peek(2)[1] != "=":
                name = self.next()[1]
                self.next()
            start = self.i
            try:
                node = self.expr()
                if self.peek()[1] not in (",", ")"):
                    raise ValueError(f"unexpected {self.peek()!r}")
            except ValueError:
                node = Opaque(self.skip_to_arg_end(start))
            out.append((name, node))
            if self.peek()[1] == ",":
                self.next()
        self.expect(")")
        return out

    def primary(self):
        t = self.next()
        if t[0] in ("str", "chr"):
            return Lit(unescape(t[2]))
        if t[0] == "num":
            v = t[1].rstrip("fFL")
            return Lit(float(v) if "." in v else int(v))
        if t[0] == "id":
            if t[1] == "true":
                return Lit(True)
            if t[1] == "false":
                return Lit(False)
            if t[1] == "null":
                return Lit(None)
            if t[1] in ("if", "when", "object"):
                raise ValueError(t[1])
            return Name(t[1])
        if t[1] == "(":
            node = self.expr()
            self.expect(")")
            return node
        if t[1] == "::":
            return Opaque("::" + self.next()[1])
        raise ValueError(f"unexpected token {t!r}")


def read_declarations(path: Path, vals: dict, funs: dict, origin: dict):
    toks = tokenize(path.read_text(encoding="utf-8-sig"))
    p = Parser(toks)
    depth = 0
    while p.peek()[0] != "eof":
        t = p.peek()
        if t[0] == "op" and t[1] in "({[":
            depth += 1
            p.next()
            continue
        if t[0] == "op" and t[1] in ")}]":
            depth -= 1
            p.next()
            continue
        if depth == 0 and t == ("id", "val"):
            p.next()
            name = p.next()[1]
            while p.peek()[1] not in ("=",):
                if p.peek()[0] == "eof":
                    return
                p.next()
            p.next()
            start = p.i
            try:
                node = p.expr()
            except ValueError as e:
                p.i = start
                node = Opaque(f"{name}: {e}")
                # Skip to the next top-level declaration.
                while p.peek()[0] != "eof" and not (p.peek() in (("id", "val"), ("id", "fun"))):
                    if p.peek()[1] in "({[":
                        p.skip_balanced()
                    else:
                        p.next()
            vals[name] = node
            origin[name] = path.stem
            continue
        if depth == 0 and t == ("id", "fun"):
            p.next()
            name = p.next()[1]
            if p.peek()[1] == ".":  # extension function: not ours to read
                continue
            if p.peek()[1] != "(":
                continue
            # Parameters: names only.
            p.expect("(")
            params = []
            d = 0
            expect_name = True
            while not (p.peek()[1] == ")" and d == 0):
                tok = p.next()
                if tok[1] in "([{<":
                    d += 1
                elif tok[1] in ")]}>":
                    d -= 1
                elif tok[1] == "," and d == 0:
                    expect_name = True
                    continue
                if expect_name and tok[0] == "id" and d == 0:
                    params.append(tok[1])
                    expect_name = False
            p.expect(")")
            while p.peek()[1] not in ("=", "{") and p.peek()[0] != "eof":
                p.next()
            if p.peek()[1] == "=":
                p.next()
                try:
                    funs[name] = (params, p.expr())
                except ValueError:
                    pass
            continue
        p.next()


# ---------------------------------------------------------------------------
# Evaluation into plain Python values
# ---------------------------------------------------------------------------

KEYITEM_PARAMS = [
    "center", "left", "topLeft", "top", "topRight", "right", "bottomRight", "bottom",
    "bottomLeft", "nextTapActions", "widthMultiplier", "backgroundColor", "swipeType",
    "slideType", "longPress",
]
KEYC_TEXT_PARAMS = [
    "text", "displayText", "displayFont", "swipeReturnAction", "display",
    "capsModeDisplay", "size", "color",
]
KEYC_ACTION_PARAMS = ["action", "swipeReturnAction", "display", "capsModeDisplay", "size", "color"]

ACTION_PARAMS = {
    "CommitText": ["text"],
    "SendEvent": ["event"],
    "ReplaceLastText": ["text", "trimCount"],
    "ReplaceTrailingWhitespace": ["text", "distanceBack"],
    "ToggleShiftMode": ["enable"],
    "ToggleCtrlMode": ["enable"],
    "ToggleAltMode": ["enable"],
    "ShiftAndCapsLock": ["enable"],
    "ToggleCurrentWordCapitalization": ["toggleUp"],
    "ToggleNumericMode": ["enable"],
    "ToggleEmojiMode": ["enable"],
    "ToggleClipboardMode": ["enable"],
    "ToggleSketchMode": ["enable"],
    "ComposeLastKey": ["text"],
    "NormalizeLastKey": ["text", "form"],
    "SmartQuotes": ["start", "end"],
}


class Action:
    def __init__(self, kind, args=None):
        self.kind, self.args = kind, args or {}

    def __repr__(self):
        return f"{self.kind}({self.args})" if self.args else self.kind


def bind(params, args, what):
    out = {}
    pos = 0
    for name, value in args:
        if name is None:
            if pos >= len(params):
                raise ValueError(f"too many arguments to {what}")
            out[params[pos]] = value
            pos += 1
        else:
            if name not in params:
                raise ValueError(f"{what} has no parameter {name}")
            out[name] = value
    return out


# Values Thumb-Key works out from the phone's locale at run time; we take the
# fallback the Kotlin gives when the locale's currency is one of $ £ €.
CURRENCY_FALLBACKS = {"KB_ES_MESSAGEASE_CURRENCY_SYMBOL": "$"}


class Evaluator:
    def __init__(self, vals, funs):
        self.vals, self.funs = vals, funs
        self.cache = {}
        self.stack = []

    def value(self, name):
        if name in CURRENCY_FALLBACKS:
            return CURRENCY_FALLBACKS[name]
        if name not in self.cache:
            if name in self.stack:
                raise ValueError(f"cycle at {name}")
            self.stack.append(name)
            self.cache[name] = self.eval(self.vals[name], {})
            self.stack.pop()
        return self.cache[name]

    def eval(self, node, env):
        if isinstance(node, Lit):
            return node.value
        if isinstance(node, Opaque):
            return Unsupported(node.src)
        if isinstance(node, Name):
            n = node.name
            if n in env:
                return env[n]
            if n in self.vals:
                return self.value(n)
            if n.startswith("KeyAction."):
                return Action(n[len("KeyAction."):])
            return Sym(n)
        if isinstance(node, Plus):
            a, b = self.eval(node.a, env), self.eval(node.b, env)
            if isinstance(a, list) and isinstance(b, list):
                return a + b
            if isinstance(a, str) and isinstance(b, str):
                return a + b
            raise ValueError(f"cannot add {a!r} and {b!r}")
        if isinstance(node, Attr):
            obj = self.eval(node.obj, env)
            if isinstance(obj, dict) and node.name in obj:
                return obj[node.name]
            raise ValueError(f"attribute {node.name} of {obj!r}")
        if isinstance(node, Call):
            return self.call(node, env)
        raise ValueError(f"cannot evaluate {node!r}")

    def call(self, node, env):
        fn = node.fn
        # receiver.copy(...)
        if isinstance(fn, Attr) and fn.name == "copy":
            base = self.eval(fn.obj, env)
            return self.copy(base, node.args, env)
        if isinstance(fn, Name) and fn.name.endswith(".copy"):
            base = self.eval(Name(fn.name[: -len(".copy")]), env)
            return self.copy(base, node.args, env)
        if not isinstance(fn, Name):
            raise ValueError("call on an expression")
        name = fn.name
        args = [(k, self.eval(v, env)) for k, v in node.args]
        short = name.split(".")[-1]
        if short in ("listOf", "arrayOf", "setOf", "mutableListOf"):
            return [v for _, v in args]
        if short == "KeyC":
            first = args[0][1] if args and args[0][0] is None else None
            named = {k for k, _ in args if k}
            if isinstance(first, str) or "text" in named:
                d = bind(KEYC_TEXT_PARAMS, args, "KeyC")
                d["action"] = Action("CommitText", {"text": d.pop("text")})
                if "displayText" in d:
                    d["display"] = ("text", d.pop("displayText"))
                return {"__type": "KeyC", **d}
            d = bind(KEYC_ACTION_PARAMS, args, "KeyC")
            return {"__type": "KeyC", **d}
        if short == "KeyItemC":
            return {"__type": "KeyItemC", **bind(KEYITEM_PARAMS, args, "KeyItemC")}
        if short == "KeyboardC":
            return {"__type": "KeyboardC", **bind(["arr"], args, "KeyboardC")}
        if short == "KeyboardDefinitionModes":
            return {"__type": "Modes", **bind(
                ["main", "shifted", "numeric", "ctrled", "alted", "emoji"], args, "Modes")}
        if short == "KeyboardDefinition":
            return {"__type": "Definition", **bind(["title", "modes", "settings"], args, "Definition")}
        if short == "TextDisplay":
            return ("text", args[0][1])
        if short == "IconDisplay":
            return ("icon", args[0][1])
        if short == "KeyEvent":
            return Sym("KeyEvent(" + ",".join(
                v.name if isinstance(v, Sym) else repr(v) for _, v in args) + ")")
        if short in ACTION_PARAMS:
            return Action(short, bind(ACTION_PARAMS[short], args, short))
        if name in self.funs:
            params, body = self.funs[name]
            local = dict(env)
            local.update(bind(params, args, name))
            return self.eval(body, local)
        # Settings and processors (KeyboardDefinitionSettings, KoreanTextProcessor…).
        return Unsupported(name + "(...)")

    def copy(self, base, raw_args, env):
        if not isinstance(base, dict):
            raise ValueError(f"copy of {base!r}")
        out = dict(base)
        for k, v in raw_args:
            if k is None:
                raise ValueError("positional copy argument")
            out[k] = self.eval(v, env)
        return out


# ---------------------------------------------------------------------------
# Conversion to WM Keyboard's layout format
# ---------------------------------------------------------------------------

DIRS = {
    "left": "left", "topLeft": "up_left", "top": "up", "topRight": "up_right",
    "right": "right", "bottomRight": "down_right", "bottom": "down",
    "bottomLeft": "down_left",
}

# ComposeLastKey("x") combines the character before the caret with an accent.
# The keyboard types the matching combining mark instead.
COMPOSE_MARKS = {
    "`": "̀", "'": "́", "^": "̂", "~": "̃", "-": "̄",
    "˘": "̆", '"': "̈", "?": "̉", "°": "̊", "ˇ": "̌",
    "*": "̣",
}


class Drop(Exception):
    """An arm left out on purpose; the reason is counted."""


DROPPED: Counter = Counter()


def edit(op):
    return {"type": "edit", "op": op}


def tool(name):
    return {"type": "tool", "tool": name}


def map_action(a, where: str):
    """A Thumb-Key action -> ("text", str) or ("action", json)."""
    if isinstance(a, Unsupported):
        if "getLocalCurrency" in a.src:
            raise Drop("local-currency arm")
        raise ValueError(f"{where}: unreadable action {a.src!r}")
    if isinstance(a, Sym):
        a = Action(a.last)
    if not isinstance(a, Action):
        raise ValueError(f"{where}: not an action: {a!r}")
    k, args = a.kind.split(".")[-1], a.args
    if k == "CommitText":
        return "text", args["text"]
    if k == "NormalizeLastKey":
        return "text", args["text"]
    if k == "ReplaceTrailingWhitespace":
        return "text", args["text"].strip()
    if k == "ComposeLastKey":
        t = args["text"]
        if t == "゛":
            return "action", {"type": "kana_variant"}
        if t in COMPOSE_MARKS:
            return "text", COMPOSE_MARKS[t]
        raise Drop(f"compose {t!r} (no combining-mark equivalent)")
    if k == "ToggleShiftMode":
        return "action", {"type": "shift" if args["enable"] else "shift_off"}
    if k == "ShiftAndCapsLock":
        return "action", {"type": "caps_lock" if args["enable"] else "shift_off"}
    if k == "ToggleCapsLock":
        return "action", {"type": "caps_lock"}
    if k == "ToggleCurrentWordCapitalization":
        return "action", edit("CASE_UP" if args["toggleUp"] else "CASE_DOWN")
    if k == "ToggleNumericMode":
        return "action", {"type": "symbols" if args["enable"] else "letters"}
    if k == "ToggleEmojiMode":
        if args["enable"]:
            return "action", {"type": "emoji"}
        raise ValueError(f"{where}: emoji back key")
    if k == "ToggleClipboardMode":
        return "action", tool("CLIPBOARD")
    simple = {
        "Copy": edit("COPY"), "Cut": edit("CUT"), "Paste": edit("PASTE"),
        "SelectAll": edit("SELECT_ALL"), "SelectLineWithCursor": edit("SELECT_LINE"),
        "Undo": tool("UNDO"), "Redo": tool("REDO"),
        "GotoSettings": tool("SETTINGS"), "SwitchIMEVoice": tool("VOICE"),
        "CycleRight": tool("ONE_HANDED"), "CycleLeft": tool("ONE_HANDED"),
        "SwitchLanguage": {"type": "language_switch"},
        "SwitchIME": {"type": "switch_input_method"},
        "HideKeyboard": tool("HIDE_KEYBOARD"),
        "DeleteKeyAction": {"type": "delete"},
        "DeleteCharacterAfterCursor": {"type": "forward_delete"},
        "DeleteWordAfterCursor": edit("DELETE_WORD_FORWARD"),
        "DeleteWordBeforeCursor": edit("DELETE_WORD"),
        "PreviousWordBeforeCursor": edit("WORD_LEFT"),
        "NextWordAfterCursor": edit("WORD_RIGHT"),
        "CursorToLineStart": edit("HOME"), "CursorToLineEnd": edit("END"),
        "CursorToTextStart": edit("DOC_START"), "CursorToTextEnd": edit("DOC_END"),
        "CursorUp": edit("UP"), "CursorDown": edit("DOWN"),
        "IMECompleteAction": {"type": "enter"},
    }
    if k in simple:
        return "action", simple[k]
    if k in ("ToggleHideLetters", "ToggleSketchMode"):
        raise Drop(k)
    if k == "SendEvent":
        ev = args["event"].name if isinstance(args["event"], Sym) else str(args["event"])
        if "DPAD_LEFT" in ev:
            return "action", edit("LEFT")
        if "DPAD_RIGHT" in ev:
            return "action", edit("RIGHT")
    raise ValueError(f"{where}: unmapped action {a!r}")


LABELS = {
    "delete": "⌫", "enter": "⏎", "space": " ", "symbols": "123", "letters": "ABC",
    "emoji": "😊", "shift": "⇧", "caps_lock": "⇪", "shift_off": "⇩",
}


def center_of(item):
    c = item["center"]
    return c


def center_text(item):
    a = item["center"].get("action")
    if isinstance(a, Action) and a.kind == "CommitText":
        return a.args["text"]
    return None


def arm_texts(item):
    out = {}
    for p, d in DIRS.items():
        kc = item.get(p)
        if isinstance(kc, dict):
            a = kc.get("action")
            if isinstance(a, Action) and a.kind in ("CommitText", "NormalizeLastKey"):
                out[d] = a.args["text"]
    return out


def display_text(kc):
    d = kc.get("display")
    # A display we cannot read (toki pona's sitelen glyph lookup) falls back
    # to the text the key types.
    if isinstance(d, tuple) and d[0] == "text" and isinstance(d[1], str):
        return d[1]
    return None


def convert_item(item, shifted, where, notes):
    """One KeyItemC (and its shifted twin) -> a Key."""
    center = item["center"]
    kind, value = map_action(center.get("action"), where + ".center")
    key: dict = {}
    special = None
    if kind == "text":
        if value == " ":
            special = "space"
            key["label"] = " "
            key["action"] = {"type": "space"}
        else:
            label = display_text(center)
            key["label"] = label if label else value
            if label and label != value:
                key["output"] = value
    else:
        special = value["type"]
        key["label"] = display_text(center) or LABELS.get(special, "")
        if not key["label"]:
            raise ValueError(f"{where}: no label for centre action {value}")
        key["action"] = value

    flick, actions, returns, return_texts = {}, {}, {}, {}
    for p, d in DIRS.items():
        kc = item.get(p)
        if kc is None:
            continue
        if isinstance(kc, Unsupported):
            if "getLocalCurrency" in kc.src:
                DROPPED["local-currency arm"] += 1
                continue
            raise ValueError(f"{where}.{p}: unreadable {kc.src!r}")
        # Per-key rules: the delete key keeps only its right arm, the space bar
        # leaves left/right to our own cursor slide.
        if special == "delete" and p != "right":
            DROPPED["delete-key arm other than right"] += 1
            continue
        if special == "space" and p in ("left", "right"):
            DROPPED["space-bar left/right arrow"] += 1
            continue
        try:
            k2, v2 = map_action(kc.get("action"), f"{where}.{p}")
        except Drop as e:
            DROPPED[str(e)] += 1
            continue
        if k2 == "text":
            flick[d] = v2
        else:
            actions[d] = {"action": v2}
        ret = kc.get("swipeReturnAction")
        if ret is not None:
            try:
                k3, v3 = map_action(ret, f"{where}.{p}.return")
            except Drop as e:
                DROPPED[str(e)] += 1
                continue
            if k3 == "text":
                # Out and back on a text arm types the arm's shifted text.
                return_texts[d] = v3
            else:
                returns[d] = {"action": v3}
    if center.get("swipeReturnAction") is not None:
        raise ValueError(f"{where}: centre swipe-return action")

    if flick:
        key["flick"] = flick
    if actions:
        key["flickActions"] = actions
    if returns:
        key["flickReturn"] = returns

    w = item.get("widthMultiplier", 1)
    if w != 1:
        key["width"] = w

    nta = item.get("nextTapActions")
    if nta:
        cycle = []
        for a in nta:
            if not (isinstance(a, Action) and a.kind == "ReplaceLastText"):
                raise ValueError(f"{where}: next-tap action {a!r}")
            cycle.append(a.args["text"])
        key["spaceCycle"] = cycle

    lp = item.get("longPress")
    if lp is not None:
        if special == "delete" and isinstance(lp, Sym) and lp.last == "DeleteWordBeforeCursor":
            DROPPED["delete-key hold (ours deletes repeatedly)"] += 1
        elif special == "enter" and isinstance(lp, Action) and lp.kind == "CommitText" and lp.args["text"] == "\n":
            key["actionAlternates"] = [{"action": {"type": "newline"}, "label": "↵"}]
        else:
            k4, v4 = map_action(lp, where + ".longPress")
            if k4 == "text":
                key["longPress"] = [v4]
            else:
                key["actionAlternates"] = [{"action": v4}]
    if special == "enter" and "actionAlternates" not in key:
        key["actionAlternates"] = [{"action": {"type": "newline"}, "label": "↵"}]

    # Shift: only what a plain uppercase of the main key gets wrong.
    if shifted is not None and kind == "text" and special is None:
        s_center = center_text(shifted)
        if s_center is not None and s_center != value.upper():
            key["shiftLabel"] = display_text(shifted["center"]) or s_center
        s_arms = arm_texts(shifted)
        fs = {}
        for d, t in s_arms.items():
            if d in flick:
                if t != flick[d].upper():
                    fs[d] = t
            else:
                fs[d] = t
        if fs:
            key["flickShift"] = fs
        # Action arms the shifted grid changes (shift -> caps lock) cannot be
        # told apart in one layer; note them.
        for p, d in DIRS.items():
            ka, kb = item.get(p), shifted.get(p)
            if isinstance(ka, dict) and isinstance(kb, dict):
                aa, ab = ka.get("action"), kb.get("action")
                if not (isinstance(aa, Action) and aa.kind == "CommitText") and repr(aa) != repr(ab):
                    notes[f"shifted grid changes {aa!r} -> {ab!r} (main kept)"] += 1
    if return_texts:
        fs = key.setdefault("flickShift", {})
        for d, t in return_texts.items():
            if d in fs and fs[d] != t:
                raise ValueError(f"{where}.{d}: shifted text and return text disagree")
            fs[d] = t
    return key


def convert_grid(grid, shifted, where, notes):
    rows = []
    srows = shifted["arr"] if shifted else None
    for r, row in enumerate(grid["arr"]):
        out = []
        for c, item in enumerate(row):
            twin = None
            if srows is not None and r < len(srows) and c < len(srows[r]):
                twin = srows[r][c]
            out.append(convert_item(item, twin, f"{where}[{r}][{c}]", notes))
        rows.append(out)
    return rows


def build(defn, out_stem, main_asset, notes):
    modes = defn["modes"]
    main_spec = json.loads((LAYOUTS / f"{main_asset}.wmlayout.json").read_text(encoding="utf-8"))["layout"]
    layout: dict = {}
    if out_stem == "en_flick":
        layout["id"] = "asset_en_flick"
        layout["name"] = main_spec["name"]
    else:
        layout["id"] = f"asset_{out_stem}"
        layout["name"] = defn["title"].strip()
    layout["langId"] = main_spec["langId"]
    if main_spec.get("composer"):
        layout["composer"] = main_spec["composer"]
    layout["version"] = 2
    letters = convert_grid(modes["main"], modes["shifted"], "main", notes)
    # A grid of Thumb-Key's with no space bar (the kana pad types its space
    # from a separate mode): a full-width one under it, which the keyboard's
    # layout repair would otherwise add itself.
    if not any(k.get("action", {}).get("type") == "space" for row in letters for k in row):
        width = sum(k.get("width", 1) for k in letters[0])
        letters.append([{"label": " ", "action": {"type": "space"}, "width": width}])
        notes["space row added (grid had no space key)"] += 1
    symbols = convert_grid(modes["numeric"], None, "numeric", notes)
    # The number pad's way back to the letters says "ABC" on a Latin board; on
    # any other it shows the board's own first three letters, as the kana pad's
    # あいう key does, rather than Latin letters the board never types.
    firsts = [k["label"] for k in letters[0][:3] if "action" not in k and k.get("label")]
    if firsts and not any(c.isascii() and c.isalpha() for c in "".join(firsts)):
        for row in symbols:
            for k in row:
                if k.get("action", {}).get("type") == "letters":
                    k["label"] = "".join(f[0] for f in firsts)
    layout["layers"] = {
        "letters": {
            "rows": letters,
            "bottomRowAsLaidOut": True,
        },
        "symbols": {
            "rows": symbols,
            "bottomRowAsLaidOut": True,
        },
    }
    return {"format": "wmkeyboard-layout", "version": 1, "layout": layout}


# The flick boards made by hand before #1409, which no language listed either.
HAND_MADE_GRIDS = ["en_flick", "bn_avro_flick", "bn_khipro_flick"]

LANGUAGE_MAP = Path("core/language/src/main/java/com/wasimaster/wmkeyboard/core/script/GridLayouts.kt")


def write_language_map(out: Path):
    """Writes GridLayouts.kt: each grid layout under the language its langId
    names, so it sits on that language's shelf on the Languages screen."""
    stems = sorted(p.name.removesuffix(".wmlayout.json") for p in out.glob("*.wmlayout.json")
                   if "_thumbkey" in p.name or "_messagease" in p.name)
    by_lang: dict[str, list[str]] = {}
    for stem in HAND_MADE_GRIDS + stems:
        spec = json.loads((out / f"{stem}.wmlayout.json").read_text(encoding="utf-8"))["layout"]
        by_lang.setdefault(spec["langId"], []).append(spec["id"])
    lines = [
        "package com.wasimaster.wmkeyboard.core.script",
        "",
        "/**",
        " * The 3x3 flick grids on each language's shelf (issue #1409): the Thumb-Key",
        " * and MessagEase boards and the hand-made flick boards, by the langId in",
        " * each file. Generated by `scripts/gen_thumbkey_layouts.py`; do not edit.",
        " */",
        "internal object GridLayouts {",
        "",
        "    val extraLayoutIds: Map<String, List<String>> = mapOf(",
    ]
    for lang in sorted(by_lang):
        ids = ", ".join(f'"{i}"' for i in by_lang[lang])
        lines.append(f'        "{lang}" to listOf({ids}),')
    lines += ["    )", "}", ""]
    LANGUAGE_MAP.write_text("\n".join(lines), encoding="utf-8")


def classify(stem: str) -> str | None:
    """Why a Thumb-Key file is not generated, or None when it is."""
    if stem in KEYBOARDS:
        return None
    if stem in NOT_A_LANGUAGE or stem.startswith("Numeric"):
        return "not a language grid"
    for w in SKIP_WORDS:
        if w in stem:
            return f"variant ({w})"
    base = re.sub(r"V\d+$", "", stem)
    for chosen in KEYBOARDS:
        if re.sub(r"V\d+$", "", chosen) == base:
            return f"older version of {chosen}"
    if "ThumbKey" in stem or "MessagEase" in stem:
        return "variant of a language generated from another file"
    return "not a ThumbKey/MessagEase grid"


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("thumbkey", type=Path, help="path to a thumb-key checkout")
    ap.add_argument("--out", type=Path, default=LAYOUTS)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    kb_dir = a.thumbkey / "app/src/main/java/com/dessalines/thumbkey/keyboards"
    files = sorted(kb_dir.glob("*.kt"))
    if not files:
        sys.exit(f"no keyboards under {kb_dir}")

    vals, funs, origin = {}, {}, {}
    for f in files:
        read_declarations(f, vals, funs, origin)
    ev = Evaluator(vals, funs)

    defs = {}
    for name, stem in origin.items():
        node = vals[name]
        if isinstance(node, Call) and isinstance(node.fn, Name) and node.fn.name == "KeyboardDefinition":
            defs.setdefault(stem, []).append(name)

    skipped = []
    for f in files:
        reason = classify(f.stem)
        if reason:
            skipped.append((f.stem, reason))
    missing = [s for s in KEYBOARDS if not (kb_dir / f"{s}.kt").exists()]
    if missing:
        sys.exit(f"Thumb-Key files gone: {missing}")

    written = []
    notes: Counter = Counter()
    for stem, (out_stem, main_asset) in sorted(KEYBOARDS.items()):
        names = defs.get(stem, [])
        if len(names) != 1:
            sys.exit(f"{stem}: expected one KeyboardDefinition, found {names}")
        if not (LAYOUTS / f"{main_asset}.wmlayout.json").exists():
            sys.exit(f"{stem}: main asset {main_asset} not found")
        try:
            defn = ev.value(names[0])
            doc = build(defn, out_stem, main_asset, notes)
        except (ValueError, KeyError) as e:
            sys.exit(f"{stem}: {e}")
        text = json.dumps(doc, ensure_ascii=False, indent=2) + "\n"
        json.loads(text)
        target = a.out / f"{out_stem}.wmlayout.json"
        if not a.dry_run:
            target.write_text(text, encoding="utf-8")
        written.append(target.name)

    if not a.dry_run:
        write_language_map(a.out)

    print(f"Thumb-Key {THUMBKEY_COMMIT[:12]}: {len(written)} layouts written")
    for n in written:
        print("  +", n)
    print(f"{len(skipped)} files skipped:")
    for s, r in skipped:
        print(f"  - {s}: {r}")
    if DROPPED:
        print("Arms left out on purpose:")
        for r, n in DROPPED.most_common():
            print(f"  {n:5d}  {r}")
    if notes:
        print("Notes:")
        for r, n in notes.most_common():
            print(f"  {n:5d}  {r}")


if __name__ == "__main__":
    main()
