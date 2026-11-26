"""contextvars: context variables, and contexts to run code in.

Code here runs one thing at a time, so there is one current context;
Context.run() swaps another in for the length of a call.
"""

from collections.abc import Mapping

_MISSING = object()


class Context(Mapping):
    def __init__(self):
        self._vars = {}

    def run(self, callable, /, *args, **kwargs):
        global _current
        if self is _current:
            raise RuntimeError(f"cannot enter context: {self!r} is already entered")
        saved = _current
        _current = self
        try:
            return callable(*args, **kwargs)
        finally:
            _current = saved

    def copy(self):
        c = Context()
        c._vars = dict(self._vars)
        return c

    def __getitem__(self, var):
        if not isinstance(var, ContextVar):
            raise TypeError(f"a ContextVar key was expected, got {var!r}")
        return self._vars[var]

    def __contains__(self, var):
        return var in self._vars

    def __len__(self):
        return len(self._vars)

    def __iter__(self):
        return iter(self._vars)

    def get(self, var, default=None):
        return self._vars.get(var, default)

    def keys(self):
        return self._vars.keys()

    def values(self):
        return self._vars.values()

    def items(self):
        return self._vars.items()


_current = Context()


def copy_context():
    return _current.copy()


class Token:
    MISSING = _MISSING

    def __init__(self, context, var, old_value):
        self._context = context
        self._var = var
        self._old_value = old_value
        self._used = False

    @property
    def var(self):
        return self._var

    @property
    def old_value(self):
        return self._old_value

    def __repr__(self):
        used = " used" if self._used else ""
        return f"<Token{used} var={self._var!r}>"

    __class_getitem__ = classmethod(lambda cls, item: cls)


class ContextVar:
    def __init__(self, name, *, default=_MISSING):
        if not isinstance(name, str):
            raise TypeError("context variable name must be a str")
        self._name = name
        self._default = default

    @property
    def name(self):
        return self._name

    def get(self, default=_MISSING):
        if self in _current._vars:
            return _current._vars[self]
        if default is not _MISSING:
            return default
        if self._default is not _MISSING:
            return self._default
        raise LookupError(self)

    def set(self, value):
        old = _current._vars.get(self, _MISSING)
        _current._vars[self] = value
        return Token(_current, self, old)

    def reset(self, token):
        if not isinstance(token, Token):
            raise TypeError(f"expected an instance of Token, got {token!r}")
        if token._used:
            raise RuntimeError(f"{token!r} has already been used once")
        if token._var is not self:
            raise ValueError(f"{token!r} was created by a different ContextVar")
        if token._context is not _current:
            raise ValueError(f"{token!r} was created in a different Context")
        if token._old_value is _MISSING:
            _current._vars.pop(self, None)
        else:
            _current._vars[self] = token._old_value
        token._used = True

    def __repr__(self):
        default = "" if self._default is _MISSING else f" default={self._default!r}"
        return f"<ContextVar name={self._name!r}{default}>"

    __class_getitem__ = classmethod(lambda cls, item: cls)
