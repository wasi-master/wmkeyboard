import re
[re.findall(r"a{2}", "aaaaa"), re.findall(r"a{2,}", "aaaaa"), re.findall(r"a{,2}", "aaa"), re.findall(r"a+?", "aaa"), re.search(r"<.+?>", "<a><b>").group(), re.findall(r"[a-c]+", "abcdcba"), re.findall("x{y", "x{y")]
