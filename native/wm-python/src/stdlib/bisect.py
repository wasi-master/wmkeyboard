"""bisect: binary search over a sorted list, and insertion keeping it sorted."""


def bisect_right(a, x, lo=0, hi=None, *, key=None):
    if lo < 0:
        raise ValueError("lo must be non-negative")
    if hi is None:
        hi = len(a)
    while lo < hi:
        mid = (lo + hi) // 2
        if x < (a[mid] if key is None else key(a[mid])):
            hi = mid
        else:
            lo = mid + 1
    return lo


def bisect_left(a, x, lo=0, hi=None, *, key=None):
    if lo < 0:
        raise ValueError("lo must be non-negative")
    if hi is None:
        hi = len(a)
    while lo < hi:
        mid = (lo + hi) // 2
        if (a[mid] if key is None else key(a[mid])) < x:
            lo = mid + 1
        else:
            hi = mid
    return lo


def insort_right(a, x, lo=0, hi=None, *, key=None):
    lo = bisect_right(a, x if key is None else key(x), lo, hi, key=key)
    a.insert(lo, x)


def insort_left(a, x, lo=0, hi=None, *, key=None):
    lo = bisect_left(a, x if key is None else key(x), lo, hi, key=key)
    a.insert(lo, x)


bisect = bisect_right
insort = insort_right
