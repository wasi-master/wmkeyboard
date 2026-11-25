"""frozenset and bytearray, the two built-in types this interpreter has no
native value for: classes over a set and over a list of byte values. The
interpreter loads this module the first time a script names either one."""


def _as_set(other):
    if isinstance(other, frozenset):
        return other._items
    if isinstance(other, set):
        return other
    return set(other)


class frozenset:
    """An immutable set, so it can be a dict key or a member of a set."""

    def __init__(self, items=()):
        object.__setattr__(self, "_items", set(items))

    def __setattr__(self, name, value):
        raise AttributeError("'frozenset' object has no attribute '" + name + "'")

    def __iter__(self):
        return iter(self._items)

    def __len__(self):
        return len(self._items)

    def __contains__(self, item):
        return item in self._items

    def __hash__(self):
        h = len(self._items)
        for item in self._items:
            h ^= hash(item)
        return h

    def __eq__(self, other):
        if isinstance(other, (frozenset, set)):
            return self._items == _as_set(other)
        return NotImplemented

    def __le__(self, other):
        return self._items <= _as_set(other)

    def __lt__(self, other):
        return self._items < _as_set(other)

    def __ge__(self, other):
        return self._items >= _as_set(other)

    def __gt__(self, other):
        return self._items > _as_set(other)

    def __or__(self, other):
        return frozenset(self._items | _as_set(other))

    def __and__(self, other):
        return frozenset(self._items & _as_set(other))

    def __sub__(self, other):
        return frozenset(self._items - _as_set(other))

    def __xor__(self, other):
        return frozenset(self._items ^ _as_set(other))

    # A set on the left keeps its own type, as in Python: {1} | frozenset({2}) is a set.
    def __ror__(self, other):
        return _as_set(other) | self._items

    def __rand__(self, other):
        return _as_set(other) & self._items

    def __rsub__(self, other):
        return _as_set(other) - self._items

    def __rxor__(self, other):
        return _as_set(other) ^ self._items

    def union(self, *others):
        out = set(self._items)
        for o in others:
            out = out | _as_set(o)
        return frozenset(out)

    def intersection(self, *others):
        out = set(self._items)
        for o in others:
            out = out & _as_set(o)
        return frozenset(out)

    def difference(self, *others):
        out = set(self._items)
        for o in others:
            out = out - _as_set(o)
        return frozenset(out)

    def symmetric_difference(self, other):
        return frozenset(self._items ^ _as_set(other))

    def issubset(self, other):
        return self._items <= _as_set(other)

    def issuperset(self, other):
        return self._items >= _as_set(other)

    def isdisjoint(self, other):
        return not (self._items & _as_set(other))

    def copy(self):
        return self

    def __repr__(self):
        if not self._items:
            return "frozenset()"
        return "frozenset(" + repr(self._items) + ")"


def _byte(value):
    if not isinstance(value, int):
        raise TypeError("'" + type(value) + "' object cannot be interpreted as an integer")
    if value < 0 or value > 255:
        raise ValueError("byte must be in range(0, 256)")
    return value


def _values(data):
    if isinstance(data, bytearray):
        return list(data._b)
    if isinstance(data, str):
        raise TypeError("cannot use a str as bytes; encode it first")
    return [_byte(v) for v in data]


class bytearray:
    """Mutable bytes: indexing gives and takes numbers from 0 to 255, and the
    methods of bytes work on it."""

    __hash__ = None

    def __init__(self, source=b"", encoding=None, errors="strict"):
        if isinstance(source, str):
            if encoding is None:
                raise TypeError("string argument without an encoding")
            self._b = list(source.encode(encoding))
        elif isinstance(source, int):
            self._b = [0] * source
        else:
            self._b = _values(source)

    @classmethod
    def fromhex(cls, text):
        return cls(bytes.fromhex(text))

    def __len__(self):
        return len(self._b)

    def __iter__(self):
        return iter(self._b)

    def __getitem__(self, index):
        if isinstance(index, slice):
            return bytearray(self._b[index])
        return self._b[index]

    def __setitem__(self, index, value):
        if isinstance(index, slice):
            self._b[index] = _values(value)
        else:
            self._b[index] = _byte(value)

    def __delitem__(self, index):
        del self._b[index]

    def __contains__(self, item):
        if isinstance(item, int):
            return item in self._b
        return bytes(item) in bytes(self._b)

    def __eq__(self, other):
        if isinstance(other, (bytes, bytearray)):
            return self._b == _values(other)
        return NotImplemented

    def __add__(self, other):
        return bytearray(self._b + _values(other))

    def __radd__(self, other):
        return bytes(_values(other) + self._b)

    def __mul__(self, n):
        return bytearray(self._b * n)

    def __rmul__(self, n):
        return bytearray(self._b * n)

    def append(self, value):
        self._b.append(_byte(value))

    def extend(self, values):
        self._b.extend(_values(values))

    def insert(self, index, value):
        self._b.insert(index, _byte(value))

    def pop(self, index=-1):
        return self._b.pop(index)

    def remove(self, value):
        self._b.remove(value)

    def clear(self):
        self._b.clear()

    def reverse(self):
        self._b.reverse()

    def copy(self):
        return bytearray(self._b)

    def decode(self, encoding="utf-8", errors="strict"):
        return bytes(self._b).decode(encoding, errors)

    def hex(self):
        return bytes(self._b).hex()

    def __getattr__(self, name):
        # The rest of bytes' methods (find, split, replace...), answering
        # bytearray where bytes would answer bytes.
        method = getattr(bytes(self._b), name)

        def call(*args, **kwargs):
            result = method(*args, **kwargs)
            if isinstance(result, bytes):
                return bytearray(result)
            if isinstance(result, list):
                return [bytearray(x) if isinstance(x, bytes) else x for x in result]
            return result

        return call

    def __repr__(self):
        return "bytearray(" + repr(bytes(self._b)) + ")"
