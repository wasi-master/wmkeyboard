"""marshal: Python values to bytes and back, in CPython's marshal format
(version 4, without references). Code objects can't be marshalled here,
as this interpreter has no bytecode."""

version = 4

_NULL = b"0"


def _w_long(n):
    return (n & 0xFFFFFFFF).to_bytes(4, "little")


def _dump(value, out):
    if value is None:
        out.append(b"N")
    elif value is True:
        out.append(b"T")
    elif value is False:
        out.append(b"F")
    elif value is ...:
        out.append(b".")
    elif value is StopIteration:
        out.append(b"S")
    elif type(value) is int:
        if -(1 << 31) <= value < (1 << 31):
            out.append(b"i" + _w_long(value))
        else:
            # A long: base-2**15 digits, least significant first, signed count.
            digits = []
            n = abs(value)
            while n:
                digits.append(n & 0x7FFF)
                n >>= 15
            count = len(digits) if value > 0 else -len(digits)
            out.append(b"l" + _w_long(count) + b"".join(d.to_bytes(2, "little") for d in digits))
    elif type(value) is float:
        import struct

        out.append(b"g" + struct.pack("<d", value))
    elif type(value) is complex:
        import struct

        out.append(b"y" + struct.pack("<dd", value.real, value.imag))
    elif type(value) is str:
        data = value.encode("utf-8", "surrogatepass")
        out.append(b"u" + _w_long(len(data)) + data)
    elif type(value) in (bytes, bytearray):
        out.append(b"s" + _w_long(len(value)) + bytes(value))
    elif type(value) is tuple:
        out.append(b"(" + _w_long(len(value)))
        for item in value:
            _dump(item, out)
    elif type(value) is list:
        out.append(b"[" + _w_long(len(value)))
        for item in value:
            _dump(item, out)
    elif type(value) is dict:
        out.append(b"{")
        for k, v in value.items():
            _dump(k, out)
            _dump(v, out)
        out.append(_NULL)
    elif type(value) in (set, frozenset):
        out.append((b"<" if type(value) is set else b">") + _w_long(len(value)))
        for item in value:
            _dump(item, out)
    else:
        raise ValueError("unmarshallable object")


def dumps(value, version=version, /, *, allow_code=True):
    """The bytes marshal writes for value."""
    out = []
    _dump(value, out)
    return b"".join(out)


def dump(value, file, version=version, /, *, allow_code=True):
    """Write dumps(value) to file, a binary file object."""
    file.write(dumps(value, version))


class _Reader:
    def __init__(self, data):
        self.data = bytes(data)
        self.pos = 0
        self.refs = []

    def take(self, n):
        if self.pos + n > len(self.data):
            raise EOFError("marshal data too short")
        chunk = self.data[self.pos:self.pos + n]
        self.pos += n
        return chunk

    def long(self):
        return int.from_bytes(self.take(4), "little", signed=True)

    def value(self):
        code = self.take(1)[0]
        ref = code & 0x80
        code = chr(code & 0x7F)
        if ref:
            at = len(self.refs)
            self.refs.append(None)
        v = self.read(code)
        if ref:
            self.refs[at] = v
        return v

    def read(self, code):
        import struct

        if code == "N":
            return None
        if code == "T":
            return True
        if code == "F":
            return False
        if code == ".":
            return ...
        if code == "S":
            return StopIteration
        if code == "i":
            return self.long()
        if code == "l":
            count = self.long()
            n = 0
            for i in range(abs(count)):
                n |= int.from_bytes(self.take(2), "little") << (15 * i)
            return n if count >= 0 else -n
        if code == "g":
            return struct.unpack("<d", self.take(8))[0]
        if code == "y":
            re, im = struct.unpack("<dd", self.take(16))
            return complex(re, im)
        if code in "uta":
            return self.take(self.long()).decode("utf-8" if code in "ut" else "latin-1")
        if code in "zZ":
            return self.take(self.take(1)[0]).decode("latin-1")
        if code == "s":
            return self.take(self.long())
        if code == "(":
            return tuple(self.value() for _ in range(self.long()))
        if code == ")":
            return tuple(self.value() for _ in range(self.take(1)[0]))
        if code == "[":
            return [self.value() for _ in range(self.long())]
        if code == "{":
            d = {}
            while True:
                if self.data[self.pos:self.pos + 1] == _NULL:
                    self.pos += 1
                    return d
                k = self.value()
                d[k] = self.value()
        if code in "<>":
            items = [self.value() for _ in range(self.long())]
            return set(items) if code == "<" else frozenset(items)
        if code == "r":
            return self.refs[self.long()]
        if code == "c":
            raise ValueError("bad marshal data (code objects can't be loaded here)")
        raise ValueError("bad marshal data (unknown type code)")


def loads(data, /, *, allow_code=True):
    """The value marshal bytes stand for."""
    return _Reader(data).value()


def load(file, /, *, allow_code=True):
    """loads() of what is left in file, a binary file object."""
    return loads(file.read())
