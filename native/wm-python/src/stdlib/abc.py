"""Abstract base classes: ABCMeta, ABC, abstractmethod (and its old forms
abstractproperty, abstractclassmethod and abstractstaticmethod) and the
virtual subclasses `register` adds, as Python has them.

A class made by ABCMeta (any subclass of ABC) notes which abstract methods
it leaves unimplemented in `__abstractmethods__`; creating an instance of
one with any left raises TypeError. `isinstance` and `issubclass` ask the
class's `__subclasshook__`, then what was registered.
"""


def abstractmethod(funcobj):
    """A decorator marking a method abstract: a class made by ABCMeta that
    leaves it unimplemented can't be instantiated. It sets the function's
    __isabstractmethod__, which property, classmethod and staticmethod pass on."""
    funcobj.__isabstractmethod__ = True
    return funcobj


class abstractclassmethod(classmethod):
    """A decorator indicating abstract classmethods.

    Deprecated, use 'classmethod' with 'abstractmethod' instead.
    """

    __isabstractmethod__ = True

    def __init__(self, callable):
        callable.__isabstractmethod__ = True
        super().__init__(callable)


class abstractstaticmethod(staticmethod):
    """A decorator indicating abstract staticmethods.

    Deprecated, use 'staticmethod' with 'abstractmethod' instead.
    """

    __isabstractmethod__ = True

    def __init__(self, callable):
        callable.__isabstractmethod__ = True
        super().__init__(callable)


class abstractproperty(property):
    """A decorator indicating abstract properties.

    Deprecated, use 'property' with 'abstractmethod' instead.
    """

    __isabstractmethod__ = True


def _is_abstract(value):
    try:
        return bool(getattr(value, "__isabstractmethod__", False))
    except Exception:
        return False


def _compute_abstracts(cls):
    missing = []
    seen = set()
    for c in cls.__mro__:
        if c is object:
            continue
        for name, value in vars(c).items():
            if name in seen:
                continue
            seen.add(name)
            if _is_abstract(value):
                missing.append(name)
    # Creating an instance of a class with this set non-empty is refused.
    cls.__abstractmethods__ = frozenset(missing)


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
