"""types: names for the built-in types, SimpleNamespace, and new_class."""

import sys as _sys


def _f():
    pass


def _code_of():
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
_module = type(_sys)


class _ModuleMeta(type):
    # isinstance(json, ModuleType) holds for the interpreter's own modules
    # too, which are not instances of this class.
    def __instancecheck__(cls, obj):
        return cls in getattr(type(obj), "__mro__", ()) or (cls is ModuleType and type(obj) is _module)

    def __subclasscheck__(cls, sub):
        return (cls is ModuleType and sub is _module) or cls in getattr(sub, "__mro__", ())


class ModuleType(metaclass=_ModuleMeta):
    """A module made by a script or a loader: ModuleType("name") gives an
    empty one to fill, and a class may derive from it, as six's lazy
    modules do."""

    def __init__(self, name, doc=None):
        if not isinstance(name, str):
            raise TypeError(f"module.__init__() argument 'name' must be str, not {type(name).__name__}")
        self.__name__ = name
        self.__doc__ = doc
        self.__package__ = None
        self.__loader__ = None
        self.__spec__ = None

    def __getattr__(self, name):
        # PEP 562: the module's own __getattr__ answers what it lacks.
        hook = self.__dict__.get("__getattr__")
        if hook is not None:
            return hook(name)
        raise AttributeError(f"module '{self.__dict__.get('__name__', '?')}' has no attribute '{name}'")

    def __repr__(self):
        loader = self.__dict__.get("__loader__")
        spec = self.__dict__.get("__spec__")
        name = self.__dict__.get("__name__", "?")
        origin = getattr(spec, "origin", None) if spec is not None else None
        if origin is not None and getattr(spec, "has_location", False):
            return f"<module {name!r} from {origin!r}>"
        if loader is not None:
            return f"<module {name!r} ({loader!r})>"
        return f"<module {name!r}>"


ModuleType.__name__ = ModuleType.__qualname__ = "module"
ModuleType.__module__ = "builtins"
NoneType = type(None)
EllipsisType = type(...)
NotImplementedType = type(NotImplemented)
_c = _co()
CoroutineType = type(_c)
_c.close()
AsyncGeneratorType = type(_ag())
del _f, _g, _C, _co, _ag, _c


class _Descriptor:
    """The C-level descriptor types CPython names here. Nothing in this
    interpreter is one of them; they exist to be named and checked against."""


class WrapperDescriptorType(_Descriptor):
    pass


class MethodWrapperType(_Descriptor):
    pass


class MethodDescriptorType(_Descriptor):
    pass


class ClassMethodDescriptorType(_Descriptor):
    pass


class GetSetDescriptorType(_Descriptor):
    pass


class MemberDescriptorType(_Descriptor):
    pass


WrapperDescriptorType.__name__ = WrapperDescriptorType.__qualname__ = "wrapper_descriptor"
MethodWrapperType.__name__ = MethodWrapperType.__qualname__ = "method-wrapper"
MethodDescriptorType.__name__ = MethodDescriptorType.__qualname__ = "method_descriptor"
ClassMethodDescriptorType.__name__ = ClassMethodDescriptorType.__qualname__ = "classmethod_descriptor"
GetSetDescriptorType.__name__ = GetSetDescriptorType.__qualname__ = "getset_descriptor"
MemberDescriptorType.__name__ = MemberDescriptorType.__qualname__ = "member_descriptor"
for _t in (WrapperDescriptorType, MethodWrapperType, MethodDescriptorType, ClassMethodDescriptorType,
           GetSetDescriptorType, MemberDescriptorType):
    _t.__module__ = "builtins"
del _t, _Descriptor


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


CodeType = type(_code_of.__code__)
del _code_of


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


# What a class's __dict__ is: a read-only view, from the interpreter's builtins.
MappingProxyType = type(type.__dict__)


def resolve_bases(bases):
    """Resolve MRO entries dynamically as specified by PEP 560."""
    new_bases = list(bases)
    updated = False
    shift = 0
    for i, base in enumerate(bases):
        if isinstance(base, type):
            continue
        if not hasattr(base, "__mro_entries__"):
            continue
        new_base = base.__mro_entries__(bases)
        updated = True
        if not isinstance(new_base, tuple):
            raise TypeError("__mro_entries__ must return a tuple")
        else:
            new_bases[i+shift:i+shift+1] = new_base
            shift += len(new_base) - 1
    if not updated:
        return bases
    return tuple(new_bases)

def new_class(name, bases=(), kwds=None, exec_body=None):
    ns = {}
    if exec_body is not None:
        exec_body(ns)
    kwds = dict(kwds or {})
    meta = kwds.pop("metaclass", type)
    return meta(name, tuple(bases), ns, **kwds)
