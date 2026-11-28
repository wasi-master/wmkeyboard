"""fastuuid: UUIDs, the API of fastuuid.

fastuuid is written in Rust; this is the same API in Python for WM Keyboard
plugins, which cannot load compiled code. UUID is a subclass of the standard
library's uuid.UUID, and the functions make the same versions fastuuid makes.
"""

import os as _os
import time as _time
import uuid as _uuid

__all__ = [
    "UUID",
    "uuid1",
    "uuid3",
    "uuid4",
    "uuid4_as_strings_bulk",
    "uuid4_bulk",
    "uuid5",
    "uuid7",
    "uuid7_as_strings_bulk",
    "uuid7_bulk",
    "uuid_v1mc",
]

_VARIANT = 0b10 << 62


class UUID(_uuid.UUID):
    __slots__ = ()

    def __int__(self):
        return self.int

    def __getstate__(self):
        return self.bytes

    def __reduce__(self):
        return (UUID, (None, self.bytes))


def _random(bits):
    return int.from_bytes(_os.urandom((bits + 7) // 8), "big") >> (-bits % 8)


def uuid1(node=None, clock_seq=None):
    return UUID(int=_uuid.uuid1(node, clock_seq).int)


def uuid_v1mc():
    """A version 1 UUID with a random node that has the multicast bit set."""
    return uuid1(node=_random(48) | (1 << 40))


def uuid3(namespace, name):
    return UUID(int=_uuid.uuid3(namespace, name).int)


def uuid5(namespace, name):
    return UUID(int=_uuid.uuid5(namespace, name).int)


def _v4():
    return (_random(128) & ~(0xF000 << 64) & ~(0xC000 << 48)) | (4 << 76) | _VARIANT


def uuid4():
    return UUID(int=_v4())


def uuid4_bulk(n):
    return [UUID(int=_v4()) for _ in range(n)]


def uuid4_as_strings_bulk(n):
    return [str(UUID(int=_v4())) for _ in range(n)]


_last_ms = -1
_last_counter = 0


def _v7():
    global _last_ms, _last_counter
    ms = _time.time_ns() // 1_000_000
    if ms <= _last_ms and _last_counter < (1 << 74) - 1:
        ms, counter = _last_ms, _last_counter + 1
    else:
        ms = max(ms, _last_ms + 1) if ms <= _last_ms else ms
        counter = _random(74) >> 1
    _last_ms, _last_counter = ms, counter
    return ((ms & 0xFFFFFFFFFFFF) << 80) | (7 << 76) | ((counter >> 62) << 64) | _VARIANT | (counter & ((1 << 62) - 1))


def uuid7():
    return UUID(int=_v7())


def uuid7_bulk(n):
    return [UUID(int=_v7()) for _ in range(n)]


def uuid7_as_strings_bulk(n):
    return [str(UUID(int=_v7())) for _ in range(n)]
