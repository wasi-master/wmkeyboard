def f(*a):
    return a
xs = [1, 2]
[f(*xs), f(0, *xs, 3), f(*"ab"), max(*xs), print(*xs)]
