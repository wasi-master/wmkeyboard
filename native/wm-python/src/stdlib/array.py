"""array: compact arrays of one numeric type (or characters), with the
same type codes, sizes and byte layout as CPython's on a 64-bit device."""

import struct as _struct

typecodes = "bBuwhHiIlLqQfd"

_INFO = {
    "b": (1, -128, 127, "signed char"),
    "B": (1, 0, 255, "unsigned byte integer"),
    "h": (2, -32768, 32767, "signed short integer"),
    "H": (2, 0, 65535, "unsigned short"),
    "i": (4, -2147483648, 2147483647, "signed integer"),
    "I": (4, 0, 4294967295, "unsigned int"),
    "l": (8, None, None, "signed long"),
    "L": (8, 0, None, "unsigned long"),
    "q": (8, None, None, "signed long long"),
    "Q": (8, 0, None, "unsigned long long"),
    "u": (4, None, None, "unicode character"),
    "w": (4, None, None, "unicode character"),
    "f": (4, None, None, "float"),
    "d": (8, None, None, "double"),
}


class array:
    def __init__(self, typecode, initializer=None, /):
        if not isinstance(typecode, str) or len(typecode) != 1 or typecode not in typecodes:
            raise ValueError("bad typecode (must be b, B, u, h, H, i, I, l, L, q, Q, f or d)")
        self._code = typecode
        self._items = []
        if initializer is None:
            return
        if isinstance(initializer, str):
            if typecode not in "uw":
                raise TypeError(f"cannot use a str to initialize an array with typecode '{typecode}'")
            self.fromunicode(initializer)
        elif isinstance(initializer, (bytes, bytearray)):
            self.frombytes(initializer)
        elif isinstance(initializer, array):
            if typecode in "uw" and initializer._code not in "uw":
                raise TypeError("cannot use a unicode array to initialize an array with a type other than 'u' or 'w'")
            self.extend(list(initializer))
        else:
            self.extend(initializer)

    @property
    def typecode(self):
        return self._code

    @property
    def itemsize(self):
        return _INFO[self._code][0]

    def _check(self, v):
        code = self._code
        if code in "uw":
            if not isinstance(v, str) or len(v) != 1:
                raise TypeError(f"array item must be a unicode character, not {type(v).__name__}")
            return v
        if code in "fd":
            if not isinstance(v, (int, float)):
                raise TypeError(f"must be real number, not {type(v).__name__}")
            v = float(v)
            if code == "f":
                v = _struct.unpack("f", _struct.pack("f", v))[0]
            return v
        if isinstance(v, bool):
            v = int(v)
        if not isinstance(v, int):
            raise TypeError(f"'{type(v).__name__}' object cannot be interpreted as an integer")
        size, lo, hi, name = _INFO[code]
        if lo is not None and v < lo:
            if code in "LQ":
                raise OverflowError("can't convert negative int to unsigned" if code == "Q" else f"{name} is less than minimum")
            raise OverflowError(f"{name} is less than minimum")
        if hi is not None and v > hi:
            raise OverflowError(f"{name} is greater than maximum")
        return v

    def _same(self, other, what):
        if not isinstance(other, array):
            raise TypeError(f'can only {what} array (not "{type(other).__name__}") to array')
        if other._code != self._code:
            raise TypeError("bad argument type for built-in operation")

    def append(self, v):
        self._items.append(self._check(v))

    def extend(self, iterable):
        if isinstance(iterable, array):
            if iterable._code != self._code:
                raise TypeError("can only extend with array of same kind")
            self._items.extend(iterable._items)
            return
        for v in iterable:
            self._items.append(self._check(v))

    def insert(self, i, v):
        self._items.insert(i, self._check(v))

    def pop(self, i=-1):
        if not self._items:
            raise IndexError("pop from empty array")
        return self._items.pop(i)

    def remove(self, v):
        try:
            self._items.remove(v)
        except ValueError:
            raise ValueError("array.remove(x): x not in array") from None

    def index(self, v, start=0, stop=None):
        items = self._items
        stop = len(items) if stop is None else stop
        for i in range(*slice(start, stop).indices(len(items))):
            if items[i] == v:
                return i
        raise ValueError("array.index(x): x not in array")

    def count(self, v):
        return self._items.count(v)

    def reverse(self):
        self._items.reverse()

    def buffer_info(self):
        return (id(self), len(self._items))

    def byteswap(self):
        if self._code in "uw":
            raise RuntimeError("don't know how to byteswap this array type")
        size = self.itemsize
        raw = self.tobytes()
        swapped = b"".join(raw[i : i + size][::-1] for i in range(0, len(raw), size))
        self._items = []
        self.frombytes(swapped)

    def tobytes(self):
        if self._code in "uw":
            return "".join(self._items).encode("utf-32-le")
        return _struct.pack(f"<{len(self._items)}{self._fmt()}", *self._items)

    def _fmt(self):
        return {"l": "q", "L": "Q"}.get(self._code, self._code)

    def frombytes(self, buffer):
        data = bytes(buffer)
        size = self.itemsize
        if len(data) % size:
            raise ValueError("bytes length not a multiple of item size")
        if self._code in "uw":
            self._items.extend(data.decode("utf-32-le"))
            return
        self._items.extend(_struct.unpack(f"<{len(data) // size}{self._fmt()}", data))

    tostring = tobytes
    fromstring = frombytes

    def tolist(self):
        return list(self._items)

    def fromlist(self, items):
        if not isinstance(items, list):
            raise TypeError("arg must be list")
        checked = [self._check(v) for v in items]
        self._items.extend(checked)

    def tounicode(self):
        if self._code not in "uw":
            raise ValueError("tounicode() may only be called on unicode type arrays ('u' or 'w')")
        return "".join(self._items)

    def fromunicode(self, s):
        if self._code not in "uw":
            raise ValueError("fromunicode() may only be called on unicode type arrays ('u' or 'w')")
        self._items.extend(s)

    def tofile(self, f):
        f.write(self.tobytes())

    def fromfile(self, f, n):
        data = f.read(n * self.itemsize)
        self.frombytes(data[: len(data) - len(data) % self.itemsize])
        if len(data) < n * self.itemsize:
            raise EOFError("read() didn't return enough bytes")

    def __len__(self):
        return len(self._items)

    def __iter__(self):
        return iter(self._items)

    def __contains__(self, v):
        return v in self._items

    def __getitem__(self, i):
        if isinstance(i, slice):
            out = array(self._code)
            out._items = self._items[i]
            return out
        try:
            return self._items[i]
        except IndexError:
            raise IndexError("array index out of range") from None

    def __setitem__(self, i, v):
        if isinstance(i, slice):
            if not isinstance(v, array):
                raise TypeError(f"can only assign array (not \"{type(v).__name__}\") to array slice")
            self._same(v, "assign")
            self._items[i] = v._items
            return
        try:
            self._items[i] = self._check(v)
        except IndexError:
            raise IndexError("array assignment index out of range") from None

    def __delitem__(self, i):
        del self._items[i]

    def __add__(self, other):
        self._same(other, "append")
        out = array(self._code)
        out._items = self._items + other._items
        return out

    def __iadd__(self, other):
        self._same(other, "extend")
        self._items.extend(other._items)
        return self

    def __mul__(self, n):
        out = array(self._code)
        out._items = self._items * n
        return out

    __rmul__ = __mul__

    def __imul__(self, n):
        self._items *= n
        return self

    def __eq__(self, other):
        return self._items == other._items if isinstance(other, array) else NotImplemented

    def __ne__(self, other):
        return self._items != other._items if isinstance(other, array) else NotImplemented

    def __lt__(self, other):
        return self._items < other._items if isinstance(other, array) else NotImplemented

    def __le__(self, other):
        return self._items <= other._items if isinstance(other, array) else NotImplemented

    def __gt__(self, other):
        return self._items > other._items if isinstance(other, array) else NotImplemented

    def __ge__(self, other):
        return self._items >= other._items if isinstance(other, array) else NotImplemented

    __hash__ = None

    def __copy__(self):
        out = array(self._code)
        out._items = list(self._items)
        return out

    def __deepcopy__(self, memo):
        return self.__copy__()

    def __repr__(self):
        if not self._items:
            return f"array('{self._code}')"
        if self._code in "uw":
            return f"array('{self._code}', {''.join(self._items)!r})"
        return f"array('{self._code}', {self._items!r})"

    __class_getitem__ = classmethod(lambda cls, item: cls)


ArrayType = array
