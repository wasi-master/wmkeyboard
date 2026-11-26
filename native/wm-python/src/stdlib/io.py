"""io: StringIO and BytesIO, text and bytes held in memory. Nothing here
opens a file."""


class UnsupportedOperation(OSError, ValueError):
    pass


SEEK_SET = 0
SEEK_CUR = 1
SEEK_END = 2


class _Buffer:
    _empty = ""

    def __init__(self, initial=None):
        self._value = self._empty if initial is None else initial
        self._pos = 0
        self.closed = False

    def _check(self):
        if self.closed:
            raise ValueError("I/O operation on closed file.")

    def getvalue(self):
        self._check()
        return self._value

    def write(self, s):
        self._check()
        if not isinstance(s, type(self._empty)):
            raise TypeError(f"{self._kind} argument expected, got '{type(s).__name__}'")
        v = self._value
        if self._pos > len(v):
            v = v + self._pad * (self._pos - len(v))
        self._value = v[:self._pos] + s + v[self._pos + len(s):]
        self._pos += len(s)
        return len(s)

    def writelines(self, lines):
        for line in lines:
            self.write(line)

    def read(self, size=-1):
        self._check()
        if size is None or size < 0:
            out = self._value[self._pos:]
        else:
            out = self._value[self._pos:self._pos + size]
        self._pos += len(out)
        return out

    def readline(self, size=-1):
        self._check()
        rest = self._value[self._pos:]
        at = rest.find(self._newline)
        line = rest if at < 0 else rest[:at + 1]
        if size is not None and size >= 0:
            line = line[:size]
        self._pos += len(line)
        return line

    def readlines(self, hint=-1):
        out = []
        while True:
            line = self.readline()
            if not line:
                return out
            out.append(line)

    def __iter__(self):
        return self

    def __next__(self):
        line = self.readline()
        if not line:
            raise StopIteration
        return line

    def seek(self, pos, whence=0):
        self._check()
        if whence == 1:
            pos += self._pos
        elif whence == 2:
            pos += len(self._value)
        if pos < 0:
            raise ValueError(f"negative seek position {pos}")
        self._pos = pos
        return pos

    def tell(self):
        self._check()
        return self._pos

    def truncate(self, size=None):
        self._check()
        if size is None:
            size = self._pos
        self._value = self._value[:size]
        return size

    def flush(self):
        pass

    def close(self):
        self.closed = True

    def readable(self):
        return True

    def writable(self):
        return True

    def seekable(self):
        return True

    def isatty(self):
        return False

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
        return False


class StringIO(_Buffer):
    _empty = ""
    _pad = "\0"
    _newline = "\n"
    _kind = "string"

    def __init__(self, initial_value="", newline="\n"):
        _Buffer.__init__(self, initial_value or "")


class BytesIO(_Buffer):
    _empty = b""
    _pad = b"\0"
    _newline = b"\n"
    _kind = "a bytes-like object is required, not"

    def __init__(self, initial_bytes=b""):
        _Buffer.__init__(self, bytes(initial_bytes or b""))

    def getbuffer(self):
        return self._value


TextIOBase = _Buffer
IOBase = _Buffer
BufferedIOBase = _Buffer


def open(*args, **kwargs):
    raise OSError("plugins cannot open files; use wm.storage or wm.files")
