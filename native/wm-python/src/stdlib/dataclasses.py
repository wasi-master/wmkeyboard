"""dataclasses: classes whose __init__, __repr__ and __eq__ are written for
you from their annotated fields.

    @dataclass
    class Item:
        name: str
        done: bool = False
        tags: list = field(default_factory=list)
"""

class _MISSING_TYPE:
    def __repr__(self):
        return "MISSING"


MISSING = _MISSING_TYPE()


class _FIELD_BASE:
    def __init__(self, name):
        self.name = name

    def __repr__(self):
        return self.name


# What a class attribute's annotation makes it: a field, a class variable
# (`ClassVar[int]`, not a field) or an argument only __init__ takes
# (`InitVar[int]`, passed on to __post_init__).
_FIELD = _FIELD_BASE("_FIELD")
_FIELD_CLASSVAR = _FIELD_BASE("_FIELD_CLASSVAR")
_FIELD_INITVAR = _FIELD_BASE("_FIELD_INITVAR")
_FIELDS = "__dataclass_fields__"
_PARAMS = "__dataclass_params__"
_POST_INIT_NAME = "__post_init__"


class KW_ONLY:
    """`_: KW_ONLY` in a dataclass: the fields after it are keyword-only."""


class InitVar:
    """`x: InitVar[int]`: an __init__ argument handed to __post_init__, not a field."""

    __slots__ = ("type",)

    def __init__(self, type):
        self.type = type

    def __repr__(self):
        name = getattr(self.type, "__name__", None) or repr(self.type)
        return f"dataclasses.InitVar[{name}]"

    def __class_getitem__(cls, type):
        return InitVar(type)

    __class_getitem__ = classmethod(__class_getitem__)


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


class FrozenInstanceError(AttributeError):
    pass


class Field:
    def __init__(self, default=MISSING, default_factory=None, init=True, repr=True, compare=True, hash=None, metadata=None, kw_only=MISSING, doc=None):
        self.name = None
        self.type = None
        self.default = default
        self.default_factory = default_factory
        self.init = init
        self.repr = repr
        self.compare = compare
        self.hash = hash
        self.metadata = {} if metadata is None else metadata
        self.kw_only = kw_only
        self.doc = doc
        self._field_type = None

    def __repr__(self):
        return (
            f"Field(name={self.name!r},type={self.type!r},default={self.default!r},"
            f"default_factory={self.default_factory!r},init={self.init!r},repr={self.repr!r},"
            f"hash={self.hash!r},compare={self.compare!r},metadata={self.metadata!r},"
            f"kw_only={self.kw_only!r},_field_type={self._field_type})"
        )


def field(*, default=MISSING, default_factory=None, init=True, repr=True, hash=None, compare=True, metadata=None, kw_only=MISSING, doc=None):
    if default_factory is MISSING:
        default_factory = None
    if default is not MISSING and default_factory is not None:
        raise ValueError("cannot specify both default and default_factory")
    return Field(default, default_factory, init, repr, compare, hash, metadata, kw_only, doc)


def _is_classvar(annotation):
    if isinstance(annotation, str):
        return annotation.startswith(("ClassVar", "typing.ClassVar", "t.ClassVar"))
    import sys
    typing = sys.modules.get("typing")
    if typing is None:
        return False
    return annotation is typing.ClassVar or getattr(annotation, "__origin__", None) is typing.ClassVar


def _is_initvar(annotation):
    if isinstance(annotation, str):
        return annotation.startswith(("InitVar", "dataclasses.InitVar"))
    return annotation is InitVar or isinstance(annotation, InitVar)


def _is_kw_only(annotation):
    if isinstance(annotation, str):
        return annotation in ("KW_ONLY", "dataclasses.KW_ONLY")
    return annotation is KW_ONLY


def _collect(cls, kw_only=False):
    # Fields come from every base, furthest first, then the class itself.
    chain = [c for c in cls.__mro__ if c is not object]
    found = {}
    for k in reversed(chain):
        inherited = vars(k).get("__dataclass_fields__")
        if inherited is not None and k is not cls:
            for name, f in inherited.items():
                found[name] = f
            continue
        if k is not cls:
            continue
        own = vars(k)
        after_marker = kw_only
        for name, annotation in own.get("__annotations__", {}).items():
            if _is_kw_only(annotation):
                after_marker = True
                continue
            value = own.get(name, MISSING)
            if isinstance(value, Field):
                f = value
            else:
                f = Field(default=value)
            f.name = name
            f.type = annotation
            if _is_classvar(annotation):
                f._field_type = _FIELD_CLASSVAR
            elif _is_initvar(annotation):
                f._field_type = _FIELD_INITVAR
            else:
                f._field_type = _FIELD
            if f._field_type is not _FIELD and f.default_factory is not None:
                raise TypeError(f"field {name} cannot have a default factory")
            if f._field_type is _FIELD and isinstance(f.default, (list, dict, set)):
                raise ValueError(f"mutable default {type(f.default)} for field {name} is not allowed: use default_factory")
            if f.kw_only is MISSING:
                f.kw_only = after_marker
            found[name] = f
    return list(found.values())


def _make_init(fields, frozen):
    taken = [f for f in fields if f.init and f._field_type is not _FIELD_CLASSVAR]
    init_fields = [f for f in taken if not f.kw_only]
    keyword_fields = [f for f in taken if f.kw_only]
    other_fields = [f for f in fields if not f.init and f._field_type is _FIELD]
    init_vars = [f.name for f in taken if f._field_type is _FIELD_INITVAR]
    required = len([f for f in init_fields if f.default is MISSING and f.default_factory is None])

    def value_of(f, kwargs, who, missing):
        if f.name in kwargs:
            return kwargs.pop(f.name)
        if f.default is not MISSING:
            return f.default
        if f.default_factory is not None:
            return f.default_factory()
        missing.append(f.name)
        return MISSING

    def __init__(self, *args, **kwargs):
        who = type(self).__name__ + ".__init__()"
        if len(args) > len(init_fields):
            raise _too_many_error(who, 1 + required, 1 + len(init_fields), 1 + len(args))
        missing = []
        passed = {}
        for i, f in enumerate(init_fields):
            if i < len(args):
                if f.name in kwargs:
                    raise TypeError(f"{who} got multiple values for argument '{f.name}'")
                passed[f.name] = args[i]
            else:
                value = value_of(f, kwargs, who, missing)
                if value is not MISSING:
                    passed[f.name] = value
        if missing:
            raise _missing_error(who, missing)
        for f in keyword_fields:
            value = value_of(f, kwargs, who, missing)
            if value is not MISSING:
                passed[f.name] = value
        if missing:
            noun = "argument" if len(missing) == 1 else "arguments"
            raise TypeError(f"{who} missing {len(missing)} required keyword-only {noun}: {_arg_names(missing)}")
        if kwargs:
            raise TypeError(f"{who} got an unexpected keyword argument '{list(kwargs)[0]}'")
        for f in taken:
            if f._field_type is _FIELD and f.name in passed:
                object.__setattr__(self, f.name, passed[f.name])
        for f in other_fields:
            if f.default is not MISSING:
                object.__setattr__(self, f.name, f.default)
            elif f.default_factory is not None:
                object.__setattr__(self, f.name, f.default_factory())
        if hasattr(self, "__post_init__"):
            self.__post_init__(*[passed[n] for n in init_vars if n in passed])

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


def _make_hash(fields):
    def __hash__(self):
        return hash(_key(self, fields))

    return __hash__


class _DataclassParams:
    def __init__(self, init, repr, eq, order, unsafe_hash, frozen, match_args, kw_only, slots, weakref_slot):
        self.init = init
        self.repr = repr
        self.eq = eq
        self.order = order
        self.unsafe_hash = unsafe_hash
        self.frozen = frozen
        self.match_args = match_args
        self.kw_only = kw_only
        self.slots = slots
        self.weakref_slot = weakref_slot

    def __repr__(self):
        return (
            f"_DataclassParams(init={self.init!r},repr={self.repr!r},eq={self.eq!r},order={self.order!r},"
            f"unsafe_hash={self.unsafe_hash!r},frozen={self.frozen!r},match_args={self.match_args!r},"
            f"kw_only={self.kw_only!r},slots={self.slots!r},weakref_slot={self.weakref_slot!r})"
        )


def dataclass(cls=None, *, init=True, repr=True, eq=True, order=False, unsafe_hash=False, frozen=False, match_args=True, kw_only=False, slots=False, weakref_slot=False):
    def wrap(cls):
        all_fields = _collect(cls, kw_only)
        fields = [f for f in all_fields if f._field_type is _FIELD]
        own = vars(cls)
        cls.__dataclass_fields__ = {f.name: f for f in all_fields}
        cls.__dataclass_params__ = _DataclassParams(init, repr, eq, order, unsafe_hash, frozen, match_args, kw_only, slots, weakref_slot)
        for f in all_fields:
            if f.name in own and isinstance(own[f.name], Field):
                if f.default is not MISSING:
                    setattr(cls, f.name, f.default)
                else:
                    delattr(cls, f.name)
        if init and "__init__" not in own:
            cls.__init__ = _make_init(all_fields, frozen)
        # What `case Point(x, y):` matches positionally.
        if match_args and "__match_args__" not in own:
            cls.__match_args__ = tuple(f.name for f in fields if f.init and not f.kw_only)
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
        # Frozen and compared by value: hashed by value too, so equal ones are one dict key.
        if unsafe_hash or (frozen and eq and "__hash__" not in own):
            cls.__hash__ = _make_hash(fields)
        return cls

    if cls is None:
        return wrap
    return wrap(cls)


def is_dataclass(obj):
    return hasattr(obj, "__dataclass_fields__")


def fields(class_or_instance):
    try:
        found = class_or_instance.__dataclass_fields__
    except AttributeError:
        raise TypeError("must be called with a dataclass type or instance") from None
    return tuple(f for f in found.values() if f._field_type is _FIELD)


def asdict(obj, *, dict_factory=dict):
    if is_dataclass(obj) and dict_factory is not dict:
        return dict_factory([(f.name, asdict(getattr(obj, f.name), dict_factory=dict_factory)) for f in fields(obj)])
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


def replace(obj, /, **changes):
    if not is_dataclass(obj) or isinstance(obj, type):
        raise TypeError("replace() should be called on dataclass instances")
    for f in obj.__dataclass_fields__.values():
        if f._field_type is _FIELD_CLASSVAR:
            continue
        if not f.init:
            if f.name in changes:
                raise ValueError(f"field {f.name} is declared with init=False, it cannot be specified with replace()")
            continue
        if f.name not in changes:
            if f._field_type is _FIELD_INITVAR and f.default is MISSING:
                raise TypeError(f"InitVar {f.name!r} must be specified with replace()")
            if f._field_type is _FIELD:
                changes[f.name] = getattr(obj, f.name)
    return type(obj)(**changes)


def make_dataclass(cls_name, fields, *, bases=(), namespace=None, init=True, repr=True, eq=True, order=False, unsafe_hash=False, frozen=False, match_args=True, kw_only=False, slots=False, weakref_slot=False, module=None):
    """A dataclass made from a list of names, (name, type) or (name, type, Field)."""
    namespace = dict(namespace or {})
    annotations = {}
    seen = set()
    for item in fields:
        if isinstance(item, str):
            name, tp = item, "typing.Any"
        elif len(item) == 2:
            name, tp = item
        elif len(item) == 3:
            name, tp, spec = item
            namespace[name] = spec
        else:
            raise TypeError(f"Invalid field: {item!r}")
        if not isinstance(name, str) or not name.isidentifier():
            raise TypeError(f"Field names must be valid identifiers: {name!r}")
        if name in seen:
            raise TypeError(f"Field name duplicated: {name!r}")
        seen.add(name)
        annotations[name] = tp
    namespace["__annotations__"] = annotations
    if module is not None:
        namespace["__module__"] = module
    cls = type(cls_name, tuple(bases), namespace)
    return dataclass(cls, init=init, repr=repr, eq=eq, order=order, unsafe_hash=unsafe_hash, frozen=frozen, match_args=match_args, kw_only=kw_only, slots=slots, weakref_slot=weakref_slot)
