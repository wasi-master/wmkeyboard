"""fnmatch: shell-style wildcards (*, ?, [seq], [!seq]) matched against names."""

import re

_cache = {}


def translate(pat):
    i, n = 0, len(pat)
    res = []
    while i < n:
        c = pat[i]
        i += 1
        if c == "*":
            if not res or res[-1] != ".*":
                res.append(".*")
        elif c == "?":
            res.append(".")
        elif c == "[":
            j = i
            if j < n and pat[j] == "!":
                j += 1
            if j < n and pat[j] == "]":
                j += 1
            while j < n and pat[j] != "]":
                j += 1
            if j >= n:
                res.append("\\[")
            else:
                stuff = pat[i:j].replace("\\", "\\\\")
                i = j + 1
                if stuff[0] == "!":
                    stuff = "^" + stuff[1:]
                elif stuff[0] == "^":
                    stuff = "\\" + stuff
                res.append("[" + stuff + "]")
        else:
            res.append(re.escape(c))
    return "(?s)" + "".join(res) + "\\Z"


def _compiled(pat):
    if pat not in _cache:
        _cache[pat] = re.compile(translate(pat))
    return _cache[pat]


def fnmatchcase(name, pat):
    return _compiled(pat).match(name) is not None


def fnmatch(name, pat):
    return fnmatchcase(name, pat)


def filter(names, pat):
    match = _compiled(pat).match
    return [n for n in names if match(n) is not None]
