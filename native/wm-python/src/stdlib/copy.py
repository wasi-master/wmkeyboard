"""copy: shallow and deep copies, made the way CPython's copy module makes
them: through `__copy__` and `__deepcopy__`, `__reduce_ex__` or
`__reduce__`, `__getstate__` and `__setstate__`, and otherwise a new object
of the same class with the same attributes. A subclass of a built-in type
keeps its class: a copy of a dict subclass is that subclass again, with
its items."""


class Error(Exception):
    pass


error = Error


def _defined(cls, name):
    """Whether a class in cls's resolution order (not a built-in one) defines name."""
    for k in getattr(cls, "__mro__", (cls,)):
        if getattr(k, "__module__", None) == "builtins":
            continue
        if name in getattr(k, "__dict__", {}):
            return True
    return False


def _is_object(x):
    return hasattr(x, "__class__") and hasattr(x, "__dict__")


def _builtin_base(x):
    for base in (dict, list, set, tuple, str, int, float, bytes):
        if isinstance(x, base):
            return base
    return None


def copy(x):
    cls = type(x)
    if cls in (list, dict, set):
        return cls(x)
    if not _is_object(x):
        return x
    if _defined(cls, "__copy__"):
        return x.__copy__()
    return _reconstruct(x, None)


def deepcopy(x, memo=None, _nil=[]):
    if memo is None:
        memo = {}
    d = id(x)
    y = memo.get(d, _nil)
    if y is not _nil:
        return y
    cls = type(x)
    if cls is list:
        y = []
        memo[d] = y
        for item in x:
            y.append(deepcopy(item, memo))
    elif cls is dict:
        y = {}
        memo[d] = y
        for k, v in x.items():
            y[deepcopy(k, memo)] = deepcopy(v, memo)
    elif cls is set:
        y = set(deepcopy(item, memo) for item in x)
    elif cls is tuple:
        y = tuple(deepcopy(item, memo) for item in x)
    elif not _is_object(x):
        y = x
    elif _defined(cls, "__deepcopy__"):
        y = x.__deepcopy__(memo)
    else:
        y = _reconstruct(x, memo)
    if y is not x:
        memo[d] = y
    return y


def _reconstruct(x, memo):
    deep = memo is not None
    cls = type(x)
    for name in ("__reduce_ex__", "__reduce__"):
        if _defined(cls, name):
            rv = x.__reduce_ex__(4) if name == "__reduce_ex__" else x.__reduce__()
            if isinstance(rv, str):
                return x
            return _from_reduce(x, rv, memo)
    base = _builtin_base(x)
    if base in (tuple, str, int, float, bytes):
        # As copyreg does: cls.__new__(cls, *x.__getnewargs__()).
        args = x.__getnewargs__()
        if deep:
            args = deepcopy(args, memo)
        y = cls.__new__(cls, *args)
    elif base is not None:
        y = cls.__new__(cls)
    else:
        y = object.__new__(cls)
    if deep:
        memo[id(x)] = y
    if _defined(cls, "__getstate__"):
        state = x.__getstate__()
    else:
        state = dict(vars(x))
    if state:
        if deep:
            state = deepcopy(state, memo)
        _set_state(y, state)
    if base is list:
        for item in x:
            y.append(deepcopy(item, memo) if deep else item)
    elif base is dict:
        for k, v in x.items():
            if deep:
                k, v = deepcopy(k, memo), deepcopy(v, memo)
            y[k] = v
    elif base is set:
        for item in x:
            y.add(deepcopy(item, memo) if deep else item)
    return y


def _set_state(y, state):
    if _defined(type(y), "__setstate__"):
        y.__setstate__(state)
        return
    slots = None
    if isinstance(state, tuple) and len(state) == 2:
        state, slots = state
    if state:
        for k, v in state.items():
            object.__setattr__(y, k, v)
    if slots:
        for k, v in slots.items():
            setattr(y, k, v)


def _from_reduce(x, rv, memo):
    deep = memo is not None
    func, args = rv[0], rv[1]
    state = rv[2] if len(rv) > 2 else None
    listiter = rv[3] if len(rv) > 3 else None
    dictiter = rv[4] if len(rv) > 4 else None
    if deep:
        args = deepcopy(args, memo)
    y = func(*args)
    if deep:
        memo[id(x)] = y
    if state is not None:
        if deep:
            state = deepcopy(state, memo)
        _set_state(y, state)
    if listiter is not None:
        for item in listiter:
            y.append(deepcopy(item, memo) if deep else item)
    if dictiter is not None:
        for k, v in dictiter:
            if deep:
                k, v = deepcopy(k, memo), deepcopy(v, memo)
            y[k] = v
    return y


def replace(obj, /, **changes):
    """Python 3.13's copy.replace: what the object's `__replace__` makes."""
    cls = obj.__class__
    func = getattr(cls, "__replace__", None)
    if func is None:
        raise TypeError(f"replace() does not support {cls.__name__} objects")
    return func(obj, **changes)
