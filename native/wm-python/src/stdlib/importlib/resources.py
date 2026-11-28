"""importlib.resources: data files inside packages.

A plugin's packages are not folders on disk, but the data files that came
with them are kept (see _plugin_data), so files() gives a pathlib.Path to
the package's folder under /plugin/, as CPython gives for a package on disk:
it lists, opens and reads them. Writing raises PermissionError, and a file
the package did not come with is not there.
"""

import contextlib as _contextlib
import pathlib as _pathlib

__all__ = [
    "Anchor", "Package", "as_file", "contents", "files", "is_resource", "open_binary", "open_text", "path",
    "read_binary", "read_text",
]

Anchor = Package = object
_MISSING = object()


def _module(anchor):
    import importlib
    import sys

    if anchor is None:
        try:
            anchor = sys._getframe(2).f_globals["__name__"]
        except (AttributeError, KeyError, ValueError):
            raise TypeError("files() needs the package to look in, such as files(__package__)") from None
    if isinstance(anchor, str):
        return importlib.import_module(anchor)
    return anchor


def files(anchor=None):
    """The folder of package (or module) [anchor] as a pathlib.Path."""
    mod = _module(anchor)
    folders = getattr(mod, "__path__", None)
    if folders:
        return _pathlib.Path(list(folders)[0])
    where = getattr(mod, "__file__", None)
    if not where:
        raise TypeError(f"{getattr(mod, '__name__', mod)!r} has no folder of its own")
    return _pathlib.Path(where).parent


@_contextlib.contextmanager
def as_file(path):
    """[path] as a path open() takes: the data files open by their own path here."""
    yield path


def _join(anchor, path_names):
    return files(anchor).joinpath(*path_names)


def open_binary(anchor, *path_names):
    return _join(anchor, path_names).open("rb")


def read_binary(anchor, *path_names):
    return _join(anchor, path_names).read_bytes()


def open_text(anchor, *path_names, encoding=_MISSING, errors="strict"):
    if encoding is _MISSING:
        if len(path_names) > 1:
            raise TypeError("'encoding' argument required with multiple path names")
        encoding = "utf-8"
    return _join(anchor, path_names).open("r", encoding=encoding, errors=errors)


def read_text(anchor, *path_names, encoding=_MISSING, errors="strict"):
    with open_text(anchor, *path_names, encoding=encoding, errors=errors) as f:
        return f.read()


def path(anchor, *path_names):
    return as_file(_join(anchor, path_names))


def is_resource(anchor, *path_names):
    return _join(anchor, path_names).is_file()


def contents(anchor, *path_names):
    return (p.name for p in _join(anchor, path_names).iterdir())
