"""uuid_utils: UUIDs of every version (1, 3-8), the API of uuid-utils.

uuid-utils is written in Rust; this is the same API in Python for WM Keyboard
plugins, which cannot load compiled code. UUID here is a subclass of the
standard library's uuid.UUID with uuid-utils' extra timestamp property, and
uuid_utils.compat gives standard uuid.UUID objects.
"""

import os as _os
import time as _time
import uuid as _uuid
from uuid import RESERVED_FUTURE, RESERVED_MICROSOFT, RESERVED_NCS, RFC_4122, SafeUUID

__version__ = "1.3.0"

__all__ = [
    "MAX",
    "NAMESPACE_DNS",
    "NAMESPACE_OID",
    "NAMESPACE_URL",
    "NAMESPACE_X500",
    "NIL",
    "RESERVED_FUTURE",
    "RESERVED_MICROSOFT",
    "RESERVED_NCS",
    "RFC_4122",
    "UUID",
    "SafeUUID",
    "__version__",
    "getnode",
    "reseed_rng",
    "uuid1",
    "uuid3",
    "uuid4",
    "uuid5",
    "uuid6",
    "uuid7",
    "uuid8",
]

# 100-nanosecond intervals between 1582-10-15 (UUID v1 and v6 time) and 1970-01-01.
_GREGORIAN = 0x01B21DD213814000
_VARIANT = 0b10 << 62


class UUID(_uuid.UUID):
    """A UUID: the standard library's, plus timestamp."""

    __slots__ = ()

    @property
    def time(self):
        if self.version == 6:
            n = self.int
            return ((n >> 96) << 28) | (((n >> 80) & 0xFFFF) << 12) | ((n >> 64) & 0x0FFF)
        return super().time

    @property
    def timestamp(self):
        """Milliseconds since 1970 for a version 1, 6 or 7 UUID."""
        v = self.version
        if v == 7:
            return self.int >> 80
        if v == 1 or v == 6:
            return (self.time - _GREGORIAN) // 10_000
        raise ValueError("UUID version should be one of (v1, v6 or v7).")

    def __int__(self):
        return self.int

    def __reduce__(self):
        return (_from_int, (self.int,))


def _from_int(n):
    return UUID(int=n)


def _random(bits):
    return int.from_bytes(_os.urandom((bits + 7) // 8), "big") >> (-bits % 8)


def getnode():
    return _uuid.getnode()


def reseed_rng():
    """Nothing to reseed: random bits come from os.urandom each time."""


def uuid1(node=None, clock_seq=None):
    return _from_int(_uuid.uuid1(node, clock_seq).int)


def uuid3(namespace, name):
    return _from_int(_uuid.uuid3(namespace, name).int)


def _uuid4_int():
    return (_random(128) & ~(0xF000 << 64) & ~(0xC000 << 48)) | (4 << 76) | _VARIANT


def uuid4():
    return _from_int(_uuid4_int())


def uuid5(namespace, name):
    return _from_int(_uuid.uuid5(namespace, name).int)


_last_v6 = 0


def _uuid6_int(node=None, clock_seq=None):
    global _last_v6
    ts = _time.time_ns() // 100 + _GREGORIAN
    if ts <= _last_v6:
        ts = _last_v6 + 1
    _last_v6 = ts
    if clock_seq is None:
        clock_seq = _random(14)
    if node is None:
        node = getnode()
    return ((ts >> 12) << 80) | (6 << 76) | ((ts & 0x0FFF) << 64) | _VARIANT | ((clock_seq & 0x3FFF) << 48) | (node & 0xFFFFFFFFFFFF)


def uuid6(node=None, clock_seq=None):
    return _from_int(_uuid6_int(node, clock_seq))


_last_ms = -1
_last_counter = 0
_COUNTER = (1 << 74) - 1


def _uuid7_int(*, nanoseconds=None):
    """48 bits of Unix milliseconds, then 74 bits that count up within a
    millisecond from a random start, so UUIDs made in order sort in order."""
    global _last_ms, _last_counter
    if nanoseconds is not None:
        ms, counter = nanoseconds // 1_000_000, _random(74) >> 1
    else:
        ms = _time.time_ns() // 1_000_000
        if ms <= _last_ms and _last_counter < _COUNTER:
            ms, counter = _last_ms, _last_counter + 1
        else:
            if ms <= _last_ms:
                ms = _last_ms + 1
            # The top bit stays clear, leaving room to count up.
            counter = _random(74) >> 1
        _last_ms, _last_counter = ms, counter
    return ((ms & 0xFFFFFFFFFFFF) << 80) | (7 << 76) | ((counter >> 62) << 64) | _VARIANT | (counter & ((1 << 62) - 1))


def uuid7(*, nanoseconds=None):
    return _from_int(_uuid7_int(nanoseconds=nanoseconds))


def _uuid8_int(a=None, b=None, c=None):
    a = _random(48) if a is None else a
    b = _random(12) if b is None else b
    c = _random(62) if c is None else c
    return ((a & 0xFFFFFFFFFFFF) << 80) | (8 << 76) | ((b & 0x0FFF) << 64) | _VARIANT | (c & ((1 << 62) - 1))


def uuid8(a=None, b=None, c=None):
    return _from_int(_uuid8_int(a, b, c))


NAMESPACE_DNS = _from_int(_uuid.NAMESPACE_DNS.int)
NAMESPACE_URL = _from_int(_uuid.NAMESPACE_URL.int)
NAMESPACE_OID = _from_int(_uuid.NAMESPACE_OID.int)
NAMESPACE_X500 = _from_int(_uuid.NAMESPACE_X500.int)
NIL = _from_int(0)
MAX = _from_int((1 << 128) - 1)
