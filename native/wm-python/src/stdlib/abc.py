"""Abstract base classes, as far as a class without metaclasses can go.

`abstractmethod` marks a method; `ABC.__init_subclass__` notes which marked
methods a class leaves unimplemented in `__abstractmethods__`, and creating
an instance of such a class raises TypeError, as in Python.
"""


def abstractmethod(f):
    _ABSTRACT[id(f)] = True
    return f


_ABSTRACT = {}


def _is_abstract(value):
    return callable(value) and id(value) in _ABSTRACT


class ABC:
    def __init_subclass__(cls):
        missing = []
        c = cls
        seen = set()
        while c is not None:
            for name, value in vars(c).items():
                if name in seen:
                    continue
                seen.add(name)
                if _is_abstract(value):
                    missing.append(name)
            c = c.__base__
        # Creating an instance of a class with this set non-empty is refused.
        cls.__abstractmethods__ = set(missing)


ABCMeta = ABC
