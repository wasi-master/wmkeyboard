d = {1: "a", 2: "b"}
e = d.copy()
x = d.popitem()
d.clear()
[d, e, x, {1: 1, 1.0: 2, True: 3}, dict(zip("ab", [1, 2]))]
