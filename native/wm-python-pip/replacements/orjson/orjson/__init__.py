"""orjson: JSON to and from bytes, the API of orjson.

orjson is written in Rust; this is the same API in Python for WM Keyboard
plugins, which cannot load compiled code. dumps gives the same bytes orjson
gives (compact, UTF-8, dataclasses, datetimes, enums and UUIDs as orjson
writes them), and loads reads what orjson reads, over the json module.
It is slower than orjson, which plugins sending a few kilobytes do not notice.
"""

import dataclasses as _dataclasses
import datetime as _dt
import enum as _enum
import json as _json
import uuid as _uuid
from json.encoder import encode_basestring as _quote

__version__ = "3.13.0"

__all__ = (
    "__version__",
    "dumps",
    "Fragment",
    "JSONDecodeError",
    "JSONEncodeError",
    "loads",
    "OPT_APPEND_NEWLINE",
    "OPT_INDENT_2",
    "OPT_NAIVE_UTC",
    "OPT_NON_STR_KEYS",
    "OPT_OMIT_MICROSECONDS",
    "OPT_PASSTHROUGH_DATACLASS",
    "OPT_PASSTHROUGH_DATETIME",
    "OPT_PASSTHROUGH_SUBCLASS",
    "OPT_SERIALIZE_DATACLASS",
    "OPT_SERIALIZE_NUMPY",
    "OPT_SERIALIZE_UUID",
    "OPT_SORT_KEYS",
    "OPT_STRICT_INTEGER",
    "OPT_UTC_Z",
)

OPT_INDENT_2 = 1
OPT_NAIVE_UTC = 1 << 1
OPT_NON_STR_KEYS = 1 << 2
OPT_OMIT_MICROSECONDS = 1 << 3
OPT_SERIALIZE_NUMPY = 1 << 4
OPT_SORT_KEYS = 1 << 5
OPT_STRICT_INTEGER = 1 << 6
OPT_UTC_Z = 1 << 7
OPT_PASSTHROUGH_SUBCLASS = 1 << 8
OPT_PASSTHROUGH_DATETIME = 1 << 9
OPT_APPEND_NEWLINE = 1 << 10
OPT_PASSTHROUGH_DATACLASS = 1 << 11
# Always on since orjson 3.0; kept so code naming them still runs.
OPT_SERIALIZE_DATACLASS = 0
OPT_SERIALIZE_UUID = 0

_ALL_OPTS = (1 << 12) - 1
_RECURSION_LIMIT = 255
_DEFAULT_LIMIT = 254
_INF = float("inf")


class JSONDecodeError(_json.JSONDecodeError):
    pass


class JSONEncodeError(TypeError):
    pass


class Fragment:
    """Already-serialized JSON that dumps writes as it is."""

    __slots__ = ("contents",)

    def __init__(self, contents, /):
        object.__setattr__(self, "contents", contents)

    def __setattr__(self, name, value):
        raise AttributeError(f"'orjson.Fragment' object attribute '{name}' is read-only")

    def __repr__(self):
        return f"<orjson.Fragment object at {id(self):#x}>"


def _float(v):
    if v != v or v == _INF or v == -_INF:
        return "null"
    r = repr(v)
    if "e" not in r:
        return r
    m, e = r.split("e")
    x = int(e)
    if x == -5:
        # Python writes 1e-05 and orjson 0.00001.
        sign = "-" if m.startswith("-") else ""
        return sign + "0.0000" + m.lstrip("-").replace(".", "")
    return f"{m}e{'+' if x > 0 else '-'}{-x if x < 0 else x}"


def _offset(o, opts):
    tz = o.tzinfo
    if tz is not None and hasattr(tz, "normalize"):
        # pytz: its zones give the offset of the wall time through normalize.
        off = tz.normalize(o).utcoffset()
    else:
        off = o.utcoffset()
    if off is None:
        if opts & OPT_NAIVE_UTC:
            return "Z" if opts & OPT_UTC_Z else "+00:00"
        return ""
    secs = off.days * 86400 + off.seconds
    if secs == 0 and opts & OPT_UTC_Z:
        return "Z"
    sign = "-" if secs < 0 else "+"
    secs = -secs if secs < 0 else secs
    return "%s%02d:%02d" % (sign, secs // 3600, secs // 60 % 60)


def _clock(o, opts):
    s = "%02d:%02d:%02d" % (o.hour, o.minute, o.second)
    if o.microsecond and not opts & OPT_OMIT_MICROSECONDS:
        s += ".%06d" % o.microsecond
    return s


def _datetime(o, opts):
    return "%04d-%02d-%02dT%s%s" % (o.year, o.month, o.day, _clock(o, opts), _offset(o, opts))


def _date(o):
    return "%04d-%02d-%02d" % (o.year, o.month, o.day)


def _time(o, opts):
    if o.tzinfo is not None:
        raise JSONEncodeError("datetime.time must not have tzinfo set")
    return _clock(o, opts)


def _when(o, opts):
    """A datetime, date or time as orjson writes it, else None."""
    if isinstance(o, _dt.datetime):
        return _datetime(o, opts)
    if isinstance(o, _dt.date):
        return _date(o)
    if isinstance(o, _dt.time):
        return _time(o, opts)
    return None


def _int(o, opts):
    if opts & OPT_STRICT_INTEGER:
        if not -9007199254740991 <= o <= 9007199254740991:
            raise JSONEncodeError("Integer exceeds 53-bit range")
    elif not -9223372036854775808 <= o <= 18446744073709551615:
        raise JSONEncodeError("Integer exceeds 64-bit range")
    return str(int(o))


def _key(k, opts):
    if type(k) is str:
        return k
    if not opts & OPT_NON_STR_KEYS:
        raise JSONEncodeError("Dict key must be str")
    if isinstance(k, str):
        return str.__str__(k)
    if k is None:
        return "null"
    if k is True:
        return "true"
    if k is False:
        return "false"
    if isinstance(k, _enum.Enum):
        return _key(k.value, opts)
    if isinstance(k, int):
        # Keys are not held to OPT_STRICT_INTEGER.
        return _int(k, opts & ~OPT_STRICT_INTEGER)
    if isinstance(k, float):
        return _float(k)
    if type(k) is _uuid.UUID:
        return str(k)
    w = _when(k, opts)
    if w is not None:
        return w
    raise JSONEncodeError("Dict key must a type serializable with OPT_NON_STR_KEYS")


class _Encoder:
    __slots__ = ("opts", "default", "indent", "out", "default_depth")

    def __init__(self, opts, default):
        self.opts = opts
        self.default = default
        self.indent = bool(opts & OPT_INDENT_2)
        self.out = []
        self.default_depth = 0

    def fallback(self, o, depth):
        if self.default is None:
            raise JSONEncodeError(f"Type is not JSON serializable: {type(o).__name__}")
        if self.default_depth > _DEFAULT_LIMIT:
            raise JSONEncodeError("default serializer exceeds recursion limit")
        try:
            new = self.default(o)
        except Exception as e:
            raise JSONEncodeError(f"Type is not JSON serializable: {type(o).__name__}") from e
        self.default_depth += 1
        try:
            self.write(new, depth)
        finally:
            self.default_depth -= 1

    def write(self, o, depth):
        out = self.out
        opts = self.opts
        t = type(o)
        if t is str:
            out.append(_quote(o))
        elif o is None:
            out.append("null")
        elif o is True:
            out.append("true")
        elif o is False:
            out.append("false")
        elif t is int:
            out.append(_int(o, opts))
        elif t is float:
            out.append(_float(o))
        elif t is dict:
            self.mapping(o, depth)
        elif t is list or t is tuple:
            self.sequence(o, depth)
        elif t is Fragment:
            c = o.contents
            if isinstance(c, bytes):
                try:
                    c = c.decode("utf-8")
                except UnicodeDecodeError:
                    raise JSONEncodeError("orjson.Fragment's content is not of type bytes or str") from None
            elif not isinstance(c, str):
                raise JSONEncodeError("orjson.Fragment's content is not of type bytes or str")
            out.append(c)
        elif isinstance(o, _enum.Enum):
            self.write(o.value, depth)
        elif isinstance(o, (_dt.datetime, _dt.date, _dt.time)):
            if opts & OPT_PASSTHROUGH_DATETIME:
                self.fallback(o, depth)
            else:
                out.append('"' + _when(o, opts) + '"')
        elif t is _uuid.UUID:
            out.append('"' + str(o) + '"')
        elif isinstance(o, (str, int, dict, list)) and not isinstance(o, bool):
            if opts & OPT_PASSTHROUGH_SUBCLASS:
                self.fallback(o, depth)
            elif isinstance(o, str):
                out.append(_quote(str.__str__(o)))
            elif isinstance(o, int):
                out.append(_int(int(o), opts))
            elif isinstance(o, dict):
                self.mapping(o, depth)
            else:
                self.sequence(o, depth)
        elif _dataclasses.is_dataclass(o) and not isinstance(o, type):
            if opts & OPT_PASSTHROUGH_DATACLASS:
                self.fallback(o, depth)
            else:
                self.dataclass(o, depth)
        else:
            self.fallback(o, depth)

    def open(self, depth):
        if depth >= _RECURSION_LIMIT - 1:
            raise JSONEncodeError("Recursion limit reached")

    def mapping(self, o, depth, sort=True):
        self.open(depth)
        out = self.out
        if not o:
            out.append("{}")
            return
        opts = self.opts
        items = [(_key(k, opts), v) for k, v in dict.items(o)]
        if sort and opts & OPT_SORT_KEYS:
            items.sort(key=lambda kv: kv[0])
        if self.indent:
            pad = "\n" + "  " * (depth + 1)
            out.append("{")
            first = True
            for k, v in items:
                out.append(pad if first else "," + pad)
                first = False
                out.append(_quote(k))
                out.append(": ")
                self.write(v, depth + 1)
            out.append("\n" + "  " * depth + "}")
        else:
            out.append("{")
            first = True
            for k, v in items:
                if not first:
                    out.append(",")
                first = False
                out.append(_quote(k))
                out.append(":")
                self.write(v, depth + 1)
            out.append("}")

    def sequence(self, o, depth):
        self.open(depth)
        out = self.out
        if not o:
            out.append("[]")
            return
        if self.indent:
            pad = "\n" + "  " * (depth + 1)
            out.append("[")
            first = True
            for v in o:
                out.append(pad if first else "," + pad)
                first = False
                self.write(v, depth + 1)
            out.append("\n" + "  " * depth + "]")
        else:
            out.append("[")
            first = True
            for v in o:
                if not first:
                    out.append(",")
                first = False
                self.write(v, depth + 1)
            out.append("]")

    def dataclass(self, o, depth):
        fields = getattr(type(o), "__dataclass_fields__", {})
        d = {}
        for name, f in fields.items():
            # orjson leaves out ClassVar and InitVar pseudo-fields, and names starting with _.
            if getattr(f, "_field_type", _dataclasses._FIELD) is not _dataclasses._FIELD or name.startswith("_"):
                continue
            d[name] = getattr(o, name)
        # In field order: OPT_SORT_KEYS does not reorder a dataclass.
        self.mapping(d, depth, sort=False)


def dumps(obj, /, default=None, option=None):
    """[obj] as JSON in UTF-8 bytes."""
    if option is None:
        opts = 0
    elif isinstance(option, int) and not isinstance(option, bool) and 0 <= option <= _ALL_OPTS:
        opts = option
    else:
        raise JSONEncodeError("Invalid opts")
    if default is not None and not callable(default):
        raise JSONEncodeError("default serializer must be callable")
    enc = _Encoder(opts, default)
    enc.write(obj, 0)
    if opts & OPT_APPEND_NEWLINE:
        enc.out.append("\n")
    try:
        return "".join(enc.out).encode("utf-8")
    except UnicodeEncodeError:
        raise JSONEncodeError("str is not valid UTF-8: surrogates not allowed") from None


_HIGH = "89abAB"
_LOW = "cdefCDEF"


def _escaped(text, i):
    """Whether the backslash at [i] is itself escaped (an odd run of backslashes before it)."""
    k = i
    while k > 0 and text[k - 1] == "\\":
        k -= 1
    return (i - k) % 2 == 1


def _lone_surrogates(text):
    """Refuses a \\uD800-\\uDFFF escape that is not half of a pair, as orjson does."""
    for prefix in ("\\ud", "\\uD"):
        i = text.find(prefix)
        while i != -1:
            half = text[i + 3 : i + 4]
            if half and half in _HIGH + _LOW and not _escaped(text, i):
                if half in _HIGH:
                    pair = text[i + 6 : i + 10]
                    ok = len(pair) == 4 and pair[0] == "\\" and pair[1] == "u" and pair[2] in "dD" and pair[3] in _LOW
                else:
                    before = text[i - 6 : i - 2] if i >= 6 else ""
                    ok = len(before) == 4 and before[0] == "\\" and before[1] == "u" and before[2] in "dD" and before[3] in _HIGH and not _escaped(text, i - 6)
                if not ok:
                    raise JSONDecodeError("lone surrogate in hex escape", text, i)
            i = text.find(prefix, i + 1)


def _constant(name):
    raise ValueError("unexpected character, expected a JSON value")


def _parse_int(s):
    n = int(s)
    if -9223372036854775808 <= n <= 18446744073709551615:
        return n
    return float(s)


def _parse_float(s):
    f = float(s)
    if f == _INF or f == -_INF:
        raise ValueError("number is infinity when parsed as double")
    return f


def loads(obj, /):
    """The Python value of the JSON document [obj]: bytes, bytearray, memoryview or str."""
    if isinstance(obj, str):
        text = obj
        try:
            text.encode("utf-8")
        except UnicodeEncodeError:
            raise JSONDecodeError("str is not valid UTF-8: surrogates not allowed", "", 0) from None
    elif isinstance(obj, (bytes, bytearray, memoryview)):
        try:
            text = bytes(obj).decode("utf-8")
        except UnicodeDecodeError:
            raise JSONDecodeError("str is not valid UTF-8: surrogates not allowed", "", 0) from None
    else:
        raise JSONDecodeError("Input must be bytes, bytearray, memoryview, or str", "", 0)
    if not text:
        raise JSONDecodeError("Input is a zero-length, empty document", "", 0)
    if text[0] == "﻿":
        raise JSONDecodeError("UTF-8 byte order mark (BOM) is not supported", text, 0)
    if "\\u" in text:
        _lone_surrogates(text)
    try:
        return _json.loads(text, parse_constant=_constant, parse_int=_parse_int, parse_float=_parse_float)
    except _json.JSONDecodeError as e:
        raise JSONDecodeError(e.msg, e.doc, e.pos) from None
    except (ValueError, RecursionError) as e:
        raise JSONDecodeError(str(e), text, 0) from None
