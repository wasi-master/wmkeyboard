"""collections.abc: the abstract container types, for isinstance checks and
as bases that fill in methods (a Mapping gets get, keys, items, values, ==
and `in` from __getitem__, __iter__ and __len__).

Built-in types answer by the methods they have, as in Python: a dict is a
MutableMapping, a tuple a Sequence, a generator an Iterator.
"""

from abc import ABCMeta, abstractmethod

_ITER = {"__iter__"}
_SIZED_ITER = {"__iter__", "__len__", "__contains__"}
_SEQ = _SIZED_ITER | {"__getitem__", "__reversed__", "index", "count"}
_MUT_SEQ = _SEQ | {"__setitem__", "__delitem__", "insert", "append", "extend", "pop", "remove", "reverse", "__iadd__"}
_SET = _SIZED_ITER | {"__le__", "__lt__", "__eq__", "__ne__", "__gt__", "__ge__", "__and__", "__or__", "__sub__", "__xor__", "isdisjoint"}
_MAP = _SIZED_ITER | {"__getitem__", "keys", "items", "values", "get", "__eq__", "__ne__", "__reversed__"}
_ITERATOR = {"__iter__", "__next__"}
_GENERATOR = _ITERATOR | {"send", "throw", "close"}

# What each built-in type has, standing in for its __dict__.
_BUILTIN = {
    dict: _MAP | {"__setitem__", "__delitem__", "pop", "popitem", "clear", "update", "setdefault"},
    list: _MUT_SEQ | {"sort", "clear", "copy"},
    tuple: _SEQ | {"__hash__"},
    str: _SEQ | {"__hash__"},
    bytes: _SEQ | {"__hash__"},
    range: _SEQ | {"__hash__"},
    set: _SET | {"add", "discard", "remove", "pop", "clear", "__ior__", "__iand__", "__ixor__", "__isub__"},
    int: {"__hash__"},
    float: {"__hash__"},
    bool: {"__hash__"},
    type(None): {"__hash__"},
    type(len): {"__call__", "__hash__"},
    type(lambda: None): {"__call__", "__hash__"},
    type: {"__call__", "__hash__"},
}


def _gen():
    yield


for _t in (type(iter([])), type(iter(())), type(iter("")), type(iter({})), type(iter(set())), type(iter(range(0))),
           type(map(len, [])), type(filter(None, [])), type(zip()), type(enumerate([])), type(reversed([]))):
    _BUILTIN[_t] = _ITERATOR
_BUILTIN[type(_gen())] = _GENERATOR
_BUILTIN[type({}.keys())] = _SET | {"__reversed__"}
_BUILTIN[type({}.items())] = _SET | {"__reversed__"}
_BUILTIN[type({}.values())] = _SIZED_ITER | {"__reversed__"}
_BUILTIN[type(x for x in ())] = _GENERATOR


def _mro(C):
    try:
        return C.__mro__
    except AttributeError:
        return (C,)


def _check_methods(C, *methods):
    """True when C (or a base) defines every one of methods, else NotImplemented."""
    mro = _mro(C)
    for method in methods:
        for B in mro:
            if B in _BUILTIN:
                if method in _BUILTIN[B]:
                    break
                continue
            if B is object:
                if method in ("__hash__", "__eq__", "__ne__"):
                    break
                continue
            own = vars(B)
            if method in own:
                if own[method] is None:
                    return NotImplemented
                break
        else:
            return NotImplemented
    return True


class Hashable(metaclass=ABCMeta):
    @abstractmethod
    def __hash__(self):
        return 0

    @classmethod
    def __subclasshook__(cls, C):
        if cls is Hashable:
            if C in (list, dict, set):
                return False
            return _check_methods(C, "__hash__")
        return NotImplemented


class Iterable(metaclass=ABCMeta):
    @abstractmethod
    def __iter__(self):
        while False:
            yield None

    @classmethod
    def __subclasshook__(cls, C):
        if cls is Iterable:
            return _check_methods(C, "__iter__")
        return NotImplemented


class Iterator(Iterable):
    @abstractmethod
    def __next__(self):
        raise StopIteration

    def __iter__(self):
        return self

    @classmethod
    def __subclasshook__(cls, C):
        if cls is Iterator:
            return _check_methods(C, "__iter__", "__next__")
        return NotImplemented


class Reversible(Iterable):
    @abstractmethod
    def __reversed__(self):
        while False:
            yield None

    @classmethod
    def __subclasshook__(cls, C):
        if cls is Reversible:
            return _check_methods(C, "__reversed__", "__iter__")
        return NotImplemented


class Generator(Iterator):
    def __next__(self):
        return self.send(None)

    @abstractmethod
    def send(self, value):
        raise StopIteration

    @abstractmethod
    def throw(self, typ, val=None, tb=None):
        raise typ if val is None else val

    def close(self):
        pass

    @classmethod
    def __subclasshook__(cls, C):
        if cls is Generator:
            return _check_methods(C, "__iter__", "__next__", "send", "throw", "close")
        return NotImplemented


class Sized(metaclass=ABCMeta):
    @abstractmethod
    def __len__(self):
        return 0

    @classmethod
    def __subclasshook__(cls, C):
        if cls is Sized:
            return _check_methods(C, "__len__")
        return NotImplemented


class Container(metaclass=ABCMeta):
    @abstractmethod
    def __contains__(self, x):
        return False

    @classmethod
    def __subclasshook__(cls, C):
        if cls is Container:
            return _check_methods(C, "__contains__")
        return NotImplemented


class Collection(Sized, Iterable, Container):
    @classmethod
    def __subclasshook__(cls, C):
        if cls is Collection:
            return _check_methods(C, "__len__", "__iter__", "__contains__")
        return NotImplemented


class Callable(metaclass=ABCMeta):
    @abstractmethod
    def __call__(self, *args, **kwds):
        return False

    @classmethod
    def __subclasshook__(cls, C):
        if cls is Callable:
            return _check_methods(C, "__call__")
        return NotImplemented


class Set(Collection):
    def __le__(self, other):
        if not isinstance(other, Set):
            return NotImplemented
        if len(self) > len(other):
            return False
        for elem in self:
            if elem not in other:
                return False
        return True

    def __lt__(self, other):
        if not isinstance(other, Set):
            return NotImplemented
        return len(self) < len(other) and self.__le__(other)

    def __gt__(self, other):
        if not isinstance(other, Set):
            return NotImplemented
        return len(self) > len(other) and self.__ge__(other)

    def __ge__(self, other):
        if not isinstance(other, Set):
            return NotImplemented
        if len(self) < len(other):
            return False
        for elem in other:
            if elem not in self:
                return False
        return True

    def __eq__(self, other):
        if not isinstance(other, Set):
            return NotImplemented
        return len(self) == len(other) and self.__le__(other)

    @classmethod
    def _from_iterable(cls, it):
        return cls(it)

    def __and__(self, other):
        return self._from_iterable(value for value in other if value in self)

    def __or__(self, other):
        return self._from_iterable([e for s in (self, other) for e in s])

    def __sub__(self, other):
        return self._from_iterable(value for value in self if value not in other)

    def __xor__(self, other):
        return (self - other) | (other - self)

    def isdisjoint(self, other):
        for value in other:
            if value in self:
                return False
        return True


class MutableSet(Set):
    @abstractmethod
    def add(self, value):
        raise NotImplementedError

    @abstractmethod
    def discard(self, value):
        raise NotImplementedError

    def remove(self, value):
        if value not in self:
            raise KeyError(value)
        self.discard(value)

    def pop(self):
        for value in self:
            self.discard(value)
            return value
        raise KeyError

    def clear(self):
        for value in list(self):
            self.discard(value)


class Mapping(Collection):
    @abstractmethod
    def __getitem__(self, key):
        raise KeyError

    def get(self, key, default=None):
        try:
            return self[key]
        except KeyError:
            return default

    def __contains__(self, key):
        try:
            self[key]
        except KeyError:
            return False
        return True

    def keys(self):
        return list(self)

    def items(self):
        return [(key, self[key]) for key in self]

    def values(self):
        return [self[key] for key in self]

    def __eq__(self, other):
        if not isinstance(other, Mapping):
            return NotImplemented
        return dict(self.items()) == dict(other.items())


class MutableMapping(Mapping):
    @abstractmethod
    def __setitem__(self, key, value):
        raise KeyError

    @abstractmethod
    def __delitem__(self, key):
        raise KeyError

    _MISSING = object()

    def pop(self, key, default=_MISSING):
        try:
            value = self[key]
        except KeyError:
            if default is MutableMapping._MISSING:
                raise
            return default
        del self[key]
        return value

    def popitem(self):
        for key in self:
            value = self[key]
            del self[key]
            return key, value
        raise KeyError("popitem(): mapping is empty")

    def clear(self):
        for key in list(self):
            del self[key]

    def update(self, other=(), **kwds):
        if isinstance(other, Mapping) or hasattr(other, "keys"):
            for key in other.keys():
                self[key] = other[key]
        else:
            for key, value in other:
                self[key] = value
        for key, value in kwds.items():
            self[key] = value

    def setdefault(self, key, default=None):
        try:
            return self[key]
        except KeyError:
            self[key] = default
        return default


class Sequence(Reversible, Collection):
    @abstractmethod
    def __getitem__(self, index):
        raise IndexError

    def __iter__(self):
        i = 0
        try:
            while True:
                v = self[i]
                yield v
                i += 1
        except IndexError:
            return

    def __contains__(self, value):
        for v in self:
            if v is value or v == value:
                return True
        return False

    def __reversed__(self):
        for i in reversed(range(len(self))):
            yield self[i]

    def index(self, value, start=0, stop=None):
        i = start
        while stop is None or i < stop:
            try:
                v = self[i]
            except IndexError:
                break
            if v is value or v == value:
                return i
            i += 1
        raise ValueError

    def count(self, value):
        return sum(1 for v in self if v is value or v == value)


class MutableSequence(Sequence):
    @abstractmethod
    def __setitem__(self, index, value):
        raise IndexError

    @abstractmethod
    def __delitem__(self, index):
        raise IndexError

    @abstractmethod
    def insert(self, index, value):
        raise IndexError

    def append(self, value):
        self.insert(len(self), value)

    def clear(self):
        try:
            while True:
                self.pop()
        except IndexError:
            pass

    def reverse(self):
        n = len(self)
        for i in range(n // 2):
            self[i], self[n - i - 1] = self[n - i - 1], self[i]

    def extend(self, values):
        for v in list(values):
            self.append(v)

    def pop(self, index=-1):
        v = self[index]
        del self[index]
        return v

    def remove(self, value):
        del self[self.index(value)]

    def __iadd__(self, values):
        self.extend(values)
        return self


class ByteString(Sequence):
    pass


class MappingView(Sized):
    def __init__(self, mapping):
        self._mapping = mapping

    def __len__(self):
        return len(self._mapping)


class KeysView(MappingView, Set):
    def __contains__(self, key):
        return key in self._mapping

    def __iter__(self):
        yield from self._mapping


class ItemsView(MappingView, Set):
    def __contains__(self, item):
        key, value = item
        try:
            v = self._mapping[key]
        except KeyError:
            return False
        return v is value or v == value

    def __iter__(self):
        for key in self._mapping:
            yield (key, self._mapping[key])


class ValuesView(MappingView, Collection):
    def __contains__(self, value):
        for key in self._mapping:
            v = self._mapping[key]
            if v is value or v == value:
                return True
        return False

    def __iter__(self):
        for key in self._mapping:
            yield self._mapping[key]


Sequence.register(tuple)
Sequence.register(str)
Sequence.register(range)
ByteString.register(bytes)
MutableSequence.register(list)
MutableSet.register(set)
MutableMapping.register(dict)
KeysView.register(type({}.keys()))
ItemsView.register(type({}.items()))
ValuesView.register(type({}.values()))
