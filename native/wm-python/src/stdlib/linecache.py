"""linecache: lines of source files, for tracebacks.

Plugins have no files to read lines from, so every lookup comes back empty,
as CPython's does for a file it cannot find.
"""

__all__ = ["getline", "clearcache", "checkcache", "lazycache"]

cache = {}


def getline(filename, lineno, module_globals=None):
    lines = getlines(filename, module_globals)
    if 1 <= lineno <= len(lines):
        return lines[lineno - 1]
    return ""


def getlines(filename, module_globals=None):
    if filename in cache:
        entry = cache[filename]
        if len(entry) != 1:
            return entry[2]
    return []


def clearcache():
    cache.clear()


def checkcache(filename=None):
    pass


def updatecache(filename, module_globals=None):
    return []


def lazycache(filename, module_globals):
    return False
