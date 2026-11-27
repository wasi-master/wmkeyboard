"""json.encoder: the names CPython's Lib/json/encoder.py gives, over the
json module's own encoder, which is native here."""

import re

from json import JSONEncoder

__all__ = ["JSONEncoder"]

ESCAPE = re.compile(r'[\x00-\x1f\\"\b\f\n\r\t]')
ESCAPE_ASCII = re.compile(r'([\\"]|[^\ -~])')
HAS_UTF8 = re.compile(b"[\x80-\xff]")
ESCAPE_DCT = {
    "\\": "\\\\",
    '"': '\\"',
    "\b": "\\b",
    "\f": "\\f",
    "\n": "\\n",
    "\r": "\\r",
    "\t": "\\t",
}
for i in range(0x20):
    ESCAPE_DCT.setdefault(chr(i), "\\u{0:04x}".format(i))
del i

INFINITY = float("inf")


def encode_basestring(s):
    """[s] as a JSON string, quotes and all."""
    return __json_encode__(str(s))


def encode_basestring_ascii(s):
    """[s] as a JSON string of ASCII alone: the rest as \\u escapes."""
    return __json_encode__(str(s), ensure_ascii=True)


py_encode_basestring = c_encode_basestring = encode_basestring
py_encode_basestring_ascii = c_encode_basestring_ascii = encode_basestring_ascii
c_make_encoder = None
