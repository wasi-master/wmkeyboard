"""collections: Counter, defaultdict, OrderedDict, deque, namedtuple and
ChainMap.

Counter, defaultdict and OrderedDict are dict subclasses and namedtuple
makes tuple subclasses, as in CPython: `isinstance(Counter(), dict)` holds
and `json.dumps` takes them as they are.
"""


class OrderedDict(dict):
    """A dict that can move a key to either end. Every dict keeps its order;
    this one compares order-sensitively with another OrderedDict."""

    def move_to_end(self, key, last=True):
        value = dict.pop(self, key)
        if last:
            dict.__setitem__(self, key, value)
            return
        rest = list(dict.items(self))
        dict.clear(self)
        dict.__setitem__(self, key, value)
        for k, v in rest:
            dict.__setitem__(self, k, v)

    def popitem(self, last=True):
        if not self:
            raise KeyError("dictionary is empty")
        key = next(reversed(self)) if last else next(iter(self))
        return key, dict.pop(self, key)

    def __eq__(self, other):
        if isinstance(other, OrderedDict):
            return dict.__eq__(self, other) and list(self) == list(other)
        return dict.__eq__(self, other)

    def __ne__(self, other):
        return not self == other

    def __repr__(self):
        if not self:
            return f"{type(self).__name__}()"
        return f"{type(self).__name__}({dict.__repr__(self)})"

    def copy(self):
        return type(self)(self)

    def __or__(self, other):
        if not isinstance(other, dict):
            return NotImplemented
        new = type(self)(self)
        new.update(other)
        return new

    def __ror__(self, other):
        if not isinstance(other, dict):
            return NotImplemented
        new = type(self)(other)
        new.update(self)
        return new

    @classmethod
    def fromkeys(cls, iterable, value=None):
        out = cls()
        for key in iterable:
            out[key] = value
        return out


class Counter(dict):
    """A dict of counts: missing keys count zero."""

    def __init__(self, iterable=None, /, **kwds):
        super().__init__()
        self.update(iterable, **kwds)

    def __missing__(self, key):
        return 0

    def total(self):
        return sum(self.values())

    def most_common(self, n=None):
        # sorted() is stable, so ties keep the order they were first counted in.
        pairs = sorted(self.items(), key=lambda kv: kv[1], reverse=True)
        if n is None:
            return pairs
        return pairs[:n]

    def elements(self):
        out = []
        for k, v in self.items():
            for _ in range(v):
                out.append(k)
        return iter(out)

    @classmethod
    def fromkeys(cls, iterable, v=None):
        raise NotImplementedError("Counter.fromkeys() is undefined.  Use Counter(iterable) instead.")

    def update(self, iterable=None, /, **kwds):
        if iterable is not None:
            if isinstance(iterable, dict):
                if self:
                    for elem, count in iterable.items():
                        self[elem] = count + self.get(elem, 0)
                else:
                    dict.update(self, iterable)
            else:
                for elem in iterable:
                    self[elem] = self.get(elem, 0) + 1
        if kwds:
            self.update(kwds)

    def subtract(self, iterable=None, /, **kwds):
        if iterable is not None:
            if isinstance(iterable, dict):
                for elem, count in iterable.items():
                    self[elem] = self.get(elem, 0) - count
            else:
                for elem in iterable:
                    self[elem] = self.get(elem, 0) - 1
        if kwds:
            self.subtract(kwds)

    def copy(self):
        return type(self)(self)

    def __reduce__(self):
        return type(self), (dict(self),)

    def __delitem__(self, elem):
        if elem in self:
            super().__delitem__(elem)

    def __repr__(self):
        if not self:
            return f"{type(self).__name__}()"
        inner = ", ".join(f"{k!r}: {v!r}" for k, v in self.most_common())
        return f"{type(self).__name__}({{{inner}}})"

    def __eq__(self, other):
        if not isinstance(other, Counter):
            return NotImplemented
        return all(self[e] == other[e] for c in (self, other) for e in c)

    def __ne__(self, other):
        if not isinstance(other, Counter):
            return NotImplemented
        return not self == other

    def __le__(self, other):
        if not isinstance(other, Counter):
            return NotImplemented
        return all(self[e] <= other[e] for c in (self, other) for e in c)

    def __lt__(self, other):
        if not isinstance(other, Counter):
            return NotImplemented
        return self <= other and self != other

    def __ge__(self, other):
        if not isinstance(other, Counter):
            return NotImplemented
        return all(self[e] >= other[e] for c in (self, other) for e in c)

    def __gt__(self, other):
        if not isinstance(other, Counter):
            return NotImplemented
        return self >= other and self != other

    def __add__(self, other):
        if not isinstance(other, Counter):
            return NotImplemented
        result = Counter()
        for elem, count in self.items():
            newcount = count + other[elem]
            if newcount > 0:
                result[elem] = newcount
        for elem, count in other.items():
            if elem not in self and count > 0:
                result[elem] = count
        return result

    def __sub__(self, other):
        if not isinstance(other, Counter):
            return NotImplemented
        result = Counter()
        for elem, count in self.items():
            newcount = count - other[elem]
            if newcount > 0:
                result[elem] = newcount
        for elem, count in other.items():
            if elem not in self and count < 0:
                result[elem] = 0 - count
        return result

    def __or__(self, other):
        if not isinstance(other, Counter):
            return NotImplemented
        result = Counter()
        for elem, count in self.items():
            other_count = other[elem]
            newcount = other_count if count < other_count else count
            if newcount > 0:
                result[elem] = newcount
        for elem, count in other.items():
            if elem not in self and count > 0:
                result[elem] = count
        return result

    def __and__(self, other):
        if not isinstance(other, Counter):
            return NotImplemented
        result = Counter()
        for elem, count in self.items():
            other_count = other[elem]
            newcount = count if count < other_count else other_count
            if newcount > 0:
                result[elem] = newcount
        return result

    def __pos__(self):
        result = Counter()
        for elem, count in self.items():
            if count > 0:
                result[elem] = count
        return result

    def __neg__(self):
        result = Counter()
        for elem, count in self.items():
            if count < 0:
                result[elem] = 0 - count
        return result

    def _keep_positive(self):
        for elem in [e for e, c in self.items() if not c > 0]:
            del self[elem]
        return self

    def __iadd__(self, other):
        for elem, count in other.items():
            self[elem] += count
        return self._keep_positive()

    def __isub__(self, other):
        for elem, count in other.items():
            self[elem] -= count
        return self._keep_positive()

    def __ior__(self, other):
        for elem, other_count in other.items():
            count = self[elem]
            if other_count > count:
                self[elem] = other_count
        return self._keep_positive()

    def __iand__(self, other):
        for elem, count in self.items():
            other_count = other[elem]
            if other_count < count:
                self[elem] = other_count
        return self._keep_positive()


class defaultdict(dict):
    """A dict that makes a missing key's value with default_factory()."""

    def __init__(self, default_factory=None, /, *args, **kwargs):
        if default_factory is not None and not callable(default_factory):
            raise TypeError("first argument must be callable or None")
        super().__init__(*args, **kwargs)
        self.default_factory = default_factory

    def __missing__(self, key):
        if self.default_factory is None:
            raise KeyError(key)
        value = self.default_factory()
        self[key] = value
        return value

    def __repr__(self):
        return f"{type(self).__name__}({self.default_factory!r}, {dict.__repr__(self)})"

    def copy(self):
        return type(self)(self.default_factory, self)

    __copy__ = copy

    def __reduce__(self):
        return type(self), (self.default_factory,), None, None, iter(self.items())

    def __or__(self, other):
        if not isinstance(other, dict):
            return NotImplemented
        new = self.copy()
        new.update(other)
        return new

    def __ror__(self, other):
        if not isinstance(other, dict):
            return NotImplemented
        new = type(self)(self.default_factory, other)
        new.update(self)
        return new


class deque:
    def __init__(self, iterable=None, maxlen=None):
        self.maxlen = maxlen
        self._items = []
        if iterable is not None:
            for x in iterable:
                self.append(x)

    def _trim_right(self):
        if self.maxlen is not None:
            while len(self._items) > self.maxlen:
                self._items.pop(0)

    def _trim_left(self):
        if self.maxlen is not None:
            while len(self._items) > self.maxlen:
                self._items.pop()

    def append(self, x):
        self._items.append(x)
        self._trim_right()

    def appendleft(self, x):
        self._items.insert(0, x)
        self._trim_left()

    def extend(self, items):
        for x in items:
            self.append(x)

    def extendleft(self, items):
        for x in items:
            self.appendleft(x)

    def pop(self):
        if not self._items:
            raise IndexError("pop from an empty deque")
        return self._items.pop()

    def popleft(self):
        if not self._items:
            raise IndexError("pop from an empty deque")
        return self._items.pop(0)

    def rotate(self, n=1):
        if not self._items:
            return
        n = n % len(self._items)
        if n:
            self._items = self._items[-n:] + self._items[:-n]

    def clear(self):
        self._items = []

    def count(self, x):
        return self._items.count(x)

    def remove(self, x):
        self._items.remove(x)

    def __len__(self):
        return len(self._items)

    def __iter__(self):
        return list(self._items)

    def __getitem__(self, i):
        return self._items[i]

    def __setitem__(self, i, v):
        self._items[i] = v

    def __contains__(self, x):
        return x in self._items

    def __eq__(self, other):
        return isinstance(other, deque) and self._items == other._items

    def __repr__(self):
        if self.maxlen is None:
            return f"deque({self._items!r})"
        return f"deque({self._items!r}, maxlen={self.maxlen})"


def _arg_names(names):
    quoted = ["'" + n + "'" for n in names]
    if len(quoted) < 3:
        return " and ".join(quoted)
    return ", ".join(quoted[:-1]) + ", and " + quoted[-1]


def _missing_error(who, names):
    noun = "argument" if len(names) == 1 else "arguments"
    return TypeError(f"{who} missing {len(names)} required positional {noun}: {_arg_names(names)}")


def _too_many_error(who, least, most, given):
    # Counts take in self, as CPython's do.
    if least == most:
        takes = f"{most} positional argument" + ("" if most == 1 else "s")
    else:
        takes = f"from {least} to {most} positional arguments"
    return TypeError(f"{who} takes {takes} but {given} {'was' if given == 1 else 'were'} given")


def namedtuple(typename, field_names, *, rename=False, defaults=None, module=None):
    """A tuple subclass whose items are also named attributes."""
    if isinstance(field_names, str):
        field_names = field_names.replace(",", " ").split()
    field_names = [str(name) for name in field_names]
    if rename:
        seen = set()
        for index, name in enumerate(field_names):
            if (not name.isidentifier() or name.startswith("_") or name in seen
                    or name in ("False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
                                "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
                                "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
                                "return", "try", "while", "with", "yield")):
                field_names[index] = f"_{index}"
            seen.add(name)
    for name in [typename] + field_names:
        if not isinstance(name, str) or not name.isidentifier():
            raise ValueError(f"Type names and field names must be valid identifiers: {name!r}")
    seen = set()
    for name in field_names:
        if name.startswith("_") and not rename:
            raise ValueError(f"Field names cannot start with an underscore: {name!r}")
        if name in seen:
            raise ValueError(f"Encountered duplicate field name: {name!r}")
        seen.add(name)
    fields = tuple(field_names)
    defaults = tuple(defaults) if defaults is not None else ()
    if len(defaults) > len(fields):
        raise TypeError("Got more default values than field names")
    first_default = len(fields) - len(defaults)
    field_defaults = dict(zip(fields[first_default:], defaults))
    who = typename + ".__new__()"

    def __new__(cls, *args, **kwargs):
        if len(args) > len(fields):
            raise _too_many_error(who, 1 + first_default, 1 + len(fields), 1 + len(args))
        for name in fields[:len(args)]:
            if name in kwargs:
                raise TypeError(f"{who} got multiple values for argument '{name}'")
        values = list(args)
        missing = []
        for i in range(len(args), len(fields)):
            name = fields[i]
            if name in kwargs:
                values.append(kwargs.pop(name))
            elif i >= first_default:
                values.append(defaults[i - first_default])
            else:
                missing.append(name)
        if missing:
            raise _missing_error(who, missing)
        if kwargs:
            raise TypeError(f"{who} got an unexpected keyword argument '{list(kwargs)[0]}'")
        return tuple.__new__(cls, values)

    def _make(cls, iterable):
        result = tuple.__new__(cls, iterable)
        if len(result) != len(fields):
            raise TypeError(f"Expected {len(fields)} arguments, got {len(result)}")
        return result

    def _replace(self, /, **kwds):
        result = self._make([kwds.pop(name) if name in kwds else value for name, value in zip(fields, self)])
        if kwds:
            raise ValueError(f"Got unexpected field names: {list(kwds)!r}")
        return result

    def __repr__(self):
        inner = ", ".join(f"{n}={v!r}" for n, v in zip(fields, self))
        return f"{self.__class__.__name__}({inner})"

    def _asdict(self):
        return dict(zip(self._fields, self))

    def __getnewargs__(self):
        return tuple(self)

    def __setattr__(self, name, value):
        if name in fields:
            raise AttributeError("can't set attribute")
        raise AttributeError(f"{typename!r} object has no attribute {name!r} and no __dict__ for setting new attributes")

    namespace = {
        "__doc__": f"{typename}({', '.join(fields)})",
        "__slots__": (),
        "_fields": fields,
        "_field_defaults": field_defaults,
        "__new__": __new__,
        "_make": classmethod(_make),
        "__replace__": _replace,
        "_replace": _replace,
        "__repr__": __repr__,
        "_asdict": _asdict,
        "__getnewargs__": __getnewargs__,
        "__setattr__": __setattr__,
        "__match_args__": fields,
        "__module__": module if module is not None else "__main__",
    }
    for index, name in enumerate(fields):
        namespace[name] = property(lambda self, index=index: self[index], doc=f"Alias for field number {index}")
    return type(typename, (tuple,), namespace)


class _Mapping:
    """Dict behaviour over self._data, for ChainMap."""

    def __getitem__(self, key):
        return self._data[key]

    def __setitem__(self, key, value):
        self._data[key] = value

    def __delitem__(self, key):
        del self._data[key]

    def __contains__(self, key):
        return key in self._data

    def __len__(self):
        return len(self._data)

    def __iter__(self):
        return iter(self._data)

    def __eq__(self, other):
        if isinstance(other, _Mapping):
            return self._data == other._data
        return self._data == other

    def keys(self):
        return self._data.keys()

    def values(self):
        return self._data.values()

    def items(self):
        return self._data.items()

    def get(self, key, default=None):
        return self._data.get(key, default)

    def pop(self, key, *default):
        if default:
            return self._data.pop(key, default[0])
        return self._data.pop(key)

    def setdefault(self, key, default=None):
        return self._data.setdefault(key, default)

    def update(self, other=None, **kwargs):
        if other is not None:
            for k, v in dict(other).items():
                self[k] = v
        for k, v in kwargs.items():
            self[k] = v

    def clear(self):
        self._data.clear()

    def copy(self):
        return dict(self._data)


class ChainMap(_Mapping):
    """Several mappings searched in order as one; writes go to the first."""

    def __init__(self, *maps):
        self.maps = list(maps) or [{}]

    @property
    def _data(self):
        merged = {}
        for m in reversed(self.maps):
            merged.update(m)
        return merged

    def __getitem__(self, key):
        for m in self.maps:
            if key in m:
                return m[key]
        raise KeyError(key)

    def __setitem__(self, key, value):
        self.maps[0][key] = value

    def __delitem__(self, key):
        try:
            del self.maps[0][key]
        except KeyError:
            raise KeyError(f"Key not found in the first mapping: {key!r}")

    def __contains__(self, key):
        return any(key in m for m in self.maps)

    def get(self, key, default=None):
        return self[key] if key in self else default

    def __iter__(self):
        return iter(self._data)

    def new_child(self, m=None):
        return ChainMap({} if m is None else m, *self.maps)

    @property
    def parents(self):
        return ChainMap(*self.maps[1:])

    def __repr__(self):
        return f"ChainMap({', '.join(repr(m) for m in self.maps)})"
