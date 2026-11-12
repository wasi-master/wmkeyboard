"""statistics: averages and spread of a list of numbers."""

import math


class StatisticsError(ValueError):
    pass


def _data(data, need=1, name="data"):
    items = list(data)
    if len(items) < need:
        if need == 1:
            raise StatisticsError(f"{name} requires at least one data point")
        raise StatisticsError(f"{name} requires at least {need} data points")
    return items


def mean(data):
    items = _data(data, 1, "mean")
    return sum(items) / len(items)


def fmean(data):
    return float(mean(data))


def geometric_mean(data):
    items = _data(data, 1, "geometric_mean")
    return math.exp(sum(math.log(x) for x in items) / len(items))


def harmonic_mean(data):
    items = _data(data, 1, "harmonic_mean")
    if any(x == 0 for x in items):
        return 0
    return len(items) / sum(1 / x for x in items)


def median(data):
    items = sorted(_data(data, 1, "median"))
    n = len(items)
    mid = n // 2
    if n % 2:
        return items[mid]
    return (items[mid - 1] + items[mid]) / 2


def median_low(data):
    items = sorted(_data(data, 1, "median_low"))
    n = len(items)
    return items[(n - 1) // 2]


def median_high(data):
    items = sorted(_data(data, 1, "median_high"))
    return items[len(items) // 2]


def mode(data):
    items = _data(data, 1, "mode")
    counts = {}
    for x in items:
        counts[x] = counts.get(x, 0) + 1
    best = None
    for x in items:
        if best is None or counts[x] > counts[best]:
            best = x
    return best


def multimode(data):
    items = list(data)
    counts = {}
    for x in items:
        counts[x] = counts.get(x, 0) + 1
    if not counts:
        return []
    top = max(counts.values())
    return [x for x in counts if counts[x] == top]


def _ss(items, centre):
    return sum((x - centre) ** 2 for x in items)


def pvariance(data, mu=None):
    items = _data(data, 1, "pvariance")
    centre = mean(items) if mu is None else mu
    return _ss(items, centre) / len(items)


def variance(data, xbar=None):
    items = _data(data, 2, "variance")
    centre = mean(items) if xbar is None else xbar
    return _ss(items, centre) / (len(items) - 1)


def pstdev(data, mu=None):
    return math.sqrt(pvariance(data, mu))


def stdev(data, xbar=None):
    return math.sqrt(variance(data, xbar))


def quantiles(data, n=4):
    items = sorted(_data(data, 2, "quantiles"))
    m = len(items)
    out = []
    for i in range(1, n):
        j = i * (m - 1) / n
        lo = int(math.floor(j))
        frac = j - lo
        hi = min(lo + 1, m - 1)
        out.append(items[lo] + (items[hi] - items[lo]) * frac)
    return out
