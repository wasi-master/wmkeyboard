import re
[re.findall(r"cat|dog|bird", "dogcatbirdcow"), re.match(r"(a|ab)(c|bcd)", "abcd").groups(), re.findall(r"(?:ab)+", "ababab ab"), re.sub(r"(a)|(b)", r"[\1\2]", "ab"), re.findall(r"a|", "aba")]
