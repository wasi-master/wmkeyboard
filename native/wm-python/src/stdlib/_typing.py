"""_typing: the parts of typing that CPython 3.13 writes in C
(Objects/typevarobject.c): TypeVar, ParamSpec, TypeVarTuple, their .args
and .kwargs, TypeAliasType, Generic and NoDefault. Written here in Python,
calling back into typing for the checks and substitutions as the C does."""

import sys as _sys

_TYPING = "typing"


def _typing():
    return __import__(_TYPING)


def _idfunc(_, x):
    return x


class NoDefaultType:
    """The type of the NoDefault singleton."""

    __slots__ = ()

    def __new__(cls, *args, **kwargs):
        if args or kwargs:
            raise TypeError("NoDefaultType takes no arguments")
        return NoDefault

    def __repr__(self):
        return "typing.NoDefault"

    def __reduce__(self):
        return "NoDefault"


NoDefault = object.__new__(NoDefaultType)
NoDefaultType.__module__ = "builtins"


def _type_check(arg, msg):
    if arg is None:
        return None
    return _typing()._type_check(arg, msg)


def _check_name(kind, name):
    if not isinstance(name, str):
        raise TypeError(f"{kind}() argument 'name' must be str, not {type(name).__name__}")


def _check_variance(covariant, contravariant, infer_variance):
    if covariant and contravariant:
        raise ValueError("Bivariant types are not supported.")
    if infer_variance and (covariant or contravariant):
        raise ValueError("Variance cannot be specified with infer_variance.")


def _variance_repr(self):
    if self.__infer_variance__:
        return self.__name__
    if self.__covariant__:
        return "+" + self.__name__
    if self.__contravariant__:
        return "-" + self.__name__
    return "~" + self.__name__


def _no_subclass(kind):
    def __init_subclass__(cls, *args, **kwargs):
        raise TypeError(f"type 'typing.{kind}' is not an acceptable base type")

    return classmethod(__init_subclass__)


def _no_mro_entries(kind):
    def __mro_entries__(self, bases):
        raise TypeError(f"Cannot subclass an instance of {kind}")

    return __mro_entries__


class _Readonly:
    """An attribute a type variable sets once, in its constructor."""

    def __init__(self, slot):
        self.slot = slot

    def __set_name__(self, owner, name):
        self.name = name

    def __get__(self, obj, owner=None):
        if obj is None:
            return self
        return obj.__dict__[self.slot]

    def __set__(self, obj, value):
        raise AttributeError("readonly attribute")

    def __delete__(self, obj):
        raise AttributeError("readonly attribute")


def _set(obj, default=NoDefault, **values):
    d = obj.__dict__
    for k, v in values.items():
        d["_tv_" + k] = v
    # Assignable, unlike CPython 3.13's: code written for 3.12's typing
    # (typing_extensions before 3.13) sets it on a typing.TypeVar.
    d["__default__"] = default
    # The module that made it, as the C code reads off the calling frame.
    try:
        module = _sys._getframemodulename(2)
    except AttributeError:
        module = None
    if module is not None:
        d["__module__"] = module


def _prepare_typevar_subst(self, alias, args):
    params = alias.__parameters__
    i = params.index(self)
    if i < len(args):
        return args
    if i == len(args) and self.__default__ is not NoDefault:
        return (*args, self.__default__)
    raise TypeError(f"Too few arguments for {alias}; actual {len(args)}, expected at least {i + 1}")


class TypeVar:
    """Type variable.

    The preferred way to construct a type variable is via the dedicated
    syntax for generic functions, classes, and type aliases::

        class Sequence[T]:  # T is a TypeVar
            ...

    This syntax can also be used to create bound and constrained type
    variables::

        # S is a TypeVar bound to str
        class StrSequence[S: str]:
            ...

        # A is a TypeVar constrained to str or bytes
        class StrOrBytesSequence[A: (str, bytes)]:
            ...

    Type variables can also have defaults:

        class IntDefault[T = int]:
            ...

    However, if desired, reusable type variables can also be constructed
    manually, like so::

       T = TypeVar('T')  # Can be anything
       S = TypeVar('S', bound=str)  # Can be any subtype of str
       A = TypeVar('A', str, bytes)  # Must be exactly str or bytes
       D = TypeVar('D', default=int)  # Defaults to int

    Type variables exist primarily for the benefit of static type
    checkers.  They serve as the parameters for generic types as well
    as for generic function and type alias definitions.

    The variance of type variables is inferred by type checkers when they
    are created through the type parameter syntax and when
    ``infer_variance=True`` is passed. Manually created type variables may
    be explicitly marked covariant or contravariant by passing
    ``covariant=True`` or ``contravariant=True``. By default, manually
    created type variables are invariant. See PEP 484 and PEP 695 for more
    details.
    """

    __name__ = _Readonly("_tv_name")
    __bound__ = _Readonly("_tv_bound")
    __constraints__ = _Readonly("_tv_constraints")
    __covariant__ = _Readonly("_tv_covariant")
    __contravariant__ = _Readonly("_tv_contravariant")
    __infer_variance__ = _Readonly("_tv_infer_variance")

    def __init__(self, name, *constraints, bound=None, covariant=False, contravariant=False,
                 infer_variance=False, default=NoDefault):
        _check_name("typevar", name)
        _check_variance(covariant, contravariant, infer_variance)
        bound = _type_check(bound, "Bound must be a type.")
        if len(constraints) == 1:
            raise TypeError("A single constraint is not allowed")
        if constraints and bound is not None:
            raise TypeError("Constraints cannot be combined with bound=...")
        _set(self, name=name, bound=bound, constraints=tuple(constraints), covariant=bool(covariant),
             contravariant=bool(contravariant), infer_variance=bool(infer_variance), default=default)

    __init_subclass__ = _no_subclass("TypeVar")
    __mro_entries__ = _no_mro_entries("TypeVar")
    __repr__ = _variance_repr

    def has_default(self):
        return self.__default__ is not NoDefault

    def __typing_subst__(self, arg):
        return _typing()._typevar_subst(self, arg)

    def __typing_prepare_subst__(self, alias, args):
        return _prepare_typevar_subst(self, alias, args)

    def __reduce__(self):
        return self.__name__

    def __or__(self, right):
        return _typing()._make_union(self, right)

    def __ror__(self, left):
        return _typing()._make_union(left, self)


class TypeVarTuple:
    """Type variable tuple. A specialized form of type variable that enables
    variadic generics.

    The preferred way to construct a type variable tuple is via the
    dedicated syntax for generic functions, classes, and type aliases,
    where a single '*' indicates a type variable tuple::

        def move_first_element_to_last[T, *Ts](tup: tuple[T, *Ts]) -> tuple[*Ts, T]:
            return (*tup[1:], tup[0])

    Type variables tuples can have default values:

        type AliasWithDefault[*Ts = (str, int)] = tuple[*Ts]

    For compatibility with Python 3.11 and earlier, TypeVarTuple objects
    can also be created as follows::

        Ts = TypeVarTuple('Ts')  # Can be given any name
        DefaultTs = TypeVarTuple('Ts', default=(str, int))

    Just as a TypeVar (type variable) is a placeholder for a single type,
    a TypeVarTuple is a placeholder for an *arbitrary* number of types. For
    example, if we define a generic class using a TypeVarTuple::

        class C[*Ts]: ...

    Then we can parameterize that class with an arbitrary number of type
    arguments::

        C[int]       # Fine
        C[int, str]  # Also fine
        C[()]        # Even this is fine

    For more details, see PEP 646.

    Note that only TypeVarTuples defined in the global scope can be
    pickled.
    """

    __name__ = _Readonly("_tv_name")

    def __init__(self, name, *, default=NoDefault):
        _check_name("typevartuple", name)
        _set(self, name=name, default=default)

    __init_subclass__ = _no_subclass("TypeVarTuple")
    __mro_entries__ = _no_mro_entries("TypeVarTuple")

    def __iter__(self):
        yield _typing().Unpack[self]

    def __repr__(self):
        return self.__name__

    def has_default(self):
        return self.__default__ is not NoDefault

    def __typing_subst__(self, arg):
        raise TypeError("Substitution of bare TypeVarTuple is not supported")

    def __typing_prepare_subst__(self, alias, args):
        return _typing()._typevartuple_prepare_subst(self, alias, args)

    def __reduce__(self):
        return self.__name__


class ParamSpecArgs:
    """The args for a ParamSpec object.

    Given a ParamSpec object P, P.args is an instance of ParamSpecArgs.

    ParamSpecArgs objects have a reference back to their ParamSpec::

        >>> P = ParamSpec("P")
        >>> P.args.__origin__ is P
        True

    This type is meant for runtime introspection and has no special meaning
    to static type checkers.
    """

    def __init__(self, origin):
        self.__origin__ = origin

    __init_subclass__ = _no_subclass("ParamSpecArgs")
    __mro_entries__ = _no_mro_entries("ParamSpecArgs")

    def __repr__(self):
        if isinstance(self.__origin__, ParamSpec):
            return f"{self.__origin__.__name__}.args"
        return f"{self.__origin__!r}.args"

    def __eq__(self, other):
        if not isinstance(other, ParamSpecArgs):
            return NotImplemented
        return self.__origin__ == other.__origin__

    __hash__ = None


class ParamSpecKwargs:
    """The kwargs for a ParamSpec object.

    Given a ParamSpec object P, P.kwargs is an instance of ParamSpecKwargs.

    ParamSpecKwargs objects have a reference back to their ParamSpec::

        >>> P = ParamSpec("P")
        >>> P.kwargs.__origin__ is P
        True

    This type is meant for runtime introspection and has no special meaning
    to static type checkers.
    """

    def __init__(self, origin):
        self.__origin__ = origin

    __init_subclass__ = _no_subclass("ParamSpecKwargs")
    __mro_entries__ = _no_mro_entries("ParamSpecKwargs")

    def __repr__(self):
        if isinstance(self.__origin__, ParamSpec):
            return f"{self.__origin__.__name__}.kwargs"
        return f"{self.__origin__!r}.kwargs"

    def __eq__(self, other):
        if not isinstance(other, ParamSpecKwargs):
            return NotImplemented
        return self.__origin__ == other.__origin__

    __hash__ = None


class ParamSpec:
    """Parameter specification variable.

    The preferred way to construct a parameter specification is via the
    dedicated syntax for generic functions, classes, and type aliases,
    where the use of '**' creates a parameter specification::

        type IntFunc[**P] = Callable[P, int]

    The following syntax creates a parameter specification that defaults
    to a callable accepting two positional-only arguments of types int
    and str:

        type IntFuncDefault[**P = (int, str)] = Callable[P, int]

    For compatibility with Python 3.11 and earlier, ParamSpec objects
    can also be created as follows::

        P = ParamSpec('P')
        DefaultP = ParamSpec('DefaultP', default=(int, str))

    Parameter specification variables exist primarily for the benefit of
    static type checkers.  They are used to forward the parameter types of
    one callable to another callable, a pattern commonly found in
    higher-order functions and decorators.  They are only valid when used
    in ``Concatenate``, or as the first argument to ``Callable``, or as
    parameters for user-defined Generics. See class Generic for more
    information on generic types.

    An example for annotating a decorator::

        def add_logging[**P, T](f: Callable[P, T]) -> Callable[P, T]:
            '''A type-safe decorator to add logging to a function.'''
            def inner(*args: P.args, **kwargs: P.kwargs) -> T:
                logging.info(f'{f.__name__} was called')
                return f(*args, **kwargs)
            return inner

        @add_logging
        def add_two(x: float, y: float) -> float:
            '''Add two numbers together.'''
            return x + y

    Parameter specification variables can be introspected. e.g.::

        >>> P = ParamSpec("P")
        >>> P.__name__
        'P'

    Note that only parameter specification variables defined in the global
    scope can be pickled.
    """

    __name__ = _Readonly("_tv_name")
    __bound__ = _Readonly("_tv_bound")
    __covariant__ = _Readonly("_tv_covariant")
    __contravariant__ = _Readonly("_tv_contravariant")
    __infer_variance__ = _Readonly("_tv_infer_variance")

    def __init__(self, name, *, bound=None, covariant=False, contravariant=False, infer_variance=False,
                 default=NoDefault):
        _check_name("paramspec", name)
        _check_variance(covariant, contravariant, infer_variance)
        bound = _type_check(bound, "Bound must be a type.")
        _set(self, name=name, bound=bound, covariant=bool(covariant), contravariant=bool(contravariant),
             infer_variance=bool(infer_variance), default=default)

    __init_subclass__ = _no_subclass("ParamSpec")
    __mro_entries__ = _no_mro_entries("ParamSpec")
    __repr__ = _variance_repr

    @property
    def args(self):
        return ParamSpecArgs(self)

    @property
    def kwargs(self):
        return ParamSpecKwargs(self)

    def has_default(self):
        return self.__default__ is not NoDefault

    def __typing_subst__(self, arg):
        return _typing()._paramspec_subst(self, arg)

    def __typing_prepare_subst__(self, alias, args):
        return _typing()._paramspec_prepare_subst(self, alias, args)

    def __reduce__(self):
        return self.__name__

    def __or__(self, right):
        return _typing()._make_union(self, right)

    def __ror__(self, left):
        return _typing()._make_union(left, self)


# The class `type X = ...` makes, from the interpreter's builtins.
TypeAliasType = type(__type_alias__("_", None))


class Generic:
    """Abstract base class for generic types.

    On Python 3.12 and newer, generic classes implicitly inherit from
    Generic when they declare a parameter list after the class's name::

        class Mapping[KT, VT]:
            def __getitem__(self, key: KT) -> VT:
                ...
            # Etc.

    On older versions of Python, however, generic classes have to
    explicitly inherit from Generic.

    After a class has been declared to be generic, it can then be used as
    follows::

        def lookup_name[KT, VT](mapping: Mapping[KT, VT], key: KT, default: VT) -> VT:
            try:
                return mapping[key]
            except KeyError:
                return default
    """

    _is_protocol = False

    def __class_getitem__(cls, params):
        return _typing()._generic_class_getitem(cls, params)

    def __init_subclass__(cls, *args, **kwargs):
        _typing()._generic_init_subclass(cls, *args, **kwargs)

    __class_getitem__ = classmethod(__class_getitem__)
    __init_subclass__ = classmethod(__init_subclass__)


for _t in (TypeVar, TypeVarTuple, ParamSpec, ParamSpecArgs, ParamSpecKwargs, Generic):
    _t.__module__ = "typing"
del _t
