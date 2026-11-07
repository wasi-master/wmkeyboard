count = 0
def inc():
    global count
    count += 1
    return count
inc()
inc()
def outer():
    v = []
    def add(x):
        v.append(x)
    add(1)
    add(2)
    return v
[count, outer()]
