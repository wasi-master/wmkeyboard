"""_random: the base class of random.Random, as CPython's
Modules/_randommodule.c makes it: a Mersenne Twister, worked natively by
_mt, with random(), getrandbits(), seed(), getstate() and setstate()."""

import _mt


class Random:
    """Random() -> create a random number generator with its own internal state."""

    def __new__(cls, *args, **kwargs):
        if cls is Random and kwargs:
            raise TypeError("Random() takes no keyword arguments")
        if len(args) > 1:
            raise TypeError("Random() requires 0 or 1 argument")
        self = object.__new__(cls)
        self._mt_state = _mt.new(args[0] if args else None)
        return self

    def random(self):
        """random() -> x in the interval [0, 1)."""
        return _mt.random(self._mt_state)

    def seed(self, n=None, /):
        """seed([n]) -> None.

        Defaults to use urandom and falls back to a combination
        of the current time and the process identifier.
        """
        _mt.seed(self._mt_state, n)

    def getstate(self):
        """getstate() -> tuple containing the current state."""
        return _mt.getstate(self._mt_state)

    def setstate(self, state, /):
        """setstate(state) -> None.  Restores generator state."""
        _mt.setstate(self._mt_state, state)

    def getrandbits(self, k, /):
        """getrandbits(k) -> x.  Generates an int with k random bits."""
        return _mt.getrandbits(self._mt_state, k)


Random.__module__ = "_random"
