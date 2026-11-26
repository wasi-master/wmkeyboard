"""zlib: deflate compression with zlib, gzip or no wrapper, and the
CRC-32 and Adler-32 checksums.

compress() picks the same wrapper and header zlib does; the deflate data
itself comes from miniz_oxide, so compressed bytes can differ from
CPython's while decompressing to the same thing. A compressobj gives its
output at flush(); a decompressobj gives what it has as data comes in.
"""

import _native

MAX_WBITS = 15
DEFLATED = 8
DEF_MEM_LEVEL = 8
DEF_BUF_SIZE = 16384
Z_NO_COMPRESSION = 0
Z_BEST_SPEED = 1
Z_BEST_COMPRESSION = 9
Z_DEFAULT_COMPRESSION = -1
Z_FILTERED = 1
Z_HUFFMAN_ONLY = 2
Z_RLE = 3
Z_FIXED = 4
Z_DEFAULT_STRATEGY = 0
Z_NO_FLUSH = 0
Z_PARTIAL_FLUSH = 1
Z_SYNC_FLUSH = 2
Z_FULL_FLUSH = 3
Z_FINISH = 4
Z_BLOCK = 5
Z_TREES = 6
ZLIB_VERSION = "1.2.12"
ZLIB_RUNTIME_VERSION = "1.2.12"


class error(Exception):
    pass


def _bytes(data):
    return data if isinstance(data, bytes) else bytes(data)


def crc32(data, value=0):
    return _native.crc32(_bytes(data), value & 0xFFFFFFFF)


def adler32(data, value=1):
    return _native.adler32(_bytes(data), value & 0xFFFFFFFF)


def _header(level, wbits):
    if -15 <= wbits <= -9:
        return b""
    if 25 <= wbits <= 31:
        xfl = 2 if level == 9 else 4 if level == 1 else 0
        return bytes([0x1F, 0x8B, 8, 0, 0, 0, 0, 0, xfl, 3])
    if 9 <= wbits <= 15:
        flevel = 0 if level in (0, 1) else 1 if 2 <= level <= 5 else 2 if level in (-1, 6) else 3
        cmf = ((wbits - 8) << 4) | 8
        flg = flevel << 6
        flg += 31 - (cmf * 256 + flg) % 31
        return bytes([cmf, flg])
    raise ValueError("Invalid initialization option")


def _trailer(wbits, crc, adler, size):
    if wbits < 0:
        return b""
    if wbits > 15:
        return crc.to_bytes(4, "little") + (size & 0xFFFFFFFF).to_bytes(4, "little")
    return adler.to_bytes(4, "big")


def compress(data, /, level=-1, wbits=MAX_WBITS):
    data = _bytes(data)
    head = _header(level, wbits)
    try:
        body = _native.deflate_raw(data, level, True)
    except ValueError as e:
        raise error(str(e)) from None
    crc = crc32(data) if wbits > 15 else 0
    adler = adler32(data) if 0 < wbits <= 15 else 0
    return head + body + _trailer(wbits, crc, adler, len(data))


def _inflate(data, wbits):
    try:
        return _native.inflate(data, wbits)
    except ValueError as e:
        raise error(str(e)) from None


def decompress(data, /, wbits=MAX_WBITS, bufsize=DEF_BUF_SIZE):
    out, used, finished = _inflate(_bytes(data), wbits)
    if not finished:
        raise error("Error -5 while decompressing data: incomplete or truncated stream")
    return out


class _Compress:
    def __init__(self, level, wbits):
        self._level = level
        self._wbits = wbits
        self._head = _header(level, wbits)
        self._pending = []
        self._crc = 0
        self._adler = 1
        self._size = 0
        self._done = False

    def compress(self, data):
        if self._done:
            raise error("Error -2 while compressing data: inconsistent stream state")
        data = _bytes(data)
        self._pending.append(data)
        self._crc = crc32(data, self._crc)
        self._adler = adler32(data, self._adler)
        self._size += len(data)
        return b""

    def flush(self, mode=Z_FINISH):
        if mode == Z_NO_FLUSH:
            return b""
        if self._done:
            raise error("Error -2 while flushing: inconsistent stream state")
        data = b"".join(self._pending)
        self._pending = []
        last = mode == Z_FINISH
        out = self._head + _native.deflate_raw(data, self._level, last)
        self._head = b""
        if last:
            self._done = True
            out += _trailer(self._wbits, self._crc, self._adler, self._size)
        return out

    def copy(self):
        c = _Compress(self._level, self._wbits)
        c._head, c._pending, c._done = self._head, list(self._pending), self._done
        c._crc, c._adler, c._size = self._crc, self._adler, self._size
        return c


def compressobj(level=-1, method=DEFLATED, wbits=MAX_WBITS, memLevel=DEF_MEM_LEVEL, strategy=Z_DEFAULT_STRATEGY, zdict=None):
    if zdict is not None:
        raise error("a preset dictionary (zdict) is not supported")
    return _Compress(level, wbits)


class _Decompress:
    def __init__(self, wbits):
        self._wbits = wbits
        self._input = b""
        self._given = 0
        self._held = b""
        self.eof = False
        self.unused_data = b""
        self.unconsumed_tail = b""

    def _take(self, max_length):
        if max_length and len(self._held) > max_length:
            out, self._held = self._held[:max_length], self._held[max_length:]
            return out
        out, self._held = self._held, b""
        return out

    def decompress(self, data, max_length=0):
        if max_length < 0:
            raise ValueError("max_length must be non-negative")
        data = _bytes(data)
        if self.eof:
            self.unused_data += data
            return self._take(max_length)
        self._input += data
        out, used, finished = _inflate(self._input, self._wbits)
        self._held += out[self._given :]
        self._given = len(out)
        if finished:
            self.eof = True
            self.unused_data = self._input[used:]
            self._input = b""
        return self._take(max_length)

    def flush(self, length=DEF_BUF_SIZE):
        return self._take(0)

    def copy(self):
        d = _Decompress(self._wbits)
        d._input, d._given, d._held = self._input, self._given, self._held
        d.eof, d.unused_data = self.eof, self.unused_data
        return d


def decompressobj(wbits=MAX_WBITS, zdict=b""):
    if zdict:
        raise error("a preset dictionary (zdict) is not supported")
    return _Decompress(wbits)
