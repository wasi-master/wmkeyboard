def f(a, b=2, *rest):
    return [a, b, rest]
def g(x):
    def h(y):
        return x + y
    return h
[f(1), f(1, 3), f(1, 3, 5, 6), f(b=9, a=8), g(10)(5)]
