"""collections: Counter, defaultdict, OrderedDict, deque, namedtuple,
ChainMap, UserDict, UserList and UserString.

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
        # The module that called namedtuple(), as CPython finds it.
        "__module__": module if module is not None else (_sys._getframemodulename(1) or "__main__"),
    }
    for index, name in enumerate(fields):
        namespace[name] = property(lambda self, index=index: self[index], doc=f"Alias for field number {index}")
    return type(typename, (tuple,), namespace)


# ChainMap, UserDict, UserList and UserString below are CPython 3.13's, from
# Lib/collections/__init__.py. Python Software Foundation License Version 2;
# the text ships as app/src/main/assets/licenses/psf-2.0.txt.

import collections.abc as _collections_abc
from reprlib import recursive_repr as _recursive_repr
import sys as _sys


class ChainMap(_collections_abc.MutableMapping):
    ''' A ChainMap groups multiple dicts (or other mappings) together
    to create a single, updateable view.

    The underlying mappings are stored in a list.  That list is public and can
    be accessed or updated using the *maps* attribute.  There is no other
    state.

    Lookups search the underlying mappings successively until a key is found.
    In contrast, writes, updates, and deletions only operate on the first
    mapping.

    '''

    def __init__(self, *maps):
        '''Initialize a ChainMap by setting *maps* to the given mappings.
        If no mappings are provided, a single empty dictionary is used.

        '''
        self.maps = list(maps) or [{}]          # always at least one map

    def __missing__(self, key):
        raise KeyError(key)

    def __getitem__(self, key):
        for mapping in self.maps:
            try:
                return mapping[key]             # can't use 'key in mapping' with defaultdict
            except KeyError:
                pass
        return self.__missing__(key)            # support subclasses that define __missing__

    def get(self, key, default=None):
        return self[key] if key in self else default

    def __len__(self):
        return len(set().union(*self.maps))     # reuses stored hash values if possible

    def __iter__(self):
        d = {}
        for mapping in map(dict.fromkeys, reversed(self.maps)):
            d |= mapping                        # reuses stored hash values if possible
        return iter(d)

    def __contains__(self, key):
        return any(key in m for m in self.maps)

    def __bool__(self):
        return any(self.maps)

    @_recursive_repr()
    def __repr__(self):
        return f'{self.__class__.__name__}({", ".join(map(repr, self.maps))})'

    @classmethod
    def fromkeys(cls, iterable, value=None, /):
        'Create a new ChainMap with keys from iterable and values set to value.'
        return cls(dict.fromkeys(iterable, value))

    def copy(self):
        'New ChainMap or subclass with a new copy of maps[0] and refs to maps[1:]'
        return self.__class__(self.maps[0].copy(), *self.maps[1:])

    __copy__ = copy

    def new_child(self, m=None, **kwargs):      # like Django's Context.push()
        '''New ChainMap with a new map followed by all previous maps.
        If no map is provided, an empty dict is used.
        Keyword arguments update the map or new empty dict.
        '''
        if m is None:
            m = kwargs
        elif kwargs:
            m.update(kwargs)
        return self.__class__(m, *self.maps)

    @property
    def parents(self):                          # like Django's Context.pop()
        'New ChainMap from maps[1:].'
        return self.__class__(*self.maps[1:])

    def __setitem__(self, key, value):
        self.maps[0][key] = value

    def __delitem__(self, key):
        try:
            del self.maps[0][key]
        except KeyError:
            raise KeyError(f'Key not found in the first mapping: {key!r}')

    def popitem(self):
        'Remove and return an item pair from maps[0]. Raise KeyError is maps[0] is empty.'
        try:
            return self.maps[0].popitem()
        except KeyError:
            raise KeyError('No keys found in the first mapping.')

    def pop(self, key, *args):
        'Remove *key* from maps[0] and return its value. Raise KeyError if *key* not in maps[0].'
        try:
            return self.maps[0].pop(key, *args)
        except KeyError:
            raise KeyError(f'Key not found in the first mapping: {key!r}')

    def clear(self):
        'Clear maps[0], leaving maps[1:] intact.'
        self.maps[0].clear()

    def __ior__(self, other):
        self.maps[0].update(other)
        return self

    def __or__(self, other):
        if not isinstance(other, _collections_abc.Mapping):
            return NotImplemented
        m = self.copy()
        m.maps[0].update(other)
        return m

    def __ror__(self, other):
        if not isinstance(other, _collections_abc.Mapping):
            return NotImplemented
        m = dict(other)
        for child in reversed(self.maps):
            m.update(child)
        return self.__class__(m)


class UserDict(_collections_abc.MutableMapping):

    # Start by filling-out the abstract methods
    def __init__(self, dict=None, /, **kwargs):
        self.data = {}
        if dict is not None:
            self.update(dict)
        if kwargs:
            self.update(kwargs)

    def __len__(self):
        return len(self.data)

    def __getitem__(self, key):
        if key in self.data:
            return self.data[key]
        if hasattr(self.__class__, "__missing__"):
            return self.__class__.__missing__(self, key)
        raise KeyError(key)

    def __setitem__(self, key, item):
        self.data[key] = item

    def __delitem__(self, key):
        del self.data[key]

    def __iter__(self):
        return iter(self.data)

    # Modify __contains__ and get() to work like dict
    # does when __missing__ is present.
    def __contains__(self, key):
        return key in self.data

    def get(self, key, default=None):
        if key in self:
            return self[key]
        return default


    # Now, add the methods in dicts but not in MutableMapping
    def __repr__(self):
        return repr(self.data)

    def __or__(self, other):
        if isinstance(other, UserDict):
            return self.__class__(self.data | other.data)
        if isinstance(other, dict):
            return self.__class__(self.data | other)
        return NotImplemented

    def __ror__(self, other):
        if isinstance(other, UserDict):
            return self.__class__(other.data | self.data)
        if isinstance(other, dict):
            return self.__class__(other | self.data)
        return NotImplemented

    def __ior__(self, other):
        if isinstance(other, UserDict):
            self.data |= other.data
        else:
            self.data |= other
        return self

    def __copy__(self):
        inst = self.__class__.__new__(self.__class__)
        inst.__dict__.update(self.__dict__)
        # Create a copy and avoid triggering descriptors
        inst.__dict__["data"] = self.__dict__["data"].copy()
        return inst

    def copy(self):
        if self.__class__ is UserDict:
            return UserDict(self.data.copy())
        import copy
        data = self.data
        try:
            self.data = {}
            c = copy.copy(self)
        finally:
            self.data = data
        c.update(self)
        return c

    @classmethod
    def fromkeys(cls, iterable, value=None):
        d = cls()
        for key in iterable:
            d[key] = value
        return d


################################################################################
### UserList
################################################################################

class UserList(_collections_abc.MutableSequence):
    """A more or less complete user-defined wrapper around list objects."""

    def __init__(self, initlist=None):
        self.data = []
        if initlist is not None:
            # XXX should this accept an arbitrary sequence?
            if type(initlist) == type(self.data):
                self.data[:] = initlist
            elif isinstance(initlist, UserList):
                self.data[:] = initlist.data[:]
            else:
                self.data = list(initlist)

    def __repr__(self):
        return repr(self.data)

    def __lt__(self, other):
        return self.data < self.__cast(other)

    def __le__(self, other):
        return self.data <= self.__cast(other)

    def __eq__(self, other):
        return self.data == self.__cast(other)

    def __gt__(self, other):
        return self.data > self.__cast(other)

    def __ge__(self, other):
        return self.data >= self.__cast(other)

    def __cast(self, other):
        return other.data if isinstance(other, UserList) else other

    def __contains__(self, item):
        return item in self.data

    def __len__(self):
        return len(self.data)

    def __getitem__(self, i):
        if isinstance(i, slice):
            return self.__class__(self.data[i])
        else:
            return self.data[i]

    def __setitem__(self, i, item):
        self.data[i] = item

    def __delitem__(self, i):
        del self.data[i]

    def __add__(self, other):
        if isinstance(other, UserList):
            return self.__class__(self.data + other.data)
        elif isinstance(other, type(self.data)):
            return self.__class__(self.data + other)
        return self.__class__(self.data + list(other))

    def __radd__(self, other):
        if isinstance(other, UserList):
            return self.__class__(other.data + self.data)
        elif isinstance(other, type(self.data)):
            return self.__class__(other + self.data)
        return self.__class__(list(other) + self.data)

    def __iadd__(self, other):
        if isinstance(other, UserList):
            self.data += other.data
        elif isinstance(other, type(self.data)):
            self.data += other
        else:
            self.data += list(other)
        return self

    def __mul__(self, n):
        return self.__class__(self.data * n)

    __rmul__ = __mul__

    def __imul__(self, n):
        self.data *= n
        return self

    def __copy__(self):
        inst = self.__class__.__new__(self.__class__)
        inst.__dict__.update(self.__dict__)
        # Create a copy and avoid triggering descriptors
        inst.__dict__["data"] = self.__dict__["data"][:]
        return inst

    def append(self, item):
        self.data.append(item)

    def insert(self, i, item):
        self.data.insert(i, item)

    def pop(self, i=-1):
        return self.data.pop(i)

    def remove(self, item):
        self.data.remove(item)

    def clear(self):
        self.data.clear()

    def copy(self):
        return self.__class__(self)

    def count(self, item):
        return self.data.count(item)

    def index(self, item, *args):
        return self.data.index(item, *args)

    def reverse(self):
        self.data.reverse()

    def sort(self, /, *args, **kwds):
        self.data.sort(*args, **kwds)

    def extend(self, other):
        if isinstance(other, UserList):
            self.data.extend(other.data)
        else:
            self.data.extend(other)


################################################################################
### UserString
################################################################################

class UserString(_collections_abc.Sequence):

    def __init__(self, seq):
        if isinstance(seq, str):
            self.data = seq
        elif isinstance(seq, UserString):
            self.data = seq.data[:]
        else:
            self.data = str(seq)

    def __str__(self):
        return str(self.data)

    def __repr__(self):
        return repr(self.data)

    def __int__(self):
        return int(self.data)

    def __float__(self):
        return float(self.data)

    def __complex__(self):
        return complex(self.data)

    def __hash__(self):
        return hash(self.data)

    def __getnewargs__(self):
        return (self.data[:],)

    def __eq__(self, string):
        if isinstance(string, UserString):
            return self.data == string.data
        return self.data == string

    def __lt__(self, string):
        if isinstance(string, UserString):
            return self.data < string.data
        return self.data < string

    def __le__(self, string):
        if isinstance(string, UserString):
            return self.data <= string.data
        return self.data <= string

    def __gt__(self, string):
        if isinstance(string, UserString):
            return self.data > string.data
        return self.data > string

    def __ge__(self, string):
        if isinstance(string, UserString):
            return self.data >= string.data
        return self.data >= string

    def __contains__(self, char):
        if isinstance(char, UserString):
            char = char.data
        return char in self.data

    def __len__(self):
        return len(self.data)

    def __getitem__(self, index):
        return self.__class__(self.data[index])

    def __add__(self, other):
        if isinstance(other, UserString):
            return self.__class__(self.data + other.data)
        elif isinstance(other, str):
            return self.__class__(self.data + other)
        return self.__class__(self.data + str(other))

    def __radd__(self, other):
        if isinstance(other, str):
            return self.__class__(other + self.data)
        return self.__class__(str(other) + self.data)

    def __mul__(self, n):
        return self.__class__(self.data * n)

    __rmul__ = __mul__

    def __mod__(self, args):
        return self.__class__(self.data % args)

    def __rmod__(self, template):
        return self.__class__(str(template) % self)

    # the following methods are defined in alphabetical order:
    def capitalize(self):
        return self.__class__(self.data.capitalize())

    def casefold(self):
        return self.__class__(self.data.casefold())

    def center(self, width, *args):
        return self.__class__(self.data.center(width, *args))

    def count(self, sub, start=0, end=_sys.maxsize):
        if isinstance(sub, UserString):
            sub = sub.data
        return self.data.count(sub, start, end)

    def removeprefix(self, prefix, /):
        if isinstance(prefix, UserString):
            prefix = prefix.data
        return self.__class__(self.data.removeprefix(prefix))

    def removesuffix(self, suffix, /):
        if isinstance(suffix, UserString):
            suffix = suffix.data
        return self.__class__(self.data.removesuffix(suffix))

    def encode(self, encoding='utf-8', errors='strict'):
        encoding = 'utf-8' if encoding is None else encoding
        errors = 'strict' if errors is None else errors
        return self.data.encode(encoding, errors)

    def endswith(self, suffix, start=0, end=_sys.maxsize):
        return self.data.endswith(suffix, start, end)

    def expandtabs(self, tabsize=8):
        return self.__class__(self.data.expandtabs(tabsize))

    def find(self, sub, start=0, end=_sys.maxsize):
        if isinstance(sub, UserString):
            sub = sub.data
        return self.data.find(sub, start, end)

    def format(self, /, *args, **kwds):
        return self.data.format(*args, **kwds)

    def format_map(self, mapping):
        return self.data.format_map(mapping)

    def index(self, sub, start=0, end=_sys.maxsize):
        return self.data.index(sub, start, end)

    def isalpha(self):
        return self.data.isalpha()

    def isalnum(self):
        return self.data.isalnum()

    def isascii(self):
        return self.data.isascii()

    def isdecimal(self):
        return self.data.isdecimal()

    def isdigit(self):
        return self.data.isdigit()

    def isidentifier(self):
        return self.data.isidentifier()

    def islower(self):
        return self.data.islower()

    def isnumeric(self):
        return self.data.isnumeric()

    def isprintable(self):
        return self.data.isprintable()

    def isspace(self):
        return self.data.isspace()

    def istitle(self):
        return self.data.istitle()

    def isupper(self):
        return self.data.isupper()

    def join(self, seq):
        return self.data.join(seq)

    def ljust(self, width, *args):
        return self.__class__(self.data.ljust(width, *args))

    def lower(self):
        return self.__class__(self.data.lower())

    def lstrip(self, chars=None):
        return self.__class__(self.data.lstrip(chars))

    maketrans = str.maketrans

    def partition(self, sep):
        return self.data.partition(sep)

    def replace(self, old, new, maxsplit=-1):
        if isinstance(old, UserString):
            old = old.data
        if isinstance(new, UserString):
            new = new.data
        return self.__class__(self.data.replace(old, new, maxsplit))

    def rfind(self, sub, start=0, end=_sys.maxsize):
        if isinstance(sub, UserString):
            sub = sub.data
        return self.data.rfind(sub, start, end)

    def rindex(self, sub, start=0, end=_sys.maxsize):
        return self.data.rindex(sub, start, end)

    def rjust(self, width, *args):
        return self.__class__(self.data.rjust(width, *args))

    def rpartition(self, sep):
        return self.data.rpartition(sep)

    def rstrip(self, chars=None):
        return self.__class__(self.data.rstrip(chars))

    def split(self, sep=None, maxsplit=-1):
        return self.data.split(sep, maxsplit)

    def rsplit(self, sep=None, maxsplit=-1):
        return self.data.rsplit(sep, maxsplit)

    def splitlines(self, keepends=False):
        return self.data.splitlines(keepends)

    def startswith(self, prefix, start=0, end=_sys.maxsize):
        return self.data.startswith(prefix, start, end)

    def strip(self, chars=None):
        return self.__class__(self.data.strip(chars))

    def swapcase(self):
        return self.__class__(self.data.swapcase())

    def title(self):
        return self.__class__(self.data.title())

    def translate(self, *args):
        return self.__class__(self.data.translate(*args))

    def upper(self):
        return self.__class__(self.data.upper())

    def zfill(self, width):
        return self.__class__(self.data.zfill(width))
