import re
[re.search(r"a.*b", "axbxb").group(), re.search(r"a.*?b", "axbxb").group(), re.search(r"a??b", "ab").group(), re.findall(r"\d{2,3}?", "12345"), re.search(r"(a*)*b", "aaab").group(), re.search(r"(a|b)*c", "ababc").group(1)]
