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

    def __mod__(self, args):
        return bytearray(bytes(self._b) % args)

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
            if isinstance(result, (list, tuple)):
                items = [bytearray(x) if isinstance(x, bytes) else x for x in result]
                return items if isinstance(result, list) else tuple(items)
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


def _make_parameters(args):
    """The type variables in [args], in order and each once, as
    Objects/genericaliasobject.c's _Py_make_parameters finds them."""
    params = []
    for t in args:
        if isinstance(t, type):
            continue
        if isinstance(t, (list, tuple)):
            sub = _make_parameters(t)
        elif hasattr(t, "__typing_subst__"):
            sub = (t,)
        else:
            sub = getattr(t, "__parameters__", ())
            if not isinstance(sub, tuple):
                sub = ()
        for p in sub:
            if p not in params:
                params.append(p)
    return tuple(params)


def _unpacked_tuple_args(arg):
    if isinstance(arg, GenericAlias) and arg.__unpacked__ and arg.__origin__ is tuple:
        return arg.__args__
    return getattr(arg, "__typing_unpacked_tuple_args__", None)


def _unpack_args(item):
    out = []
    for x in item if isinstance(item, tuple) else (item,):
        if not isinstance(x, type):
            sub = _unpacked_tuple_args(x)
            if isinstance(sub, tuple) and not (sub and sub[-1] is Ellipsis):
                out.extend(sub)
                continue
        out.append(x)
    return tuple(out)


def _is_unpacked_typevartuple(x):
    return not isinstance(x, type) and getattr(x, "__typing_is_unpacked_typevartuple__", False)


def _subs_tvars(obj, params, items):
    if isinstance(obj, (list, tuple)):
        return type(obj)(_subs_tvars(x, params, items) for x in obj)
    sub = getattr(obj, "__parameters__", None)
    if not (isinstance(sub, tuple) and sub):
        return obj
    subargs = []
    for p in sub:
        if p in params:
            i = params.index(p)
            arg = items[i]
            if hasattr(type(params[i]), "__iter__") and isinstance(arg, tuple):
                subargs.extend(arg)
                continue
            subargs.append(arg)
        else:
            subargs.append(p)
    return obj[tuple(subargs)]


def _subs_parameters(alias, args, params, item):
    """alias[item]: each type variable in [args] replaced by its argument,
    as _Py_subs_parameters does it."""
    if not params:
        raise TypeError(f"{alias!r} is not a generic class")
    item = _unpack_args(item)
    for p in params:
        prepare = getattr(p, "__typing_prepare_subst__", None)
        if prepare is not None:
            item = prepare(alias, item if isinstance(item, tuple) else (item,))
    items = item if isinstance(item, tuple) else (item,)
    if len(items) != len(params):
        raise TypeError(f"Too {'many' if len(items) > len(params) else 'few'} arguments for {alias!r};"
                        f" actual {len(items)}, expected {len(params)}")
    out = []
    for arg in args:
        if isinstance(arg, type):
            out.append(arg)
            continue
        unpack = _is_unpacked_typevartuple(arg)
        subst = getattr(arg, "__typing_subst__", None)
        if subst is not None:
            arg = subst(items[params.index(arg)])
        else:
            arg = _subs_tvars(arg, params, items)
        if unpack:
            out.extend(arg)
        else:
            out.append(arg)
    return tuple(out)


def _alias_repr_item(p):
    if p is Ellipsis:
        return "..."
    if isinstance(p, list):
        return "[" + ", ".join(_alias_repr_item(x) for x in p) + "]"
    if hasattr(p, "__origin__") and hasattr(p, "__args__"):
        return repr(p)
    qualname = getattr(p, "__qualname__", None)
    module = getattr(p, "__module__", None)
    if not isinstance(qualname, str) or not isinstance(module, str):
        return repr(p)
    return qualname if module == "builtins" else f"{module}.{qualname}"


class TypeAliasType:
    """Type alias.

    Type aliases are created through the type statement::

        type Alias = int

    In this example, Alias and int will be treated equivalently by static
    type checkers.

    At runtime, Alias is an instance of TypeAliasType. The __name__
    attribute holds the name of the type alias. The value of the type alias
    is stored in the __value__ attribute. It is evaluated lazily, so the
    value is computed only if the attribute is accessed.

    Type aliases can also be generic::

        type ListOrSet[T] = list[T] | set[T]

    In this case, the type parameters of the alias are stored in the
    __type_params__ attribute.

    See PEP 695 for more information.
    """

    __module__ = "typing"

    def __init__(self, name, value, *, type_params=()):
        if not isinstance(name, str):
            raise TypeError(f"typealias() argument 'name' must be str, not {type(name).__name__}")
        if not isinstance(type_params, tuple):
            raise TypeError("type_params must be a tuple")
        seen_default = False
        for p in type_params:
            if getattr(p, "has_default", None) is not None and p.has_default():
                seen_default = True
            elif seen_default:
                raise TypeError(f"non-default type parameter '{p!r}' follows default type parameter")
        d = self.__dict__
        d["_ta_name"] = name
        d["_ta_type_params"] = type_params
        d["_ta_value"] = value
        d["_ta_lazy"] = False
        try:
            import sys
            module = sys._getframemodulename(1)
        except (AttributeError, ImportError):
            module = None
        if module is not None:
            d["__module__"] = module

    def __init_subclass__(cls, *args, **kwargs):
        raise TypeError("type 'typing.TypeAliasType' is not an acceptable base type")

    @property
    def __name__(self):
        return self.__dict__["_ta_name"]

    @property
    def __type_params__(self):
        return self.__dict__["_ta_type_params"]

    @property
    def __value__(self):
        d = self.__dict__
        if d["_ta_lazy"]:
            d["_ta_value"] = d["_ta_value"]()
            d["_ta_lazy"] = False
        return d["_ta_value"]

    @property
    def __parameters__(self):
        out = []
        for p in self.__type_params__:
            if hasattr(type(p), "__iter__") and hasattr(p, "__typing_prepare_subst__") and not hasattr(p, "__bound__"):
                out.extend(p)
            else:
                out.append(p)
        return tuple(out)

    def __getitem__(self, parameters):
        if not self.__type_params__:
            raise TypeError("Only generic type aliases are subscriptable")
        if not isinstance(parameters, tuple):
            parameters = (parameters,)
        return GenericAlias(self, parameters)

    def __or__(self, other):
        return _typing_union(self, other)

    def __ror__(self, other):
        return _typing_union(other, self)

    def __repr__(self):
        return self.__name__

    def __reduce__(self):
        return self.__name__


def _typing_union(a, b):
    import typing
    return typing.Union[a, b]


def __type_alias__(name, value):
    alias = TypeAliasType(name, None)
    alias.__dict__["_ta_value"] = value
    alias.__dict__["_ta_lazy"] = True
    return alias


_ALIAS_OWN = frozenset({
    "__class__", "__bases__", "__origin__", "__args__", "__unpacked__", "__parameters__",
    "__typing_unpacked_tuple_args__", "__mro_entries__", "__reduce_ex__", "__reduce__", "__copy__", "__deepcopy__",
})


class GenericAlias:
    """Represent a PEP 585 generic type

    E.g. for t = list[int], t.__origin__ is list and t.__args__ is (int,)."""

    __module__ = "types"

    def __init__(self, origin, args, /):
        d = self.__dict__
        d["__origin__"] = origin
        d["__args__"] = args if isinstance(args, tuple) else (args,)
        d["__unpacked__"] = False

    @property
    def __parameters__(self):
        d = self.__dict__
        params = d.get("_ga_parameters")
        if params is None:
            params = d["_ga_parameters"] = _make_parameters(d["__args__"])
        return params

    @property
    def __typing_unpacked_tuple_args__(self):
        if self.__unpacked__ and self.__origin__ is tuple:
            return self.__args__
        return None

    def __getattr__(self, name):
        if name.startswith("__") and name.endswith("__") and name in _ALIAS_OWN:
            raise AttributeError(name)
        return getattr(self.__origin__, name)

    def __call__(self, *args, **kwargs):
        obj = self.__origin__(*args, **kwargs)
        try:
            obj.__orig_class__ = self
        except Exception:
            pass
        return obj

    def __getitem__(self, item):
        args = _subs_parameters(self, self.__args__, self.__parameters__, item)
        alias = GenericAlias(self.__origin__, args)
        alias.__dict__["__unpacked__"] = self.__unpacked__
        return alias

    def __iter__(self):
        alias = GenericAlias(self.__origin__, self.__args__)
        alias.__dict__["__unpacked__"] = True
        yield alias

    def __eq__(self, other):
        if not isinstance(other, GenericAlias):
            return NotImplemented
        return (self.__origin__ == other.__origin__ and self.__args__ == other.__args__
                and self.__unpacked__ == other.__unpacked__)

    def __hash__(self):
        return hash(self.__origin__) ^ hash(self.__args__)

    def __or__(self, other):
        return __union__(self, other)

    def __ror__(self, other):
        return __union__(other, self)

    def __mro_entries__(self, bases):
        return (self.__origin__,)

    def __instancecheck__(self, obj):
        raise TypeError("isinstance() argument 2 cannot be a parameterized generic")

    def __subclasscheck__(self, cls):
        raise TypeError("issubclass() argument 2 cannot be a parameterized generic")

    def __reduce__(self):
        if self.__unpacked__:
            return next, (iter(GenericAlias(self.__origin__, self.__args__)),)
        return GenericAlias, (self.__origin__, self.__args__)

    def __dir__(self):
        return sorted(set(dir(self.__origin__)) | {"__origin__", "__args__", "__parameters__", "__unpacked__"})

    def __repr__(self):
        star = "*" if self.__unpacked__ else ""
        if not self.__args__:
            return f"{star}{_alias_repr_item(self.__origin__)}[()]"
        inner = ", ".join(_alias_repr_item(a) for a in self.__args__)
        return f"{star}{_alias_repr_item(self.__origin__)}[{inner}]"


def __aiter_sync__(iterable):
    """What `async for` in a comprehension walks: each item of the async
    iterable, its __anext__ awaited as the comprehension reaches it."""
    ait = iterable.__aiter__()
    while True:
        try:
            item = await ait.__anext__()
        except StopAsyncIteration:
            return
        yield item


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


class UnionType:
    """`int | str`: isinstance and issubclass take it as either type."""

    def __repr__(self):
        return " | ".join("None" if a is type(None) else _alias_repr_item(a) for a in self.__args__)

    __module__ = "types"

    def __init__(self, args):
        self.__args__ = args

    @property
    def __parameters__(self):
        return _make_parameters(self.__args__)

    def __getitem__(self, item):
        args = _subs_parameters(self, self.__args__, self.__parameters__, item)
        out = args[0]
        for a in args[1:]:
            out = out | a
        return out

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


class mappingproxy:
    """A read-only view of a mapping: what a class's __dict__ and vars(cls)
    are. Its methods go through dict's own (dict.get(m, k)), so a class
    attribute named get or items cannot stand in for them."""

    def __init__(self, mapping):
        if not hasattr(mapping, "__getitem__") or isinstance(mapping, (list, tuple)):
            raise TypeError(f"mappingproxy() argument must be a mapping, not {type(mapping).__name__}")
        object.__setattr__(self, "_mapping", mapping)

    def _d(self):
        return object.__getattribute__(self, "_mapping")

    def __getitem__(self, key):
        return self._d()[key]

    def __contains__(self, key):
        return key in self._d()

    def __iter__(self):
        return iter(self._d())

    def __len__(self):
        return len(self._d())

    def __reversed__(self):
        return reversed(list(self._d()))

    def __setattr__(self, name, value):
        raise AttributeError(f"'mappingproxy' object has no attribute '{name}'")

    def __setitem__(self, key, value):
        raise TypeError("'mappingproxy' object does not support item assignment")

    def __delitem__(self, key):
        raise TypeError("'mappingproxy' object does not support item deletion")

    def get(self, key, default=None):
        m = self._d()
        return dict.get(m, key, default) if isinstance(m, dict) else m.get(key, default)

    def keys(self):
        m = self._d()
        return dict.keys(m) if isinstance(m, dict) else m.keys()

    def values(self):
        m = self._d()
        return dict.values(m) if isinstance(m, dict) else m.values()

    def items(self):
        m = self._d()
        return dict.items(m) if isinstance(m, dict) else m.items()

    def copy(self):
        m = self._d()
        return dict.copy(m) if isinstance(m, dict) else m.copy()

    def __or__(self, other):
        return dict(self.items()) | dict(other)

    def __ror__(self, other):
        return dict(other) | dict(self.items())

    def __eq__(self, other):
        return dict(self.items()) == other

    def __repr__(self):
        return f"mappingproxy({self._d()!r})"

    def __str__(self):
        return str(self._d())


mappingproxy.__module__ = "builtins"


def _slot_names(cls):
    names = []
    for klass in getattr(cls, "__mro__", (cls,)):
        slots = getattr(klass, "__dict__", {}).get("__slots__", ())
        if isinstance(slots, str):
            slots = (slots,)
        names.extend(n for n in slots if n not in ("__dict__", "__weakref__"))
    return names


def __object_getstate__(self):
    """object.__getstate__: the instance's attributes, None when it has
    none, and (attributes, slots) when its class has __slots__."""
    slots = _slot_names(type(self))
    attrs = {k: v for k, v in getattr(self, "__dict__", {}).items() if k not in slots}
    state = attrs or None
    if slots:
        values = {}
        for name in slots:
            try:
                values[name] = getattr(self, name)
            except AttributeError:
                pass
        if values:
            return (state, values)
    return state


def _own(cls, name):
    """Whether a class in cls's resolution order, object aside, defines name."""
    for klass in getattr(cls, "__mro__", (cls,)):
        if klass is object:
            return False
        if name in getattr(klass, "__dict__", {}):
            return True
    return False


def _builtin_base_of(cls):
    for klass in getattr(cls, "__mro__", (cls,)):
        if klass is not object and "__dict__" not in dir(klass) and type(klass) is type and klass in (int, float, str, bytes, tuple, list, dict, set, frozenset, bytearray):
            return klass
    return object


def _common_reduce(self, protocol):
    import copyreg

    cls = type(self)
    if protocol < 2:
        # copyreg._reduce_ex: rebuilt from the nearest built-in base's value.
        base = _builtin_base_of(cls)
        state = None if base is object else base(self)
        getstate = getattr(self, "__getstate__", None)
        if _own(cls, "__slots__") and not _own(cls, "__getstate__"):
            raise TypeError(f"cannot pickle {cls.__name__!r} object: a class that defines __slots__ without defining __getstate__ cannot be pickled with protocol {protocol}")
        attrs = getstate() if getstate is not None else None
        args = (cls, base, state)
        if attrs:
            return copyreg._reconstructor, args, attrs
        return copyreg._reconstructor, args
    # typeobject.c's reduce_newobj.
    args, kwargs = (), None
    if hasattr(self, "__getnewargs_ex__"):
        args, kwargs = self.__getnewargs_ex__()
    elif hasattr(self, "__getnewargs__"):
        args = self.__getnewargs__()
    if kwargs:
        newobj, newargs = copyreg.__newobj_ex__, (cls, tuple(args), kwargs)
    else:
        newobj, newargs = copyreg.__newobj__, (cls,) + tuple(args)
    state = self.__getstate__()
    listitems = iter(self) if isinstance(self, list) else None
    dictitems = iter(self.items()) if isinstance(self, dict) else None
    return newobj, newargs, state, listitems, dictitems


def __object_reduce__(self):
    return _common_reduce(self, 0)


def __object_reduce_ex__(self, protocol=0):
    """object.__reduce_ex__: the class's own __reduce__ when it has one."""
    if _own(type(self), "__reduce__"):
        return self.__reduce__()
    return _common_reduce(self, protocol)


class ModuleSpec:
    """Where a module came from, as importlib describes it: `__spec__` of
    every imported module. CPython's importlib._bootstrap.ModuleSpec
    (Python Software Foundation License Version 2)."""

    def __init__(self, name, loader, *, origin=None, loader_state=None, is_package=None):
        self.name = name
        self.loader = loader
        self.origin = origin
        self.loader_state = loader_state
        self.submodule_search_locations = [] if is_package else None
        self._uninitialized_submodules = []
        self._set_fileattr = False
        self._cached = None

    def __repr__(self):
        args = [f"name={self.name!r}", f"loader={self.loader!r}"]
        if self.origin is not None:
            args.append(f"origin={self.origin!r}")
        if self.submodule_search_locations is not None:
            args.append(f"submodule_search_locations={self.submodule_search_locations}")
        return f"{self.__class__.__name__}({', '.join(args)})"

    def __eq__(self, other):
        smsl = self.submodule_search_locations
        try:
            return (self.name == other.name
                    and self.loader == other.loader
                    and self.origin == other.origin
                    and smsl == other.submodule_search_locations
                    and self.cached == other.cached
                    and self.has_location == other.has_location)
        except AttributeError:
            return NotImplemented

    @property
    def cached(self):
        return self._cached

    @cached.setter
    def cached(self, cached):
        self._cached = cached

    @property
    def parent(self):
        """The name of the module's parent."""
        if self.submodule_search_locations is None:
            return self.name.rpartition(".")[0]
        return self.name

    @property
    def has_location(self):
        return self._set_fileattr

    @has_location.setter
    def has_location(self, value):
        self._set_fileattr = bool(value)


ModuleSpec.__module__ = "_frozen_importlib"


class code:
    """What a function's __code__ is: its name, parameters, flags and the
    file and line it was written at. The interpreter runs the function's
    tree, so there is no bytecode in it (co_code is empty)."""

    co_code = b""
    co_lnotab = b""
    co_linetable = b""
    co_exceptiontable = b""
    co_stacksize = 0

    def __repr__(self):
        return f'<code object {self.co_name} at {hex(id(self))}, file "{self.co_filename}", line {self.co_firstlineno}>'

    def replace(self, **changes):
        new = object.__new__(code)
        new.__dict__.update(self.__dict__)
        new.__dict__.update(changes)
        return new


def compile(source, filename, mode, flags=0, dont_inherit=False, optimize=-1, *, _feature_version=-1):
    """compile(source, filename, mode): the source, checked to parse, kept
    in a code object that eval() and exec() run. There is no bytecode;
    flags with ast.PyCF_ONLY_AST (0x400) give ast.parse's tree instead."""
    if isinstance(source, (bytes, bytearray)):
        source = bytes(source).decode("utf-8")
    elif not isinstance(source, str):
        raise TypeError("compile() arg 1 must be a string, bytes or AST object")
    if not isinstance(mode, str):
        raise TypeError(f"compile() argument 'mode' must be str, not {type(mode).__name__}")
    if mode not in ("exec", "eval", "single"):
        raise ValueError("compile() mode must be 'exec', 'eval' or 'single'")
    if isinstance(filename, bytes):
        filename = filename.decode("utf-8")
    elif not isinstance(filename, str):
        filename = getattr(type(filename), "__fspath__", None) and filename.__fspath__()
        if not isinstance(filename, str):
            raise TypeError("compile() argument 'filename' must be str, bytes or os.PathLike")
    if flags & 0x400:
        import ast
        return ast.parse(source, filename, mode)
    __syntax_check__(source, mode)
    c = object.__new__(code)
    c.__dict__.update(
        co_name="<module>", co_qualname="<module>", co_filename=filename, co_firstlineno=1, co_argcount=0,
        co_posonlyargcount=0, co_kwonlyargcount=0, co_nlocals=0, co_varnames=(), co_freevars=(), co_cellvars=(),
        co_names=(), co_consts=(), co_flags=0, _source=source, _mode=mode,
    )
    return c


# MARK: - exception groups

def _group_matcher(condition):
    if isinstance(condition, type) and issubclass(condition, BaseException):
        return lambda e: isinstance(e, condition)
    if isinstance(condition, tuple) and all(isinstance(c, type) and issubclass(c, BaseException) for c in condition):
        return lambda e: isinstance(e, condition)
    if callable(condition) and not isinstance(condition, type):
        return condition
    raise TypeError("expected an exception type, a tuple of exception types, or a callable (other than a class)")


class BaseExceptionGroup(BaseException):
    """Several exceptions raised together, as Objects/exceptions.c makes
    them: one made of Exceptions only is an ExceptionGroup. `except*` is not
    there; split() and subgroup() pick a group apart."""

    def __new__(cls, message, exceptions, /):
        if not isinstance(message, str):
            raise TypeError(f"BaseExceptionGroup.__new__() argument 1 must be str, not {type(message).__name__}")
        if isinstance(exceptions, (str, bytes, dict, set, frozenset)) or not hasattr(exceptions, "__getitem__") or not hasattr(exceptions, "__len__"):
            raise TypeError("second argument (exceptions) must be a sequence")
        items = tuple(exceptions)
        if not items:
            raise ValueError("second argument (exceptions) must be a non-empty sequence")
        nested_base = False
        for i, e in enumerate(items):
            if not isinstance(e, BaseException):
                raise ValueError(f"Item {i} of second argument (exceptions) is not an exception")
            if not isinstance(e, Exception):
                nested_base = True
        if cls is BaseExceptionGroup:
            if not nested_base:
                cls = ExceptionGroup
        elif issubclass(cls, Exception) and nested_base:
            if cls is ExceptionGroup:
                raise TypeError("Cannot nest BaseExceptions in an ExceptionGroup")
            raise TypeError(f"Cannot nest BaseExceptions in {cls.__name__!r}")
        self = BaseException.__new__(cls, message, exceptions)
        self._message = message
        self._exceptions = items
        return self

    def __init__(self, message, exceptions, /):
        BaseException.__init__(self, message, exceptions)

    @property
    def message(self):
        """The message the group was made with."""
        return self._message

    @property
    def exceptions(self):
        """The exceptions in the group, as a tuple."""
        return self._exceptions

    def __str__(self):
        n = len(self._exceptions)
        return f"{self._message} ({n} sub-exception{'s' if n > 1 else ''})"

    def __repr__(self):
        return f"{type(self).__name__}({self._message!r}, {self.args[1]!r})"

    def derive(self, excs):
        return BaseExceptionGroup(self._message, excs)

    def _subset(self, excs):
        if not excs:
            return None
        group = self.derive(excs)
        if not isinstance(group, BaseExceptionGroup):
            raise TypeError("derive must return an instance of BaseExceptionGroup")
        group.__traceback__ = self.__traceback__
        group.__cause__ = self.__cause__
        group.__context__ = self.__context__
        notes = getattr(self, "__notes__", None)
        if notes is not None:
            group.__notes__ = notes
        return group

    def _split(self, match, want_rest):
        if match(self):
            return self, None
        matching, rest = [], []
        for e in self._exceptions:
            if isinstance(e, BaseExceptionGroup):
                m, r = e._split(match, want_rest)
                if m is not None:
                    matching.append(m)
                if r is not None:
                    rest.append(r)
            elif match(e):
                matching.append(e)
            elif want_rest:
                rest.append(e)
        return self._subset(matching), (self._subset(rest) if want_rest else None)

    def split(self, condition):
        return self._split(_group_matcher(condition), True)

    def subgroup(self, condition):
        return self._split(_group_matcher(condition), False)[0]

    def __class_getitem__(cls, item):
        return GenericAlias(cls, item)


class ExceptionGroup(BaseExceptionGroup, Exception):
    pass


# MARK: - what reaches outside the plugin

def input(prompt="", /):
    """A plugin has no one at a terminal: input() writes its prompt and
    finds its input ended."""
    if prompt:
        print(prompt, end="")
    raise EOFError("EOF when reading a line")


def open(file, mode="r", buffering=-1, encoding=None, errors=None, newline=None, closefd=True, opener=None):
    """Plugins have no file system: the keyboard's wm.files and
    wm.storage read and keep what a plugin needs."""
    raise PermissionError(13, "plugins have no file system", file)


def breakpoint(*args, **kws):
    """No debugger to stop in: carries on, as PYTHONBREAKPOINT=0 does."""
    return None


class _Quitter:
    def __init__(self, name):
        self.name = name

    def __repr__(self):
        return f"Use {self.name}() or Ctrl-D (i.e. EOF) to exit"

    def __call__(self, code=None):
        raise SystemExit(code)


exit = _Quitter("exit")
quit = _Quitter("quit")


class _Helper:
    def __repr__(self):
        return "Type help() for interactive help, or help(object) for help about object."

    def __call__(self, *args, **kwds):
        if not args:
            print(repr(self))
            return
        obj = args[0]
        name = getattr(obj, "__name__", type(obj).__name__)
        doc = getattr(obj, "__doc__", None)
        if not isinstance(doc, str) or not doc.strip():
            print(f"No Python documentation found for {name!r}.")
            return
        lines = doc.expandtabs().splitlines()
        indents = [len(line) - len(line.lstrip()) for line in lines[1:] if line.strip()]
        margin = min(indents) if indents else 0
        body = [lines[0].strip()] + [line[margin:].rstrip() for line in lines[1:]]
        while body and not body[-1]:
            body.pop()
        print(f"Help on {name}:\n")
        print("\n".join(body))


help = _Helper()


def __exception_add_note__(self, note, /):
    """BaseException.add_note: one more line for the error's report."""
    if not isinstance(note, str):
        raise TypeError(f"note must be a str, not '{type(note).__name__}'")
    notes = getattr(self, "__notes__", None)
    if notes is None:
        notes = []
        self.__notes__ = notes
    elif not isinstance(notes, list):
        raise TypeError("Cannot add note: __notes__ is not a list")
    notes.append(note)


# MARK: - re.Scanner

class _re_Scanner:
    """re.Scanner: a lexer made of (pattern, action) pairs, as CPython's
    Lib/re/__init__.py has it. Each pattern goes in a group of one compiled
    alternation; at each place the first that matches gives its action."""

    def __init__(self, lexicon, flags=0):
        import re
        self.lexicon = lexicon
        parts = []
        self._groups = []
        at = 1
        for phrase, action in lexicon:
            parts.append(f"({phrase})")
            self._groups.append(at)
            at += 1 + re.compile(phrase, flags).groups
        self.scanner = re.compile("|".join(parts), flags)

    def scan(self, string):
        result = []
        i = 0
        while True:
            m = self.scanner.match(string, i)
            if not m:
                break
            j = m.end()
            if i == j:
                break
            action = None
            for index, at in enumerate(self._groups):
                if m.group(at) is not None:
                    action = self.lexicon[index][1]
                    break
            if callable(action):
                self.match = m
                action = action(self, m.group())
            if action is not None:
                result.append(action)
            i = j
        return result, string[i:]


_re_Scanner.__name__ = _re_Scanner.__qualname__ = "Scanner"
_re_Scanner.__module__ = "re"


class member_descriptor:
    """What a name in a class's __slots__ is on the class: CPython's member
    descriptor, which reads, sets and deletes that attribute of an instance."""

    def __init__(self, name, objclass):
        self.__name__ = name
        self.__objclass__ = objclass
        self.__qualname__ = f"{objclass.__qualname__}.{name}"

    def __get__(self, obj, objtype=None):
        if obj is None:
            return self
        if self.__name__ == "__weakref__":
            return None
        try:
            return vars(obj)[self.__name__]
        except KeyError:
            raise AttributeError(f"'{type(obj).__name__}' object has no attribute '{self.__name__}'") from None

    def __set__(self, obj, value):
        vars(obj)[self.__name__] = value

    def __delete__(self, obj):
        try:
            del vars(obj)[self.__name__]
        except KeyError:
            raise AttributeError(f"'{type(obj).__name__}' object has no attribute '{self.__name__}'") from None

    def __repr__(self):
        return f"<member '{self.__name__}' of '{self.__objclass__.__name__}' objects>"

    def __eq__(self, other):
        return isinstance(other, member_descriptor) and self.__name__ == other.__name__ and self.__objclass__ is other.__objclass__

    def __hash__(self):
        return hash((self.__name__, id(self.__objclass__)))


member_descriptor.__module__ = "builtins"


class JSONDecodeError(ValueError):
    """json.JSONDecodeError, as CPython's Lib/json/decoder.py has it: a
    ValueError that also says where in the document reading stopped.

    msg: the message alone; doc: the JSON text; pos: the character it points at;
    lineno and colno: that character's line and column, counted from 1.
    """

    def __init__(self, msg, doc, pos):
        lineno = doc.count("\n", 0, pos) + 1
        colno = pos - doc.rfind("\n", 0, pos)
        errmsg = "%s: line %d column %d (char %d)" % (msg, lineno, colno, pos)
        ValueError.__init__(self, errmsg)
        self.msg = msg
        self.doc = doc
        self.pos = pos
        self.lineno = lineno
        self.colno = colno

    def __reduce__(self):
        return self.__class__, (self.msg, self.doc, self.pos)


JSONDecodeError.__module__ = "json.decoder"

_JSON_CONSTANTS = {"-Infinity": float("-inf"), "Infinity": float("inf"), "NaN": float("nan")}


class JSONDecoder(object):
    """json.JSONDecoder: CPython's options and methods over the json module's
    own decoder. A hook left out is read natively, without a call."""

    def __init__(self, *, object_hook=None, parse_float=None, parse_int=None, parse_constant=None, strict=True, object_pairs_hook=None):
        self.object_hook = object_hook
        self.parse_float = parse_float or float
        self.parse_int = parse_int or int
        self.parse_constant = parse_constant or _JSON_CONSTANTS.__getitem__
        self.strict = strict
        self.object_pairs_hook = object_pairs_hook
        self.memo = {}

    def _options(self):
        constant = self.parse_constant
        # The default (the dict's own lookup) is what the decoder does anyway.
        if getattr(constant, "__self__", None) is _JSON_CONSTANTS:
            constant = None
        return {
            "object_hook": self.object_hook,
            "object_pairs_hook": self.object_pairs_hook,
            "parse_float": self.parse_float,
            "parse_int": self.parse_int,
            "parse_constant": constant,
            "strict": self.strict,
        }

    def decode(self, s, _w=None):
        """The Python value of the JSON document [s], which may have
        whitespace either side but nothing else."""
        return __json_decode__(s, None, **self._options())

    def raw_decode(self, s, idx=0):
        """The value whose JSON starts at character [idx] of [s], and the
        index just past it: for a document with more after the value."""
        return __json_decode__(s, idx, **self._options())

    def scan_once(self, string, idx):
        try:
            return __json_decode__(string, idx, **self._options())
        except JSONDecodeError as e:
            if e.msg == "Expecting value" and e.pos == idx:
                raise StopIteration(idx) from None
            raise


JSONDecoder.__module__ = "json.decoder"


class JSONEncoder(object):
    """json.JSONEncoder: CPython's options and methods over the json
    module's own encoder. A subclass's default() is called for each value
    JSON has no form for."""

    item_separator = ", "
    key_separator = ": "

    # ensure_ascii is off unless asked, as json.dumps has it here.
    def __init__(self, *, skipkeys=False, ensure_ascii=False, check_circular=True, allow_nan=True, sort_keys=False, indent=None, separators=None, default=None):
        self.skipkeys = skipkeys
        self.ensure_ascii = ensure_ascii
        self.check_circular = check_circular
        self.allow_nan = allow_nan
        self.sort_keys = sort_keys
        self.indent = indent
        if separators is not None:
            self.item_separator, self.key_separator = separators
        elif indent is not None:
            self.item_separator = ","
        if default is not None:
            self.default = default

    def default(self, o):
        """What [o], a value JSON can't hold, is written as. Override it to
        return something JSON can hold; this one raises a TypeError."""
        raise TypeError(f"Object of type {o.__class__.__name__} is not JSON serializable")

    def _encode(self, o):
        return __json_encode__(
            o,
            skipkeys=self.skipkeys,
            ensure_ascii=self.ensure_ascii,
            check_circular=self.check_circular,
            allow_nan=self.allow_nan,
            sort_keys=self.sort_keys,
            indent=self.indent,
            separators=(self.item_separator, self.key_separator),
            default=self.default,
        )

    def encode(self, o):
        """[o] as one JSON string."""
        if type(self).iterencode is not JSONEncoder.iterencode:
            return "".join(self.iterencode(o, _one_shot=True))
        return self._encode(o)

    def iterencode(self, o, _one_shot=False):
        """[o] as JSON, in pieces to write one by one: here, one piece."""
        return iter((self._encode(o),))


JSONEncoder.__module__ = "json.encoder"
