"""io: StringIO and BytesIO, text and bytes held in memory,
TextIOWrapper for text over bytes, and the base classes and buffered
wrappers (BufferedReader, BufferedWriter) that file-like classes build on,
with CPython's default methods. Nothing here opens a file."""


class UnsupportedOperation(OSError, ValueError):
    pass


SEEK_SET = 0
SEEK_CUR = 1
SEEK_END = 2
DEFAULT_BUFFER_SIZE = 8192


class IOBase:
    """The base of every stream: readline, iteration, readlines, writelines
    and the context manager, built on read() (or peek() when there is one),
    as CPython's IOBase gives them."""

    _io_closed = False

    @property
    def closed(self):
        return self._io_closed

    @closed.setter
    def closed(self, value):
        self._io_closed = value

    def _unsupported(self, name):
        raise UnsupportedOperation(f"{type(self).__name__}.{name}() not supported")

    def _checkClosed(self, msg=None):
        if self.closed:
            raise ValueError("I/O operation on closed file." if msg is None else msg)

    def seek(self, pos, whence=0):
        self._unsupported("seek")

    def tell(self):
        return self.seek(0, 1)

    def truncate(self, pos=None):
        self._unsupported("truncate")

    def flush(self):
        self._checkClosed()

    def close(self):
        if not self._io_closed:
            try:
                self.flush()
            finally:
                self._io_closed = True

    def fileno(self):
        self._unsupported("fileno")

    def readable(self):
        return False

    def writable(self):
        return False

    def seekable(self):
        return False

    def isatty(self):
        self._checkClosed()
        return False

    def readline(self, size=-1):
        if size is None:
            size = -1
        res = bytearray()
        while size < 0 or len(res) < size:
            if hasattr(self, "peek"):
                ahead = self.peek(1)
                if not ahead:
                    n = 1
                else:
                    i = ahead.find(b"\n") + 1 if isinstance(ahead, (bytes, bytearray)) else 0
                    n = i if i > 0 else len(ahead)
                    if size >= 0:
                        n = min(n, size - len(res))
                b = self.read(n)
            else:
                b = self.read(1)
            if not b:
                break
            res += b
            if res.endswith(b"\n"):
                break
        return bytes(res)

    def __iter__(self):
        self._checkClosed()
        return self

    def __next__(self):
        line = self.readline()
        if not line:
            raise StopIteration
        return line

    def readlines(self, hint=None):
        if hint is None or hint <= 0:
            return list(self)
        n = 0
        lines = []
        for line in self:
            lines.append(line)
            n += len(line)
            if n >= hint:
                break
        return lines

    def writelines(self, lines):
        self._checkClosed()
        for line in lines:
            self.write(line)

    def __enter__(self):
        self._checkClosed()
        return self

    def __exit__(self, *args):
        self.close()


class RawIOBase(IOBase):
    def read(self, size=-1):
        if size is None:
            size = -1
        if size < 0:
            return self.readall()
        b = bytearray(size)
        n = self.readinto(b)
        if n is None:
            return None
        return bytes(b[:n])

    def readall(self):
        res = bytearray()
        while True:
            data = self.read(DEFAULT_BUFFER_SIZE)
            if not data:
                break
            res += data
        return bytes(res) if res else data

    def readinto(self, b):
        self._unsupported("readinto")

    def write(self, b):
        self._unsupported("write")


class BufferedIOBase(IOBase):
    def read(self, size=-1):
        self._unsupported("read")

    def read1(self, size=-1):
        return self.read(size)

    def readinto(self, b):
        data = self.read(len(b))
        n = len(data)
        b[:n] = data
        return n

    def readinto1(self, b):
        return self.readinto(b)

    def write(self, b):
        self._unsupported("write")

    def detach(self):
        self._unsupported("detach")


class TextIOBase(IOBase):
    def read(self, size=-1):
        self._unsupported("read")

    def write(self, s):
        self._unsupported("write")

    def truncate(self, pos=None):
        self._unsupported("truncate")

    def readline(self, size=-1):
        self._unsupported("readline")

    def detach(self):
        self._unsupported("detach")

    encoding = None
    newlines = None
    errors = None


class _Buffer(IOBase):
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


class StringIO(_Buffer, TextIOBase):
    _empty = ""
    _pad = "\0"
    _newline = "\n"
    _kind = "string"

    def __init__(self, initial_value="", newline="\n"):
        _Buffer.__init__(self, initial_value or "")


class BytesIO(_Buffer, BufferedIOBase):
    _empty = b""
    _pad = b"\0"
    _newline = b"\n"
    _kind = "a bytes-like object is required, not"

    def __init__(self, initial_bytes=b""):
        _Buffer.__init__(self, bytes(initial_bytes or b""))

    def getbuffer(self):
        return self._value


    def read1(self, size=-1):
        return self.read(size)


class BufferedReader(BufferedIOBase):
    """Reads from a raw stream through a buffer, with peek()."""

    def __init__(self, raw, buffer_size=DEFAULT_BUFFER_SIZE):
        if buffer_size <= 0:
            raise ValueError("invalid buffer size")
        self.raw = raw
        self.buffer_size = buffer_size
        self._read_buf = b""

    @property
    def closed(self):
        return self.raw.closed

    @property
    def name(self):
        return self.raw.name

    @property
    def mode(self):
        return self.raw.mode

    def readable(self):
        return True

    def seekable(self):
        return self.raw.seekable()

    def fileno(self):
        return self.raw.fileno()

    def isatty(self):
        return self.raw.isatty()

    def read(self, size=-1):
        if size is None or size < 0:
            rest = self.raw.read()
            data = self._read_buf + (rest or b"")
            self._read_buf = b""
            return data
        while len(self._read_buf) < size:
            chunk = self.raw.read(max(size - len(self._read_buf), self.buffer_size))
            if not chunk:
                break
            self._read_buf += chunk
        data, self._read_buf = self._read_buf[:size], self._read_buf[size:]
        return data

    def read1(self, size=-1):
        if self._read_buf:
            n = len(self._read_buf) if size is None or size < 0 else min(size, len(self._read_buf))
            return self.read(n)
        return self.raw.read(self.buffer_size if size is None or size < 0 else size) or b""

    def peek(self, size=0):
        if not self._read_buf:
            self._read_buf = self.raw.read(self.buffer_size) or b""
        return self._read_buf

    def tell(self):
        return self.raw.tell() - len(self._read_buf)

    def seek(self, pos, whence=0):
        if whence == SEEK_CUR:
            pos -= len(self._read_buf)
        self._read_buf = b""
        return self.raw.seek(pos, whence)

    def flush(self):
        pass

    def close(self):
        if not self.raw.closed:
            self.raw.close()

    def detach(self):
        raw = self.raw
        self.raw = None
        return raw


class BufferedWriter(BufferedIOBase):
    """Writes to a raw stream."""

    def __init__(self, raw, buffer_size=DEFAULT_BUFFER_SIZE):
        self.raw = raw
        self.buffer_size = buffer_size

    @property
    def closed(self):
        return self.raw.closed

    @property
    def name(self):
        return self.raw.name

    @property
    def mode(self):
        return self.raw.mode

    def writable(self):
        return True

    def seekable(self):
        return self.raw.seekable()

    def write(self, b):
        n = self.raw.write(b)
        return len(b) if n is None else n

    def tell(self):
        return self.raw.tell()

    def seek(self, pos, whence=0):
        return self.raw.seek(pos, whence)

    def truncate(self, pos=None):
        return self.raw.truncate(pos)

    def flush(self):
        if hasattr(self.raw, "flush"):
            self.raw.flush()

    def close(self):
        if not self.raw.closed:
            self.flush()
            self.raw.close()

    def detach(self):
        raw = self.raw
        self.raw = None
        return raw


class BufferedRandom(BufferedReader, BufferedWriter):
    def __init__(self, raw, buffer_size=DEFAULT_BUFFER_SIZE):
        BufferedReader.__init__(self, raw, buffer_size)

    def writable(self):
        return True

    def write(self, b):
        if self._read_buf:
            self.raw.seek(-len(self._read_buf), SEEK_CUR)
            self._read_buf = b""
        return BufferedWriter.write(self, b)

    def close(self):
        BufferedWriter.close(self)


class BufferedRWPair(BufferedIOBase):
    def __init__(self, reader, writer, buffer_size=DEFAULT_BUFFER_SIZE):
        self.reader = BufferedReader(reader, buffer_size)
        self.writer = BufferedWriter(writer, buffer_size)

    @property
    def closed(self):
        return self.writer.closed

    def read(self, size=-1):
        return self.reader.read(size)

    def peek(self, size=0):
        return self.reader.peek(size)

    def read1(self, size=-1):
        return self.reader.read1(size)

    def write(self, b):
        return self.writer.write(b)

    def readable(self):
        return True

    def writable(self):
        return True

    def flush(self):
        self.writer.flush()

    def close(self):
        try:
            self.writer.close()
        finally:
            self.reader.close()


class FileIO(RawIOBase):
    def __init__(self, file, mode="r", closefd=True, opener=None):
        raise PermissionError("plugins cannot open files; use wm.storage or wm.files")


def text_encoding(encoding, stacklevel=2):
    return "utf-8" if encoding is None else encoding


def open_code(path):
    raise OSError("plugins cannot open files; use wm.storage or wm.files")


class TextIOWrapper(TextIOBase):
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
