"""email.header: RFC 2047 encoded words in headers, decoded and made."""

import base64
import binascii
import re

from email import charset as _charset
from email.errors import HeaderParseError

Charset = _charset.Charset

NL = "\n"
SPACE = " "
BSPACE = b" "
EMPTYSTRING = ""
MAXLINELEN = 78
FWS = " \t"
USASCII = Charset("us-ascii")
UTF8 = Charset("utf-8")

ecre = re.compile(r"=\?([^?]*?)\?([qQbB])\?(.*?)\?=", re.MULTILINE)


def _qp_decode(s):
    s = s.replace("_", " ")
    return re.sub(r"=[a-fA-F0-9]{2}", lambda m: chr(int(m.group(0)[1:], 16)), s, flags=re.ASCII)


def decode_header(header):
    if hasattr(header, "_chunks"):
        return [(string.encode(str(charset), "replace") if str(charset) != "unknown-8bit" else string.encode("latin-1"), str(charset)) for string, charset in header._chunks]
    if not ecre.search(header):
        return [(header, None)]
    words = []
    for line in header.splitlines():
        parts = ecre.split(line)
        first = True
        while parts:
            unencoded = parts.pop(0)
            if first:
                unencoded = unencoded.lstrip()
                first = False
            if unencoded:
                words.append((unencoded, None, None))
            if parts:
                charset = parts.pop(0).lower()
                encoding = parts.pop(0).lower()
                encoded = parts.pop(0)
                words.append((encoded, encoding, charset))
    droplist = []
    for n, w in enumerate(words):
        if n > 1 and w[1] and words[n - 2][1] and words[n - 1][0].isspace():
            droplist.append(n - 1)
    for d in reversed(droplist):
        del words[d]
    decoded_words = []
    for encoded_string, encoding, charset in words:
        if encoding is None:
            decoded_words.append((encoded_string, charset))
        elif encoding == "q":
            decoded_words.append((_qp_decode(encoded_string), charset))
        elif encoding == "b":
            paderr = len(encoded_string) % 4
            if paderr:
                encoded_string += "==="[: 4 - paderr]
            try:
                word = base64.b64decode(encoded_string)
            except binascii.Error:
                raise HeaderParseError("Base64 decoding error") from None
            decoded_words.append((word, charset))
        else:
            raise AssertionError("Unexpected encoding: " + encoding)
    collapsed = []
    last_word = last_charset = None
    for word, charset in decoded_words:
        if isinstance(word, str):
            word = bytes(word, "raw-unicode-escape")
        if last_word is None:
            last_word = word
            last_charset = charset
        elif charset != last_charset:
            collapsed.append((last_word, last_charset))
            last_word = word
            last_charset = charset
        elif last_charset is None:
            last_word += BSPACE + word
        else:
            last_word += word
    collapsed.append((last_word, last_charset))
    return collapsed


def make_header(decoded_seq, maxlinelen=None, header_name=None, continuation_ws=" "):
    h = Header(maxlinelen=maxlinelen, header_name=header_name, continuation_ws=continuation_ws)
    for s, charset in decoded_seq:
        if charset is not None and not isinstance(charset, Charset):
            charset = Charset(charset)
        h.append(s, charset)
    return h


class Header:
    def __init__(self, s=None, charset=None, maxlinelen=None, header_name=None, continuation_ws=" ", errors="strict"):
        if charset is None:
            charset = USASCII
        elif not isinstance(charset, Charset):
            charset = Charset(charset)
        self._charset = charset
        self._continuation_ws = continuation_ws
        self._chunks = []
        if s is not None:
            self.append(s, charset, errors)
        if maxlinelen is None:
            maxlinelen = MAXLINELEN
        self._maxlinelen = maxlinelen
        self._headerlen = 0 if header_name is None else len(header_name) + 2

    @staticmethod
    def _nonctext(s):
        return s.isspace() or s in ("(", ")", "\\")

    def __str__(self):
        self._normalize()
        uchunks = []
        lastcs = None
        lastspace = None
        for string, charset in self._chunks:
            nextcs = charset
            if uchunks:
                hasspace = string and self._nonctext(string[0])
                if lastcs not in (None, "us-ascii"):
                    if nextcs in (None, "us-ascii") and not hasspace:
                        uchunks.append(SPACE)
                        nextcs = None
                elif nextcs not in (None, "us-ascii") and not lastspace:
                    uchunks.append(SPACE)
            lastspace = string and self._nonctext(string[-1])
            lastcs = nextcs
            uchunks.append(string)
        return EMPTYSTRING.join(uchunks)

    def __eq__(self, other):
        return other == str(self)

    def append(self, s, charset=None, errors="strict"):
        if charset is None:
            charset = self._charset
        elif not isinstance(charset, Charset):
            charset = Charset(charset)
        if not isinstance(s, str):
            input_charset = charset.input_codec or "us-ascii"
            if input_charset == _charset.UNKNOWN8BIT:
                s = s.decode("latin-1")
            else:
                s = s.decode(input_charset, errors)
        output_charset = charset.output_codec or "us-ascii"
        if output_charset != _charset.UNKNOWN8BIT:
            try:
                s.encode(output_charset, errors)
            except UnicodeEncodeError:
                if output_charset != "us-ascii":
                    raise
                charset = UTF8
        self._chunks.append((s, charset))

    def _normalize(self):
        chunks = []
        last_charset = None
        last_chunk = []
        for string, charset in self._chunks:
            if charset == last_charset:
                last_chunk.append(string)
            else:
                if last_charset is not None:
                    chunks.append((SPACE.join(last_chunk), last_charset))
                last_chunk = [string]
                last_charset = charset
        if last_chunk:
            chunks.append((SPACE.join(last_chunk), last_charset))
        self._chunks = chunks

    def encode(self, splitchars=";, \t", maxlinelen=None, linesep="\n"):
        self._normalize()
        parts = []
        for string, charset in self._chunks:
            if str(charset) in ("us-ascii", "None"):
                parts.append(string)
            else:
                parts.append(charset.header_encode(string))
        return SPACE.join(parts)
