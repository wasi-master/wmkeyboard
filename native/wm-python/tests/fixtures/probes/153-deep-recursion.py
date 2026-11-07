def f(n):
    return 0 if n == 0 else 1 + f(n - 1)
f(250)
