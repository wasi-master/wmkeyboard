import re
[re.findall(r"[\d.]+", "a1.5b22"), re.findall(r"[^\W\d]+", "ab12cd"), re.findall(r"[-a]", "a-b"), re.findall(r"[]a]", "]a"), re.findall(r"\s+", "a \t\nb"), re.findall(r"[A-Z]", "aBcD", re.I), re.findall(r"\bcat\b", "cat concat cat."), re.findall(r"\Bat", "cat at")]
