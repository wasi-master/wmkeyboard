"""itertools, mostly eagerly: each function returns a list rather than a
lazy iterator, since this interpreter has no generators. The infinite ones
(count, cycle, repeat without a count) are iterator objects; islice,
takewhile, next() and zip() take from them only what they need."""


class count:
    def __init__(self, start=0, step=1):
        self._next = start
        self._step = step

    def __iter__(self):
        return self

    def __next__(self):
        value = self._next
        self._next = value + self._step
        return value

    def __repr__(self):
        if self._step == 1:
            return f"count({self._next!r})"
        return f"count({self._next!r}, {self._step!r})"


class cycle:
    def __init__(self, iterable):
        self._items = list(iterable)
        self._at = 0

    def __iter__(self):
        return self

    def __next__(self):
        if not self._items:
            raise StopIteration
        value = self._items[self._at]
        self._at = (self._at + 1) % len(self._items)
        return value


class _Repeat:
    def __init__(self, obj):
        self._obj = obj

    def __iter__(self):
        return self

    def __next__(self):
        return self._obj

    def __repr__(self):
        return f"repeat({self._obj!r})"


class chain:
    def __init__(self, *iterables):
        self._items = []
        for it in iterables:
            self._items.extend(list(it))

    def __iter__(self):
        return self._items

    def __len__(self):
        return len(self._items)

    @staticmethod
    def from_iterable(iterables):
        return chain(*list(iterables))


def product(*iterables, repeat=1):
    pools = [list(p) for p in iterables] * repeat
    out = [()]
    for pool in pools:
        out = [x + (y,) for x in out for y in pool]
    return out


def permutations(iterable, r=None):
    pool = list(iterable)
    n = len(pool)
    if r is None:
        r = n
    if r > n:
        return []
    out = []

    def build(prefix, used):
        if len(prefix) == r:
            out.append(tuple(prefix))
            return
        for i in range(n):
            if not used[i]:
                used[i] = True
                prefix.append(pool[i])
                build(prefix, used)
                prefix.pop()
                used[i] = False

    build([], [False] * n)
    return out


def combinations(iterable, r):
    pool = list(iterable)
    n = len(pool)
    out = []

    def build(start, prefix):
        if len(prefix) == r:
            out.append(tuple(prefix))
            return
        for i in range(start, n):
            prefix.append(pool[i])
            build(i + 1, prefix)
            prefix.pop()

    build(0, [])
    return out


def combinations_with_replacement(iterable, r):
    pool = list(iterable)
    n = len(pool)
    out = []

    def build(start, prefix):
        if len(prefix) == r:
            out.append(tuple(prefix))
            return
        for i in range(start, n):
            prefix.append(pool[i])
            build(i, prefix)
            prefix.pop()

    build(0, [])
    return out


def groupby(iterable, key=None):
    out = []
    current = None
    group = None
    for item in iterable:
        k = key(item) if key is not None else item
        if group is None or k != current:
            group = []
            current = k
            out.append((k, group))
        group.append(item)
    return out


def accumulate(iterable, func=None, *, initial=None):
    out = []
    acc = initial
    if initial is not None:
        out.append(initial)
    first = initial is None
    for x in iterable:
        if first:
            acc = x
            first = False
        elif func is None:
            acc = acc + x
        else:
            acc = func(acc, x)
        out.append(acc)
    return out


def zip_longest(*iterables, fillvalue=None):
    lists = [list(it) for it in iterables]
    n = max([len(x) for x in lists], default=0)
    return [tuple(x[i] if i < len(x) else fillvalue for x in lists) for i in range(n)]


def islice(iterable, *args):
    if len(args) == 1:
        start, stop, step = 0, args[0], 1
    elif len(args) == 2:
        start, stop, step = args[0], args[1], 1
    else:
        start, stop, step = args[0], args[1], args[2]
    if start is None:
        start = 0
    if step is None:
        step = 1
    if start < 0 or (stop is not None and stop < 0) or step < 1:
        raise ValueError("indices for islice() must be None or non-negative integers, and step at least 1")
    # Taken one at a time, so an endless iterator gives up only what is asked for.
    it = iter(iterable)
    out = []
    i = 0
    wanted = start
    while stop is None or i < stop:
        try:
            item = next(it)
        except StopIteration:
            break
        if i == wanted:
            out.append(item)
            wanted += step
        i += 1
    return out


def repeat(obj, times=None):
    if times is None:
        return _Repeat(obj)
    return [obj] * max(times, 0)


def starmap(function, iterable):
    return [function(*args) for args in iterable]


def takewhile(predicate, iterable):
    it = iter(iterable)
    out = []
    while True:
        try:
            x = next(it)
        except StopIteration:
            break
        if not predicate(x):
            break
        out.append(x)
    return out


def dropwhile(predicate, iterable):
    out = []
    dropping = True
    for x in iterable:
        if dropping and predicate(x):
            continue
        dropping = False
        out.append(x)
    return out


def filterfalse(predicate, iterable):
    if predicate is None:
        return [x for x in iterable if not x]
    return [x for x in iterable if not predicate(x)]


def compress(data, selectors):
    return [d for d, s in zip(data, selectors) if s]


def pairwise(iterable):
    items = list(iterable)
    return [(items[i], items[i + 1]) for i in range(len(items) - 1)]


def batched(iterable, n):
    if n < 1:
        raise ValueError("n must be at least one")
    items = list(iterable)
    return [tuple(items[i:i + n]) for i in range(0, len(items), n)]
