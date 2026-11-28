"""rpds: persistent (immutable) collections, the API of rpds-py.

rpds-py wraps Rust's rpds crate; this is the same API in Python for WM
Keyboard plugins, which cannot load compiled code. Each change returns a new
collection and leaves the old one as it was. Changes copy, so they cost O(n)
where rpds-py's cost O(log n); the collections plugins hand around (a JSON
schema registry, a few hundred entries at most) do not notice.
"""

from collections.abc import ItemsView, KeysView, Mapping, Set, ValuesView

__all__ = ["HashTrieMap", "HashTrieSet", "List", "Queue", "Stack"]

_MISSING = object()


def _pairs(value):
    if isinstance(value, Mapping):
        return list(value.items())
    return [tuple(p) for p in value]


class _KeysView(KeysView):
    def __init__(self, m):
        self._mapping = m

    def __repr__(self):
        return "keys_view({%s})" % ", ".join(repr(k) for k in self._mapping)

    def intersection(self, other):
        return HashTrieSet(k for k in self if k in other)

    def union(self, other):
        return HashTrieSet(list(self) + list(other))

    def __and__(self, other):
        return self.intersection(other)

    __rand__ = __and__

    def __or__(self, other):
        return self.union(other)

    __ror__ = __or__

    def __hash__(self):
        raise TypeError("unhashable type: 'keys_view'")


class _ValuesView(ValuesView):
    def __init__(self, m):
        self._mapping = m

    def __repr__(self):
        return "values_view([%s])" % ", ".join(repr(v) for v in self._mapping._d.values())


class _ItemsView(ItemsView):
    def __init__(self, m):
        self._mapping = m

    def __repr__(self):
        return "items_view([%s])" % ", ".join(repr(p) for p in self._mapping._d.items())

    def __contains__(self, item):
        try:
            k, v = item
        except (TypeError, ValueError):
            return False
        got = self._mapping._d.get(k, _MISSING)
        return got is not _MISSING and (got is v or got == v)

    def intersection(self, other):
        return HashTrieSet(p for p in self if p in other)

    def union(self, other):
        return HashTrieSet(list(self) + list(other))

    def __and__(self, other):
        return self.intersection(other)

    __rand__ = __and__

    def __or__(self, other):
        return self.union(other)

    __ror__ = __or__


class HashTrieMap(Mapping):
    """An immutable mapping: insert, remove, discard and update give a new one."""

    __slots__ = ("_d",)

    def __init__(self, value=(), **kwds):
        d = dict(_pairs(value))
        d.update(kwds)
        object.__setattr__(self, "_d", d)

    @classmethod
    def _of(cls, d):
        m = cls.__new__(cls)
        object.__setattr__(m, "_d", d)
        return m

    @classmethod
    def convert(cls, value):
        """[value] as a HashTrieMap: itself when it is one already."""
        if isinstance(value, HashTrieMap):
            return value
        return cls(value)

    @classmethod
    def fromkeys(cls, keys, value=None):
        return cls._of({k: value for k in keys})

    def __setattr__(self, name, value):
        raise AttributeError(f"'{type(self).__name__}' object is immutable")

    def __getitem__(self, key):
        return self._d[key]

    def __contains__(self, key):
        return key in self._d

    def __iter__(self):
        return iter(self._d)

    def __len__(self):
        return len(self._d)

    def get(self, key, default=None):
        return self._d.get(key, default)

    def keys(self):
        return _KeysView(self)

    def values(self):
        return _ValuesView(self)

    def items(self):
        return _ItemsView(self)

    def insert(self, key, val):
        d = dict(self._d)
        d[key] = val
        return self._of(d)

    def remove(self, key):
        if key not in self._d:
            raise KeyError(key)
        d = dict(self._d)
        del d[key]
        return self._of(d)

    def discard(self, key):
        if key not in self._d:
            return self
        return self.remove(key)

    def update(self, *args):
        d = dict(self._d)
        for a in args:
            d.update(_pairs(a))
        return self._of(d)

    def __eq__(self, other):
        if isinstance(other, HashTrieMap):
            return self._d == other._d
        if isinstance(other, Mapping):
            return self._d == dict(other.items())
        return NotImplemented

    def __ne__(self, other):
        eq = self.__eq__(other)
        return eq if eq is NotImplemented else not eq

    def __hash__(self):
        for k, v in self._d.items():
            try:
                hash(v)
            except Exception:
                raise TypeError(f"Unhashable type in HashTrieMap of key {k!r}") from None
        return hash(frozenset(self._d.items()))

    def __repr__(self):
        inner = ", ".join(f"{k!r}: {v!r}" for k, v in self._d.items())
        return "HashTrieMap({%s})" % inner

    def __reduce__(self):
        return (HashTrieMap, (dict(self._d),))

    def __class_getitem__(cls, item):
        return cls


class HashTrieSet(Set):
    """An immutable set: insert, remove, discard and update give a new one."""

    __slots__ = ("_s",)

    def __init__(self, value=()):
        object.__setattr__(self, "_s", frozenset(value))

    @classmethod
    def _of(cls, s):
        m = cls.__new__(cls)
        object.__setattr__(m, "_s", s)
        return m

    @classmethod
    def _from_iterable(cls, it):
        return cls._of(frozenset(it))

    def __setattr__(self, name, value):
        raise AttributeError(f"'{type(self).__name__}' object is immutable")

    def __contains__(self, value):
        return value in self._s

    def __iter__(self):
        return iter(self._s)

    def __len__(self):
        return len(self._s)

    def insert(self, value):
        return self._of(self._s | {value})

    def remove(self, value):
        if value not in self._s:
            raise KeyError(value)
        return self._of(self._s - {value})

    def discard(self, value):
        if value not in self._s:
            return self
        return self._of(self._s - {value})

    def update(self, *args):
        s = set(self._s)
        for a in args:
            s.update(a)
        return self._of(frozenset(s))

    def union(self, *others):
        return self.update(*others)

    def intersection(self, *others):
        s = self._s
        for o in others:
            s = s.intersection(o)
        return self._of(s)

    def difference(self, *others):
        s = self._s
        for o in others:
            s = s.difference(o)
        return self._of(s)

    def symmetric_difference(self, other):
        return self._of(self._s.symmetric_difference(other))

    def __or__(self, other):
        if not isinstance(other, Set):
            return NotImplemented
        return self.union(other)

    __ror__ = __or__

    def __and__(self, other):
        if not isinstance(other, Set):
            return NotImplemented
        return self.intersection(other)

    __rand__ = __and__

    def __sub__(self, other):
        if not isinstance(other, Set):
            return NotImplemented
        return self.difference(other)

    def __rsub__(self, other):
        if not isinstance(other, Set):
            return NotImplemented
        return self._of(frozenset(other) - self._s)

    def __xor__(self, other):
        if not isinstance(other, Set):
            return NotImplemented
        return self.symmetric_difference(other)

    __rxor__ = __xor__

    def __eq__(self, other):
        if isinstance(other, HashTrieSet):
            return self._s == other._s
        if isinstance(other, (set, frozenset)):
            return self._s == other
        if isinstance(other, Set):
            return self._s == frozenset(other)
        return NotImplemented

    def __ne__(self, other):
        eq = self.__eq__(other)
        return eq if eq is NotImplemented else not eq

    def __hash__(self):
        return hash(self._s)

    def __repr__(self):
        return "HashTrieSet({%s})" % ", ".join(repr(v) for v in self._s)

    def __reduce__(self):
        return (HashTrieSet, (list(self._s),))

    def __class_getitem__(cls, item):
        return cls


def _items(value, more):
    # List(1, 2, 3) is List([1, 2, 3]).
    return [value, *more] if more else list(value)


class _Sequence:
    """What List, Queue and Stack share: a tuple of items in a fixed order."""

    __slots__ = ("_t",)
    _name = ""

    @classmethod
    def _of(cls, t):
        m = cls.__new__(cls)
        object.__setattr__(m, "_t", t)
        return m

    def __setattr__(self, name, value):
        raise AttributeError(f"'{type(self).__name__}' object is immutable")

    def __iter__(self):
        return iter(self._t)

    def __len__(self):
        return len(self._t)

    def __bool__(self):
        return bool(self._t)

    def __eq__(self, other):
        if type(other) is not type(self):
            return NotImplemented
        return self._t == other._t

    def __ne__(self, other):
        eq = self.__eq__(other)
        return eq if eq is NotImplemented else not eq

    def __lt__(self, other):
        if type(other) is not type(self):
            return NotImplemented
        return self._t < other._t

    def __le__(self, other):
        if type(other) is not type(self):
            return NotImplemented
        return self._t <= other._t

    def __gt__(self, other):
        if type(other) is not type(self):
            return NotImplemented
        return self._t > other._t

    def __ge__(self, other):
        if type(other) is not type(self):
            return NotImplemented
        return self._t >= other._t

    def __hash__(self):
        return hash((self._name, self._t))

    def __repr__(self):
        return "%s([%s])" % (self._name, ", ".join(repr(v) for v in self))

    def __reduce__(self):
        return (type(self), (list(self),))

    def __class_getitem__(cls, item):
        return cls


class List(_Sequence):
    """An immutable singly linked list: push_front and drop_first give a new one."""

    __slots__ = ()
    _name = "List"

    def __init__(self, value=(), *more):
        object.__setattr__(self, "_t", tuple(_items(value, more)))

    @property
    def first(self):
        if not self._t:
            raise IndexError("empty list has no first element")
        return self._t[0]

    @property
    def rest(self):
        return self._of(self._t[1:])

    def push_front(self, value):
        return self._of((value,) + self._t)

    def drop_first(self):
        if not self._t:
            raise IndexError("empty list has no first element")
        return self._of(self._t[1:])

    def __reversed__(self):
        return self._of(self._t[::-1])


class Queue(_Sequence):
    """An immutable first-in first-out queue: enqueue and dequeue give a new one."""

    __slots__ = ()
    _name = "Queue"

    def __init__(self, value=(), *more):
        object.__setattr__(self, "_t", tuple(_items(value, more)))

    def __hash__(self):
        try:
            return hash((self._name, self._t))
        except TypeError:
            raise TypeError("Unhashable type in Queue") from None

    @property
    def is_empty(self):
        return not self._t

    def peek(self):
        if not self._t:
            raise IndexError("peeked an empty queue")
        return self._t[0]

    def enqueue(self, value):
        return self._of(self._t + (value,))

    def dequeue(self):
        if not self._t:
            raise IndexError("dequeued an empty queue")
        return self._of(self._t[1:])


class Stack(_Sequence):
    """An immutable last-in first-out stack: push and pop give a new one."""

    __slots__ = ()
    _name = "Stack"

    def __init__(self, value=(), *more):
        # The top is the last item given, which iteration yields first.
        object.__setattr__(self, "_t", tuple(_items(value, more))[::-1])

    def __repr__(self):
        return "Stack([%s])" % ", ".join(repr(v) for v in self._t[::-1])

    def __reduce__(self):
        return (Stack, (list(self._t[::-1]),))

    def peek(self):
        if not self._t:
            raise IndexError("peeked an empty stack")
        return self._t[0]

    def push(self, value):
        return self._of((value,) + self._t)

    def pop(self):
        if not self._t:
            raise IndexError("popped an empty stack")
        return self._of(self._t[1:])
