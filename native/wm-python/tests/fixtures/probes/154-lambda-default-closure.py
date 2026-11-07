fs = [lambda x, i=i: x + i for i in range(3)]
[f(10) for f in fs]
