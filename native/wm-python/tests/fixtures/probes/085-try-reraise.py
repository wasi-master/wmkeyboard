def f():
    try:
        int("x")
    except ValueError:
        raise
f()
