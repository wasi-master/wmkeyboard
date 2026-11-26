"""functools: reduce, partial, wraps, lru_cache, cache, cmp_to_key,
total_ordering."""

_MISSING = object()


def reduce(function, iterable, initial=_MISSING):
    items = list(iterable)
    if initial is _MISSING:
        if not items:
            raise TypeError("reduce() of empty iterable with no initial value")
        acc = items[0]
        items = items[1:]
    else:
        acc = initial
    for x in items:
        acc = function(acc, x)
    return acc


class partial:
    def __init__(self, func, *args, **kwargs):
        self.func = func
        self.args = args
        self.keywords = kwargs

    def __call__(self, *args, **kwargs):
        merged = dict(self.keywords)
        merged.update(kwargs)
        return self.func(*self.args, *args, **merged)

    def __repr__(self):
        return f"functools.partial({self.func!r})"


WRAPPER_ASSIGNMENTS = ("__module__", "__name__", "__qualname__", "__doc__")
WRAPPER_UPDATES = ("__dict__",)


def update_wrapper(wrapper, wrapped, assigned=WRAPPER_ASSIGNMENTS, updated=WRAPPER_UPDATES):
    """Give wrapper the name and doc of wrapped, and __wrapped__ pointing at it."""
    for name in assigned:
        try:
            value = getattr(wrapped, name)
        except AttributeError:
            continue
        try:
            setattr(wrapper, name, value)
        except AttributeError:
            pass
    try:
        wrapper.__wrapped__ = wrapped
    except AttributeError:
        pass
    return wrapper


def wraps(wrapped, assigned=WRAPPER_ASSIGNMENTS, updated=WRAPPER_UPDATES):
    def decorator(wrapper):
        return update_wrapper(wrapper, wrapped, assigned, updated)

    return decorator


def _make_key(args, kwargs):
    if not kwargs:
        return args
    return args + tuple(sorted(kwargs.items()))


class _Cached:
    def __init__(self, func, maxsize):
        self.func = func
        self.__name__ = getattr(func, "__name__", "cached")
        self.__wrapped__ = func
        self.maxsize = maxsize
        self.cache = {}
        self.order = []
        self.hits = 0
        self.misses = 0

    def __call__(self, *args, **kwargs):
        key = _make_key(args, kwargs)
        if key in self.cache:
            self.hits += 1
            return self.cache[key]
        self.misses += 1
        value = self.func(*args, **kwargs)
        self.cache[key] = value
        if self.maxsize is not None:
            self.order.append(key)
            while len(self.order) > self.maxsize:
                old = self.order.pop(0)
                self.cache.pop(old, None)
        return value

    def cache_clear(self):
        self.cache = {}
        self.order = []
        self.hits = 0
        self.misses = 0

    def cache_info(self):
        return {"hits": self.hits, "misses": self.misses, "maxsize": self.maxsize, "currsize": len(self.cache)}


def lru_cache(maxsize=128):
    if callable(maxsize):
        return _Cached(maxsize, 128)

    def decorator(func):
        return _Cached(func, maxsize)

    return decorator


def cache(func):
    return _Cached(func, None)


def cached_property(func):
    name = "_cached_" + func.__name__

    def getter(self):
        if not hasattr(self, name):
            object.__setattr__(self, name, func(self))
        return getattr(self, name)

    return property(getter)


def cmp_to_key(cmp):
    class K:
        def __init__(self, obj):
            self.obj = obj

        def __lt__(self, other):
            return cmp(self.obj, other.obj) < 0

        def __gt__(self, other):
            return cmp(self.obj, other.obj) > 0

        def __eq__(self, other):
            return cmp(self.obj, other.obj) == 0

        def __le__(self, other):
            return cmp(self.obj, other.obj) <= 0

        def __ge__(self, other):
            return cmp(self.obj, other.obj) >= 0

    return K


def _gt_from_lt(self, other):
    r = type(self).__lt__(self, other)
    if r is NotImplemented:
        return r
    return not r and self != other


def _le_from_lt(self, other):
    r = type(self).__lt__(self, other)
    if r is NotImplemented:
        return r
    return r or self == other


def _ge_from_lt(self, other):
    r = type(self).__lt__(self, other)
    if r is NotImplemented:
        return r
    return not r


def _ge_from_le(self, other):
    r = type(self).__le__(self, other)
    if r is NotImplemented:
        return r
    return not r or self == other


def _lt_from_le(self, other):
    r = type(self).__le__(self, other)
    if r is NotImplemented:
        return r
    return r and self != other


def _gt_from_le(self, other):
    r = type(self).__le__(self, other)
    if r is NotImplemented:
        return r
    return not r


def _lt_from_gt(self, other):
    r = type(self).__gt__(self, other)
    if r is NotImplemented:
        return r
    return not r and self != other


def _ge_from_gt(self, other):
    r = type(self).__gt__(self, other)
    if r is NotImplemented:
        return r
    return r or self == other


def _le_from_gt(self, other):
    r = type(self).__gt__(self, other)
    if r is NotImplemented:
        return r
    return not r


def _le_from_ge(self, other):
    r = type(self).__ge__(self, other)
    if r is NotImplemented:
        return r
    return not r or self == other


def _gt_from_ge(self, other):
    r = type(self).__ge__(self, other)
    if r is NotImplemented:
        return r
    return r and self != other


def _lt_from_ge(self, other):
    r = type(self).__ge__(self, other)
    if r is NotImplemented:
        return r
    return not r


_convert = {
    "__lt__": [("__gt__", _gt_from_lt), ("__le__", _le_from_lt), ("__ge__", _ge_from_lt)],
    "__le__": [("__ge__", _ge_from_le), ("__lt__", _lt_from_le), ("__gt__", _gt_from_le)],
    "__gt__": [("__lt__", _lt_from_gt), ("__ge__", _ge_from_gt), ("__le__", _le_from_gt)],
    "__ge__": [("__le__", _le_from_ge), ("__gt__", _gt_from_ge), ("__lt__", _lt_from_ge)],
}


def total_ordering(cls):
    """The comparisons a class lacks, made from the one it has, as CPython's
    does: one it inherits (from tuple, say) counts."""
    roots = {op for op in _convert if getattr(cls, op, None) is not getattr(object, op, None)}
    if not roots:
        raise ValueError("must define at least one ordering operation: < > <= >=")
    root = max(roots)
    for name, func in _convert[root]:
        if name not in roots:
            func.__name__ = name
            setattr(cls, name, func)
    return cls
