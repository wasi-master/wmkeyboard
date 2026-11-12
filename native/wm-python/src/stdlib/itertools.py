"""itertools, eagerly: each function returns a list rather than a lazy
iterator, since this interpreter has no generators. The infinite ones
(count, cycle, repeat without a count) are left out."""


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
    items = list(iterable)
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
    if stop is None:
        stop = len(items)
    return items[start:stop:step]


def repeat(obj, times):
    return [obj] * times


def starmap(function, iterable):
    return [function(*args) for args in iterable]


def takewhile(predicate, iterable):
    out = []
    for x in iterable:
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
