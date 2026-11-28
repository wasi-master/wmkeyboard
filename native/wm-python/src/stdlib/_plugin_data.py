"""_plugin_data: the data files that came with a plugin's packages.

A plugin holds only Python modules, so `wmpy pip` and the web editor keep
the files a package has beside its modules (JSON schemas, word lists,
templates) in a module they generate inside it, <package>/_wmdata.py (and
_wmdata_2.py, ... when there is more), as TEXT (path: str) and DATA (path:
base64 str). This reads them by the path a module's __file__ leads to,
/plugin/<package>/<file>, for open(), os.stat, os.listdir, pathlib,
importlib.resources and pkgutil.get_data. Nothing here writes.
"""

PREFIX = "/plugin/"
_tables = {}


def _table(root):
    """{relative path: (kind, payload)} of package [root], or {} when it keeps no data."""
    table = _tables.get(root)
    if table is not None:
        return table
    table = {}
    import importlib

    n = 1
    while True:
        name = f"{root}._wmdata" if n == 1 else f"{root}._wmdata_{n}"
        try:
            mod = importlib.import_module(name)
        except ImportError:
            break
        for rel, text in getattr(mod, "TEXT", {}).items():
            table[rel] = ("t", text)
        for rel, b64 in getattr(mod, "DATA", {}).items():
            table[rel] = ("b", b64)
        n += 1
    _tables[root] = table
    return table


def split(path):
    """(package, path inside it) for a path under /plugin/, else None."""
    try:
        import os

        path = os.fspath(path)
    except TypeError:
        return None
    if isinstance(path, bytes):
        path = path.decode("utf-8", "surrogateescape")
    if not isinstance(path, str):
        return None
    import posixpath

    if not path.startswith("/"):
        path = posixpath.join("/", path)
    path = posixpath.normpath(path)
    if not path.startswith(PREFIX):
        return None
    root, _, rel = path[len(PREFIX):].partition("/")
    if not root:
        return None
    return root, rel


def read(path):
    """The bytes of the data file at [path], or None when there is none."""
    where = split(path)
    if where is None or not where[1]:
        return None
    entry = _table(where[0]).get(where[1])
    if entry is None:
        return None
    kind, payload = entry
    if kind == "t":
        return payload.encode("utf-8")
    import base64

    return base64.b64decode(payload)


def text(path):
    """The text of the data file at [path] when it was kept as text, else None (then read() it)."""
    where = split(path)
    if where is None or not where[1]:
        return None
    entry = _table(where[0]).get(where[1])
    return entry[1] if entry is not None and entry[0] == "t" else None


def is_file(path):
    where = split(path)
    return where is not None and bool(where[1]) and where[1] in _table(where[0])


def is_dir(path):
    """Whether [path] is a folder of data: the package's own, or one its data files sit in."""
    where = split(path)
    if where is None:
        return False
    table = _table(where[0])
    if not table:
        return False
    if not where[1]:
        return True
    prefix = where[1] + "/"
    return any(rel.startswith(prefix) for rel in table)


def listdir(path):
    """The names of the data files and folders directly in [path], or None when it is not a data folder."""
    if not is_dir(path):
        return None
    root, rel = split(path)
    prefix = rel + "/" if rel else ""
    names = []
    for key in _table(root):
        if key.startswith(prefix):
            name = key[len(prefix):].split("/", 1)[0]
            if name not in names:
                names.append(name)
    return sorted(names)


def size(path):
    data = read(path)
    return None if data is None else len(data)


def open_data(path, mode="r", encoding=None, errors=None, newline=None):
    """A read-only file object over the data file at [path], or None when there is none.
    Anything but reading raises PermissionError."""
    if any(c in mode for c in "wax+"):
        if split(path) is not None and (is_file(path) or is_dir(path)):
            raise PermissionError(13, "a package's data files are read-only in plugins", str(path))
        return None
    data = read(path)
    if data is None:
        return None
    import io

    if "b" in mode:
        f = io.BytesIO(data)
    else:
        f = io.TextIOWrapper(io.BytesIO(data), encoding=encoding or "utf-8", errors=errors, newline=newline)
    try:
        f.name = str(path)
    except (AttributeError, TypeError):
        pass
    return f
