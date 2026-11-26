"""gzip: compress() and decompress() for gzip data, and GzipFile over a
file object such as io.BytesIO. Plugins have no file paths, so open()
takes a file object too."""

import io
import time

import _native
import zlib

READ, WRITE = 1, 2
_COMPRESS_LEVEL_FAST = 1
_COMPRESS_LEVEL_TRADEOFF = 6
_COMPRESS_LEVEL_BEST = 9


class BadGzipFile(OSError):
    pass


def _header(level, mtime):
    if mtime is None:
        mtime = time.time()
    xfl = 2 if level == _COMPRESS_LEVEL_BEST else 4 if level == _COMPRESS_LEVEL_FAST else 0
    return b"\x1f\x8b\x08\x00" + int(mtime).to_bytes(4, "little") + bytes([xfl, 255])


def compress(data, compresslevel=_COMPRESS_LEVEL_BEST, *, mtime=None):
    data = bytes(data)
    body = zlib.compress(data, compresslevel, -15)
    return _header(compresslevel, mtime) + body + zlib.crc32(data).to_bytes(4, "little") + (len(data) & 0xFFFFFFFF).to_bytes(4, "little")


def decompress(data):
    data = bytes(data)
    out = []
    while data:
        if data[:2] != b"\x1f\x8b":
            if not out:
                raise BadGzipFile(f"Not a gzipped file ({data[:2]!r})")
            break
        try:
            chunk, used, finished = _native.inflate(data, 31)
        except ValueError as e:
            raise BadGzipFile(str(e)) from None
        if not finished:
            raise EOFError("Compressed file ended before the end-of-stream marker was reached")
        out.append(chunk)
        data = data[used:].lstrip(b"\x00")
    return b"".join(out)


class GzipFile:
    def __init__(self, filename=None, mode=None, compresslevel=_COMPRESS_LEVEL_BEST, fileobj=None, mtime=None):
        if fileobj is None:
            raise OSError("gzip here works on file objects (io.BytesIO): there are no file paths to open")
        if mode is None:
            mode = getattr(fileobj, "mode", "rb")
        self.mode = WRITE if mode[:1] in ("w", "a", "x") else READ
        self.fileobj = fileobj
        self.name = filename or ""
        self.compresslevel = compresslevel
        self.mtime = mtime
        self._written = []
        self._data = None
        self._pos = 0
        self.closed = False

    def _load(self):
        if self._data is None:
            self._data = decompress(self.fileobj.read())
        return self._data

    def write(self, data):
        if self.mode != WRITE:
            raise OSError("write() on read-only GzipFile object")
        data = data.encode() if isinstance(data, str) else bytes(data)
        self._written.append(data)
        return len(data)

    def read(self, size=-1):
        if self.mode != READ:
            raise OSError("read() on write-only GzipFile object")
        data = self._load()
        end = len(data) if size is None or size < 0 else self._pos + size
        out = data[self._pos : end]
        self._pos += len(out)
        return out

    def read1(self, size=-1):
        return self.read(size)

    def readline(self, size=-1):
        data = self._load()
        end = data.find(b"\n", self._pos)
        end = len(data) if end < 0 else end + 1
        if size is not None and size >= 0:
            end = min(end, self._pos + size)
        out = data[self._pos : end]
        self._pos = end
        return out

    def readlines(self, hint=-1):
        lines = []
        while True:
            line = self.readline()
            if not line:
                return lines
            lines.append(line)

    def __iter__(self):
        return iter(self.readlines())

    def seek(self, offset, whence=0):
        base = 0 if whence == 0 else self._pos if whence == 1 else len(self._load())
        self._pos = max(0, base + offset)
        return self._pos

    def tell(self):
        return self._pos

    def readable(self):
        return self.mode == READ

    def writable(self):
        return self.mode == WRITE

    def seekable(self):
        return self.mode == READ

    def flush(self):
        pass

    def close(self):
        if self.closed:
            return
        if self.mode == WRITE:
            self.fileobj.write(compress(b"".join(self._written), self.compresslevel, mtime=self.mtime))
        self.closed = True

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
        return False


def open(filename, mode="rb", compresslevel=_COMPRESS_LEVEL_BEST, encoding=None, errors=None, newline=None):
    if isinstance(filename, (str, bytes)):
        raise OSError("gzip here works on file objects (io.BytesIO): there are no file paths to open")
    f = GzipFile(None, mode.replace("t", ""), compresslevel, filename)
    if "t" in mode:
        if f.mode == READ:
            return io.StringIO(f.read().decode(encoding or "utf-8", errors or "strict"))
        raise ValueError("text mode writing is not supported here: write bytes")
    return f
