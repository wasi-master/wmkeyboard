"""os: the parts that compute and touch nothing: path joining and
splitting (os.path), the separators, and an empty environment. Plugins
read and write files through wm.storage and wm.files instead."""

import abc as _abc

from . import path

name = "posix"
sep = "/"
altsep = None
extsep = "."
pathsep = ":"
linesep = "\n"
curdir = "."
pardir = ".."
defpath = "/bin:/usr/bin"
devnull = "/dev/null"
environ = {}


class terminal_size(tuple):
    """A terminal's size, (columns, lines), as os.get_terminal_size gives it."""

    n_fields = n_sequence_fields = 2
    n_unnamed_fields = 0

    def __new__(cls, iterable):
        values = tuple(iterable)
        if len(values) != 2:
            raise TypeError(f"os.terminal_size() takes a 2-sequence ({len(values)}-sequence given)")
        return tuple.__new__(cls, values)

    @property
    def columns(self):
        return self[0]

    @property
    def lines(self):
        return self[1]

    def __repr__(self):
        return f"os.terminal_size(columns={self[0]}, lines={self[1]})"


def get_terminal_size(fd=1):
    """A plugin has no terminal: OSError, as CPython's is with none attached."""
    raise OSError(25, "Inappropriate ioctl for device")


def urandom(size, /):
    """size bytes from the system's randomness, for cryptographic use."""
    import _native
    if size < 0:
        raise ValueError("negative argument not allowed")
    return _native.urandom(size)


def getenv(key, default=None):
    return environ.get(key, default)


def fspath(p):
    if isinstance(p, (str, bytes)):
        return p
    if hasattr(p, "__fspath__"):
        return p.__fspath__()
    raise TypeError(f"expected str, bytes or os.PathLike object, not {type(p).__name__}")


class PathLike(_abc.ABC):
    """Abstract base class for implementing the file system path protocol."""

    __slots__ = ()

    @_abc.abstractmethod
    def __fspath__(self):
        """Return the file system path representation of the object."""
        raise NotImplementedError

    @classmethod
    def __subclasshook__(cls, subclass):
        if cls is PathLike:
            return hasattr(subclass, "__fspath__") or NotImplemented
        return NotImplemented

    def __class_getitem__(cls, item):
        from types import GenericAlias
        return GenericAlias(cls, item)

    __class_getitem__ = classmethod(__class_getitem__)


def cpu_count():
    return 1


def getpid():
    return 0
