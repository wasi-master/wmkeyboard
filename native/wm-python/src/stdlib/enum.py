"""enum: named constants.

    class Color(Enum):
        RED = 1
        GREEN = 2

`Color.RED.name` is "RED", `.value` is 1, `Color(1)` and `Color["RED"]`
find a member, `for c in Color` walks them in order and `len(Color)` counts
them.
"""


class _Auto:
    pass


def auto():
    return _Auto()


class Enum:
    def __init_subclass__(cls):
        members = {}
        values = {}
        next_value = 1
        for name, value in list(vars(cls).items()):
            if name.startswith("_") or callable(value):
                continue
            if isinstance(value, _Auto):
                value = next_value
            if isinstance(value, int):
                next_value = value + 1
            member = object.__new__(cls)
            object.__setattr__(member, "name", name)
            object.__setattr__(member, "value", value)
            object.__setattr__(member, "_name_", name)
            object.__setattr__(member, "_value_", value)
            setattr(cls, name, member)
            members[name] = member
            key = repr(value)
            if key not in values:
                values[key] = member
        if members:
            cls._member_map_ = members
            cls._value_map_ = values

    def __new__(cls, value):
        member = getattr(cls, "_value_map_", {}).get(repr(value))
        if member is None:
            raise ValueError(f"{value!r} is not a valid {cls.__name__}")
        return member

    def __class_getitem__(cls, name):
        members = getattr(cls, "_member_map_", {})
        if name not in members:
            raise KeyError(name)
        return members[name]

    def __repr__(self):
        return f"<{type(self).__name__}.{self.name}: {self.value!r}>"

    def __str__(self):
        return f"{type(self).__name__}.{self.name}"

    def __eq__(self, other):
        return self is other

    def __hash__(self):
        return hash((type(self).__name__, self.name))

    def __setattr__(self, name, value):
        raise AttributeError("enum members cannot be changed")

    @classmethod
    def lookup(cls, value):
        member = cls._value_map_.get(repr(value))
        if member is None:
            raise ValueError(f"{value!r} is not a valid {cls.__name__}")
        return member

    @classmethod
    def members(cls):
        return list(cls._member_map_.values())


class IntEnum(Enum):
    def __int__(self):
        return self.value

    def __eq__(self, other):
        if isinstance(other, int):
            return self.value == other
        return self is other

    def __lt__(self, other):
        return self.value < (other.value if isinstance(other, IntEnum) else other)

    def __hash__(self):
        return hash(self.value)


class StrEnum(Enum):
    def __str__(self):
        return str(self.value)

    def __eq__(self, other):
        if isinstance(other, str):
            return self.value == other
        return self is other

    def __hash__(self):
        return hash(self.value)


Flag = IntEnum


def unique(cls):
    seen = {}
    for member in cls._member_map_.values():
        key = repr(member.value)
        if key in seen:
            raise ValueError(f"duplicate values found in {cls.__name__}: {member.name} -> {seen[key]}")
        seen[key] = member.name
    return cls
