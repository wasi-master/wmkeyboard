import re
m = re.match(r"(?P<y>\d{4})-(?P<m>\d\d)(-(\d\d))?", "2024-05")
[m.groupdict(), m.group("y"), m.group(1, 2), m.groups(), m.groups("-"), m.start(3), m.span(4)]
