"""io: StringIO and BytesIO, text and bytes held in memory, and
TextIOWrapper for text over bytes. Nothing here opens a file."""


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
        # partition, not find: "\r\n" is one character here, and find's
        # index would land inside it.
        head, sep, _ = rest.partition(self._newline)
        line = head + sep
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
RawIOBase = _Buffer
DEFAULT_BUFFER_SIZE = 8192


class TextIOWrapper:
    """Text over a binary buffer, such as a BytesIO: reads decode what the
    buffer holds, writes encode into it. With newline None, reading turns
    "\r\n" and "\r" into "\n", as Python's universal newlines do."""

    def __init__(self, buffer, encoding=None, errors=None, newline=None, line_buffering=False, write_through=False):
        if newline not in (None, "", "\n", "\r", "\r\n"):
            raise ValueError(f"illegal newline value: {newline!r}")
        self.buffer = buffer
        self.encoding = encoding or "utf-8"
        self.errors = errors or "strict"
        self._newline = newline
        self.line_buffering = line_buffering
        self.write_through = write_through
        self._text = None
        self._pos = 0

    @property
    def closed(self):
        return self.buffer.closed

    @property
    def newlines(self):
        return None

    def _check(self):
        if self.buffer.closed:
            raise ValueError("I/O operation on closed file.")

    def _load(self):
        if self._text is None:
            text = self.buffer.read().decode(self.encoding, self.errors)
            if self._newline is None:
                text = text.replace("\r\n", "\n").replace("\r", "\n")
            self._text = text
        return self._text

    def read(self, size=-1):
        self._check()
        text = self._load()
        if size is None or size < 0:
            out = text[self._pos:]
        else:
            out = text[self._pos:self._pos + size]
        self._pos += len(out)
        return out

    def readline(self, size=-1):
        self._check()
        head, sep, _ = self._load()[self._pos:].partition("\n")
        line = head + sep
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

    def write(self, s):
        self._check()
        if not isinstance(s, str):
            raise TypeError(f"write() argument must be str, not {type(s).__name__}")
        text = s if self._newline in (None, "", "\n") else s.replace("\n", self._newline)
        self.buffer.write(text.encode(self.encoding, self.errors))
        return len(s)

    def writelines(self, lines):
        for line in lines:
            self.write(line)

    def detach(self):
        buffer = self.buffer
        self.buffer = None
        return buffer

    def flush(self):
        self.buffer.flush()

    def close(self):
        self.buffer.close()

    def readable(self):
        return self.buffer.readable()

    def writable(self):
        return self.buffer.writable()

    def seekable(self):
        return self.buffer.seekable()

    def isatty(self):
        return False

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
        return False


def open(*args, **kwargs):
    raise OSError("plugins cannot open files; use wm.storage or wm.files")
