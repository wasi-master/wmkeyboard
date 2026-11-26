"""weakref: references to objects, which here keep the object alive.

There is no garbage collector to watch an object die, so a ref always
answers with its object, the weak dictionaries and WeakSet keep entries
until they are removed, and a finalize callback runs when called (or
detached and never). Code that uses weak references as caches still works.
"""

from collections.abc import MutableMapping, MutableSet


class ReferenceType:
    def __init__(self, ob, callback=None):
        self._ob = ob
        self.__callback__ = callback

    def __call__(self):
        return self._ob

    def __eq__(self, other):
        if isinstance(other, ReferenceType):
            return self._ob == other._ob
        return NotImplemented

    def __ne__(self, other):
        result = self.__eq__(other)
        return result if result is NotImplemented else not result

    def __hash__(self):
        return hash(self._ob)

    def __repr__(self):
        return f"<weakref; to '{type(self._ob).__name__}'>"

    __class_getitem__ = classmethod(lambda cls, item: cls)


ref = ReferenceType


class WeakMethod(ReferenceType):
    def __init__(self, meth, callback=None):
        if not hasattr(meth, "__self__") or not hasattr(meth, "__func__"):
            raise TypeError(f"argument should be a bound method, not {type(meth).__name__}")
        super().__init__(meth, callback)


class ProxyType:
    def __init__(self, ob, callback=None):
        object.__setattr__(self, "_ob", ob)

    def __getattr__(self, name):
        return getattr(self._ob, name)

    def __setattr__(self, name, value):
        setattr(self._ob, name, value)

    def __delattr__(self, name):
        delattr(self._ob, name)

    def __call__(self, *args, **kwargs):
        return self._ob(*args, **kwargs)

    def __len__(self):
        return len(self._ob)

    def __iter__(self):
        return iter(self._ob)

    def __getitem__(self, key):
        return self._ob[key]

    def __setitem__(self, key, value):
        self._ob[key] = value

    def __delitem__(self, key):
        del self._ob[key]

    def __contains__(self, item):
        return item in self._ob

    def __bool__(self):
        return bool(self._ob)

    def __eq__(self, other):
        return self._ob == other

    def __str__(self):
        return str(self._ob)

    def __repr__(self):
        return f"<weakproxy; to '{type(self._ob).__name__}'>"


CallableProxyType = ProxyType
ProxyTypes = (ProxyType, CallableProxyType)


def proxy(ob, callback=None):
    return ProxyType(ob, callback)


def getweakrefcount(ob):
    return 0


def getweakrefs(ob):
    return []


class WeakValueDictionary(MutableMapping):
    def __init__(self, other=(), /, **kwargs):
        self.data = {}
        self.update(other, **kwargs)

    def __getitem__(self, key):
        return self.data[key]

    def __setitem__(self, key, value):
        self.data[key] = value

    def __delitem__(self, key):
        del self.data[key]

    def __iter__(self):
        return iter(list(self.data))

    def __len__(self):
        return len(self.data)

    def __contains__(self, key):
        return key in self.data

    def __repr__(self):
        return f"<{type(self).__name__}>"

    def copy(self):
        return type(self)(self.data)

    __copy__ = copy

    def itervaluerefs(self):
        return (ref(v) for v in self.data.values())

    def valuerefs(self):
        return [ref(v) for v in self.data.values()]


class WeakKeyDictionary(WeakValueDictionary):
    def keyrefs(self):
        return [ref(k) for k in self.data]


class WeakSet(MutableSet):
    def __init__(self, data=None):
        self.data = set()
        if data is not None:
            for item in data:
                self.data.add(item)

    def __contains__(self, item):
        return item in self.data

    def __iter__(self):
        return iter(list(self.data))

    def __len__(self):
        return len(self.data)

    def add(self, item):
        self.data.add(item)

    def discard(self, item):
        self.data.discard(item)

    def copy(self):
        return WeakSet(self.data)

    def __repr__(self):
        return f"<WeakSet {self.data!r}>"


class finalize:
    """Calls func(*args, **kwargs) once, when called; nothing here frees
    objects, so it does not run on its own, and atexit is kept for show."""

    _registry = {}

    def __init__(self, obj, func, /, *args, **kwargs):
        self._info = (obj, func, args, kwargs)
        self.atexit = True

    def __call__(self, _=None):
        info, self._info = self._info, None
        if info is not None:
            obj, func, args, kwargs = info
            return func(*args, **kwargs)

    def detach(self):
        info, self._info = self._info, None
        if info is not None:
            obj, func, args, kwargs = info
            return (obj, func, args, kwargs)

    def peek(self):
        if self._info is not None:
            obj, func, args, kwargs = self._info
            return (obj, func, args, kwargs)

    @property
    def alive(self):
        return self._info is not None
