"""secrets: random tokens and numbers for passwords, keys and the like.

The bytes come from a generator keyed by the operating system's randomness,
apart from random's, which a seed can repeat.
"""

import base64

import _native

DEFAULT_ENTROPY = 32


def token_bytes(nbytes=None):
    if nbytes is None:
        nbytes = DEFAULT_ENTROPY
    return _native.urandom(nbytes)


def token_hex(nbytes=None):
    return token_bytes(nbytes).hex()


def token_urlsafe(nbytes=None):
    return base64.urlsafe_b64encode(token_bytes(nbytes)).rstrip(b"=").decode("ascii")


def randbits(k):
    if k < 0:
        raise ValueError("number of bits must be non-negative")
    if k == 0:
        return 0
    n = int.from_bytes(_native.urandom((k + 7) // 8), "big")
    return n >> ((k + 7) // 8 * 8 - k)


def randbelow(exclusive_upper_bound):
    if exclusive_upper_bound <= 0:
        raise ValueError("Upper bound must be positive.")
    k = exclusive_upper_bound.bit_length()
    while True:
        r = randbits(k)
        if r < exclusive_upper_bound:
            return r


def choice(seq):
    if not len(seq):
        raise IndexError("Cannot choose from an empty sequence")
    return seq[randbelow(len(seq))]


def compare_digest(a, b):
    if isinstance(a, str) and isinstance(b, str):
        if not (a.isascii() and b.isascii()):
            raise TypeError("comparing strings with non-ASCII characters is not supported")
        a, b = a.encode(), b.encode()
    elif isinstance(a, (bytes, bytearray)) and isinstance(b, (bytes, bytearray)):
        a, b = bytes(a), bytes(b)
    else:
        raise TypeError(f"unsupported operand types(s) or combination of types: '{type(a).__name__}' and '{type(b).__name__}'")
    diff = len(a) ^ len(b)
    for x, y in zip(a, b):
        diff |= x ^ y
    return diff == 0


class SystemRandom:
    """random's methods over the same source as the rest of this module."""

    def random(self):
        return randbits(53) / 9007199254740992

    def getrandbits(self, k):
        return randbits(k)

    def randrange(self, start, stop=None, step=1):
        if stop is None:
            start, stop = 0, start
        width = stop - start
        if step == 1:
            if width <= 0:
                raise ValueError(f"empty range in randrange({start}, {stop})")
            return start + randbelow(width)
        n = (width + step - 1) // step if step > 0 else (width + step + 1) // step
        if n <= 0:
            raise ValueError("empty range for randrange()")
        return start + step * randbelow(n)

    def randint(self, a, b):
        return self.randrange(a, b + 1)

    def choice(self, seq):
        return choice(seq)

    def choices(self, population, weights=None, *, cum_weights=None, k=1):
        n = len(population)
        if cum_weights is None and weights is None:
            return [population[randbelow(n)] for _ in range(k)]
        if cum_weights is None:
            cum_weights, total = [], 0
            for w in weights:
                total += w
                cum_weights.append(total)
        total = cum_weights[-1]
        out = []
        for _ in range(k):
            x = self.random() * total
            lo, hi = 0, n - 1
            while lo < hi:
                mid = (lo + hi) // 2
                if cum_weights[mid] <= x:
                    lo = mid + 1
                else:
                    hi = mid
            out.append(population[lo])
        return out

    def shuffle(self, x):
        for i in range(len(x) - 1, 0, -1):
            j = randbelow(i + 1)
            x[i], x[j] = x[j], x[i]

    def sample(self, population, k, *, counts=None):
        pool = list(population)
        if not 0 <= k <= len(pool):
            raise ValueError("Sample larger than population or is negative")
        self.shuffle(pool)
        return pool[:k]

    def uniform(self, a, b):
        return a + (b - a) * self.random()

    def seed(self, *args, **kwargs):
        pass


_sysrand = SystemRandom()
