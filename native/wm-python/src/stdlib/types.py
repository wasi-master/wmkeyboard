"""types: names for the built-in types, SimpleNamespace, and new_class."""

import sys as _sys


def _f():
    pass


def _g():
    yield


async def _co():
    pass


async def _ag():
    yield


class _C:
    def _m(self):
        pass


FunctionType = type(_f)
LambdaType = type(lambda: None)
GeneratorType = type(_g())
MethodType = type(_C()._m)
BuiltinFunctionType = type(len)
BuiltinMethodType = type(len)
ModuleType = type(_sys)
NoneType = type(None)
EllipsisType = type(...)
NotImplementedType = type(NotImplemented)
_c = _co()
CoroutineType = type(_c)
_c.close()
AsyncGeneratorType = type(_ag())
del _f, _g, _C, _co, _ag, _c


GenericAlias = type(list[int])
UnionType = type(int | str)


class TracebackType:
    """What a traceback would be; exceptions here carry none (__traceback__ is None)."""

    def __init__(self, tb_next=None, tb_frame=None, tb_lasti=0, tb_lineno=0):
        self.tb_next = tb_next
        self.tb_frame = tb_frame
        self.tb_lasti = tb_lasti
        self.tb_lineno = tb_lineno


class FrameType:
    pass


class CodeType:
    pass


def coroutine(func):
    """Marks a generator function as a coroutine; here it is one already."""
    return func


class SimpleNamespace:
    """An object whose attributes are the keywords it was made with."""

    def __init__(self, mapping=None, /, **kwargs):
        if mapping is not None:
            for k, v in dict(mapping).items():
                setattr(self, k, v)
        for k, v in kwargs.items():
            setattr(self, k, v)

    def __repr__(self):
        inner = ", ".join(f"{k}={v!r}" for k, v in vars(self).items())
        return f"namespace({inner})"

    def __eq__(self, other):
        if not isinstance(other, SimpleNamespace):
            return NotImplemented
        return vars(self) == vars(other)


class MappingProxyType:
    """A read-only view of a mapping."""

    def __init__(self, mapping):
        object.__setattr__(self, "_mapping", mapping)

    def __getitem__(self, key):
        return self._mapping[key]

    def __contains__(self, key):
        return key in self._mapping

    def __iter__(self):
        return iter(self._mapping)

    def __len__(self):
        return len(self._mapping)

    def __setattr__(self, name, value):
        raise AttributeError("'mappingproxy' object attribute is read-only")

    def get(self, key, default=None):
        return self._mapping.get(key, default)

    def keys(self):
        return self._mapping.keys()

    def values(self):
        return self._mapping.values()

    def items(self):
        return self._mapping.items()

    def copy(self):
        return dict(self._mapping)

    def __repr__(self):
        return f"mappingproxy({self._mapping!r})"


def new_class(name, bases=(), kwds=None, exec_body=None):
    ns = {}
    if exec_body is not None:
        exec_body(ns)
    kwds = dict(kwds or {})
    meta = kwds.pop("metaclass", type)
    return meta(name, tuple(bases), ns, **kwds)
