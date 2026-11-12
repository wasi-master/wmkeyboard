"""dataclasses: classes whose __init__, __repr__ and __eq__ are written for
you from their annotated fields.

    @dataclass
    class Item:
        name: str
        done: bool = False
        tags: list = field(default_factory=list)
"""

MISSING = object()


class FrozenInstanceError(AttributeError):
    pass


class Field:
    def __init__(self, default=MISSING, default_factory=None, init=True, repr=True, compare=True):
        self.name = None
        self.default = default
        self.default_factory = default_factory
        self.init = init
        self.repr = repr
        self.compare = compare

    def __repr__(self):
        return f"Field(name={self.name!r})"


def field(*, default=MISSING, default_factory=None, init=True, repr=True, compare=True):
    if default is not MISSING and default_factory is not None:
        raise ValueError("cannot specify both default and default_factory")
    return Field(default, default_factory, init, repr, compare)


def _collect(cls):
    chain = []
    c = cls
    while c is not None:
        chain.append(c)
        c = c.__base__
    found = {}
    for k in reversed(chain):
        inherited = vars(k).get("__dataclass_fields__")
        if inherited is not None and k is not cls:
            for name, f in inherited.items():
                found[name] = f
            continue
        own = vars(k)
        for name in own.get("__annotations__", {}):
            value = own.get(name, MISSING)
            if isinstance(value, Field):
                f = value
            else:
                f = Field(default=value)
            if isinstance(f.default, (list, dict, set)):
                raise ValueError(f"mutable default {type(f.default)} for field {name} is not allowed: use default_factory")
            f.name = name
            found[name] = f
    return list(found.values())


def _make_init(fields, frozen):
    init_fields = [f for f in fields if f.init]
    other_fields = [f for f in fields if not f.init]

    def __init__(self, *args, **kwargs):
        if len(args) > len(init_fields):
            raise TypeError(f"__init__() takes {len(init_fields)} positional arguments but {len(args)} were given")
        for i, f in enumerate(init_fields):
            if i < len(args):
                if f.name in kwargs:
                    raise TypeError(f"__init__() got multiple values for argument '{f.name}'")
                value = args[i]
            elif f.name in kwargs:
                value = kwargs.pop(f.name)
            elif f.default is not MISSING:
                value = f.default
            elif f.default_factory is not None:
                value = f.default_factory()
            else:
                raise TypeError(f"__init__() missing required argument '{f.name}'")
            object.__setattr__(self, f.name, value)
        if kwargs:
            raise TypeError(f"__init__() got an unexpected keyword argument '{list(kwargs)[0]}'")
        for f in other_fields:
            if f.default is not MISSING:
                object.__setattr__(self, f.name, f.default)
            elif f.default_factory is not None:
                object.__setattr__(self, f.name, f.default_factory())
        if hasattr(self, "__post_init__"):
            self.__post_init__()

    return __init__


def _make_repr(fields):
    shown = [f for f in fields if f.repr]

    def __repr__(self):
        inner = ", ".join(f"{f.name}={getattr(self, f.name)!r}" for f in shown)
        return f"{type(self).__name__}({inner})"

    return __repr__


def _key(self, fields):
    return tuple(getattr(self, f.name) for f in fields if f.compare)


def _make_eq(fields):
    def __eq__(self, other):
        if type(other) is not type(self):
            return NotImplemented
        return _key(self, fields) == _key(other, fields)

    return __eq__


def _make_order(fields, op):
    def compare(self, other):
        if type(other) is not type(self):
            return NotImplemented
        a = _key(self, fields)
        b = _key(other, fields)
        if op == "lt":
            return a < b
        if op == "le":
            return a <= b
        if op == "gt":
            return a > b
        return a >= b

    return compare


def _frozen_setattr(self, name, value):
    raise FrozenInstanceError(f"cannot assign to field '{name}'")


def _frozen_delattr(self, name):
    raise FrozenInstanceError(f"cannot delete field '{name}'")


def dataclass(cls=None, *, init=True, repr=True, eq=True, order=False, frozen=False):
    def wrap(cls):
        fields = _collect(cls)
        own = vars(cls)
        cls.__dataclass_fields__ = {f.name: f for f in fields}
        for f in fields:
            if f.name in own and isinstance(own[f.name], Field):
                if f.default is not MISSING:
                    setattr(cls, f.name, f.default)
                else:
                    delattr(cls, f.name)
        if init and "__init__" not in own:
            cls.__init__ = _make_init(fields, frozen)
        if repr and "__repr__" not in own:
            cls.__repr__ = _make_repr(fields)
        if eq and "__eq__" not in own:
            cls.__eq__ = _make_eq(fields)
        if order:
            for op in ["lt", "le", "gt", "ge"]:
                setattr(cls, f"__{op}__", _make_order(fields, op))
        if frozen:
            cls.__setattr__ = _frozen_setattr
            cls.__delattr__ = _frozen_delattr
        return cls

    if cls is None:
        return wrap
    return wrap(cls)


def is_dataclass(obj):
    return hasattr(obj, "__dataclass_fields__")


def fields(obj):
    if not is_dataclass(obj):
        raise TypeError("fields() needs a dataclass or an instance of one")
    return list(obj.__dataclass_fields__.values())


def asdict(obj):
    if is_dataclass(obj):
        return {f.name: asdict(getattr(obj, f.name)) for f in fields(obj)}
    if isinstance(obj, list):
        return [asdict(x) for x in obj]
    if isinstance(obj, tuple):
        return tuple(asdict(x) for x in obj)
    if isinstance(obj, dict):
        return {k: asdict(v) for k, v in obj.items()}
    return obj


def astuple(obj):
    return tuple(asdict(obj).values())


def replace(obj, **changes):
    values = {f.name: getattr(obj, f.name) for f in fields(obj) if f.init}
    for k, v in changes.items():
        values[k] = v
    return type(obj)(**values)
