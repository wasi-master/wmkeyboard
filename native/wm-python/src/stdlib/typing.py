"""typing: the names annotations use. Annotations are never evaluated here,
so these exist for imports to succeed and for code that mentions them."""

TYPE_CHECKING = False


class _Special:
    """A special form or generic alias: Optional, List, Callable and the rest.
    Subscripted it keeps its arguments, for get_args and get_origin."""

    def __init__(self, name, origin=None):
        self._name = name
        self.__origin__ = origin

    def __getitem__(self, item):
        args = item if isinstance(item, tuple) else (item,)
        if self._name == "Optional":
            return _Alias(Union, (args[0], type(None)))
        return _Alias(self, args)

    def __call__(self, *args, **kwargs):
        if self.__origin__ is not None and callable(self.__origin__):
            return self.__origin__(*args, **kwargs)
        return args[0] if args else None

    def __or__(self, other):
        return _Alias(Union, (self, other))

    def __ror__(self, other):
        return _Alias(Union, (other, self))

    def __mro_entries__(self, bases):
        return (self.__origin__,) if isinstance(self.__origin__, type) else ()

    def __instancecheck__(self, obj):
        if isinstance(self.__origin__, type):
            return isinstance(obj, self.__origin__)
        return True

    def __repr__(self):
        return "typing." + self._name


class _Alias:
    """A subscripted special form: List[int], Optional[str], Callable[[int], str]."""

    def __init__(self, origin, args):
        self.__origin__ = origin
        self.__args__ = args
        self.__parameters__ = ()

    def __getitem__(self, item):
        return _Alias(self.__origin__, item if isinstance(item, tuple) else (item,))

    def __call__(self, *args, **kwargs):
        return self.__origin__(*args, **kwargs)

    def __or__(self, other):
        return _Alias(Union, (self, other))

    def __ror__(self, other):
        return _Alias(Union, (other, self))

    def __mro_entries__(self, bases):
        origin = get_origin(self)
        return (origin,) if isinstance(origin, type) else ()

    def __instancecheck__(self, obj):
        if self.__origin__ is Union:
            return any(isinstance(obj, getattr(a, "__origin__", a) if not isinstance(a, _Alias) else a) for a in self.__args__ if a is not Any) or Any in self.__args__
        origin = get_origin(self)
        return isinstance(obj, origin) if isinstance(origin, type) else True

    def __eq__(self, other):
        if not isinstance(other, _Alias):
            return NotImplemented
        return self.__origin__ is other.__origin__ and self.__args__ == other.__args__

    def __hash__(self):
        return hash((id(self.__origin__), self.__args__))

    def __repr__(self):
        def show(a):
            if isinstance(a, list):
                return "[" + ", ".join(show(x) for x in a) + "]"
            if a is type(None) or a is None:
                return "None"
            if a is Ellipsis:
                return "..."
            if isinstance(a, type):
                return a.__name__ if getattr(a, "__module__", "builtins") == "builtins" else a.__module__ + "." + a.__name__
            return repr(a)
        if self.__origin__ is Union and len(self.__args__) == 2 and type(None) in self.__args__:
            other = [a for a in self.__args__ if a is not type(None)][0]
            return f"typing.Optional[{show(other)}]"
        return f"{self.__origin__!r}[{', '.join(show(a) for a in self.__args__)}]"


import collections as _collections
import collections.abc as _abc

Any = _Special("Any")
Optional = _Special("Optional")
Union = _Special("Union")
List = _Special("List", list)
Dict = _Special("Dict", dict)
Set = _Special("Set", set)
FrozenSet = _Special("FrozenSet", frozenset)
Tuple = _Special("Tuple", tuple)
Type = _Special("Type", type)
Callable = _Special("Callable", _abc.Callable)
Iterable = _Special("Iterable", _abc.Iterable)
Iterator = _Special("Iterator", _abc.Iterator)
Reversible = _Special("Reversible", _abc.Reversible)
Sequence = _Special("Sequence", _abc.Sequence)
MutableSequence = _Special("MutableSequence", _abc.MutableSequence)
Mapping = _Special("Mapping", _abc.Mapping)
MutableMapping = _Special("MutableMapping", _abc.MutableMapping)
AbstractSet = _Special("AbstractSet", _abc.Set)
MutableSet = _Special("MutableSet", _abc.MutableSet)
Collection = _Special("Collection", _abc.Collection)
Container = _Special("Container", _abc.Container)
Sized = _Special("Sized", _abc.Sized)
Hashable = _Special("Hashable", _abc.Hashable)
KeysView = _Special("KeysView", _abc.KeysView)
ItemsView = _Special("ItemsView", _abc.ItemsView)
ValuesView = _Special("ValuesView", _abc.ValuesView)
MappingView = _Special("MappingView", _abc.MappingView)
Generator = _Special("Generator", _abc.Generator)
Awaitable = _Special("Awaitable", _abc.Awaitable)
Coroutine = _Special("Coroutine", _abc.Coroutine)
AsyncIterable = _Special("AsyncIterable", _abc.AsyncIterable)
AsyncIterator = _Special("AsyncIterator", _abc.AsyncIterator)
AsyncGenerator = _Special("AsyncGenerator", _abc.AsyncGenerator)
DefaultDict = _Special("DefaultDict", _collections.defaultdict)
OrderedDict = _Special("OrderedDict", _collections.OrderedDict)
Counter = _Special("Counter", _collections.Counter)
ChainMap = _Special("ChainMap", _collections.ChainMap)
Deque = _Special("Deque", _collections.deque)
ContextManager = _Special("ContextManager")
AsyncContextManager = _Special("AsyncContextManager")
Literal = _Special("Literal")
Final = _Special("Final")
ClassVar = _Special("ClassVar")
Annotated = _Special("Annotated")
NoReturn = _Special("NoReturn")
Never = _Special("Never")
Self = _Special("Self")
TypeAlias = _Special("TypeAlias")
TypeGuard = _Special("TypeGuard")
TypeIs = _Special("TypeIs")
LiteralString = _Special("LiteralString", str)
Concatenate = _Special("Concatenate")
Unpack = _Special("Unpack")
Required = _Special("Required")
NotRequired = _Special("NotRequired")
ReadOnly = _Special("ReadOnly")
Text = str
AnyStr = _Special("AnyStr")
SupportsInt = _Special("SupportsInt")
SupportsFloat = _Special("SupportsFloat")
SupportsComplex = _Special("SupportsComplex")
SupportsBytes = _Special("SupportsBytes")
SupportsIndex = _Special("SupportsIndex")
SupportsAbs = _Special("SupportsAbs")
SupportsRound = _Special("SupportsRound")


class IO:
    """The base of file-like objects, for annotations and isinstance."""

    def __class_getitem__(cls, item):
        return cls


class TextIO(IO):
    pass


class BinaryIO(IO):
    pass


import re as _re

Pattern = _re.Pattern
Match = _re.Match


class TypeVar:
    """A type variable; it only names itself here."""

    def __init__(self, name, *constraints, bound=None, covariant=False, contravariant=False, infer_variance=False, default=None):
        self.__name__ = name
        self.__constraints__ = constraints
        self.__bound__ = bound
        self.__covariant__ = covariant
        self.__contravariant__ = contravariant

    def __or__(self, other):
        return _Alias(Union, (self, other))

    def __ror__(self, other):
        return _Alias(Union, (other, self))

    def __repr__(self):
        prefix = "+" if self.__covariant__ else "-" if self.__contravariant__ else "~"
        return prefix + self.__name__


class ParamSpec(TypeVar):
    def __init__(self, name, *, bound=None, covariant=False, contravariant=False, default=None):
        super().__init__(name, bound=bound)
        self.args = _Special(name + ".args")
        self.kwargs = _Special(name + ".kwargs")


class TypeVarTuple(TypeVar):
    def __init__(self, name, *, default=None):
        super().__init__(name)

    def __iter__(self):
        yield Unpack[self]


ParamSpecArgs = _Special("ParamSpecArgs")
ParamSpecKwargs = _Special("ParamSpecKwargs")


class ForwardRef:
    def __init__(self, arg, is_argument=True, module=None):
        self.__forward_arg__ = arg

    def __eq__(self, other):
        return isinstance(other, ForwardRef) and self.__forward_arg__ == other.__forward_arg__

    def __hash__(self):
        return hash(self.__forward_arg__)

    def __repr__(self):
        return f"ForwardRef({self.__forward_arg__!r})"


def NewType(name, tp):
    def new_type(x):
        return x

    new_type.__name__ = name
    new_type.__qualname__ = name
    new_type.__supertype__ = tp
    return new_type


def get_origin(tp):
    if isinstance(tp, _Alias):
        o = tp.__origin__
        if isinstance(o, _Special) and o.__origin__ is not None:
            return o.__origin__
        return o
    if type(tp).__name__ in ("GenericAlias", "UnionType"):
        return getattr(tp, "__origin__", type(tp))
    if tp is Generic or tp is Protocol:
        return tp
    return None


def get_args(tp):
    if isinstance(tp, _Alias) or type(tp).__name__ in ("GenericAlias", "UnionType"):
        args = tp.__args__
        if isinstance(tp, _Alias) and tp.__origin__ is Callable and args and isinstance(args[0], list):
            return args
        return args
    return ()


def is_typeddict(tp):
    return isinstance(tp, type) and issubclass(tp, TypedDict)


def runtime_checkable(cls):
    return cls


def no_type_check(arg):
    return arg


def no_type_check_decorator(decorator):
    return decorator


def dataclass_transform(**kwargs):
    return lambda cls: cls


def override(method):
    return method


def assert_never(arg):
    raise AssertionError(f"Expected code to be unreachable, but got: {arg!r}")


def assert_type(val, typ):
    return val


def reveal_type(obj):
    print(f"Runtime type is {type(obj).__name__!r}")
    return obj


def get_overloads(func):
    return []


def clear_overloads():
    pass


def cast(typ, value):
    return value


def overload(func):
    return func


def final(obj):
    return obj


def get_type_hints(obj):
    return dict(getattr(obj, "__annotations__", {}))


def _arg_names(names):
    quoted = ["'" + n + "'" for n in names]
    if len(quoted) < 3:
        return " and ".join(quoted)
    return ", ".join(quoted[:-1]) + ", and " + quoted[-1]


def _missing_error(who, names):
    noun = "argument" if len(names) == 1 else "arguments"
    return TypeError(f"{who} missing {len(names)} required positional {noun}: {_arg_names(names)}")


def _too_many_error(who, least, most, given):
    # Counts take in self, as CPython's do.
    if least == most:
        takes = f"{most} positional argument" + ("" if most == 1 else "s")
    else:
        takes = f"from {least} to {most} positional arguments"
    return TypeError(f"{who} takes {takes} but {given} {'was' if given == 1 else 'were'} given")


class Generic:
    def __class_getitem__(cls, item):
        return cls


class Protocol:
    def __class_getitem__(cls, item):
        return cls


class NamedTuple:
    """A record class written with annotations: class Point(NamedTuple): x: int; y: int = 0.
    Its instances unpack, index and compare like the tuple of their fields,
    though isinstance(p, tuple) is False here."""

    def __init_subclass__(cls):
        own = vars(cls)
        names = list(own.get("__annotations__", {}))
        cls._fields = tuple(names)
        cls.__match_args__ = cls._fields
        cls._field_defaults = {n: own[n] for n in names if n in own}

    def __init__(self, *args, **kwargs):
        cls = type(self)
        names = cls._fields
        who = cls.__name__ + ".__new__()"
        if len(args) > len(names):
            least = 1 + len([n for n in names if n not in cls._field_defaults])
            raise _too_many_error(who, least, 1 + len(names), 1 + len(args))
        for name in names[:len(args)]:
            if name in kwargs:
                raise TypeError(f"{who} got multiple values for argument '{name}'")
        values = list(args)
        missing = []
        for name in names[len(args):]:
            if name in kwargs:
                values.append(kwargs.pop(name))
            elif name in cls._field_defaults:
                values.append(cls._field_defaults[name])
            else:
                missing.append(name)
        if missing:
            raise _missing_error(who, missing)
        if kwargs:
            raise TypeError(f"{who} got an unexpected keyword argument '{list(kwargs)[0]}'")
        object.__setattr__(self, "_values", tuple(values))
        for name, value in zip(names, values):
            object.__setattr__(self, name, value)

    def __setattr__(self, name, value):
        raise AttributeError(f"can't set attribute '{name}' on a {type(self).__name__}")

    def __getitem__(self, i):
        return self._values[i]

    def __len__(self):
        return len(self._values)

    def __iter__(self):
        return iter(self._values)

    def __eq__(self, other):
        if isinstance(other, tuple):
            return self._values == other
        return type(other) is type(self) and self._values == other._values

    def __lt__(self, other):
        return self._values < (other._values if isinstance(other, NamedTuple) else other)

    def __hash__(self):
        return hash(self._values)

    def __repr__(self):
        inner = ", ".join(f"{n}={v!r}" for n, v in zip(self._fields, self._values))
        return f"{type(self).__name__}({inner})"

    def _asdict(self):
        return dict(zip(self._fields, self._values))

    def _replace(self, **changes):
        values = self._asdict()
        for k, v in changes.items():
            if k not in values:
                raise ValueError(f"Got unexpected field name: '{k}'")
            values[k] = v
        return type(self)(**values)


class TypedDict:
    pass
