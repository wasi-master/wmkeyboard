def fib(n):
    return n if n < 2 else fib(n - 1) + fib(n - 2)
def fact(n):
    return 1 if n == 0 else n * fact(n - 1)
[fib(15), fact(20), fact(25)]
