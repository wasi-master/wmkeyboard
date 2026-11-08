import json
[json.dumps("😀é\u0001\b"), json.loads('"\\ud83d\\ude00"') if False else 0, json.loads('"\\u00e9\\n"'), json.dumps({"a": 1.0, "b": 1e20, "c": -0.0}), json.loads("1e2"), json.loads("-0"), json.loads("[1,2,]") if False else 0]
