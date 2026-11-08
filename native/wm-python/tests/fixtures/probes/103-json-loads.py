import json
[json.loads('{"a": [1, 2.5, -3e2, "\\u00e9", null, true, false], "b": {}}'), json.loads("  42 "), json.loads('"x\\ty"'), json.loads("[]")]
