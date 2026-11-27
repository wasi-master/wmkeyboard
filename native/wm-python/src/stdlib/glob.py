"""glob, for a plugin, which has no files to find.

glob() and iglob() find nothing, as they do for a pattern no file matches.
escape(), has_magic() and translate() (CPython 3.13's) work on the text.
"""

import os
import re

__all__ = ["escape", "glob", "iglob", "translate"]

magic_check = re.compile("([*?[])")
magic_check_bytes = re.compile(b"([*?[])")


def glob(pathname, *, root_dir=None, dir_fd=None, recursive=False, include_hidden=False):
    return []


def iglob(pathname, *, root_dir=None, dir_fd=None, recursive=False, include_hidden=False):
    return iter(())


def glob0(dirname, pattern):
    return []


def glob1(dirname, pattern):
    return []


def has_magic(s):
    if isinstance(s, bytes):
        return magic_check_bytes.search(s) is not None
    return magic_check.search(s) is not None


def escape(pathname):
    drive, pathname = os.path.splitdrive(pathname)
    if isinstance(pathname, bytes):
        pathname = magic_check_bytes.sub(rb"[\1]", pathname)
    else:
        pathname = magic_check.sub(r"[\1]", pathname)
    return drive + pathname


_special_parts = ("", ".", "..")
_dir_open_flags = 0
_no_recurse_symlinks = object()


def _translate_part(pat, star, question_mark):
    """One path segment's wildcards as a regular expression, `*` and `?` kept inside the segment."""
    i, n = 0, len(pat)
    res = []
    while i < n:
        c = pat[i]
        i += 1
        if c == "*":
            if not res or res[-1] != star:
                res.append(star)
        elif c == "?":
            res.append(question_mark)
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
    return "".join(res)


def translate(pat, *, recursive=False, include_hidden=False, seps=None):
    """A glob pattern as a regular expression, as CPython 3.13's glob.translate gives it."""
    if not seps:
        seps = (os.path.sep, os.path.altsep) if os.path.altsep else os.path.sep
    escaped_seps = "".join(map(re.escape, seps))
    any_sep = f"[{escaped_seps}]" if len(seps) > 1 else escaped_seps
    not_sep = f"[^{escaped_seps}]"
    if include_hidden:
        one_last_segment = f"{not_sep}+"
        one_segment = f"{one_last_segment}{any_sep}"
        any_segments = f"(?:.+{any_sep})?"
        any_last_segments = ".*"
    else:
        one_last_segment = f"[^{escaped_seps}.]{not_sep}*"
        one_segment = f"{one_last_segment}{any_sep}"
        any_segments = f"(?:{one_segment})*"
        any_last_segments = f"{any_segments}(?:{one_last_segment})?"
    results = []
    parts = re.split(any_sep, pat)
    last_part_idx = len(parts) - 1
    for idx, part in enumerate(parts):
        if part == "*":
            results.append(one_segment if idx < last_part_idx else one_last_segment)
        elif recursive and part == "**":
            if idx < last_part_idx:
                if parts[idx + 1] != "**":
                    results.append(any_segments)
            else:
                results.append(any_last_segments)
        else:
            if part:
                if not include_hidden and part[0] in "*?":
                    results.append(r"(?!\.)")
                results.append(_translate_part(part, f"{not_sep}*", not_sep))
            if idx < last_part_idx:
                results.append(any_sep)
    res = "".join(results)
    # CPython writes (?s:...); the keyboard's regex engine takes the flag at the start, which means the same here.
    return rf"(?s){res}\Z"
