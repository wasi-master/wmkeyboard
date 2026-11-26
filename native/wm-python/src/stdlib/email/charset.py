"""email.charset: character sets as mail headers and bodies encode them."""

import base64
import binascii

QP = 1
BASE64 = 2
SHORTEST = 3
RFC2047_CHROME_LEN = 7
DEFAULT_CHARSET = "us-ascii"
UNKNOWN8BIT = "unknown-8bit"
EMPTYSTRING = ""

CHARSETS = {
    "iso-8859-1": (QP, QP, None), "iso-8859-2": (QP, QP, None), "iso-8859-3": (QP, QP, None), "iso-8859-4": (QP, QP, None),
    "iso-8859-9": (QP, QP, None), "iso-8859-10": (QP, QP, None), "iso-8859-13": (QP, QP, None), "iso-8859-14": (QP, QP, None),
    "iso-8859-15": (QP, QP, None), "iso-8859-16": (QP, QP, None), "windows-1252": (QP, QP, None), "viscii": (QP, QP, None),
    "us-ascii": (None, None, None), "big5": (BASE64, BASE64, None), "gb2312": (BASE64, BASE64, None),
    "euc-jp": (BASE64, None, "iso-2022-jp"), "shift_jis": (BASE64, None, "iso-2022-jp"), "iso-2022-jp": (BASE64, None, None),
    "koi8-r": (BASE64, BASE64, None), "utf-8": (SHORTEST, BASE64, "utf-8"),
}
ALIASES = {
    "latin_1": "iso-8859-1", "latin-1": "iso-8859-1", "latin_2": "iso-8859-2", "latin-2": "iso-8859-2",
    "latin_9": "iso-8859-15", "latin-9": "iso-8859-15", "cp949": "ks_c_5601-1987", "euc_jp": "euc-jp",
    "euc_kr": "euc-kr", "ascii": "us-ascii", "utf8": "utf-8",
}
CODEC_MAP = {"gb2312": "eucgb2312_cn", "big5": "big5_tw", "us-ascii": None}


def add_charset(charset, header_enc=None, body_enc=None, output_charset=None):
    CHARSETS[charset] = (header_enc, body_enc, output_charset)


def add_alias(alias, canonical):
    ALIASES[alias] = canonical


def add_codec(charset, codecname):
    CODEC_MAP[charset] = codecname


def _qp_header(data):
    out = []
    for b in data:
        c = chr(b)
        if c == " ":
            out.append("_")
        elif c.isalnum() and b < 128 or c in "!*+-/":
            out.append(c)
        else:
            out.append(f"={b:02X}")
    return "".join(out)


def _qp_body(text):
    out = []
    for line in text.splitlines(True):
        body = line.rstrip("\r\n")
        ending = line[len(body) :]
        encoded = []
        for ch in body:
            b = ch.encode("latin-1", "replace")[0] if ord(ch) < 256 else None
            if b is not None and (33 <= b <= 126 and ch != "=" or ch in " \t"):
                encoded.append(ch)
            else:
                encoded.extend(f"={x:02X}" for x in ch.encode("utf-8"))
        joined = "".join(encoded)
        if joined[-1:] in (" ", "\t"):
            joined = joined[:-1] + f"={ord(joined[-1]):02X}"
        out.append(joined + ("\n" if ending else ""))
    return "".join(out)


class Charset:
    def __init__(self, input_charset=DEFAULT_CHARSET):
        input_charset = str(input_charset).lower()
        self.input_charset = ALIASES.get(input_charset, input_charset)
        henc, benc, conv = CHARSETS.get(self.input_charset, (SHORTEST, BASE64, None))
        if not conv:
            conv = self.input_charset
        self.header_encoding = henc
        self.body_encoding = benc
        self.output_charset = ALIASES.get(conv, conv)
        self.input_codec = CODEC_MAP.get(self.input_charset, self.input_charset)
        self.output_codec = CODEC_MAP.get(self.output_charset, self.output_charset)

    def __repr__(self):
        return self.input_charset.lower()

    __str__ = __repr__

    def __eq__(self, other):
        return str(self) == str(other).lower()

    def __hash__(self):
        return hash(str(self))

    def get_body_encoding(self):
        if self.body_encoding == QP:
            return "quoted-printable"
        if self.body_encoding == BASE64:
            return "base64"
        return "7bit"

    def get_output_charset(self):
        return self.output_charset or self.input_charset

    def header_encode(self, string):
        codec = self.output_codec or "us-ascii"
        header_bytes = string.encode(codec)
        encoding = self.header_encoding
        if encoding == SHORTEST:
            b = base64.b64encode(header_bytes).decode("ascii")
            q = _qp_header(header_bytes)
            encoding = BASE64 if len(b) < len(q) else QP
        if encoding == BASE64:
            return f"=?{self.get_output_charset()}?b?{base64.b64encode(header_bytes).decode('ascii')}?="
        if encoding == QP:
            return f"=?{self.get_output_charset()}?q?{_qp_header(header_bytes)}?="
        return string

    def body_encode(self, string):
        if not string:
            return string
        if self.body_encoding is BASE64:
            if isinstance(string, str):
                string = string.encode(self.output_charset)
            return base64.encodebytes(string).decode("ascii")
        if self.body_encoding is QP:
            if isinstance(string, bytes):
                string = string.decode("latin-1")
            return _qp_body(string)
        if isinstance(string, str):
            string = string.encode(self.output_charset).decode("ascii")
        return string
