import re
[re.sub(r"\s+", "_", "a  b   c"), re.sub(r"(\w+) (\w+)", r"\2 \1", "hello world"), re.sub(r"(?P<d>\d)", r"<\g<d>>", "a1b2"), re.subn("a", "b", "aaa"), re.sub("a", "b", "aaa", 2), re.sub(r"\d", lambda m: str(int(m.group()) * 2), "a1b2c3")]
