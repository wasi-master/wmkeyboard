try:
    try:
        raise ValueError("a")
    except ValueError:
        raise KeyError("b")
except KeyError as e:
    r = repr(e)
r
