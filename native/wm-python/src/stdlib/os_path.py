"""os.path, for POSIX paths: text only, no file is looked at, but for the
data files that came with a plugin's packages (see _plugin_data)."""

sep = "/"
curdir = "."
pardir = ".."
extsep = "."
altsep = None
pathsep = ":"
defpath = "/bin:/usr/bin"
devnull = "/dev/null"
supports_unicode_filenames = False


def isabs(s):
    return s.startswith("/")


def join(a, *p):
    path = a
    for b in p:
        if b.startswith("/"):
            path = b
        elif not path or path.endswith("/"):
            path = path + b
        else:
            path = path + "/" + b
    return path


def split(p):
    i = p.rfind("/") + 1
    head, tail = p[:i], p[i:]
    if head and head != "/" * len(head):
        head = head.rstrip("/")
    return head, tail


def basename(p):
    return p[p.rfind("/") + 1:]


def dirname(p):
    return split(p)[0]


def splitext(p):
    sep_index = p.rfind("/")
    dot_index = p.rfind(".")
    if dot_index > sep_index:
        filename_index = sep_index + 1
        while filename_index < dot_index:
            if p[filename_index] != ".":
                return p[:dot_index], p[dot_index:]
            filename_index += 1
    return p, ""


def splitdrive(p):
    return "", p


def normpath(path):
    if not path:
        return "."
    initial_slashes = 1 if path.startswith("/") else 0
    if initial_slashes and path.startswith("//") and not path.startswith("///"):
        initial_slashes = 2
    comps = path.split("/")
    new_comps = []
    for comp in comps:
        if comp in ("", "."):
            continue
        if comp != ".." or (not initial_slashes and not new_comps) or (new_comps and new_comps[-1] == ".."):
            new_comps.append(comp)
        elif new_comps:
            new_comps.pop()
    path = "/".join(new_comps)
    if initial_slashes:
        path = "/" * initial_slashes + path
    return path or "."


def abspath(path):
    return normpath(path if isabs(path) else join("/", path))


def relpath(path, start="."):
    start_list = [x for x in abspath(start).split("/") if x]
    path_list = [x for x in abspath(path).split("/") if x]
    i = 0
    while i < min(len(start_list), len(path_list)) and start_list[i] == path_list[i]:
        i += 1
    rel_list = [".."] * (len(start_list) - i) + path_list[i:]
    return join(*rel_list) if rel_list else "."


def commonprefix(m):
    if not m:
        return ""
    s1 = min(m)
    s2 = max(m)
    for i, c in enumerate(s1):
        if c != s2[i]:
            return s1[:i]
    return s1


def commonpath(paths):
    if not paths:
        raise ValueError("commonpath() arg is an empty sequence")
    split_paths = [[c for c in p.split("/") if c and c != "."] for p in paths]
    prefix = "/" if paths[0].startswith("/") else ""
    common = split_paths[0]
    for s in split_paths[1:]:
        i = 0
        while i < min(len(common), len(s)) and common[i] == s[i]:
            i += 1
        common = common[:i]
    return prefix + "/".join(common)


def expanduser(path):
    return path


def expandvars(path):
    return path


def normcase(s):
    return s


def exists(path):
    import _plugin_data

    return _plugin_data.is_file(path) or _plugin_data.is_dir(path)


def isfile(path):
    import _plugin_data

    return _plugin_data.is_file(path)


def isdir(s):
    import _plugin_data

    return _plugin_data.is_dir(s)


def islink(path):
    return False


def realpath(path, *, strict=False):
    """The path made absolute and normalised: there are no links to follow."""
    return abspath(path)


def samefile(f1, f2):
    raise FileNotFoundError(2, "No such file or directory", f1)


def getsize(filename):
    import _plugin_data

    n = _plugin_data.size(filename)
    if n is None:
        raise FileNotFoundError(2, "No such file or directory", filename)
    return n


def getmtime(filename):
    if exists(filename):
        return 0.0
    raise FileNotFoundError(2, "No such file or directory", filename)


getatime = getmtime
getctime = getmtime


def ismount(path):
    return path == "/"


def isjunction(path):
    return False


def splitroot(p):
    if not p.startswith("/"):
        return "", "", p
    if p.startswith("//") and not p.startswith("///"):
        return "", "//", p[2:]
    return "", "/", p.lstrip("/")


lexists = exists
