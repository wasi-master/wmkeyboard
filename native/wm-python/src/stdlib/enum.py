"""enum: named constants.

    class Color(Enum):
        RED = 1
        GREEN = 2

`Color.RED.name` is "RED", `.value` is 1, `Color(1)` and `Color["RED"]`
find a member, `for c in Color` walks them in order and `len(Color)` counts
them. A second name for a value is an alias of the first member.

Mixed with a built-in type (`class Color(str, Enum)`, IntEnum, StrEnum,
IntFlag) each member is a value of that type: `Color.RED == "red"`, and it
goes into json and str methods as one.
"""

_MIXINS = (int, str, float, tuple, bytes)
_type = type


class _Auto:
    pass


def auto():
    return _Auto()


def _member_type(cls):
    for base in cls.__mro__:
        if base in _MIXINS:
            return base
    return object


def _is_descriptor(value):
    return isinstance(value, (property, staticmethod, classmethod)) or hasattr(value, "__get__")


class Enum:
    @staticmethod
    def _generate_next_value_(name, start, count, last_values):
        for last in reversed(last_values):
            if isinstance(last, int):
                return last + 1
        return start

    def __init_subclass__(cls, **kwargs):
        member_type = _member_type(cls)
        cls._member_type_ = member_type
        for base in cls.__mro__[1:]:
            if getattr(base, "_member_names_", None):
                raise TypeError(f"<enum {cls.__name__!r}> cannot extend <enum {base.__name__!r}>")
        members = {}
        everything = {}
        by_value = {}
        unhashable = []
        last_values = []
        for name, value in list(vars(cls).items()):
            if name.startswith("_") or callable(value) or _is_descriptor(value):
                continue
            if isinstance(value, _Auto):
                value = cls._generate_next_value_(name, 1, len(members), list(last_values))
            if member_type is object:
                mvalue = value
            else:
                args = value if isinstance(value, tuple) and member_type is not tuple else (value,)
                mvalue = member_type(*args)
            alias = None
            try:
                alias = by_value.get(mvalue)
            except TypeError:
                for m in unhashable:
                    if m._value_ == mvalue:
                        alias = m
            if alias is not None:
                setattr(cls, name, alias)
                everything[name] = alias
                continue
            if member_type is object:
                member = object.__new__(cls)
            else:
                member = member_type.__new__(cls, *args)
            for attr, v in (("_name_", name), ("_value_", mvalue), ("name", name), ("value", mvalue)):
                object.__setattr__(member, attr, v)
            if hasattr(cls, "__init__") and "__init__" in _own_dicts(cls):
                cls.__init__(member, *(value if isinstance(value, tuple) else (value,)))
            setattr(cls, name, member)
            members[name] = member
            everything[name] = member
            last_values.append(mvalue)
            try:
                by_value[mvalue] = member
            except TypeError:
                unhashable.append(member)
        cls._member_map_ = members
        cls._member_names_ = list(members)
        cls._value2member_map_ = by_value
        cls._unhashable_values_ = unhashable
        cls.__members__ = everything

    def __new__(cls, value, names=None, *, module=None, qualname=None, type=None, start=1, boundary=None):
        if names is not None:
            return cls._create_(value, names, module=module, type=type, start=start)
        if isinstance(value, cls):
            return value
        try:
            member = cls._value2member_map_.get(value)
        except TypeError:
            member = None
            for m in getattr(cls, "_unhashable_values_", ()):
                if m._value_ == value:
                    member = m
        if member is None:
            member = cls._missing_(value)
        if member is None:
            raise ValueError(f"{value!r} is not a valid {cls.__qualname__}")
        return member

    @classmethod
    def _create_(cls, class_name, names, *, module=None, type=None, start=1):
        if isinstance(names, str):
            names = names.replace(",", " ").split()
        pairs = []
        if isinstance(names, dict):
            pairs = list(names.items())
        else:
            names = list(names)
            if names and isinstance(names[0], str):
                last_values = []
                for count, name in enumerate(names):
                    v = cls._generate_next_value_(name, start, count, last_values)
                    last_values.append(v)
                    pairs.append((name, v))
            else:
                pairs = [tuple(p) for p in names]
        bases = (cls,) if type is None else (type, cls)
        ns = dict(pairs)
        ns["__module__"] = module if module is not None else "__main__"
        return _type(class_name, bases, ns)

    @classmethod
    def _missing_(cls, value):
        return None

    def __class_getitem__(cls, name):
        members = getattr(cls, "__members__", {})
        if name not in members:
            raise KeyError(name)
        return members[name]

    def __repr__(self):
        return f"<{type(self).__name__}.{self._name_}: {self._value_!r}>"

    def __str__(self):
        return f"{type(self).__name__}.{self._name_}"

    def __format__(self, spec):
        return format(str(self), spec)

    def __setattr__(self, name, value):
        if name in ("name", "value", "_name_", "_value_"):
            raise AttributeError(f"cannot reassign the {name} of {self!r}")
        object.__setattr__(self, name, value)

    def __copy__(self):
        return self

    def __deepcopy__(self, memo):
        return self

    def __reduce_ex__(self, proto):
        return self.__class__, (self._value_,)

    @classmethod
    def lookup(cls, value):
        return cls(value)

    @classmethod
    def members(cls):
        return list(cls._member_map_.values())


def _own_dicts(cls):
    """The names the classes between cls and Enum define themselves."""
    names = set()
    for base in cls.__mro__:
        if base is Enum or base in _MIXINS or base is object:
            break
        names.update(getattr(base, "__dict__", {}))
    return names


class ReprEnum(Enum):
    """Only the repr changes: str() and format() are the mixed-in type's."""

    def __str__(self):
        member_type = type(self)._member_type_
        if member_type is object:
            return Enum.__str__(self)
        return member_type.__repr__(self) if member_type is not str else member_type.__str__(self)

    def __format__(self, spec):
        member_type = type(self)._member_type_
        if member_type is object:
            return Enum.__format__(self, spec)
        return member_type.__format__(self, spec)


class IntEnum(int, ReprEnum):
    pass


class StrEnum(str, ReprEnum):
    @staticmethod
    def _generate_next_value_(name, start, count, last_values):
        return name.lower()


class Flag(Enum):
    """Members that combine with |, & and ^ into values naming several."""

    @staticmethod
    def _generate_next_value_(name, start, count, last_values):
        ints = [v for v in last_values if isinstance(v, int)]
        if not ints:
            return start
        return 1 << max(ints).bit_length()

    @classmethod
    def _missing_(cls, value):
        if not isinstance(value, int):
            return None
        names = []
        covered = 0
        for m in cls._member_map_.values():
            v = m._value_
            if v and v & (v - 1) == 0 and value & v == v:
                names.append(m._name_)
                covered |= v
        if value & ~covered:
            if cls._member_type_ is object:
                return None
            names.append(str(value & ~covered))
        if cls._member_type_ is object:
            pseudo = object.__new__(cls)
        else:
            pseudo = cls._member_type_.__new__(cls, value)
        name = "|".join(names) if names else None
        for attr, v in (("_name_", name), ("_value_", value), ("name", name), ("value", value)):
            object.__setattr__(pseudo, attr, v)
        cls._value2member_map_[value] = pseudo
        return pseudo

    def _other_value(self, other):
        if isinstance(other, type(self)):
            return other._value_
        member_type = type(self)._member_type_
        if member_type is not object and isinstance(other, member_type):
            return other
        return NotImplemented

    def __or__(self, other):
        v = self._other_value(other)
        if v is NotImplemented:
            return v
        return type(self)(self._value_ | v)

    def __and__(self, other):
        v = self._other_value(other)
        if v is NotImplemented:
            return v
        return type(self)(self._value_ & v)

    def __xor__(self, other):
        v = self._other_value(other)
        if v is NotImplemented:
            return v
        return type(self)(self._value_ ^ v)

    __ror__ = __or__
    __rand__ = __and__
    __rxor__ = __xor__

    def __invert__(self):
        mask = 0
        for m in type(self)._member_map_.values():
            mask |= m._value_
        return type(self)(mask & ~self._value_)

    def __contains__(self, other):
        if not isinstance(other, type(self)):
            raise TypeError(f"unsupported operand type(s) for 'in': {type(other).__qualname__!r} and {type(self).__qualname__!r}")
        return other._value_ & self._value_ == other._value_

    def __bool__(self):
        return bool(self._value_)

    def __iter__(self):
        for m in type(self)._member_map_.values():
            v = m._value_
            if v and v & (v - 1) == 0 and self._value_ & v == v:
                yield m

    def __len__(self):
        return len(list(self.__iter__()))

    def __repr__(self):
        if self._name_ is None:
            return f"<{type(self).__name__}: {self._value_!r}>"
        return f"<{type(self).__name__}.{self._name_}: {self._value_!r}>"

    def __str__(self):
        if self._name_ is None:
            return f"{type(self).__name__}({self._value_!r})"
        return f"{type(self).__name__}.{self._name_}"


class IntFlag(int, ReprEnum, Flag):
    pass


def unique(cls):
    duplicates = [(name, member._name_) for name, member in cls.__members__.items() if name != member._name_]
    if duplicates:
        details = ", ".join(f"{alias} -> {name}" for alias, name in duplicates)
        raise ValueError(f"duplicate values found in {cls!r}: {details}")
    return cls


class verify:
    def __init__(self, *checks):
        self.checks = checks

    def __call__(self, cls):
        return cls


class EnumCheck(Enum):
    CONTINUOUS = "no skipped integer values"
    NAMED_FLAGS = "multi-flag aliases may not contain unnamed flags"
    UNIQUE = "one name per value"


CONTINUOUS, NAMED_FLAGS, UNIQUE = EnumCheck.CONTINUOUS, EnumCheck.NAMED_FLAGS, EnumCheck.UNIQUE


class FlagBoundary(StrEnum):
    STRICT = auto()
    CONFORM = auto()
    EJECT = auto()
    KEEP = auto()


STRICT, CONFORM, EJECT, KEEP = FlagBoundary.STRICT, FlagBoundary.CONFORM, FlagBoundary.EJECT, FlagBoundary.KEEP


def member(value):
    return value


def nonmember(value):
    return value


EnumType = type
EnumMeta = type
