"""Readers for formats a file preview meets often. The heavy ones (csv,
unpack) run in the keyboard; the rest are plain Python.

    formats.csv(text)                 # [["a", "b"], ["1", "2"]]
    formats.unpack("<HI", data, 4)    # struct.unpack_from
    formats.ini(text)                 # {"section": {"key": "value"}}
    formats.bencode(data)             # a .torrent's dicts, lists, ints and bytes
    formats.tar(data)                 # [{"name", "size", "offset", "folder"}]
"""

import _wm


def csv(text, delimiter=None, max_rows=None):
    """Rows of strings. [delimiter] is guessed from the first line (comma,
    semicolon, tab or bar) when not given. Quotes as RFC 4180 has them."""
    return _wm.csv(text, delimiter, max_rows)


def guess_delimiter(text):
    """The delimiter csv() would pick for [text]: the one its first line
    uses most of comma, semicolon, tab and bar."""
    return _wm.csv_delimiter(text)


def unpack(fmt, data, offset=0):
    """Python's struct.unpack_from for the codes x c b B ? h H i I l L q Q e
    f d s, little-endian unless [fmt] starts with > or !."""
    return _wm.unpack(fmt, data, offset)


def calcsize(fmt):
    """The number of bytes unpack([fmt], ...) reads."""
    return _wm.calcsize(fmt)


def ini(text):
    """An INI, .desktop or .properties file: {section: {key: value}}; keys
    before any section go under ""."""
    out = {"": {}}
    section = out[""]
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line[0] in "#;!":
            continue
        if line.startswith("[") and line.endswith("]"):
            name = line[1:-1].strip()
            section = out.setdefault(name, {})
            continue
        cut = -1
        for sep in ("=", ":"):
            i = line.find(sep)
            if i > 0 and (cut < 0 or i < cut):
                cut = i
        if cut < 0:
            section[line] = ""
        else:
            section[line[:cut].strip()] = line[cut + 1:].strip()
    if not out[""]:
        out.pop("")
    return out


def bencode(data):
    """A bencoded value (a .torrent file): ints, lists, dicts with str keys,
    and strings as bytes."""
    value, end = _bdecode(data, 0, 0)
    return value


def _bdecode(data, at, depth):
    if depth > 64:
        raise ValueError("bencode is nested too deep")
    if at >= len(data):
        raise ValueError("bencode is cut short")
    c = data[at]
    if c == 105:  # i
        end = data.find(b"e", at)
        if end < 0:
            raise ValueError("bencode is cut short")
        return int(data[at + 1:end].decode()), end + 1
    if c == 108:  # l
        out = []
        at = at + 1
        while at < len(data) and data[at] != 101:
            v, at = _bdecode(data, at, depth + 1)
            out.append(v)
        return out, at + 1
    if c == 100:  # d
        out = {}
        at = at + 1
        while at < len(data) and data[at] != 101:
            k, at = _bdecode(data, at, depth + 1)
            v, at = _bdecode(data, at, depth + 1)
            out[k.decode("utf-8", "replace") if isinstance(k, bytes) else str(k)] = v
        return out, at + 1
    colon = data.find(b":", at)
    if colon < 0:
        raise ValueError("bencode is not well formed")
    n = int(data[at:colon].decode())
    return data[colon + 1:colon + 1 + n], colon + 1 + n


def tar(data):
    """The entries of a tar archive: [{"name", "size", "offset", "folder",
    "modified"}]. Read an entry with data[offset:offset + size]."""
    out = []
    at = 0
    long_name = None
    while at + 512 <= len(data) and len(out) < 5000:
        header = data[at:at + 512]
        if header[0] == 0:
            break
        name = header[0:100].split(b"\x00")[0].decode("utf-8", "replace")
        size = _octal(header[124:136])
        modified = _octal(header[136:148])
        kind = header[156:157]
        prefix = header[345:500].split(b"\x00")[0].decode("utf-8", "replace") if header[257:262] == b"ustar" else ""
        if prefix:
            name = prefix + "/" + name
        body = at + 512
        if kind == b"L":
            long_name = data[body:body + size].split(b"\x00")[0].decode("utf-8", "replace")
        elif kind not in (b"x", b"g"):
            if long_name is not None:
                name = long_name
                long_name = None
            out.append({"name": name, "size": size, "offset": body, "folder": kind == b"5" or name.endswith("/"),
                        "modified": modified})
        at = body + ((size + 511) // 512) * 512
    return out


def _octal(field):
    text = field.split(b"\x00")[0].strip().decode("utf-8", "replace")
    if not text:
        return 0
    return int(text, 8)
