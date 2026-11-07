import re
m = re.search(r"(\w+)@(\w+)\.com", "mail bob@example.com now")
[m.group(), m.group(1), m.group(2), m.groups(), m.start(), m.end(), m.span(1), m.string, m.lastindex, m[0]]
