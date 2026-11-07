g = 1
def f():
    global g
    del g
f()
"g" in str([1])
