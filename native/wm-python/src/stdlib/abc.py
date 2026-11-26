"""Abstract base classes: ABCMeta, ABC, abstractmethod and the virtual
subclasses `register` adds, as Python has them.

A class made by ABCMeta (any subclass of ABC) notes which abstract methods
it leaves unimplemented in `__abstractmethods__`; creating an instance of
one with any left raises TypeError. `isinstance` and `issubclass` ask the
class's `__subclasshook__`, then what was registered.
"""


def abstractmethod(f):
    _ABSTRACT[id(f)] = True
    return f


_ABSTRACT = {}


def _unwrap(value):
    # A property, staticmethod or classmethod is abstract when what it wraps is.
    if isinstance(value, property):
        return value.fget
    func = getattr(value, "__func__", None)
    return value if func is None else func


def _is_abstract(value):
    value = _unwrap(value)
    return callable(value) and id(value) in _ABSTRACT


def _compute_abstracts(cls):
    missing = []
    seen = set()
    for c in cls.__mro__:
        if c is object:
            continue
        # dict.items: a class may have its own method called items.
        for name, value in dict.items(vars(c)):
            if name in seen:
                continue
            seen.add(name)
            if _is_abstract(value):
                missing.append(name)
    # Creating an instance of a class with this set non-empty is refused.
    cls.__abstractmethods__ = set(missing)


class ABCMeta(type):
    def __new__(mcls, name, bases, namespace, **kwargs):
        cls = super().__new__(mcls, name, bases, namespace, **kwargs)
        _compute_abstracts(cls)
        cls._abc_registry = []
        cls._abc_subclasses = []
        # Each abstract base hears of the classes made from it, whose
        # registered classes count as its own.
        for base in bases:
            known = getattr(base, "_abc_subclasses", None)
            if known is not None:
                known.append(cls)
        return cls

    def register(cls, subclass):
        """Make subclass a virtual subclass of cls, and hand it back."""
        if not cls._is_virtual(subclass):
            cls._abc_registry.append(subclass)
        return subclass

    def __subclasshook__(cls, subclass):
        return NotImplemented

    def __instancecheck__(cls, instance):
        return cls.__subclasscheck__(type(instance))

    def __subclasscheck__(cls, subclass):
        if cls in getattr(subclass, "__mro__", ()):
            return True
        ok = cls.__subclasshook__(subclass)
        if ok is not NotImplemented:
            return bool(ok)
        return cls._is_virtual(subclass)

    def _is_virtual(cls, subclass):
        for r in cls._abc_registry:
            if r is subclass or (isinstance(r, type) and issubclass(subclass, r)):
                return True
        for c in cls._abc_subclasses:
            if c._is_virtual(subclass):
                return True
        return False


class ABC(metaclass=ABCMeta):
    pass


def get_cache_token():
    return 0


def update_abstractmethods(cls):
    _compute_abstracts(cls)
    return cls
