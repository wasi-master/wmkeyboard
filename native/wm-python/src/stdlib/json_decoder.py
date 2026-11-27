"""json.decoder: the names CPython's Lib/json/decoder.py gives, over the
json module's own decoder, which is native here."""

import re

from json import JSONDecodeError, JSONDecoder

__all__ = ["JSONDecoder", "JSONDecodeError"]

FLAGS = re.VERBOSE | re.MULTILINE | re.DOTALL

NaN = float("nan")
PosInf = float("inf")
NegInf = float("-inf")

_CONSTANTS = {
    "-Infinity": NegInf,
    "Infinity": PosInf,
    "NaN": NaN,
}

WHITESPACE = re.compile(r"[ \t\n\r]*", FLAGS)
WHITESPACE_STR = " \t\n\r"


def scanstring(s, end, strict=True):
    """The JSON string whose opening quote is just before character [end]
    of [s], and the index just past its closing quote."""
    if end > 0 and s[end - 1] == '"':
        return JSONDecoder(strict=strict).raw_decode(s, end - 1)
    try:
        value, stop = JSONDecoder(strict=strict).raw_decode('"' + s[end:])
    except JSONDecodeError as e:
        raise JSONDecodeError(e.msg, s, e.pos + end - 1) from None
    return value, stop + end - 1


py_scanstring = c_scanstring = scanstring
