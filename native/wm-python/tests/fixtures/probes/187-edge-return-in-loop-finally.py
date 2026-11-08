def f():
    for i in range(3):
        try:
            if i == 1:
                return i
        finally:
            print("fin", i)
    return -1
f()
