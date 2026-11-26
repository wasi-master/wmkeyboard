"""unicodedata: normal forms and character properties, from the Unicode
tables the interpreter carries. Character names are not among them."""

import _native

unidata_version = "15.1.0"


def normalize(form, unistr):
    return _native.normalize(form, unistr)


def is_normalized(form, unistr):
    return _native.is_normalized(form, unistr)


def category(chr):
    return _native.category(chr)


def combining(chr):
    return _native.combining(chr)


def east_asian_width(chr):
    return _native.east_asian_width(chr)


def mirrored(chr):
    return _native.mirrored(chr)


_MISSING = object()


def decimal(chr, default=_MISSING):
    v = _native.decimal(chr)
    if v is None:
        if default is _MISSING:
            raise ValueError("not a decimal")
        return default
    return v


def digit(chr, default=_MISSING):
    v = _native.decimal(chr)
    if v is None:
        if chr in "¹²³⁴⁵⁶⁷⁸⁹⁰":
            return "⁰¹²³⁴⁵⁶⁷⁸⁹".index(chr)
        if default is _MISSING:
            raise ValueError("not a digit")
        return default
    return v


def numeric(chr, default=_MISSING):
    try:
        return float(digit(chr))
    except ValueError:
        fractions = {"½": 0.5, "⅓": 1 / 3, "⅔": 2 / 3, "¼": 0.25, "¾": 0.75, "⅕": 0.2, "⅛": 0.125}
        if chr in fractions:
            return fractions[chr]
        if default is _MISSING:
            raise ValueError("not a numeric character")
        return default


def decomposition(chr):
    d = normalize("NFD", chr)
    if d == chr:
        return ""
    return " ".join(f"{ord(c):04X}" for c in d)
