"""frozenset and bytearray, the two built-in types this interpreter has no
native value for: classes over a set and over a list of byte values; what
`list[int]` makes; what a `type X = ...` statement makes; and aiter() and
anext(). The interpreter loads this module the
first time a script names one."""



# Their classes are builtins, as Python names them.
__name__ = "builtins"

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


def __iter_call__(func, sentinel):
    """What iter(callable, sentinel) gives: callable()'s results until one equals sentinel."""
    while True:
        value = func()
        if value == sentinel:
            return
        yield value


class memoryview:
    """A view of bytes or a bytearray: indexing gives numbers, slicing gives
    a view of the slice, and writes to a bytearray's view reach the bytearray."""

    format = "B"
    itemsize = 1
    ndim = 1

    def __init__(self, obj):
        if isinstance(obj, memoryview):
            self._obj, self._start, self._stop = obj._obj, obj._start, obj._stop
        elif isinstance(obj, (bytes, bytearray)):
            self._obj, self._start, self._stop = obj, 0, len(obj)
        else:
            raise TypeError(f"memoryview: a bytes-like object is required, not '{type(obj).__name__}'")
        self._released = False

    def _live(self):
        if self._released:
            raise ValueError("operation forbidden on released memoryview object")

    @property
    def obj(self):
        self._live()
        return self._obj

    @property
    def readonly(self):
        return isinstance(self._obj, bytes)

    @property
    def nbytes(self):
        return self._stop - self._start

    @property
    def shape(self):
        return (self._stop - self._start,)

    @property
    def strides(self):
        return (1,)

    @property
    def c_contiguous(self):
        return True

    contiguous = c_contiguous
    f_contiguous = c_contiguous

    def __len__(self):
        self._live()
        return self._stop - self._start

    def __getitem__(self, index):
        self._live()
        n = self._stop - self._start
        if isinstance(index, slice):
            start, stop, step = index.indices(n)
            if step != 1:
                return memoryview(bytes(self._obj[self._start : self._stop][index]))
            view = memoryview(self._obj)
            view._start = self._start + start
            view._stop = self._start + max(start, stop)
            return view
        if index < 0:
            index += n
        if not 0 <= index < n:
            raise IndexError("index out of bounds on dimension 1")
        return self._obj[self._start + index]

    def __setitem__(self, index, value):
        self._live()
        if self.readonly:
            raise TypeError("cannot modify read-only memory")
        n = self._stop - self._start
        if isinstance(index, slice):
            start, stop, step = index.indices(n)
            value = bytes(value)
            if len(value) != len(range(start, stop, step)):
                raise ValueError("memoryview assignment: lvalue and rvalue have different structures")
            for i, b in zip(range(start, stop, step), value):
                self._obj[self._start + i] = b
            return
        if index < 0:
            index += n
        if not 0 <= index < n:
            raise IndexError("index out of bounds on dimension 1")
        self._obj[self._start + index] = value

    def __iter__(self):
        self._live()
        return iter(self.tobytes())

    def __eq__(self, other):
        if isinstance(other, (bytes, bytearray, memoryview)):
            return self.tobytes() == bytes(other)
        return NotImplemented

    def __hash__(self):
        if not self.readonly:
            raise ValueError("cannot hash writable memoryview object")
        return hash(self.tobytes())

    def tobytes(self, order=None):
        self._live()
        return bytes(self._obj[self._start : self._stop])

    def tolist(self):
        return list(self.tobytes())

    def hex(self, *args):
        return self.tobytes().hex(*args)

    def toreadonly(self):
        view = memoryview(self.tobytes())
        return view

    def cast(self, format, shape=None):
        if format not in ("B", "b", "c"):
            raise NotImplementedError(f"memoryview.cast() to '{format}': only byte formats are supported here")
        return memoryview(self)

    def release(self):
        self._released = True

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.release()
        return False

    def __repr__(self):
        return "<released memory>" if self._released else "<memory>"


class TypeAliasType:
    """What `type Name = value` binds: the value is worked out the first time
    it is asked for, so it may name what is defined after it."""

    __module__ = "typing"

    def __init__(self, name, value, type_params=()):
        self.__name__ = name
        self.__qualname__ = name
        self.__type_params__ = type_params
        self._evaluate = value

    @property
    def __value__(self):
        return self._evaluate() if callable(self._evaluate) else self._evaluate

    def __getitem__(self, params):
        return self

    def __or__(self, other):
        return __union__(self, other)

    def __ror__(self, other):
        return __union__(other, self)

    def __repr__(self):
        return self.__name__


def __type_alias__(name, value):
    return TypeAliasType(name, value)


class GenericAlias:
    """`list[int]` or `dict[str, int]`: the type with its arguments. Calling
    it calls the type; annotations and typing.get_args read the rest."""

    __module__ = "types"

    def __init__(self, origin, args):
        self.__origin__ = origin
        self.__args__ = args if isinstance(args, tuple) else (args,)
        self.__parameters__ = ()

    def __call__(self, *args, **kwargs):
        return self.__origin__(*args, **kwargs)

    def __getitem__(self, args):
        return GenericAlias(self.__origin__, args)

    def __eq__(self, other):
        if not isinstance(other, GenericAlias):
            return NotImplemented
        return self.__origin__ is other.__origin__ and self.__args__ == other.__args__

    def __hash__(self):
        return hash((id(self.__origin__), self.__args__))

    def __or__(self, other):
        return __union__(self, other)

    def __ror__(self, other):
        return __union__(other, self)

    def __mro_entries__(self, bases):
        return (self.__origin__,)

    def __repr__(self):
        def show(a):
            if a is Ellipsis:
                return "..."
            if isinstance(a, type) or callable(a) and hasattr(a, "__name__"):
                return a.__name__ if getattr(a, "__module__", "builtins") in ("builtins", None) else a.__module__ + "." + a.__name__
            if a is None or a is type(None):
                return "None"
            return repr(a)
        inner = ", ".join(show(a) for a in self.__args__) if self.__args__ else "()"
        return f"{self.__origin__.__name__}[{inner}]"


def aiter(iterable):
    return iterable.__aiter__()


_NO_DEFAULT = object()


def anext(iterator, default=_NO_DEFAULT):
    if default is _NO_DEFAULT:
        return iterator.__anext__()
    return _anext_or(iterator, default)


async def _anext_or(iterator, default):
    try:
        return await iterator.__anext__()
    except StopAsyncIteration:
        return default


def _type_repr(a):
    if a is None or a is type(None):
        return "None"
    if a is Ellipsis:
        return "..."
    name = getattr(a, "__qualname__", None) or getattr(a, "__name__", None)
    module = getattr(a, "__module__", "builtins")
    if name is None or not (isinstance(a, type) or callable(a)):
        return repr(a)
    return name if module in ("builtins", None, "__main__") else module + "." + name


class UnionType:
    """`int | str`: isinstance and issubclass take it as either type."""

    __module__ = "types"

    def __init__(self, args):
        self.__args__ = args

    def __or__(self, other):
        return __union__(self, other)

    def __ror__(self, other):
        return __union__(other, self)

    def _classes(self):
        return tuple(getattr(a, "__origin__", a) for a in self.__args__)

    def __instancecheck__(self, obj):
        return isinstance(obj, self._classes())

    def __subclasscheck__(self, cls):
        return issubclass(cls, self._classes())

    def __eq__(self, other):
        if not isinstance(other, UnionType):
            return NotImplemented
        return set(self.__args__) == set(other.__args__)

    def __hash__(self):
        return hash(frozenset(self.__args__))

    def __repr__(self):
        return " | ".join(_type_repr(a) for a in self.__args__)


def __union__(a, b):
    args = []
    for x in (a, b):
        for item in (x.__args__ if isinstance(x, UnionType) else (x,)):
            item = type(None) if item is None else item
            if item not in args:
                args.append(item)
    if len(args) == 1:
        return args[0]
    return UnionType(tuple(args))
