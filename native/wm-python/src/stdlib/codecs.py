"""codecs: encoders and decoders by name, byte order marks, incremental
and stream codecs.

The text encodings are the ones str.encode() knows (UTF-8, UTF-16, UTF-32,
ASCII, Latin-1, cp1252, unicode_escape); the bytes-to-bytes codecs hex,
base64, zlib and quopri and the str-to-str rot13 work through encode() and
decode(). An error handler given to register_error() is kept for
lookup_error() but str.encode() only knows the built-in ones.
"""

import _native

BOM_UTF8 = b"\xef\xbb\xbf"
BOM_UTF16_LE = BOM_LE = b"\xff\xfe"
BOM_UTF16_BE = BOM_BE = b"\xfe\xff"
BOM_UTF32_LE = b"\xff\xfe\x00\x00"
BOM_UTF32_BE = b"\x00\x00\xfe\xff"
BOM = BOM_UTF16 = BOM_UTF16_LE
BOM_UTF32 = BOM_UTF32_LE


class CodecInfo:
    def __init__(self, encode, decode, streamreader=None, streamwriter=None, incrementalencoder=None, incrementaldecoder=None, name=None, *, _is_text_encoding=None):
        self.name = name
        self.encode = encode
        self.decode = decode
        self.incrementalencoder = incrementalencoder
        self.incrementaldecoder = incrementaldecoder
        self.streamwriter = streamwriter
        self.streamreader = streamreader
        self._is_text_encoding = True if _is_text_encoding is None else _is_text_encoding

    def __iter__(self):
        return iter((self.encode, self.decode, self.streamreader, self.streamwriter))

    def __getitem__(self, i):
        return (self.encode, self.decode, self.streamreader, self.streamwriter)[i]

    def __len__(self):
        return 4

    def __repr__(self):
        return f"<codecs.CodecInfo object for encoding {self.name}>"


def _rot13(s):
    out = []
    for c in s:
        if "a" <= c <= "z":
            out.append(chr((ord(c) - 97 + 13) % 26 + 97))
        elif "A" <= c <= "Z":
            out.append(chr((ord(c) - 65 + 13) % 26 + 65))
        else:
            out.append(c)
    return "".join(out)


def _bytes_codec(name):
    import base64
    import binascii
    import zlib

    table = {
        "hex": (binascii.b2a_hex, binascii.a2b_hex),
        "base64": (base64.encodebytes, base64.decodebytes),
        "zlib": (zlib.compress, zlib.decompress),
        "quopri": (binascii.b2a_qp, binascii.a2b_qp),
    }
    return table.get(name)


_ALIASES = {
    "hex_codec": "hex",
    "base64_codec": "base64",
    "base_64": "base64",
    "zlib_codec": "zlib",
    "zip": "zlib",
    "quopri_codec": "quopri",
    "quotedprintable": "quopri",
    "quoted_printable": "quopri",
    "rot_13": "rot13",
}
_search = []
_errors = {}


def _norm(encoding):
    return encoding.strip().lower().replace("-", "_").replace(" ", "_")


def _text_info(canonical):
    def encode(input, errors="strict"):
        return input.encode(canonical, errors), len(input)

    def decode(input, errors="strict"):
        data = bytes(input)
        return data.decode(canonical, errors), len(data)

    return CodecInfo(
        encode,
        decode,
        streamreader=_reader_for(canonical),
        streamwriter=_writer_for(canonical),
        incrementalencoder=_incremental_encoder_for(canonical),
        incrementaldecoder=_incremental_decoder_for(canonical),
        name=canonical,
    )


def lookup(encoding):
    if not isinstance(encoding, str):
        raise TypeError(f"lookup() argument must be str, not {type(encoding).__name__}")
    key = _norm(encoding)
    key = _ALIASES.get(key, key)
    if key == "rot13":
        return CodecInfo(lambda s, errors="strict": (_rot13(s), len(s)), lambda s, errors="strict": (_rot13(s), len(s)), name="rot-13", _is_text_encoding=False)
    pair = _bytes_codec(key)
    if pair is not None:
        enc, dec = pair
        return CodecInfo(lambda b, errors="strict": (enc(bytes(b)), len(b)), lambda b, errors="strict": (dec(bytes(b)), len(b)), name=key, _is_text_encoding=False)
    for search in _search:
        info = search(key)
        if info is not None:
            return info
    return _text_info(_native.encoding_name(encoding))


def encode(obj, encoding="utf-8", errors="strict"):
    info = lookup(encoding)
    if info._is_text_encoding and not isinstance(obj, str):
        raise TypeError(f"utf_8_encode() argument 1 must be str, not {type(obj).__name__}")
    return info.encode(obj, errors)[0]


def decode(obj, encoding="utf-8", errors="strict"):
    return lookup(encoding).decode(obj, errors)[0]


def getencoder(encoding):
    return lookup(encoding).encode


def getdecoder(encoding):
    return lookup(encoding).decode


def getincrementalencoder(encoding):
    factory = lookup(encoding).incrementalencoder
    if factory is None:
        raise LookupError(encoding)
    return factory


def getincrementaldecoder(encoding):
    factory = lookup(encoding).incrementaldecoder
    if factory is None:
        raise LookupError(encoding)
    return factory


def getreader(encoding):
    return lookup(encoding).streamreader


def getwriter(encoding):
    return lookup(encoding).streamwriter


def register(search_function):
    _search.append(search_function)


def unregister(search_function):
    if search_function in _search:
        _search.remove(search_function)


def _builtin_handler(name):
    def handler(exc):
        raise exc

    handler.__name__ = name + "_errors"
    return handler


for _name in ("strict", "ignore", "replace", "backslashreplace", "xmlcharrefreplace", "namereplace"):
    _errors[_name] = _builtin_handler(_name)
strict_errors = _errors["strict"]
ignore_errors = _errors["ignore"]
replace_errors = _errors["replace"]
backslashreplace_errors = _errors["backslashreplace"]
xmlcharrefreplace_errors = _errors["xmlcharrefreplace"]
namereplace_errors = _errors["namereplace"]


def register_error(name, error_handler):
    if not callable(error_handler):
        raise TypeError("handler must be callable")
    _errors[name] = error_handler


def lookup_error(name):
    if name not in _errors:
        raise LookupError(f"unknown error handler name '{name}'")
    return _errors[name]


class Codec:
    """The base of a codec's stateless encode and decode, as packages subclass it."""

    def encode(self, input, errors="strict"):
        raise NotImplementedError

    def decode(self, input, errors="strict"):
        raise NotImplementedError


class IncrementalEncoder:
    def __init__(self, errors="strict"):
        self.errors = errors
        self.buffer = ""

    def encode(self, input, final=False):
        raise NotImplementedError

    def reset(self):
        pass

    def getstate(self):
        return 0

    def setstate(self, state):
        pass


class IncrementalDecoder:
    def __init__(self, errors="strict"):
        self.errors = errors

    def decode(self, input, final=False):
        raise NotImplementedError

    def reset(self):
        pass

    def getstate(self):
        return (b"", 0)

    def setstate(self, state):
        pass


class BufferedIncrementalEncoder(IncrementalEncoder):
    """An encoder that keeps what it cannot encode yet for the next call."""

    def __init__(self, errors="strict"):
        IncrementalEncoder.__init__(self, errors)
        self.buffer = ""

    def _buffer_encode(self, input, errors, final):
        raise NotImplementedError

    def encode(self, input, final=False):
        data = self.buffer + input
        result, consumed = self._buffer_encode(data, self.errors, final)
        self.buffer = data[consumed:]
        return result

    def reset(self):
        IncrementalEncoder.reset(self)
        self.buffer = ""

    def getstate(self):
        return self.buffer or 0

    def setstate(self, state):
        self.buffer = state or ""


class BufferedIncrementalDecoder(IncrementalDecoder):
    def __init__(self, errors="strict"):
        super().__init__(errors)
        self.buffer = b""

    def _buffer_decode(self, input, errors, final):
        raise NotImplementedError

    def decode(self, input, final=False):
        data = self.buffer + bytes(input)
        result, consumed = self._buffer_decode(data, self.errors, final)
        self.buffer = data[consumed:]
        return result

    def reset(self):
        self.buffer = b""

    def getstate(self):
        return (self.buffer, 0)

    def setstate(self, state):
        self.buffer = state[0]


def _complete(data, canonical):
    """How many leading bytes of data make whole characters."""
    if canonical in ("utf-8", "utf-8-sig"):
        end = len(data)
        for back in range(1, min(4, len(data)) + 1):
            b = data[-back]
            if b & 0xC0 == 0x80:
                continue
            need = 2 if b & 0xE0 == 0xC0 else 3 if b & 0xF0 == 0xE0 else 4 if b & 0xF8 == 0xF0 else 1
            if need > back:
                end = len(data) - back
            break
        return end
    if canonical.startswith("utf-16"):
        end = len(data) - len(data) % 2
        if end >= 2:
            unit = data[end - 2 : end]
            high = unit[1] if canonical != "utf-16-be" else unit[0]
            if 0xD8 <= high <= 0xDB:
                end -= 2
        return end
    if canonical.startswith("utf-32"):
        return len(data) - len(data) % 4
    if canonical == "unicode-escape":
        cut = data.rfind(b"\\")
        return cut if cut >= 0 and len(data) - cut < 10 else len(data)
    return len(data)


def _incremental_decoder_for(canonical):
    class Decoder(BufferedIncrementalDecoder):
        def __init__(self, errors="strict"):
            super().__init__(errors)
            self._bom_seen = canonical not in ("utf-16", "utf-32", "utf-8-sig")
            self._codec = canonical

        def _buffer_decode(self, data, errors, final):
            codec = self._codec
            if not self._bom_seen:
                need = {"utf-16": 2, "utf-32": 4, "utf-8-sig": 3}[canonical]
                if len(data) < need and not final:
                    return "", 0
                self._bom_seen = True
                if canonical == "utf-16":
                    codec = self._codec = "utf-16-be" if data[:2] == BOM_UTF16_BE else "utf-16-le"
                    if data[:2] in (BOM_UTF16_LE, BOM_UTF16_BE):
                        data = data[2:]
                        text, used = self._buffer_decode(data, errors, final)
                        return text, used + 2
                elif canonical == "utf-32":
                    codec = self._codec = "utf-32-be" if data[:4] == BOM_UTF32_BE else "utf-32-le"
                    if data[:4] in (BOM_UTF32_LE, BOM_UTF32_BE):
                        text, used = self._buffer_decode(data[4:], errors, final)
                        return text, used + 4
                else:
                    codec = self._codec = "utf-8"
                    if data[:3] == BOM_UTF8:
                        text, used = self._buffer_decode(data[3:], errors, final)
                        return text, used + 3
            end = len(data) if final else _complete(data, codec)
            return data[:end].decode(codec, errors), end

        def reset(self):
            super().reset()
            self._bom_seen = canonical not in ("utf-16", "utf-32", "utf-8-sig")
            self._codec = canonical

    Decoder.__name__ = "IncrementalDecoder"
    return Decoder


def _incremental_encoder_for(canonical):
    class Encoder(IncrementalEncoder):
        def __init__(self, errors="strict"):
            super().__init__(errors)
            self._first = True

        def encode(self, input, final=False):
            codec = canonical
            if not self._first:
                codec = {"utf-16": "utf-16-le", "utf-32": "utf-32-le", "utf-8-sig": "utf-8"}.get(canonical, canonical)
            if input:
                self._first = False
            return input.encode(codec, self.errors)

        def reset(self):
            self._first = True

    Encoder.__name__ = "IncrementalEncoder"
    return Encoder


class StreamWriter:
    encoding = "utf-8"

    def __init__(self, stream, errors="strict"):
        self.stream = stream
        self.errors = errors
        self._encoder = getincrementalencoder(self.encoding)(errors)

    def write(self, object):
        self.stream.write(self._encoder.encode(object))

    def writelines(self, list):
        self.write("".join(list))

    def reset(self):
        self._encoder.reset()

    def __getattr__(self, name):
        return getattr(self.stream, name)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.stream.close()


class StreamReader:
    encoding = "utf-8"

    def __init__(self, stream, errors="strict"):
        self.stream = stream
        self.errors = errors
        self._decoder = getincrementaldecoder(self.encoding)(errors)
        self._text = ""

    def read(self, size=-1, chars=-1, firstline=False):
        data = self.stream.read() if size is None or size < 0 else self.stream.read(size)
        self._text += self._decoder.decode(data, final=not data)
        if chars is not None and chars >= 0:
            out, self._text = self._text[:chars], self._text[chars:]
            return out
        out, self._text = self._text, ""
        return out

    def readline(self, size=None, keepends=True):
        while "\n" not in self._text:
            data = self.stream.read(256)
            if not data:
                self._text += self._decoder.decode(b"", final=True)
                break
            self._text += self._decoder.decode(data)
        cut = self._text.find("\n")
        cut = len(self._text) if cut < 0 else cut + 1
        line, self._text = self._text[:cut], self._text[cut:]
        return line if keepends else line.rstrip("\r\n")

    def readlines(self, sizehint=None, keepends=True):
        return self.read().splitlines(keepends)

    def reset(self):
        self._decoder.reset()
        self._text = ""

    def __iter__(self):
        return self

    def __next__(self):
        line = self.readline()
        if not line:
            raise StopIteration
        return line

    def __getattr__(self, name):
        return getattr(self.stream, name)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.stream.close()


class StreamReaderWriter:
    def __init__(self, stream, Reader, Writer, errors="strict"):
        self.stream = stream
        self.reader = Reader(stream, errors)
        self.writer = Writer(stream, errors)

    def read(self, size=-1):
        return self.reader.read(size)

    def readline(self, size=None):
        return self.reader.readline(size)

    def readlines(self, sizehint=None):
        return self.reader.readlines(sizehint)

    def write(self, data):
        return self.writer.write(data)

    def writelines(self, list):
        return self.writer.writelines(list)

    def __iter__(self):
        return iter(self.reader)

    def __getattr__(self, name):
        return getattr(self.stream, name)


def _reader_for(canonical):
    class Reader(StreamReader):
        encoding = canonical

    Reader.__name__ = "StreamReader"
    return Reader


def _writer_for(canonical):
    class Writer(StreamWriter):
        encoding = canonical

    Writer.__name__ = "StreamWriter"
    return Writer


def iterencode(iterator, encoding, errors="strict", **kwargs):
    encoder = getincrementalencoder(encoding)(errors, **kwargs)
    for input in iterator:
        output = encoder.encode(input)
        if output:
            yield output
    output = encoder.encode("", True)
    if output:
        yield output


def iterdecode(iterator, encoding, errors="strict", **kwargs):
    decoder = getincrementaldecoder(encoding)(errors, **kwargs)
    for input in iterator:
        output = decoder.decode(input)
        if output:
            yield output
    output = decoder.decode(b"", True)
    if output:
        yield output


def open(filename, mode="r", encoding=None, errors="strict", buffering=-1):
    raise OSError("codecs.open(): plugins have no file paths; read files through wm.files")


def EncodedFile(file, data_encoding, file_encoding=None, errors="strict"):
    raise OSError("codecs.EncodedFile() is not supported here")


def utf_8_encode(input, errors="strict"):
    return input.encode("utf-8", errors), len(input)


def utf_8_decode(input, errors="strict", final=False):
    data = bytes(input)
    end = len(data) if final else _complete(data, "utf-8")
    return data[:end].decode("utf-8", errors), end


def latin_1_encode(input, errors="strict"):
    return input.encode("latin-1", errors), len(input)


def latin_1_decode(input, errors="strict"):
    return bytes(input).decode("latin-1", errors), len(input)


def ascii_encode(input, errors="strict"):
    return input.encode("ascii", errors), len(input)


def ascii_decode(input, errors="strict"):
    return bytes(input).decode("ascii", errors), len(input)


def escape_decode(data, errors="strict"):
    data = data.encode("latin-1") if isinstance(data, str) else bytes(data)
    return data.decode("unicode_escape", errors).encode("latin-1"), len(data)


def escape_encode(data, errors="strict"):
    text = data.decode("latin-1")
    out = text.encode("unicode_escape").decode("latin-1").replace("'", "\\'")
    return out.encode("latin-1"), len(data)


def unicode_escape_encode(input, errors="strict"):
    return input.encode("unicode_escape", errors), len(input)


def unicode_escape_decode(input, errors="strict", final=True):
    data = input.encode("latin-1") if isinstance(input, str) else bytes(input)
    return data.decode("unicode_escape", errors), len(data)


# In CPython these live in the C module _codecs, and pickles name them
# there (`_codecs.encode` rebuilds bytes in protocols 0 to 2).
for _f in (encode, decode, lookup, register, register_error, lookup_error, escape_decode, escape_encode, unicode_escape_encode, unicode_escape_decode):
    _f.__module__ = "_codecs"
del _f
