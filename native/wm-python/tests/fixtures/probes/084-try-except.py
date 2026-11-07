out = []
try:
    [1][5]
except IndexError as e:
    out.append(str(e))
try:
    {}["k"]
except LookupError as e:
    out.append(repr(e))
try:
    1 / 0
except (TypeError, ZeroDivisionError) as e:
    out.append(type(e))
finally:
    out.append("fin")
try:
    raise ValueError("v")
except Exception as e:
    out.append(e.args)
try:
    raise "plain"
except Exception as e:
    out.append(str(e))
out
