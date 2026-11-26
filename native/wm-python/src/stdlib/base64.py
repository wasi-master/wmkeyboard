"""base64: bytes as base64, base32, base16, base85 and Ascii85 text, and back."""

import binascii

import _native

bytes_types = (bytes, bytearray)


def _input(s):
    if isinstance(s, bytes):
        return s
    if isinstance(s, bytearray):
        return bytes(s)
    raise TypeError(f"a bytes-like object is required, not '{type(s).__name__}'")


def _bytes_from_decode_data(s):
    if isinstance(s, str):
        if not s.isascii():
            raise ValueError("string argument should contain only ASCII characters")
        return s.encode("ascii")
    if isinstance(s, (bytes, bytearray)):
        return bytes(s)
    raise TypeError(f"argument should be a bytes-like object or ASCII string, not '{type(s).__name__}'")


def _swap(data, frm, to):
    out = bytearray(data)
    for i, b in enumerate(out):
        if b == frm[0]:
            out[i] = to[0]
        elif b == frm[1]:
            out[i] = to[1]
    return bytes(out)


def b64encode(s, altchars=None):
    encoded = _native.b64encode(_input(s), False)
    if altchars is not None:
        altchars = _input(altchars)
        if len(altchars) != 2:
            raise ValueError(f"invalid altchars: {altchars!r}")
        encoded = _swap(encoded, b"+/", altchars)
    return encoded


def b64decode(s, altchars=None, validate=False):
    s = _bytes_from_decode_data(s)
    if altchars is not None:
        altchars = _bytes_from_decode_data(altchars)
        if len(altchars) != 2:
            raise ValueError(f"invalid altchars: {altchars!r}")
        s = _swap(s, altchars, b"+/")
    return binascii.a2b_base64(s, strict_mode=validate)


def standard_b64encode(s):
    return b64encode(s)


def standard_b64decode(s):
    return b64decode(s)


def urlsafe_b64encode(s):
    return b64encode(s, b"-_")


def urlsafe_b64decode(s):
    return b64decode(_bytes_from_decode_data(s), b"-_")


def encodebytes(s):
    s = _input(s)
    lines = [binascii.b2a_base64(s[i : i + 57]) for i in range(0, len(s), 57)]
    return b"".join(lines)


def decodebytes(s):
    return binascii.a2b_base64(_input(s))


def b16encode(s):
    return _input(s).hex().upper().encode("ascii")


def b16decode(s, casefold=False):
    s = _bytes_from_decode_data(s)
    if casefold:
        s = s.upper()
    if any(c not in b"0123456789ABCDEF" for c in s):
        raise binascii.Error("Non-base16 digit found")
    return binascii.unhexlify(s)


_B32 = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
_B32HEX = b"0123456789ABCDEFGHIJKLMNOPQRSTUV"


def _b32encode(alphabet, s):
    s = _input(s)
    out = bytearray()
    for i in range(0, len(s), 5):
        chunk = s[i : i + 5]
        n = int.from_bytes(chunk + b"\x00" * (5 - len(chunk)), "big")
        digits = [alphabet[(n >> (35 - 5 * k)) & 31] for k in range(8)]
        keep = {1: 2, 2: 4, 3: 5, 4: 7, 5: 8}[len(chunk)]
        out.extend(digits[:keep])
        out.extend(b"=" * (8 - keep))
    return bytes(out)


def _b32decode(alphabet, s, casefold=False, map01=None):
    s = _bytes_from_decode_data(s)
    if len(s) % 8:
        raise binascii.Error("Incorrect padding")
    if map01 is not None:
        map01 = _bytes_from_decode_data(map01)
        s = s.replace(b"0", b"O").replace(b"1", map01)
    if casefold:
        s = s.upper()
    body = s.rstrip(b"=")
    pad = len(s) - len(body)
    if pad not in (0, 1, 3, 4, 6):
        raise binascii.Error("Incorrect padding")
    out = bytearray()
    for i in range(0, len(body), 8):
        chunk = body[i : i + 8]
        n = 0
        for c in chunk:
            d = alphabet.find(bytes([c]))
            if d < 0:
                raise binascii.Error("Non-base32 digit found")
            n = n * 32 + d
        n <<= 5 * (8 - len(chunk))
        size = {8: 5, 7: 4, 5: 3, 4: 2, 2: 1}.get(len(chunk))
        if size is None:
            raise binascii.Error("Incorrect padding")
        out.extend(n.to_bytes(5, "big")[:size])
    return bytes(out)


def b32encode(s):
    return _b32encode(_B32, s)


def b32decode(s, casefold=False, map01=None):
    return _b32decode(_B32, s, casefold, map01)


def b32hexencode(s):
    return _b32encode(_B32HEX, s)


def b32hexdecode(s, casefold=False):
    return _b32decode(_B32HEX, s, casefold)


_B85 = b"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#$%&()*+-;<=>?@^_`{|}~"
_Z85 = b"0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-:+=^!/*?&<>()[]{}@%$#"


def _85encode(s, alphabet, pad, foldnuls=False, foldspaces=False):
    s = _input(s)
    padding = -len(s) % 4
    data = s + b"\x00" * padding
    out = bytearray()
    for i in range(0, len(data), 4):
        word = int.from_bytes(data[i : i + 4], "big")
        if foldnuls and word == 0:
            out.extend(b"z")
            continue
        if foldspaces and word == 0x20202020:
            out.extend(b"y")
            continue
        digits = []
        for _ in range(5):
            word, r = divmod(word, 85)
            digits.append(alphabet[r])
        out.extend(reversed(digits))
    if padding and not pad:
        if out[-1:] == b"z":
            out[-1:] = alphabet[0:1] * 5
        del out[len(out) - padding :]
    return bytes(out)


def _85decode(b, alphabet, what):
    table = {c: i for i, c in enumerate(alphabet)}
    padding = -len(b) % 5
    b = b + alphabet[-1:] * padding
    out = bytearray()
    for i in range(0, len(b), 5):
        n = 0
        for j, c in enumerate(b[i : i + 5]):
            if c not in table:
                raise ValueError(f"bad {what} character at position {i + j}")
            n = n * 85 + table[c]
        if n > 0xFFFFFFFF:
            raise ValueError(f"{what} overflow in hunk starting at byte {i}")
        out.extend(n.to_bytes(4, "big"))
    if padding:
        del out[len(out) - padding :]
    return bytes(out)


def b85encode(b, pad=False):
    return _85encode(b, _B85, pad)


def b85decode(b):
    return _85decode(_bytes_from_decode_data(b), _B85, "base85")


def z85encode(s):
    return _85encode(s, _Z85, True)


def z85decode(s):
    return _85decode(_bytes_from_decode_data(s), _Z85, "z85")


_A85 = bytes(range(33, 118))


def a85encode(b, *, foldspaces=False, wrapcol=0, pad=False, adobe=False):
    out = _85encode(b, _A85, pad, True, foldspaces)
    if adobe:
        out = b"<~" + out
    if wrapcol and out:
        width = max(2 if adobe else 1, wrapcol)
        out = b"\n".join(out[i : i + width] for i in range(0, len(out), width))
    if adobe:
        out += b"~>"
    return out


def a85decode(b, *, foldspaces=False, adobe=False, ignorechars=b" \t\n\r\x0b"):
    b = _bytes_from_decode_data(b)
    if adobe:
        if not b.endswith(b"~>"):
            raise ValueError(f"Ascii85 encoded byte sequences must end with {b'~>'!r}")
        b = b[2:-2] if b.startswith(b"<~") else b[:-2]
    data = bytearray()
    for c in b:
        if c in ignorechars:
            continue
        if c == 0x7A:
            data.extend(b"!!!!!")
        elif c == 0x79 and foldspaces:
            data.extend(b"+<VdL")
        else:
            data.append(c)
    return _85decode(bytes(data), _A85, "Ascii85")


def encode(input, output):
    while True:
        s = input.read(57)
        if not s:
            break
        output.write(binascii.b2a_base64(s))


def decode(input, output):
    output.write(binascii.a2b_base64(input.read()))
