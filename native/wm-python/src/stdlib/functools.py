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


def wraps(wrapped):
    def decorator(wrapper):
        return wrapper

    return decorator


def update_wrapper(wrapper, wrapped):
    return wrapper


def _make_key(args, kwargs):
    if not kwargs:
        return args
    return args + tuple(sorted(kwargs.items()))


class _Cached:
    def __init__(self, func, maxsize):
        self.func = func
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


def total_ordering(cls):
    own = vars(cls)
    if "__lt__" in own:
        if "__gt__" not in own:
            cls.__gt__ = lambda self, other: not (self < other) and self != other
        if "__le__" not in own:
            cls.__le__ = lambda self, other: self < other or self == other
        if "__ge__" not in own:
            cls.__ge__ = lambda self, other: not (self < other)
    elif "__gt__" in own:
        if "__lt__" not in own:
            cls.__lt__ = lambda self, other: not (self > other) and self != other
        if "__ge__" not in own:
            cls.__ge__ = lambda self, other: self > other or self == other
        if "__le__" not in own:
            cls.__le__ = lambda self, other: not (self > other)
    else:
        raise ValueError("total_ordering needs at least __lt__ or __gt__")
    return cls
