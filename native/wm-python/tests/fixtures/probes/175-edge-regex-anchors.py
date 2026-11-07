import re
[re.findall(r"^\w", "ab\ncd", re.M), re.findall(r"\w$", "ab\ncd", re.M), re.findall(r"\w$", "ab\ncd"), re.search(r"\Aab\Z", "ab") is not None, re.sub(r"$", "!", "a\nb", flags=re.M), re.sub(r"^", ">", "a\nb", 0, re.M)]
