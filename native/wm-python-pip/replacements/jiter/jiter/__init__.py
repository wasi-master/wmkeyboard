"""jiter: fast JSON parsing with a partial mode, the API of jiter.

jiter is written in Rust; this is the same API in Python for WM Keyboard
plugins, which cannot load compiled code. A whole document goes to the json
module. In partial mode an unfinished document (a reply still streaming in)
is cut back to the last value it completes and closed, as jiter reads it:
an unfinished string is left out, or kept with partial_mode="trailing-strings".
"""

import json as _json
from decimal import Decimal as _Decimal

__all__ = ["LosslessFloat", "cache_clear", "cache_usage", "from_json"]

__version__ = "0.19.0"

_PARTIAL = {False: "off", "off": "off", True: "on", "on": "on", "trailing-strings": "trailing-strings"}
_CACHE = {True: "all", "all": "all", "keys": "keys", False: "none", "none": "none"}
_FLOAT = ("float", "decimal", "lossless-float")
_PARTIAL_MSG = "Invalid partial mode, should be `'off'`, `'on'`, `'trailing-strings'` or a `bool`"
_LITERALS = ("true", "false", "null", "NaN", "Infinity", "-Infinity")
_SPACE = " \t\r\n"
_NUMBER = "0123456789+-.eE"


class LosslessFloat:
    """A float from JSON kept as the text it was written as."""

    __slots__ = ("_text",)

    def __init__(self, json_float):
        text = bytes(json_float).decode("utf-8") if not isinstance(json_float, str) else json_float
        try:
            value = _json.loads(text)
        except ValueError:
            raise ValueError(f"trailing characters at line 1 column {_bad_number_at(text)}") from None
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError("expected value at line 1 column 1")
        self._text = text

    def as_decimal(self):
        return _Decimal(self._text)

    def __float__(self):
        return float(self._text)

    def __bytes__(self):
        return self._text.encode("ascii")

    def __str__(self):
        return self._text

    def __repr__(self):
        return f"LosslessFloat({self._text})"

    def __eq__(self, other):
        if isinstance(other, LosslessFloat):
            return self._text == other._text
        if isinstance(other, (int, float)):
            return float(self) == other
        return NotImplemented

    def __hash__(self):
        return hash(float(self))


def _bad_number_at(text):
    i = 0
    while i < len(text) and text[i] in _NUMBER:
        i += 1
    return i + 1


def cache_clear():
    """Nothing to clear: this stand-in keeps no string cache."""


def cache_usage():
    """How much the string cache holds: nothing, here."""
    return 0


def _cut_partial_character(data):
    """[data] as text without the start of a UTF-8 character the end cut off,
    or None when it is not UTF-8 for another reason."""
    for cut in (1, 2, 3):
        if len(data) <= cut:
            return None
        lead, rest = data[-cut], data[-cut + 1 :] if cut > 1 else b""
        need = 2 if 0xC0 <= lead < 0xE0 else 3 if 0xE0 <= lead < 0xF0 else 4 if 0xF0 <= lead < 0xF8 else 0
        if need > cut and all(0x80 <= x < 0xC0 for x in rest):
            try:
                return data[:-cut].decode("utf-8")
            except UnicodeDecodeError:
                return None
    return None


def _closers(stack):
    return "".join("}" if c == "{" else "]" for c in reversed(stack))


def _string_end(text, i):
    """Where the string opening at [i] closes (the index after its quote), or -1 when the text ends first."""
    j = i + 1
    while True:
        j = text.find('"', j)
        if j == -1:
            return -1
        k = j
        while text[k - 1] == "\\":
            k -= 1
        if (j - k) % 2 == 0:
            return j + 1
        j += 1


def _trim_escape(raw):
    """[raw] (an unfinished string's text) without an escape the text cut off,
    or a high surrogate whose low half has not come yet."""
    k = len(raw)
    while k > 0 and raw[k - 1] == "\\":
        k -= 1
    if (len(raw) - k) % 2 == 1:
        raw = raw[:-1]
    u = raw.rfind("\\u")
    if u != -1 and len(raw) - u < 6:
        b = u
        while b > 0 and raw[b - 1] == "\\":
            b -= 1
        if (u - b) % 2 == 0:
            raw = raw[:u]
    if len(raw) >= 6 and raw[-6:-4] == "\\u" and raw[-4] in "dD" and raw[-3] in "89abAB":
        raw = raw[:-6]
    return raw


def _complete_scalar(token):
    if token in _LITERALS:
        return True
    # A number that the text could still go on (2. 1e -) is not finished.
    if not token or token[-1] not in "0123456789" or any(ch not in _NUMBER for ch in token):
        return False
    try:
        float(token)
    except ValueError:
        return False
    return True


def _repair(text, trailing):
    """[text], an unfinished document, cut back and closed so that it parses,
    or None when it holds no value yet."""
    stack = []
    # Per open container: what comes next. "v" a value (or the end, just after
    # the opening), "k" a key, ":" a colon, "," a comma or the end.
    want = []
    safe = None
    n = len(text)
    i = 0
    while True:
        while i < n and text[i] in _SPACE:
            i += 1
        if i >= n:
            break
        c = text[i]
        top = want[-1] if want else "v"
        if c == "{" or c == "[":
            stack.append(c)
            want.append("k" if c == "{" else "v")
            i += 1
            safe = (i, _closers(stack))
            continue
        if c == "}" or c == "]":
            if not stack:
                return None
            stack.pop()
            want.pop()
            if want:
                want[-1] = ","
            i += 1
            safe = (i, _closers(stack))
            if not stack:
                break
            continue
        if c == ",":
            if want:
                want[-1] = "k" if stack[-1] == "{" else "v"
            i += 1
            continue
        if c == ":":
            if want:
                want[-1] = "v"
            i += 1
            continue
        if c == '"':
            end = _string_end(text, i)
            if end == -1:
                if trailing and top == "v":
                    return text[:i] + '"' + _trim_escape(text[i + 1 :]) + '"' + _closers(stack)
                break
            i = end
            if top == "k":
                want[-1] = ":"
                continue
        else:
            j = i
            while j < n and text[j] not in _SPACE and text[j] not in ",:]}":
                j += 1
            if j >= n and not _complete_scalar(text[i:j]):
                break
            i = j
        if want:
            want[-1] = ","
            safe = (i, _closers(stack))
        else:
            safe = (i, "")
            break
    if safe is None:
        return None
    return text[: safe[0]] + safe[1]


def from_json(
    json_data,
    /,
    *,
    allow_inf_nan=True,
    cache_mode="all",
    partial_mode=False,
    catch_duplicate_keys=False,
    float_mode="float",
):
    """The Python value of the JSON document [json_data] (bytes)."""
    try:
        partial = _PARTIAL[partial_mode]
    except (KeyError, TypeError):
        raise (ValueError if isinstance(partial_mode, str) else TypeError)(_PARTIAL_MSG) from None
    if cache_mode not in _CACHE:
        raise ValueError("Invalid cache mode, should be `'all'`, `'keys'`, `'none`' or a `bool`")
    if float_mode not in _FLOAT:
        raise ValueError("Invalid float mode, should be `'float'`, `'decimal'` or `'lossless-float'`")
    if isinstance(json_data, str):
        text = json_data
    else:
        data = bytes(json_data)
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            text = None if partial == "off" else _cut_partial_character(data)
            if text is None:
                raise ValueError("invalid unicode code point" if partial != "off" else "EOF while parsing a string") from None
    hooks = {}
    if not allow_inf_nan:
        def constant(name):
            raise ValueError("expected value")
        hooks["parse_constant"] = constant
    if float_mode == "decimal":
        hooks["parse_float"] = _Decimal
    elif float_mode == "lossless-float":
        hooks["parse_float"] = LosslessFloat
    if catch_duplicate_keys:
        def pairs(items):
            out = {}
            for k, v in items:
                if k in out:
                    raise ValueError(f'Detected duplicate key "{k}"')
                out[k] = v
            return out
        hooks["object_pairs_hook"] = pairs
    try:
        return _json.loads(text, **hooks)
    except _json.JSONDecodeError as e:
        fixed = None if partial == "off" else _repair(text, partial == "trailing-strings")
        if fixed is None or fixed == text:
            raise _error(e, text) from None
    try:
        return _json.loads(fixed, **hooks)
    except _json.JSONDecodeError as e:
        raise _error(e, fixed) from None


def _innermost(text):
    """What the text leaves open at its end: "list", "object" or "value"."""
    stack = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            end = _string_end(text, i)
            if end == -1:
                break
            i = end
            continue
        if c == "{" or c == "[":
            stack.append(c)
        elif (c == "}" or c == "]") and stack:
            stack.pop()
        i += 1
    if not stack:
        return "value"
    return "object" if stack[-1] == "{" else "list"


def _error(e, text):
    """A json module error as jiter words it: what went wrong, at line and column."""
    msg, pos = e.msg, e.pos
    line = text.count("\n", 0, pos) + 1
    col = pos - (text.rfind("\n", 0, pos) + 1)
    if msg.startswith("Unterminated string"):
        what, col = "EOF while parsing a string", len(text) - (text.rfind("\n") + 1)
    elif pos >= len(text.rstrip()):
        kind = _innermost(text)
        what = "EOF while parsing " + ("an object" if kind == "object" else "a " + kind)
    elif msg.startswith("Expecting value"):
        what, col = "expected value", col + 1
    elif msg.startswith("Extra data"):
        what, col = "trailing characters", col + 1
    elif "trailing comma" in msg:
        what, col = "trailing comma", col + 1
    else:
        what, col = msg[0].lower() + msg[1:], col + 1
    return ValueError(f"{what} at line {line} column {col}")
