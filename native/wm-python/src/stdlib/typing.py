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
    """A record class written with annotations: class Point(NamedTuple): x: int; y: int = 0.
    Its instances unpack, index and compare like the tuple of their fields,
    though isinstance(p, tuple) is False here."""

    def __init_subclass__(cls):
        own = vars(cls)
        names = list(own.get("__annotations__", {}))
        cls._fields = tuple(names)
        cls._field_defaults = {n: own[n] for n in names if n in own}

    def __init__(self, *args, **kwargs):
        cls = type(self)
        names = cls._fields
        if len(args) > len(names):
            raise TypeError(f"{cls.__name__}() takes {len(names)} arguments but {len(args)} were given")
        values = list(args)
        for name in names[len(args):]:
            if name in kwargs:
                values.append(kwargs.pop(name))
            elif name in cls._field_defaults:
                values.append(cls._field_defaults[name])
            else:
                raise TypeError(f"{cls.__name__}() missing required argument '{name}'")
        if kwargs:
            raise TypeError(f"{cls.__name__}() got an unexpected keyword argument '{list(kwargs)[0]}'")
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
