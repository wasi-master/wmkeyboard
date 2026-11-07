f = lambda x, y=2: x * y
g = lambda *a: len(a)
[f(3), f(3, 4), g(1, 2, 3), (lambda: 1)(), 1 if 0 else 2]
