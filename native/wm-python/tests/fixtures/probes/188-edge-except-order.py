out = []
for exc in [ValueError("v"), KeyError("k"), ZeroDivisionError("z"), IndexError("i")]:
    try:
        raise exc
    except ArithmeticError:
        out.append("arith")
    except LookupError as e:
        out.append("lookup:" + str(e))
    except Exception as e:
        out.append(type(e))
out
