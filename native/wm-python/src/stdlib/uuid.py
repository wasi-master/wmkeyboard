"""uuid: UUID objects and the uuid1, uuid3, uuid4 and uuid5 generators, as
CPython's module has them (RFC 4122). A UUID holds its 128-bit int.

uuid4 takes its bytes from the operating system's randomness. uuid1 has no
hardware address to use, so its node is a random 48-bit number with the
multicast bit set, which is what RFC 4122 says to do then and what CPython
falls back to."""

from enum import Enum

import _native

RESERVED_NCS, RFC_4122, RESERVED_MICROSOFT, RESERVED_FUTURE = [
    "reserved for NCS compatibility",
    "specified in RFC 4122",
    "reserved for Microsoft compatibility",
    "reserved for future definition",
]

int_ = int
bytes_ = bytes


class SafeUUID(Enum):
    safe = 0
    unsafe = -1
    unknown = None


class UUID:
    """A UUID, from 32 hex digits, 16 bytes (big-endian or with the first
    three fields little-endian), the six fields, or one 128-bit int."""

    __slots__ = ("int", "is_safe", "__weakref__")

    def __init__(self, hex=None, bytes=None, bytes_le=None, fields=None, int=None, version=None, *, is_safe=SafeUUID.unknown):
        if [hex, bytes, bytes_le, fields, int].count(None) != 4:
            raise TypeError("one of the hex, bytes, bytes_le, fields, or int arguments must be given")
        if hex is not None:
            hex = hex.replace("urn:", "").replace("uuid:", "")
            hex = hex.strip("{}").replace("-", "")
            if len(hex) != 32:
                raise ValueError("badly formed hexadecimal UUID string")
            int = int_(hex, 16)
        if bytes_le is not None:
            if len(bytes_le) != 16:
                raise ValueError("bytes_le is not a 16-char string")
            bytes = bytes_le[3::-1] + bytes_le[5:3:-1] + bytes_le[7:5:-1] + bytes_le[8:]
        if bytes is not None:
            if len(bytes) != 16:
                raise ValueError("bytes is not a 16-char string")
            int = int_.from_bytes(bytes, "big")
        if fields is not None:
            if len(fields) != 6:
                raise ValueError("fields is not a 6-tuple")
            time_low, time_mid, time_hi_version, clock_seq_hi_variant, clock_seq_low, node = fields
            if not 0 <= time_low < 1 << 32:
                raise ValueError("field 1 out of range (need a 32-bit value)")
            if not 0 <= time_mid < 1 << 16:
                raise ValueError("field 2 out of range (need a 16-bit value)")
            if not 0 <= time_hi_version < 1 << 16:
                raise ValueError("field 3 out of range (need a 16-bit value)")
            if not 0 <= clock_seq_hi_variant < 1 << 8:
                raise ValueError("field 4 out of range (need an 8-bit value)")
            if not 0 <= clock_seq_low < 1 << 8:
                raise ValueError("field 5 out of range (need an 8-bit value)")
            if not 0 <= node < 1 << 48:
                raise ValueError("field 6 out of range (need a 48-bit value)")
            clock_seq = (clock_seq_hi_variant << 8) | clock_seq_low
            int = (time_low << 96) | (time_mid << 80) | (time_hi_version << 64) | (clock_seq << 48) | node
        if not 0 <= int < 1 << 128:
            raise ValueError("int is out of range (need a 128-bit value)")
        if version is not None:
            if not 1 <= version <= 5:
                raise ValueError("illegal version number")
            # The RFC 4122 variant, then the version.
            int &= ~(0xC000 << 48)
            int |= 0x8000 << 48
            int &= ~(0xF000 << 64)
            int |= version << 76
        object.__setattr__(self, "int", int)
        object.__setattr__(self, "is_safe", is_safe)

    def __getstate__(self):
        d = {"int": self.int}
        if self.is_safe != SafeUUID.unknown:
            d["is_safe"] = self.is_safe.value
        return d

    def __setstate__(self, state):
        object.__setattr__(self, "int", state["int"])
        object.__setattr__(self, "is_safe", SafeUUID(state["is_safe"]) if "is_safe" in state else SafeUUID.unknown)

    def __eq__(self, other):
        if isinstance(other, UUID):
            return self.int == other.int
        return NotImplemented

    def __lt__(self, other):
        if isinstance(other, UUID):
            return self.int < other.int
        return NotImplemented

    def __gt__(self, other):
        if isinstance(other, UUID):
            return self.int > other.int
        return NotImplemented

    def __le__(self, other):
        if isinstance(other, UUID):
            return self.int <= other.int
        return NotImplemented

    def __ge__(self, other):
        if isinstance(other, UUID):
            return self.int >= other.int
        return NotImplemented

    def __hash__(self):
        return hash(self.int)

    def __int__(self):
        return self.int

    def __repr__(self):
        return "%s(%r)" % (self.__class__.__name__, str(self))

    def __setattr__(self, name, value):
        raise TypeError("UUID objects are immutable")

    def __str__(self):
        h = "%032x" % self.int
        return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"

    @property
    def bytes(self):
        return self.int.to_bytes(16, "big")

    @property
    def bytes_le(self):
        b = self.bytes
        return b[3::-1] + b[5:3:-1] + b[7:5:-1] + b[8:]

    @property
    def fields(self):
        return (self.time_low, self.time_mid, self.time_hi_version, self.clock_seq_hi_variant, self.clock_seq_low, self.node)

    @property
    def time_low(self):
        return self.int >> 96

    @property
    def time_mid(self):
        return (self.int >> 80) & 0xFFFF

    @property
    def time_hi_version(self):
        return (self.int >> 64) & 0xFFFF

    @property
    def clock_seq_hi_variant(self):
        return (self.int >> 56) & 0xFF

    @property
    def clock_seq_low(self):
        return (self.int >> 48) & 0xFF

    @property
    def time(self):
        return ((self.time_hi_version & 0x0FFF) << 48) | (self.time_mid << 32) | self.time_low

    @property
    def clock_seq(self):
        return ((self.clock_seq_hi_variant & 0x3F) << 8) | self.clock_seq_low

    @property
    def node(self):
        return self.int & 0xFFFFFFFFFFFF

    @property
    def hex(self):
        return "%032x" % self.int

    @property
    def urn(self):
        return "urn:uuid:" + str(self)

    @property
    def variant(self):
        if not self.int & (0x8000 << 48):
            return RESERVED_NCS
        if not self.int & (0x4000 << 48):
            return RFC_4122
        if not self.int & (0x2000 << 48):
            return RESERVED_MICROSOFT
        return RESERVED_FUTURE

    @property
    def version(self):
        if self.variant == RFC_4122:
            return int_((self.int >> 76) & 0xF)
        return None


_node = None


def getnode():
    """A 48-bit node id: random, with the multicast bit set, chosen once."""
    global _node
    if _node is None:
        import random

        _node = random.getrandbits(48) | (1 << 40)
    return _node


_last_timestamp = None


def uuid1(node=None, clock_seq=None):
    """A UUID from the current time, a sequence number and the node."""
    global _last_timestamp
    import time

    timestamp = time.time_ns() // 100 + 0x01B21DD213814000
    if _last_timestamp is not None and timestamp <= _last_timestamp:
        timestamp = _last_timestamp + 1
    _last_timestamp = timestamp
    if clock_seq is None:
        import random

        clock_seq = random.getrandbits(14)
    time_low = timestamp & 0xFFFFFFFF
    time_mid = (timestamp >> 32) & 0xFFFF
    time_hi_version = (timestamp >> 48) & 0x0FFF
    clock_seq_low = clock_seq & 0xFF
    clock_seq_hi_variant = (clock_seq >> 8) & 0x3F
    if node is None:
        node = getnode()
    return UUID(fields=(time_low, time_mid, time_hi_version, clock_seq_hi_variant, clock_seq_low, node), version=1)


def uuid3(namespace, name):
    """A UUID from the MD5 hash of a namespace UUID and a name."""
    if isinstance(name, str):
        name = bytes_(name, "utf-8")
    from hashlib import md5

    return UUID(bytes=md5(namespace.bytes + name, usedforsecurity=False).digest()[:16], version=3)


def uuid4():
    """A random UUID."""
    return UUID(bytes=_native.urandom(16), version=4)


def uuid5(namespace, name):
    """A UUID from the SHA-1 hash of a namespace UUID and a name."""
    if isinstance(name, str):
        name = bytes_(name, "utf-8")
    from hashlib import sha1

    return UUID(bytes=sha1(namespace.bytes + name).digest()[:16], version=5)


NAMESPACE_DNS = UUID("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
NAMESPACE_URL = UUID("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
NAMESPACE_OID = UUID("6ba7b812-9dad-11d1-80b4-00c04fd430c8")
NAMESPACE_X500 = UUID("6ba7b814-9dad-11d1-80b4-00c04fd430c8")
