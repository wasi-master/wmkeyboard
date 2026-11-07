out = []
try:
    try:
        raise TypeError("inner")
    except ValueError:
        out.append("wrong")
except TypeError as e:
    out.append("outer " + str(e))
out
