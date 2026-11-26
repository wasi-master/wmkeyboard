"""keyword: Python's reserved words."""

kwlist = [
    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
    "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal",
    "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
]
softkwlist = ["_", "case", "match", "type"]
_kw = set(kwlist)
_soft = set(softkwlist)


def iskeyword(s):
    return s in _kw


def issoftkeyword(s):
    return s in _soft
