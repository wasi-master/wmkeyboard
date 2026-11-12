"""copy: shallow and deep copies of lists, dicts, sets, tuples and objects."""


def copy(x):
    if isinstance(x, list):
        return list(x)
    if isinstance(x, dict):
        return dict(x)
    if isinstance(x, set):
        return set(x)
    if hasattr(x, "__copy__"):
        return x.__copy__()
    if hasattr(x, "__dict__") and hasattr(x, "__class__"):
        out = object.__new__(type(x))
        for k, v in vars(x).items():
            object.__setattr__(out, k, v)
        return out
    return x


def deepcopy(x, memo=None):
    if memo is None:
        memo = {}
    key = id(x)
    if key in memo:
        return memo[key]
    if isinstance(x, list):
        out = []
        memo[key] = out
        for item in x:
            out.append(deepcopy(item, memo))
        return out
    if isinstance(x, dict):
        out = {}
        memo[key] = out
        for k, v in x.items():
            out[deepcopy(k, memo)] = deepcopy(v, memo)
        return out
    if isinstance(x, set):
        return set(deepcopy(item, memo) for item in x)
    if isinstance(x, tuple):
        return tuple(deepcopy(item, memo) for item in x)
    if hasattr(x, "__deepcopy__"):
        return x.__deepcopy__(memo)
    if hasattr(x, "__dict__") and hasattr(x, "__class__") and not callable(x):
        out = object.__new__(type(x))
        memo[key] = out
        for k, v in vars(x).items():
            object.__setattr__(out, k, deepcopy(v, memo))
        return out
    return x
