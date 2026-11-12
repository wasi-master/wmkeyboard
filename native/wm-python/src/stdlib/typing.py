"""typing: the names annotations use. Annotations are never evaluated here,
so these exist for imports to succeed and for code that mentions them."""

TYPE_CHECKING = False


class _Special:
    def __init__(self, name):
        self._name = name

    def __getitem__(self, item):
        return self

    def __call__(self, *args, **kwargs):
        return args[0] if args else None

    def __repr__(self):
        return "typing." + self._name


Any = _Special("Any")
Optional = _Special("Optional")
Union = _Special("Union")
List = _Special("List")
Dict = _Special("Dict")
Set = _Special("Set")
FrozenSet = _Special("FrozenSet")
Tuple = _Special("Tuple")
Callable = _Special("Callable")
Iterable = _Special("Iterable")
Iterator = _Special("Iterator")
Sequence = _Special("Sequence")
Mapping = _Special("Mapping")
MutableMapping = _Special("MutableMapping")
Literal = _Special("Literal")
Final = _Special("Final")
ClassVar = _Special("ClassVar")
Type = _Special("Type")
Annotated = _Special("Annotated")
NoReturn = _Special("NoReturn")
Self = _Special("Self")


def TypeVar(name, *constraints, **kwargs):
    return _Special(name)


def cast(typ, value):
    return value


def overload(func):
    return func


def final(obj):
    return obj


def get_type_hints(obj):
    return dict(getattr(obj, "__annotations__", {}))


class Generic:
    def __class_getitem__(cls, item):
        return cls


class Protocol:
    def __class_getitem__(cls, item):
        return cls


class NamedTuple:
    pass


class TypedDict:
    pass
