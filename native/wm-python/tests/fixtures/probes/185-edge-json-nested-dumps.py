import json
print(json.dumps({"a": {"b": {"c": [1, [2, [3]]]}}}, indent=1))
json.dumps([[], {}, [[]]], indent=2)
