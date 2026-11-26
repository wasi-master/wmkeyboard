"""itertools: iterators that hand out one item at a time, as CPython's do.
Each follows the "roughly equivalent" code in Python's documentation. Bad
arguments are refused when the call is made, not at the first next()."""

class count:
    def __init__(self, start=0, step=1):
        for v in (start, step):
            if isinstance(v, (str, bytes, list, tuple)) or not (isinstance(v, (int, float)) or hasattr(v, "__add__")):
                raise TypeError("a number is required")
        self._next = start
        self._step = step

    def __iter__(self):
        return self

    def __next__(self):
        value = self._next
        self._next = value + self._step
        return value

    def __repr__(self):
        if self._step == 1 and not isinstance(self._step, float):
            return f"count({self._next!r})"
        return f"count({self._next!r}, {self._step!r})"


class cycle:
    """Each item as the iterable gives it, then the saved ones over again."""

    def __init__(self, iterable):
        self._it = iter(iterable)
        self._saved = []
        self._at = -1

    def __iter__(self):
        return self

    def __next__(self):
        if self._it is not None:
            try:
                item = next(self._it)
            except StopIteration:
                self._it = None
            else:
                self._saved.append(item)
                return item
        if not self._saved:
            raise StopIteration
        self._at = (self._at + 1) % len(self._saved)
        return self._saved[self._at]


class repeat:
    def __init__(self, object, times=None):
        self._obj = object
        self._left = None if times is None else max(times, 0)

    def __iter__(self):
        return self

    def __next__(self):
        if self._left is None:
            return self._obj
        if self._left <= 0:
            raise StopIteration
        self._left -= 1
        return self._obj

    def __length_hint__(self):
        if self._left is None:
            raise TypeError("len() of unsized object")
        return self._left

    def __repr__(self):
        if self._left is None:
            return f"repeat({self._obj!r})"
        return f"repeat({self._obj!r}, {self._left})"


def _chain(iterables):
    for it in iterables:
        yield from it


def chain(*iterables):
    return _chain(iterables)


def _from_iterable(iterables):
    return _chain(iterables)


chain.from_iterable = _from_iterable


def _accumulate(iterable, func, initial):
    it = iter(iterable)
    total = initial
    if initial is None:
        try:
            total = next(it)
        except StopIteration:
            return
    yield total
    for element in it:
        total = total + element if func is None else func(total, element)
        yield total


def accumulate(iterable, func=None, *, initial=None):
    return _accumulate(iterable, func, initial)


def _batched(iterable, n, strict):
    it = iter(iterable)
    while True:
        batch = tuple(islice(it, n))
        if not batch:
            return
        if strict and len(batch) != n:
            raise ValueError("batched(): incomplete batch")
        yield batch


def batched(iterable, n, *, strict=False):
    if n < 1:
        raise ValueError("n must be at least one")
    return _batched(iterable, n, strict)


def _compress(data, selectors):
    for d, s in zip(data, selectors):
        if s:
            yield d


def compress(data, selectors):
    return _compress(data, selectors)


def _dropwhile(predicate, iterable):
    it = iter(iterable)
    for x in it:
        if not predicate(x):
            yield x
            break
    yield from it


def dropwhile(predicate, iterable):
    return _dropwhile(predicate, iterable)


def _filterfalse(predicate, iterable):
    if predicate is None:
        predicate = bool
    for x in iterable:
        if not predicate(x):
            yield x


def filterfalse(predicate, iterable):
    return _filterfalse(predicate, iterable)


class groupby:
    """Runs of equal keys: each (key, group), where the group is an iterator
    over the run that stops being any use once groupby moves past it."""

    def __init__(self, iterable, key=None):
        self._keyfunc = key if key is not None else (lambda x: x)
        self._it = iter(iterable)
        self._tgtkey = self._currkey = self._currvalue = object()
        self._id = object()

    def __iter__(self):
        return self

    def __next__(self):
        self._id = object()
        while self._currkey == self._tgtkey:
            self._currvalue = next(self._it)
            self._currkey = self._keyfunc(self._currvalue)
        self._tgtkey = self._currkey
        return (self._currkey, self._grouper(self._tgtkey, self._id))

    def _grouper(self, tgtkey, id):
        while self._id is id and self._currkey == tgtkey:
            yield self._currvalue
            try:
                self._currvalue = next(self._it)
            except StopIteration:
                return
            self._currkey = self._keyfunc(self._currvalue)


def _islice(iterable, start, stop, step):
    it = iter(iterable)
    # Taken one at a time, so an endless iterator gives up only what is asked for.
    i = 0
    wanted = start
    while stop is None or i < stop:
        try:
            item = next(it)
        except StopIteration:
            return
        if i == wanted:
            yield item
            wanted += step
        i += 1


def islice(iterable, *args):
    s = slice(*args)
    start, stop, step = s.start, s.stop, s.step
    if stop is not None and (not isinstance(stop, int) or stop < 0):
        raise ValueError("Stop argument for islice() must be None or an integer: 0 <= x <= sys.maxsize.")
    start = 0 if start is None else start
    if not isinstance(start, int) or start < 0:
        raise ValueError("Indices for islice() must be None or an integer: 0 <= x <= sys.maxsize.")
    step = 1 if step is None else step
    if not isinstance(step, int) or step < 1:
        raise ValueError("Step for islice() must be a positive integer or None.")
    return _islice(iterable, start, stop, step)


def _pairwise(iterable):
    it = iter(iterable)
    try:
        a = next(it)
    except StopIteration:
        return
    for b in it:
        yield a, b
        a = b


def pairwise(iterable):
    return _pairwise(iterable)


def _starmap(function, iterable):
    for args in iterable:
        yield function(*args)


def starmap(function, iterable):
    return _starmap(function, iterable)


def _takewhile(predicate, iterable):
    for x in iterable:
        if not predicate(x):
            return
        yield x


def takewhile(predicate, iterable):
    return _takewhile(predicate, iterable)


class _TeeLink:
    __slots__ = ("value", "next")

    def __init__(self):
        self.value = None
        self.next = None


class _Tee:
    """One of tee's iterators: they share a linked list of what the source
    has given, so each sees every item once, at its own pace."""

    def __init__(self, it, link):
        self._it = it
        self._link = link

    def __iter__(self):
        return self

    def __next__(self):
        link = self._link
        if link.next is None:
            link.value = next(self._it)
            link.next = _TeeLink()
        self._link = link.next
        return link.value

    def __copy__(self):
        return _Tee(self._it, self._link)


def tee(iterable, n=2):
    if n < 0:
        raise ValueError("n must be >= 0")
    it = iter(iterable)
    if isinstance(it, _Tee):
        return tuple([it] + [it.__copy__() for _ in range(n - 1)]) if n else ()
    link = _TeeLink()
    return tuple(_Tee(it, link) for _ in range(n))


def _zip_longest(iterators, fillvalue):
    num_active = len(iterators)
    if not num_active:
        return
    while True:
        values = []
        for i, it in enumerate(iterators):
            try:
                value = next(it)
            except StopIteration:
                num_active -= 1
                if not num_active:
                    return
                iterators[i] = repeat(fillvalue)
                value = fillvalue
            values.append(value)
        yield tuple(values)


def zip_longest(*iterables, fillvalue=None):
    return _zip_longest([iter(it) for it in iterables], fillvalue)


def _product(pools):
    n = len(pools)
    if any(len(p) == 0 for p in pools):
        return
    indices = [0] * n
    yield tuple(p[0] for p in pools)
    while True:
        for i in reversed(range(n)):
            indices[i] += 1
            if indices[i] < len(pools[i]):
                break
            indices[i] = 0
        else:
            return
        yield tuple(pools[k][indices[k]] for k in range(n))


def product(*iterables, repeat=1):
    if repeat < 0:
        raise ValueError("repeat argument cannot be negative")
    pools = [tuple(pool) for pool in iterables] * repeat
    return _product(pools)


def _permutations(pool, r):
    n = len(pool)
    if r > n:
        return
    indices = list(range(n))
    cycles = list(range(n, n - r, -1))
    yield tuple(pool[i] for i in indices[:r])
    while n:
        for i in reversed(range(r)):
            cycles[i] -= 1
            if cycles[i] == 0:
                indices[i:] = indices[i + 1:] + indices[i:i + 1]
                cycles[i] = n - i
            else:
                j = cycles[i]
                indices[i], indices[-j] = indices[-j], indices[i]
                yield tuple(pool[i] for i in indices[:r])
                break
        else:
            return


def permutations(iterable, r=None):
    pool = tuple(iterable)
    r = len(pool) if r is None else r
    if r < 0:
        raise ValueError("r must be non-negative")
    return _permutations(pool, r)


def _combinations(pool, r):
    n = len(pool)
    if r > n:
        return
    indices = list(range(r))
    yield tuple(pool[i] for i in indices)
    while True:
        for i in reversed(range(r)):
            if indices[i] != i + n - r:
                break
        else:
            return
        indices[i] += 1
        for j in range(i + 1, r):
            indices[j] = indices[j - 1] + 1
        yield tuple(pool[i] for i in indices)


def combinations(iterable, r):
    if r < 0:
        raise ValueError("r must be non-negative")
    return _combinations(tuple(iterable), r)


def _combinations_with_replacement(pool, r):
    n = len(pool)
    if not n and r:
        return
    indices = [0] * r
    yield tuple(pool[i] for i in indices)
    while True:
        for i in reversed(range(r)):
            if indices[i] != n - 1:
                break
        else:
            return
        indices[i:] = [indices[i] + 1] * (r - i)
        yield tuple(pool[i] for i in indices)


def combinations_with_replacement(iterable, r):
    if r < 0:
        raise ValueError("r must be non-negative")
    return _combinations_with_replacement(tuple(iterable), r)
