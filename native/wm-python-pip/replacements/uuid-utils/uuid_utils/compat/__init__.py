"""uuid_utils.compat: the same functions, giving the standard library's uuid.UUID."""

from uuid import (
    NAMESPACE_DNS,
    NAMESPACE_OID,
    NAMESPACE_URL,
    NAMESPACE_X500,
    RESERVED_FUTURE,
    RESERVED_MICROSOFT,
    RESERVED_NCS,
    RFC_4122,
    UUID,
    SafeUUID,
    getnode,
)

import uuid_utils
from uuid_utils import _uuid4_int, _uuid6_int, _uuid7_int, _uuid8_int

NIL = UUID("00000000-0000-0000-0000-000000000000")
MAX = UUID("ffffffff-ffff-ffff-ffff-ffffffffffff")

__version__ = uuid_utils.__version__

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
    "__version__",
    "getnode",
    "uuid1",
    "uuid3",
    "uuid4",
    "uuid5",
    "uuid6",
    "uuid7",
    "uuid8",
]


def uuid1(node=None, clock_seq=None):
    return UUID(int=uuid_utils.uuid1(node, clock_seq).int)


def uuid3(namespace, name):
    return UUID(int=uuid_utils.uuid3(namespace, name).int)


def uuid4():
    return UUID(int=_uuid4_int())


def uuid5(namespace, name):
    return UUID(int=uuid_utils.uuid5(namespace, name).int)


def uuid6(node=None, clock_seq=None):
    return UUID(int=_uuid6_int(node, clock_seq))


def uuid7(*, nanoseconds=None):
    return UUID(int=_uuid7_int(nanoseconds=nanoseconds))


def uuid8(a=None, b=None, c=None):
    return UUID(int=_uuid8_int(a, b, c))
