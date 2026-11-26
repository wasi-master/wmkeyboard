"""binascii: hex, base64 and CRC conversions between bytes and ASCII."""

import _native


class Error(ValueError):
    pass


class Incomplete(Exception):
    pass


def _bytes(data, name):
    if isinstance(data, str):
        if not data.isascii():
            raise ValueError("string argument should contain only ASCII characters")
        return data.encode("ascii")
    if isinstance(data, bytes):
        return data
    if isinstance(data, bytearray):
        return bytes(data)
    raise TypeError(f"a bytes-like object is required, not '{type(data).__name__}'")


def hexlify(data, sep=None, bytes_per_sep=1):
    data = _bytes(data, "hexlify")
    if sep is None:
        return data.hex().encode("ascii")
    if isinstance(sep, bytes):
        sep = sep.decode("ascii")
    if len(sep) != 1:
        raise ValueError("sep must be length 1.")
    digits = data.hex()
    step = abs(bytes_per_sep) * 2
    if not step:
        return digits.encode("ascii")
    if bytes_per_sep > 0:
        # Groups count from the right, as CPython's do.
        head = len(digits) % step or step
        parts = [digits[:head]] + [digits[i:i + step] for i in range(head, len(digits), step)]
    else:
        parts = [digits[i:i + step] for i in range(0, len(digits), step)]
    return sep.join(p for p in parts if p).encode("ascii")


b2a_hex = hexlify


def unhexlify(hexstr):
    data = _bytes(hexstr, "unhexlify").decode("ascii")
    if len(data) % 2:
        raise Error("Odd-length string")
    if any(c not in "0123456789abcdefABCDEF" for c in data):
        raise Error("Non-hexadecimal digit found")
    return bytes.fromhex(data)


a2b_hex = unhexlify


def b2a_base64(data, *, newline=True):
    out = _native.b64encode(_bytes(data, "b2a_base64"), False)
    return out + b"\n" if newline else out


def a2b_base64(data, *, strict_mode=False):
    data = _bytes(data, "a2b_base64")
    keep = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
    if strict_mode:
        for b in data:
            if b not in keep:
                raise Error("Only base64 data is allowed")
    cleaned = bytes(b for b in data if b in keep)
    first_pad = cleaned.find(b"=")
    body = cleaned if first_pad < 0 else cleaned[:first_pad]
    pads = 0 if first_pad < 0 else cleaned[first_pad:].count(b"=")
    if len(body) % 4 == 1:
        raise Error(f"Invalid base64-encoded string: number of data characters ({len(body)}) cannot be 1 more than a multiple of 4")
    if len(body) % 4 and pads < 4 - len(body) % 4:
        raise Error("Incorrect padding")
    try:
        return _native.b64decode(body + b"=" * (-len(body) % 4), False)
    except ValueError:
        raise Error("Incorrect padding") from None


def crc32(data, crc=0):
    return _native.crc32(_bytes(data, "crc32"), crc & 0xFFFFFFFF)


def crc_hqx(data, crc):
    crc &= 0xFFFF
    for b in _bytes(data, "crc_hqx"):
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def b2a_qp(data, quotetabs=False, istext=True, header=False):
    out = []
    data = _bytes(data, "b2a_qp")
    for b in data:
        c = chr(b)
        if (33 <= b <= 126 and c != "=") or (c == " " and not header) or (c == "\t" and not quotetabs) or (c in "\r\n" and istext):
            out.append(c)
        elif c == " " and header:
            out.append("_")
        else:
            out.append(f"={b:02X}")
    return "".join(out).encode("ascii")


def a2b_qp(data, header=False):
    data = _bytes(data, "a2b_qp").decode("latin-1")
    hexdigits = "0123456789abcdefABCDEF"
    out = bytearray()
    i = 0
    while i < len(data):
        c = data[i]
        if c == "=":
            pair = data[i + 1 : i + 3]
            if pair[:1] == "\n":
                i += 2
                continue
            if pair[:2] == "\r\n":
                i += 3
                continue
            if len(pair) == 2 and pair[0] in hexdigits and pair[1] in hexdigits:
                out.append(int(pair, 16))
                i += 3
                continue
        out.append(ord(" ") if header and c == "_" else ord(c))
        i += 1
    return bytes(out)
