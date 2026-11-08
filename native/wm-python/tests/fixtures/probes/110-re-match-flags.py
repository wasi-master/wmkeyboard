import re
[re.match("ab", "abc") is not None, re.match("bc", "abc"), re.fullmatch(r"\d+", "123") is not None, re.fullmatch(r"\d+", "123a"), re.search("^b", "a\nb", re.M) is not None, re.search("a.b", "a\nb", re.S) is not None, re.search("HELLO", "hello", re.IGNORECASE).group(), re.escape("a.b*c")]
