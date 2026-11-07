def f():
    try:
        return "try"
    finally:
        print("finally ran")
f()
