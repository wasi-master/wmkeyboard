"""collections: Counter, defaultdict, deque, namedtuple and OrderedDict.

Counter and defaultdict are classes that behave like dicts (indexing,
`in`, `len`, iteration over keys, `items()`), not dict subclasses: pass them
through `dict(...)` before `json.dumps`.
"""

OrderedDict = dict


class _Mapping:
    """Shared dict behaviour over self._data."""

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
        return list(self._data.keys())

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

    def to_dict(self):
        return dict(self._data)


class Counter(_Mapping):
    def __init__(self, iterable=None, **kwargs):
        self._data = {}
        self.update(iterable, **kwargs)

    def __getitem__(self, key):
        return self._data.get(key, 0)

    def update(self, iterable=None, **kwargs):
        if iterable is not None:
            if isinstance(iterable, (dict, _Mapping)):
                for k, v in dict(iterable).items():
                    self._data[k] = self._data.get(k, 0) + v
            else:
                for item in iterable:
                    self._data[item] = self._data.get(item, 0) + 1
        for k, v in kwargs.items():
            self._data[k] = self._data.get(k, 0) + v

    def subtract(self, iterable=None, **kwargs):
        if iterable is not None:
            if isinstance(iterable, (dict, _Mapping)):
                for k, v in dict(iterable).items():
                    self._data[k] = self._data.get(k, 0) - v
            else:
                for item in iterable:
                    self._data[item] = self._data.get(item, 0) - 1
        for k, v in kwargs.items():
            self._data[k] = self._data.get(k, 0) - v

    def most_common(self, n=None):
        # sorted() is stable, so ties keep the order they were first counted in.
        pairs = sorted(list(self._data.items()), key=lambda kv: -kv[1])
        if n is None:
            return pairs
        return pairs[:n]

    def elements(self):
        out = []
        for k, v in self._data.items():
            for _ in range(v):
                out.append(k)
        return out

    def total(self):
        return sum(self._data.values())

    def __add__(self, other):
        out = Counter()
        for k in list(self._data.keys()) + [k for k in other.keys() if k not in self._data]:
            v = self[k] + other[k]
            if v > 0:
                out._data[k] = v
        return out

    def __sub__(self, other):
        out = Counter()
        for k in self._data:
            v = self[k] - other[k]
            if v > 0:
                out._data[k] = v
        return out

    def __repr__(self):
        if not self._data:
            return "Counter()"
        inner = ", ".join(f"{k!r}: {v!r}" for k, v in self.most_common())
        return "Counter({" + inner + "})"


class defaultdict(_Mapping):
    def __init__(self, default_factory=None, *args, **kwargs):
        self.default_factory = default_factory
        self._data = dict(*args) if args else {}
        for k, v in kwargs.items():
            self._data[k] = v

    def __getitem__(self, key):
        if key in self._data:
            return self._data[key]
        if self.default_factory is None:
            raise KeyError(key)
        value = self.default_factory()
        self._data[key] = value
        return value

    def __repr__(self):
        return f"defaultdict({self.default_factory!r}, {self._data!r})"


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


def namedtuple(typename, field_names, defaults=None):
    if isinstance(field_names, str):
        field_names = field_names.replace(",", " ").split()
    fields = tuple(field_names)
    defaults = tuple(defaults or ())
    first_default = len(fields) - len(defaults)

    class _Record:
        _fields = fields

        def __init__(self, *args, **kwargs):
            if len(args) > len(fields):
                raise TypeError(f"{typename}() takes {len(fields)} arguments but {len(args)} were given")
            values = list(args)
            for i in range(len(args), len(fields)):
                name = fields[i]
                if name in kwargs:
                    values.append(kwargs.pop(name))
                elif i >= first_default:
                    values.append(defaults[i - first_default])
                else:
                    raise TypeError(f"{typename}() missing required argument '{name}'")
            if kwargs:
                raise TypeError(f"{typename}() got an unexpected keyword argument '{list(kwargs)[0]}'")
            object.__setattr__(self, "_values", tuple(values))
            for name, value in zip(fields, values):
                object.__setattr__(self, name, value)

        def __setattr__(self, name, value):
            raise AttributeError(f"can't set attribute '{name}' on a {typename}")

        def __getitem__(self, i):
            return self._values[i]

        def __len__(self):
            return len(fields)

        def __iter__(self):
            return list(self._values)

        def __eq__(self, other):
            if isinstance(other, tuple):
                return self._values == other
            return isinstance(other, _Record) and self._values == other._values

        def __hash__(self):
            return hash(self._values)

        def __repr__(self):
            inner = ", ".join(f"{n}={v!r}" for n, v in zip(fields, self._values))
            return f"{typename}({inner})"

        def _asdict(self):
            return dict(zip(fields, self._values))

        def _replace(self, **changes):
            values = dict(zip(fields, self._values))
            for k, v in changes.items():
                if k not in values:
                    raise ValueError(f"Got unexpected field name: '{k}'")
                values[k] = v
            return _Record(**values)

    _Record.__qualname__ = typename
    return _Record
