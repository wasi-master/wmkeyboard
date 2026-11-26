"""struct: values packed into bytes and back, by format strings.

As CPython's: "@" (the default) uses this device's C sizes and alignment,
"=", "<", ">" and "!" the standard sizes with no padding. Integers past 64
bits do not exist here, so a "Q" over 2**63 - 1 unpacks as a float.
"""

import _native


class error(Exception):
    pass


def _fmt(fmt):
    if isinstance(fmt, bytes):
        return fmt.decode("ascii")
    if not isinstance(fmt, str):
        raise TypeError(f"Struct() argument 1 must be a str or bytes object, not {type(fmt).__name__}")
    return fmt


def _data(buffer):
    if isinstance(buffer, bytes):
        return buffer
    return bytes(buffer)


def _run(f, *args):
    try:
        return f(*args)
    except ValueError as e:
        raise error(str(e)) from None


def calcsize(fmt):
    return _run(_native.struct_calcsize, _fmt(fmt))


def pack(fmt, *values):
    values = tuple(bytes(v) if isinstance(v, bytearray) else v for v in values)
    return _run(_native.struct_pack, _fmt(fmt), values)


def unpack(fmt, buffer):
    return _run(_native.struct_unpack, _fmt(fmt), _data(buffer), 0, True)


def unpack_from(fmt, buffer, offset=0):
    return _run(_native.struct_unpack, _fmt(fmt), _data(buffer), offset, False)


def pack_into(fmt, buffer, offset, *values):
    data = pack(fmt, *values)
    size = len(buffer)
    if offset < 0:
        if offset + size < 0:
            raise error(f"offset {offset} out of range for {size}-byte buffer")
        offset += size
    if offset + len(data) > size:
        raise error(
            f"pack_into requires a buffer of at least {offset + len(data)} bytes for packing "
            f"{len(data)} bytes at offset {offset} (actual buffer size is {size})"
        )
    buffer[offset : offset + len(data)] = data


def iter_unpack(fmt, buffer):
    size = calcsize(fmt)
    data = _data(buffer)
    if size == 0:
        raise error("cannot iteratively unpack with a struct of length 0")
    if len(data) % size:
        raise error(f"iterative unpacking requires a buffer of a multiple of {size} bytes")
    return (unpack_from(fmt, data, i) for i in range(0, len(data), size))


class Struct:
    def __init__(self, format):
        self.format = _fmt(format)
        self.size = calcsize(self.format)

    def pack(self, *values):
        return pack(self.format, *values)

    def pack_into(self, buffer, offset, *values):
        pack_into(self.format, buffer, offset, *values)

    def unpack(self, buffer):
        return unpack(self.format, buffer)

    def unpack_from(self, buffer, offset=0):
        return unpack_from(self.format, buffer, offset)

    def iter_unpack(self, buffer):
        return iter_unpack(self.format, buffer)

    def __repr__(self):
        return f"Struct({self.format!r})"
