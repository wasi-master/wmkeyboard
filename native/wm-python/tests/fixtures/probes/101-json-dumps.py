import json
[json.dumps({"a": 1, "b": [1.5, "x", None, True], "c": {}}), json.dumps([]), json.dumps("é\n\"q\""), json.dumps({"n": float("inf")}), json.dumps((1, 2)), json.dumps({1: 2})]
