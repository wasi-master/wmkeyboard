"""os: the parts that compute and touch nothing: path joining and
splitting (os.path), the separators, and an empty environment. Plugins
read and write files through wm.storage and wm.files instead."""

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


def getenv(key, default=None):
    return environ.get(key, default)


def fspath(p):
    if isinstance(p, (str, bytes)):
        return p
    if hasattr(p, "__fspath__"):
        return p.__fspath__()
    raise TypeError(f"expected str, bytes or os.PathLike object, not {type(p).__name__}")


class PathLike:
    pass


def cpu_count():
    return 1


def getpid():
    return 0
