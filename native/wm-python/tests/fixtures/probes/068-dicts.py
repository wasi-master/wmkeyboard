d = {"b": 1, "a": 2}
d["c"] = 3
d.update({"a": 20, "z": 0})
v = d.pop("b")
s = d.setdefault("q", 7)
[d, v, s, d.get("nope"), d.get("nope", 1), list(d.keys()), list(d.values()), d.items(), "a" in d, len(d)]
