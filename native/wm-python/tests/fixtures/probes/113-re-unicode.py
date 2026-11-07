import re
[re.findall(r"\w+", "café naïve 中文"), re.sub(r".", "-", "e\u0301a"), re.search("é", "cafe\u0301").span() if re.search("é", "cafe\u0301") else None]
