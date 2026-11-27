"""tempfile, for a plugin, which has no file system.

TemporaryFile and SpooledTemporaryFile work: they are held in memory, as a
SpooledTemporaryFile is until it grows, and they are gone when closed, as
a temporary file is. Anything that needs a name on disk (NamedTemporaryFile,
mkstemp, mkdtemp, TemporaryDirectory) raises PermissionError. gettempdir()
answers "/tmp" so code that only builds paths still loads.
"""

import io as _io

__all__ = [
    "NamedTemporaryFile", "TemporaryFile", "SpooledTemporaryFile", "TemporaryDirectory", "mkstemp", "mkdtemp",
    "mktemp", "TMP_MAX", "gettempprefix", "tempdir", "gettempdir", "gettempprefixb", "gettempdirb",
]

TMP_MAX = 10000
template = "tmp"
tempdir = None

_NO_FILES = "plugins have no file system, so there is no temporary file with a name on disk"


def gettempprefix():
    return template


def gettempprefixb():
    return template.encode()


def gettempdir():
    return tempdir if tempdir is not None else "/tmp"


def gettempdirb():
    return gettempdir().encode()


def _memory(mode, encoding=None, newline=None):
    if "b" in mode:
        return _io.BytesIO()
    return _io.StringIO(newline=newline) if newline is not None else _io.StringIO()


def TemporaryFile(mode="w+b", buffering=-1, encoding=None, newline=None, suffix=None, prefix=None, dir=None, *, errors=None):
    """A file with no name, held in memory and gone when closed."""
    return _memory(mode, encoding, newline)


class SpooledTemporaryFile(_io.IOBase if hasattr(_io, "IOBase") else object):
    """Held in memory, always: rollover() has no disk to move it to."""

    _rolled = False

    def __init__(self, max_size=0, mode="w+b", buffering=-1, encoding=None, newline=None, suffix=None, prefix=None, dir=None, *, errors=None):
        self._file = _memory(mode, encoding, newline)
        self._max_size = max_size
        self._mode = mode
        self._encoding = encoding
        self._errors = errors

    def rollover(self):
        pass

    def __enter__(self):
        if self._file.closed:
            raise ValueError("Cannot enter context with closed file")
        return self

    def __exit__(self, exc, value, tb):
        self._file.close()

    def __iter__(self):
        return iter(self._file)

    def close(self):
        self._file.close()

    @property
    def closed(self):
        return self._file.closed

    @property
    def encoding(self):
        return None if "b" in self._mode else (self._encoding or "utf-8")

    @property
    def errors(self):
        return None if "b" in self._mode else self._errors

    @property
    def mode(self):
        return self._mode

    @property
    def name(self):
        return None

    @property
    def newlines(self):
        return None

    def fileno(self):
        raise _io.UnsupportedOperation("fileno")

    def flush(self):
        pass

    def isatty(self):
        return False

    def read(self, *args):
        return self._file.read(*args)

    def read1(self, *args):
        return self._file.read(*args)

    def readinto(self, b):
        data = self._file.read(len(b))
        b[: len(data)] = data
        return len(data)

    def readline(self, *args):
        return self._file.readline(*args)

    def readlines(self, *args):
        return self._file.readlines(*args)

    def readable(self):
        return True

    def seek(self, *args):
        return self._file.seek(*args)

    def seekable(self):
        return True

    def tell(self):
        return self._file.tell()

    def truncate(self, size=None):
        if size is None:
            return self._file.truncate()
        return self._file.truncate(size)

    def writable(self):
        return True

    def write(self, s):
        return self._file.write(s)

    def writelines(self, iterable):
        for line in iterable:
            self._file.write(line)

    def getvalue(self):
        return self._file.getvalue()


def NamedTemporaryFile(mode="w+b", buffering=-1, encoding=None, newline=None, suffix=None, prefix=None, dir=None, delete=True, *, errors=None, delete_on_close=True):
    raise PermissionError(_NO_FILES)


def mkstemp(suffix=None, prefix=None, dir=None, text=False):
    raise PermissionError(_NO_FILES)


def mkdtemp(suffix=None, prefix=None, dir=None):
    raise PermissionError(_NO_FILES)


def mktemp(suffix="", prefix=template, dir=None):
    raise PermissionError(_NO_FILES)


class TemporaryDirectory:
    def __init__(self, suffix=None, prefix=None, dir=None, ignore_cleanup_errors=False, *, delete=True):
        raise PermissionError(_NO_FILES)

    @classmethod
    def __class_getitem__(cls, item):
        return cls
