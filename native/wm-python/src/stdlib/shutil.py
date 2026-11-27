"""shutil: the parts that need no file system. get_terminal_size answers
from COLUMNS and LINES or the fallback, copyfileobj copies between
file-like objects, and which finds nothing. The functions that copy, move
or delete files raise PermissionError, as open() does: plugins keep files
through wm.storage and wm.files."""

import os

__all__ = ["copyfileobj", "copyfile", "copymode", "copystat", "copy", "copy2", "copytree", "move", "rmtree",
           "Error", "SpecialFileError", "make_archive", "get_archive_formats", "register_archive_format",
           "unregister_archive_format", "get_unpack_formats", "register_unpack_format", "unregister_unpack_format",
           "unpack_archive", "ignore_patterns", "chown", "which", "get_terminal_size", "SameFileError", "disk_usage"]

COPY_BUFSIZE = 64 * 1024


class Error(OSError):
    pass


class SameFileError(Error):
    """Raised when source and destination are the same file."""


class SpecialFileError(OSError):
    """Raised when trying to do a kind of operation (e.g. copying) which is
    not supported on a special file (e.g. a named pipe)"""


class ExecError(OSError):
    """Raised when a command could not be executed"""


class ReadError(OSError):
    """Raised when an archive cannot be read"""


class RegistryError(Exception):
    """Raised when a registry operation with the archiving
    and unpacking registries fails"""


def get_terminal_size(fallback=(80, 24)):
    """The size of the terminal: COLUMNS and LINES from the environment,
    else [fallback]. A plugin has no terminal to ask."""
    try:
        columns = int(os.environ["COLUMNS"])
    except (KeyError, ValueError):
        columns = 0
    try:
        lines = int(os.environ["LINES"])
    except (KeyError, ValueError):
        lines = 0
    if columns <= 0:
        columns = fallback[0]
    if lines <= 0:
        lines = fallback[1]
    return os.terminal_size((columns, lines))


def copyfileobj(fsrc, fdst, length=0):
    """copy data from file-like object fsrc to file-like object fdst"""
    if not length:
        length = COPY_BUFSIZE
    fsrc_read = fsrc.read
    fdst_write = fdst.write
    while buf := fsrc_read(length):
        fdst_write(buf)


def ignore_patterns(*patterns):
    """Function that can be used as copytree() ignore parameter."""
    import fnmatch

    def _ignore_patterns(path, names):
        ignored_names = []
        for pattern in patterns:
            ignored_names.extend(fnmatch.filter(names, pattern))
        return set(ignored_names)
    return _ignore_patterns


def which(cmd, mode=None, path=None):
    """There are no programs to find: always None."""
    return None


def _no_files(name):
    def refuse(*args, **kwargs):
        target = args[0] if args else None
        raise PermissionError(13, "plugins have no file system", target)
    refuse.__name__ = refuse.__qualname__ = name
    return refuse


for _name in ("copyfile", "copymode", "copystat", "copy", "copy2", "copytree", "move", "rmtree", "chown",
              "disk_usage", "make_archive", "unpack_archive"):
    globals()[_name] = _no_files(_name)
del _name


def get_archive_formats():
    return []


def get_unpack_formats():
    return []


def register_archive_format(name, function, extra_args=None, description=''):
    pass


def unregister_archive_format(name):
    pass


def register_unpack_format(name, extensions, function, extra_args=None, description=''):
    pass


def unregister_unpack_format(name):
    pass
