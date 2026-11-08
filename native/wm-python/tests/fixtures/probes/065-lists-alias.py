a = [1]
b = a
b.append(2)
c = a[:]
c.append(3)
[a, b, c, a is b, a is c, a == b]
