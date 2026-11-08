d = {}
for k in ["z", "a", "m"]:
    d[k] = len(k)
del d["a"]
d["a"] = 9
[list(d), [k for k in d], sorted(d.items())]
