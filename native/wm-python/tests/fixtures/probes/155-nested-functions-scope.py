x = "global"
def f():
    x = "local"
    def g():
        return x
    return g()
[f(), x]
